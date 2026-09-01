package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ToneAblation"

/**
 * What the largest item in a capture is actually made of.
 *
 * `demosaic+tone` is 168-250 ms of a develop and has only ever reported as one
 * number. An earlier session established that its tone half costs about as much
 * as its demosaic half, and no more than that: which part of the tone half --
 * the colour matrix, the rendering curve's maxima and branches, or the display
 * lookup -- has never been separated, because the instrument available could
 * not see anything that fine.
 *
 * This one can. Both variants are in the binary, so a round times them back to
 * back; see [ShadingSpeedDeviceTest] for why that is the only comparison here
 * worth making and why it is paired rather than pooled.
 *
 * **Ablation differences are subtractions, and subtractions do not have to add
 * up.** Taking an item out lets the compiler and the machine rearrange what is
 * left, so what this measures is what removing a piece *saves*. That is the
 * quantity worth having -- it is what an optimisation of that piece could
 * recover at most -- but it is not the same as what the piece would cost alone,
 * and the four figures are not obliged to sum to the whole.
 */
@RunWith(AndroidJUnit4::class)
class ToneAblationDeviceTest {

    private val width = 4080
    private val height = 3072

    /** Fixed rather than auto, so the harness renders the same scene every run. */
    private fun params(gain: Float) = DevelopParams(exposureGain = gain)

    private fun bench(a: Int, b: Int, rounds: Int, gain: Float = 3.5f) =
        NativeMerge.toneBench(
            width, height, SensorProfile.DEFAULT, ColorProfile.NEUTRAL,
            params(gain), a, b, rounds,
        )

    @Test
    fun theAblationHarnessCannotSeparateThePassFromItself() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val raw = bench(ToneAblation.FULL, ToneAblation.FULL, AA_ROUNDS)
        assertThat(raw).isNotNull()
        val run = Run(raw!!)

        Log.i(TAG, "A/A left:  ${run.describeFull(run.a)}")
        Log.i(TAG, "A/A right: ${run.describeFull(run.b)}")
        Log.i(TAG, "A/A ${run.pairing()}")
        Log.i(TAG, "scene: ${run.aboveKneePercent()}% of pixels above the knee")
        DeviceKind.warnIfNotAPhone(TAG)

        // Two runs of the same code on the same plane must produce the same
        // picture. This one can be bit equality rather than a bound: it is the
        // same instantiation twice, not two spellings of the same arithmetic.
        assertThat(run.differing).isEqualTo(0L)

