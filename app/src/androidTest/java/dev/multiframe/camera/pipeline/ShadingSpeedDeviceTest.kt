package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ShadingSpeed"

/**
 * The shading pass, measured against itself before it is measured against
 * anything else.
 *
 * Every A/B this project has run until now compared two installs, and the
 * develop harness pointed at its own binary separates it from itself by two and
 * a half times: the reinstall is where the variance lives, and a difference of
 * medians across one cannot be believed. So both implementations of this pass
 * are built into the binary and timed back to back inside one process, which is
 * what [JpegEncodeSpeedDeviceTest] does for the two encoders and for the same
 * reason.
 *
 * The A/A runs first and is not a formality. If the instrument can separate the
 * fast path from the fast path, nothing it says about the fast path against the
 * slow one means anything, and this test would rather report that than a
 * speedup.
 */
@RunWith(AndroidJUnit4::class)
class ShadingSpeedDeviceTest {

    private val width = 4080
    private val height = 3072

    /**
     * A map shaped like the one the camera reports: about 17 by 13 cells, gains
     * rising to roughly 3.5 in the corners, and different per channel because
     * real falloff is a colour shift as well as a darkening.
     *
     * The shape matters to the timing, not just the correctness. The hoisting
     * being measured is worth what it is because a cell covers 240 pixels; a
     * finer grid would shorten the runs and buy less.
     */
    private fun cameraLikeMap(): ShadingMap {
        val columns = 17
        val rows = 13
        val gains = FloatArray(columns * rows * 4)
        val cx = (columns - 1) / 2f
        val cy = (rows - 1) / 2f
        val far = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val t = kotlin.math.hypot((c - cx).toDouble(), (r - cy).toDouble())
                    .toFloat() / far
                for (channel in 0 until 4) {
                    gains[(r * columns + c) * 4 + channel] =
                        1f + (2.3f + 0.25f * channel) * t * t
                }
            }
        }
        return ShadingMap(columns, rows, gains)
    }

    /** As shot on this phone under tungsten: R x2.05, B x1.44. */
    private val balance = floatArrayOf(2.052f, 1f, 1f, 1.442f)

    /**
     * The fold, measured against itself before it is measured against the three.
     *
     * Same reason as the shading A/A below, and one more that is specific to
     * this pair: the two slots do not write the same amount. The three-pass slot
     * sweeps a 50 MB plane three times and the folded one sweeps it once, so if
     * the harness had any bias from the order or from the state one slot leaves
     * the cache in, it would land on exactly the difference being claimed.
     */
    @Test
    fun theHarnessCannotSeparateTheFoldedPrepassFromItself() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val result = NativeMerge.prepassBench(
            width, height, SensorProfile.DEFAULT, balance, cameraLikeMap(),
            DevelopParams().hotPixelThreshold, PREPASS_ROUNDS, selfCheck = true,
        )
        assertThat(result).isNotNull()
        val (left, right, differing, worst) = split(result!!)

        Log.i(TAG, "prepass A/A left:  ${describe(left)}")
        Log.i(TAG, "prepass A/A right: ${describe(right)}")
        Log.i(TAG, "prepass A/A ${pairing(left, right)}")
        DeviceKind.warnIfNotAPhone(TAG)

        // The same code in both slots must produce the same plane. It is one
        // sweep with no ordering between its bands, so this is bit equality and
        // not a bound -- and it is the claim the race fix made true: reading a
        // site that another band is correcting used to make the develop depend
        // on which thread arrived first.
        assertThat(differing).isEqualTo(0L)
        assertThat(worst).isEqualTo(0L)

        val wins = wins(left, right)
        assertThat(wins).isAtLeast(PREPASS_ROUNDS * 3 / 10)
        assertThat(wins).isAtMost(PREPASS_ROUNDS * 7 / 10)
    }

    /**
     * Three sweeps of a 50 MB plane against one, and the same plane out of both.
     *
     * `black`, `hotpixels` and `shading` were three passes because the middle
     * one has to compare values black-subtracted and not yet shaded. It does not
     * have to compare them in the plane: the four nearest sites of the same
     * colour are two pixels away, +-2 preserves CFA parity, so all five share a
     * black level and can be read straight out of the merged `uint16`. Nothing
     * is written before it is read, so there is no halo and no lag -- and no
     * band edge to race on.
     *
     * Before it was built, folding *one* of the three priced at 1.25-1.29x, 57
     * of 60 rounds. This is what folding all three came to.
     */
    @Test
    fun foldingTheThreePreparatoryPassesIntoOneIsFasterAndDoesNotChangeThePlane() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val result = NativeMerge.prepassBench(
            width, height, SensorProfile.DEFAULT, balance, cameraLikeMap(),
            DevelopParams().hotPixelThreshold, PREPASS_ROUNDS, selfCheck = false,
        )
        assertThat(result).isNotNull()
        val (three, folded, differing, worst) = split(result!!)

        val total = width.toLong() * height * PREPASS_ROUNDS
        Log.i(TAG, "three passes: ${describe(three)}")
        Log.i(TAG, "folded:       ${describe(folded)}")
        Log.i(TAG, "folded ${pairing(three, folded)}")
        Log.i(
            TAG,
            "%.2fx by paired median; %d of %d values differ, worst by %.3g".format(
                median(three).toDouble() / median(folded),
                differing, total, worst / 1e9,
            ),
        )
        DeviceKind.warnIfNotAPhone(TAG)

        // Not bit equality, for the reason the shading rearrangement below
        // records: -ffast-math lets the compiler fuse and reassociate the same
        // expression differently in two different surroundings, and a last-place
        // difference on a normalised value is around 1e-7.
        //
        // What this bound rules out is the thing that would matter, and it is
        // not rounding. A site the two disagreed about would be one the fold
        // called defective and the three passes did not, or the reverse, and
        // replacing a site moves it by at least the threshold -- a tenth of full
        // scale, four orders of magnitude above this bound. So does dropping the
        // clamp at the black level, which is what makes the raw-domain
        // comparison exact rather than nearly exact.
        assertThat(worst / 1e9).isLessThan(1e-5)

        // The paired-median ratio, for the same reason the shading A/B below
        // moved away from a round count: the folded pass is fast enough on this
        // phone that scheduling noise decides individual rounds, and a win count
        // threshold calibrated on slower hardware becomes flaky. The median of
        // within-round ratios keeps the pairing while being immune to the rounds
        // noise decides. Measured: A/A null sits at 1.00-1.07x, the fold at
        // 1.51x at its lowest, so 1.25x separates the two populations.
        assertThat(perRoundRatioMedian(three, folded)).isGreaterThan(1.25)
    }

    @Test
    fun theHarnessCannotSeparateTheShadingPassFromItself() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val result = NativeMerge.shadingBench(
            width, height, SensorProfile.DEFAULT, balance, cameraLikeMap(),
            ROUNDS, selfCheck = true,
        )
        assertThat(result).isNotNull()
        val (left, right, differing, worst) = split(result!!)

        Log.i(TAG, "A/A left:  ${describe(left)}")
        Log.i(TAG, "A/A right: ${describe(right)}")
        Log.i(TAG, "A/A ${pairing(left, right)}")
        DeviceKind.warnIfNotAPhone(TAG)

        // The same code in both slots must produce the same plane, or the pass
        // is not deterministic and no comparison at all is possible.
        assertThat(differing).isEqualTo(0L)
        assertThat(worst).isEqualTo(0L)

        // The instrument passes when it *fails* to tell the two apart, and the
        // claim it has to fail to make is the paired one, because that is the
        // claim the A/B will make. A slot that wins two rounds in three is a
        // harness with a thumb on the scale -- from the order of the two, or
        // from one of them always writing the buffer the other just read.
        val wins = wins(left, right)
        assertThat(wins).isAtLeast(ROUNDS * 3 / 10)
        assertThat(wins).isAtMost(ROUNDS * 7 / 10)
    }

    @Test
    fun hoistingTheGridOutOfTheLoopIsFasterAndDoesNotChangeThePicture() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val result = NativeMerge.shadingBench(
            width, height, SensorProfile.DEFAULT, balance, cameraLikeMap(),
            ROUNDS, selfCheck = false,
        )
        assertThat(result).isNotNull()
        val (reference, hoisted, differing, worst) = split(result!!)

        val total = width.toLong() * height * ROUNDS
        Log.i(TAG, "per-pixel: ${describe(reference)}")
        Log.i(TAG, "hoisted:   ${describe(hoisted)}")
        Log.i(TAG, "hoisted ${pairing(reference, hoisted)}")
        Log.i(
            TAG,
            "%.2fx by paired median; %d of %d values differ, worst by %.3g".format(
                median(reference).toDouble() / median(hoisted),
                differing, total, worst / 1e9,
            ),
        )
        DeviceKind.warnIfNotAPhone(TAG)

        // Not bit equality, and it was written expecting to be.
        //
        // Every operation survives the rearrangement in the same order and the
        // same association, which under plain IEEE arithmetic would make the
        // two answers identical. The build compiles with -ffast-math, so the
        // compiler is licensed to reassociate and to contract a multiply and an
        // add into one fused instruction -- and it takes that licence
        // differently in the two loops, because their surroundings differ.
        // About a fifth of the plane comes back one unit in the last place
        // away, which is a difference of around 1e-7 on values running to 3.5.
        //
        // So the bound is stated rather than assumed. It is on the linear
        // plane, which still has an exposure gain, a tone curve, a gamma
        // encode and an 8-bit quantise ahead of it; carrying 1e-7 through the
        // steepest part of that -- near black, where the sRGB encode has the
        // most slope -- lands it around a thousandth of a display code. What
        // this bound rules out is the thing that would matter: a wrong corner,
        // a run picking up its neighbour's gains, an off-by-one in the grid.
        // Any of those is a whole-number difference, not a last-place one.
        assertThat(worst / 1e9).isLessThan(1e-5)

        // Separation, but the paired kind. Pooling the two sides and asking
        // for a gap between their ranges is the rule this project uses across
        // installs, and here it is the wrong test: the phone wanders by a
        // factor of three over a few seconds, so the pooled ranges overlap
        // however large the difference is. The A/A above shows the wander is
        // shared by two runs a few milliseconds apart, which is exactly what
        // makes the within-round comparison the one worth making -- and it
        // shows the pairing is not itself biased, which is what earns the
        // right to read anything into this line.
        //
        // ### Why this is a median and no longer a round count
        //
        // It used to read `wins(reference, hoisted) >= ROUNDS - ROUNDS / 10`,
        // thirty-six of forty. That threshold was calibrated on komodo, where
        // this pass took tens of milliseconds. On grizzly the hoisted pass runs
        // in five to eight milliseconds through the fast part of a run, which is
        // small enough that scheduling noise decides individual rounds. The
        // count then lands wherever the noise puts it: 30, 31, 33, 34, 35, 36
        // and 39 across seven runs here, warm and cold alike, against a bar of
        // 36. A test that passes two times in seven is not measuring anything.
        //
        // BASELINE.md, under *The hoist has outrun its own test*, is explicit
        // that the answer is not to relax the count -- that would hide the
        // finding -- but either to give the hoist more work per round or to
        // "drop the round-count assertion for a paired-median one and say why".
        // This is that, and this is why.
        //
        // The median of the per-round ratios is the right statistic because it
        // keeps the pairing the paragraph above argues for while being immune
        // to the rounds the noise decides. What it reads, measured:
        //
        //     A/A, the null      1.00x, 1.02x, 1.03x
        //     the hoist          1.53x, 1.62x, and 1.77x in BASELINE.md
        //     the hoist, komodo  1.52x, 1.57x
        //
        // So 1.25x, which noise cannot reach from a null of 1.00-1.03 and a
        // working hoist cannot miss from 1.53 at its lowest. It is a bound
        // between two measured populations rather than a number fitted to the
        // last run, which is the property the old count had lost.
        assertThat(perRoundRatioMedian(reference, hoisted)).isGreaterThan(1.25)
    }

    /** Not a data class: it holds arrays, which have no useful equality. */
    private class Split(
        val a: LongArray,
        val b: LongArray,
        val differing: Long,
        val worst: Long,
    ) {
        operator fun component1() = a
        operator fun component2() = b
        operator fun component3() = differing
        operator fun component4() = worst
    }

    private fun split(raw: LongArray): Split {
        val rounds = (raw.size - 2) / 2
        return Split(
            LongArray(rounds) { raw[it * 2] },
            LongArray(rounds) { raw[it * 2 + 1] },
            raw[rounds * 2],
            raw[rounds * 2 + 1],
        )
    }

    private fun median(v: LongArray) = v.sorted()[v.size / 2]

    /** Rounds in which the second slot beat the first, on the same cores. */
    private fun wins(a: LongArray, b: LongArray) = a.indices.count { b[it] < a[it] }

    /**
     * The within-round ratios, ordered. Pairing is what makes these readable:
     * both slots meet the same governor and the same neighbours in the round
     * they share, so the phone's wander divides out instead of accumulating.
     */
    private fun ratios(a: LongArray, b: LongArray) =
        a.indices.map { a[it].toDouble() / b[it] }.sorted()

    /** The paired statistic the A/B assertions are stated in. */
    private fun perRoundRatioMedian(a: LongArray, b: LongArray) =
        ratios(a, b).let { it[it.size / 2] }

    private fun pairing(a: LongArray, b: LongArray): String {
        val ratios = ratios(a, b)
        return "won %d of %d rounds; per-round ratio median %.2fx, worst %.2fx".format(
            wins(a, b), a.size, ratios[ratios.size / 2], ratios.first(),
        )
    }

    private fun describe(v: LongArray) =
        v.joinToString(", ") { "${it / 1000}.${(it % 1000) / 100}ms" } +
            "; median ${median(v) / 1000}ms, range ${v.min() / 1000}-${v.max() / 1000}ms"

    private companion object {
        const val ROUNDS = 40

        /**
         * Forty here too, and for the reason in the log rather than for
         * symmetry: at twenty rounds the 30-70% band an A/A is held to is
         * outside a fair coin's range 4.1% of the time, which is a test that
         * cries wolf once a session. At forty it is 0.6%.
         */
        const val PREPASS_ROUNDS = 40
    }
}