        // The instrument passes by failing to tell the two apart, and the claim
        // it has to fail to make is the paired one, because that is the claim
        // the ablations will make.
        //
        // Thirty to seventy per cent is the band; the rounds are what make it
        // affordable. At twenty rounds it is 6 to 14, which a fair coin falls
        // outside of 4% of the time -- and it did, at 15 of 20, in a full-suite
        // run. That is a test that cries wolf once a month and gets written
        // down as flakiness, which is how the four failures this suite's other
        // consistency test suffered were misread for four sessions. At forty it
        // is 12 to 28 and the same band costs 0.6%.
        assertThat(run.wins()).isAtLeast(AA_ROUNDS * 3 / 10)
        assertThat(run.wins()).isAtMost(AA_ROUNDS * 7 / 10)
    }

    @Test
    fun removingEachPieceSaysWhatThePieceIsWorth() {
        assertThat(NativeMerge.isAvailable()).isTrue()

        val named = listOf(
            "colour matrix" to ToneAblation.NO_MATRIX,
            "display table" to ToneAblation.NO_DISPLAY,
            "the whole display chain" to ToneAblation.NO_DISPLAY_CHAIN,
            "renderLinear" to ToneAblation.NO_RENDER,
            "its roll-off" to ToneAblation.NO_SHOULDER,
            "its desaturation" to ToneAblation.NO_DESAT,
            "the roll-off's table, for the arithmetic" to ToneAblation.EXACT_SHOULDER,
            "the vector demosaic, for the scalar one" to ToneAblation.SCALAR_DEMOSAIC,
            "both, for the per-pixel one" to ToneAblation.UNSPLIT_DEMOSAIC,
            "of the computed roll-off, its exponential" to ToneAblation.NO_EXP,
            "everything after the demosaic" to ToneAblation.NONE,
        )

        // NO_EXP is measured against the computed roll-off rather than the
        // shipping one. It swaps the exponential for a reciprocal *inside* the
        // computed path, so setting it beside the table would be comparing two
        // changes at once -- and would read as the exponential costing less
        // than nothing, which is what it did the first time it was run this way.
        val against = { v: Int ->
            if (v == ToneAblation.NO_EXP) ToneAblation.EXACT_SHOULDER else ToneAblation.FULL
        }

        var everythingWins = 0
        for ((label, variant) in named) {
            val raw = bench(against(variant), variant, ROUNDS)
            assertThat(raw).isNotNull()
            val run = Run(raw!!)
            Log.i(
                TAG,
                "removing %s: %.2fx, %d of %d rounds  (full %s, without %s)".format(
                    label, run.ratio(), run.wins(), ROUNDS,
                    run.describe(run.a), run.describe(run.b),
                ),
            )
            if (variant == ToneAblation.NONE) everythingWins = run.wins()
        }
        DeviceKind.warnIfNotAPhone(TAG)

        // The only claim that must hold whatever the split turns out to be:
        // doing the colour, the curve and the quantise costs something, so the
        // pass without them is faster. If this fails, the harness is measuring
        // its own overhead and every figure above it is noise.
        assertThat(everythingWins).isAtLeast(ROUNDS - ROUNDS / 10)
    }

    /**
     * The table renders the same photograph as the arithmetic it replaced.
     *
     * This is the first approximation in the render. `DisplayLut` collapses the
     * same kind of chain into a table and is exact, because its input had
     * already been quantised to the index the table is addressed by; the scene
     * peak has not, so the roll-off's table is a rounding of a curve and no
     * argument from exactness is available.
     *
     * What is available is a measurement, and it has to be the one that matters:
     * not the error in the scale, which nobody looks at, but the bytes of the
     * photograph. Twelve and a half megapixels rendered both ways, every channel
     * of every pixel compared.
     *
     * **Identical is the wrong bar and it took a measurement to see why.** With
     * the table interpolated the scale is out by around 1e-6, two orders below
     * the 1/4096 bin `DisplayLut` sorts the linear value into -- so a byte can
     * only differ where a pixel happened to sit that close to a bin boundary,
     * and no refinement of the table removes those, because nothing bounds how
     * close a pixel can land. 800 bytes of 50 million do, every one of them by a
     * single code: one byte in 62,000. So the assertion is the shape of the
     * error rather than its absence: **no byte more than one code out, and fewer
     * than one byte in twenty thousand out at all.** Both would break loudly if
     * the table were ever mis-built, which is what a test is for -- a wrong
     * table differs in millions, not hundreds.
     */
    @Test
    fun theRollOffsTableRendersTheSamePhotographAsTheArithmetic() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val raw = bench(ToneAblation.FULL, ToneAblation.EXACT_SHOULDER, ROUNDS)
        assertThat(raw).isNotNull()
        val run = Run(raw!!)
        val total = width.toLong() * height * 4
        Log.i(
            TAG,
            "tabulated against computed: %d of %d bytes differ, worst by %d".format(
                run.differing, total, run.worstByte,
            ),
        )
        Log.i(TAG, "tabulated against computed: ${run.pairing()}")
        assertThat(run.worstByte).isAtMost(1L)
        assertThat(run.differing).isLessThan(total / 20_000)
    }

    /**
     * Splitting the demosaic's loop by parity reconstructs the same picture.
     *
     * Unlike the roll-off's table this one has no reason to differ at all: it
     * is the same two arithmetic bodies reached a different way, with nothing
     * rounded and nothing approximated. The one thing that could make it differ
     * is the compiler, which is licensed by `-ffast-math` to reassociate and
     * fuse the two loops differently because their surroundings differ -- the
     * shading pass came back a fifth different for exactly that reason, by one
     * unit in the last place.
     *
     * So the bar is bytes again, and stated rather than assumed.
     */
    @Test
    fun vectorisingTheDemosaicReconstructsTheSamePicture() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val raw = bench(ToneAblation.FULL, ToneAblation.SCALAR_DEMOSAIC, ROUNDS)
        assertThat(raw).isNotNull()
        val run = Run(raw!!)
        val total = width.toLong() * height * 4
        Log.i(
            TAG,
            "vector against scalar: %d of %d bytes differ, worst by %d".format(
                run.differing, total, run.worstByte,
            ),
        )
        // The same call answers both questions, so it may as well report both.
        Log.i(TAG, "vector against scalar: ${run.pairing()}")
        assertThat(run.worstByte).isAtMost(1L)
        assertThat(run.differing).isLessThan(total / 20_000)
    }

    /**
     * The roll-off's price is a property of the photograph, not of the code.
     *
     * `renderLinear`'s work sits behind `if (scenePeak > knee)`, so what it
     * costs depends on how much of the frame is bright enough to need it. A
     * harness that reported one figure would be reporting its own scene. This
     * runs the same ablation at two exposures and prints the share of the frame
     * above the knee beside each, so the figure comes with the picture it is
     * about.
     */
    @Test
    fun whatTheRollOffCostsDependsOnHowBrightTheSceneIs() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        for (gain in listOf(3.5f, 1.1f)) {
            val cost = Run(bench(ToneAblation.FULL, ToneAblation.NO_RENDER, ROUNDS, gain)!!)
            Log.i(
                TAG,
                ("at gain %.1f, %s%% of the frame is above the knee: " +
                    "renderLinear costs %.2fx, %d of %d rounds (full %s)").format(
                    gain, cost.aboveKneePercent(), cost.ratio(),
                    cost.wins(), ROUNDS, cost.describe(cost.a),
                ),
            )

            // The table is read unconditionally where the arithmetic sat behind
            // `if (p > knee)`, so a frame with nothing above the knee now pays
            // for a lookup it used to skip. That is the one way this change
            // could have made something worse, and it is cheap to check.
            val bought = Run(bench(ToneAblation.FULL, ToneAblation.EXACT_SHOULDER, ROUNDS, gain)!!)
            Log.i(
                TAG,
                "  and the table is worth %.2fx there, winning %d of %d rounds".format(
                    1.0 / bought.ratio(), ROUNDS - bought.wins(), ROUNDS,
                ),
            )
            // Never *materially* slower, at either end of the range of scenes.
            //
            // Not `ROUNDS / 2`, which is what this said first. Half the rounds
            // is where a true null sits, so requiring the count to fall below it
            // fails **40% of the time when nothing is wrong** -- and that is the
            // second time in three commits a threshold here has been set from a
            // round number instead of from the distribution. Three quarters
            // costs 1.1% and still catches the regression it exists for, which
            // was 14 of 16.
            assertThat(bought.wins()).isAtMost(ROUNDS * 3 / 4)
        }
        DeviceKind.warnIfNotAPhone(TAG)
    }

    /** One call's worth of results: two slots per round, then two summaries. */
    private inner class Run(raw: LongArray) {
        private val rounds = (raw.size - 3) / 2
        val a = LongArray(rounds) { raw[it * 2] }
        val b = LongArray(rounds) { raw[it * 2 + 1] }
        val differing = raw[rounds * 2]
        private val aboveKnee = raw[rounds * 2 + 1]
        val worstByte = raw[rounds * 2 + 2]

        fun median(v: LongArray) = v.sorted()[v.size / 2]

        fun wins() = a.indices.count { b[it] < a[it] }

        fun aboveKneePercent() =
            "%.0f".format(100.0 * aboveKnee / (width.toLong() * height))

        /**
         * The paired ratio, which is what survives a phone that is heating up.
         *
         * A difference of medians does not. Read on a throttling phone the same
         * comparison gave 104 ms of 320 and, twenty seconds later, 269 ms of
         * 419 -- both true, neither transferable, because the whole pass had
         * slowed by half in between. The ratio of the two runs *within* a round
         * is unchanged by that, which is the same reason the comparison is
         * paired in the first place.
         */
        fun ratio(): Double {
            val ratios = a.indices.map { a[it].toDouble() / b[it] }.sorted()
            return ratios[ratios.size / 2]
        }

        fun pairing() =
            "won %d of %d rounds; per-round ratio median %.2fx".format(
                wins(), rounds, ratio(),
            )

        fun describe(v: LongArray) =
            "median ${median(v) / 1000}ms, range ${v.min() / 1000}-${v.max() / 1000}ms"

        fun describeFull(v: LongArray) =
            describe(v) + "; " + v.joinToString(", ") { "${it / 1000}" }
    }

    private companion object {
        const val ROUNDS = 16

        /** Forty, not twenty, so the A/A's band is not a coin flip. See above. */
        const val AA_ROUNDS = 40
    }
}
