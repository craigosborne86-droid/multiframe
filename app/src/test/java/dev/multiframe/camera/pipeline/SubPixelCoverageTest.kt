package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.round
import kotlin.random.Random

/**
 * The coverage prediction, held to the geometry it claims to describe.
 *
 * The strongest test here is the one against the accumulator itself: on a
 * burst whose frames all agree with the reference -- so the robustness weight
 * is one everywhere and the only holes are geometric -- the fraction the
 * prediction gives per channel must be the fraction the canvas measures. The
 * rest pin the cases the prediction exists to tell apart.
 */
class SubPixelCoverageTest {

    private val sensorW = 64
    private val sensorH = 48
    private val cfa = intArrayOf(0, 1, 1, 2)
    private val profile = SensorProfile(
        blackLevel = intArrayOf(64, 64, 64, 64),
        whiteLevel = 1023,
        cfaPattern = cfa,
    )

    private val tilesX = maxOf(1, (sensorW / 2) / SubPixelAccumulator.TILE_TARGET)
    private val tilesY = maxOf(1, (sensorH / 2) / SubPixelAccumulator.TILE_TARGET)

    /** A uniform shift of ([sx], [sy]) sensor pixels, as the aligner would report it. */
    private fun fieldFor(sx: Float, sy: Float): AlignmentField {
        val n = tilesX * tilesY
        val proxyX = -sx / 2f
        val proxyY = -sy / 2f
        val ix = round(proxyX).toInt()
        val iy = round(proxyY).toInt()
        return AlignmentField(
            tilesX, tilesY,
            IntArray(n) { ix }, IntArray(n) { iy },
            FloatArray(n) { proxyX - ix }, FloatArray(n) { proxyY - iy },
        )
    }

    /** A smooth scene, so every alternate agrees with the reference and nothing is rejected. */
    private fun frame(sx: Float, sy: Float): BayerFrame {
        val data = ShortArray(sensorW * sensorH)
        for (y in 0 until sensorH) {
            for (x in 0 until sensorW) {
                val c = Demosaic.colourAt(profile, x, y)
                val u = (x + sx) / sensorW
                val v = (y + sy) / sensorH
                val level = 200f + 500f * (0.4f * u + 0.6f * v)
                val tint = when (c) { 0 -> 1.1f; 2 -> 0.9f; else -> 1f }
                data[y * sensorW + x] = (64 + level * tint).toInt().coerceIn(0, 1023).toShort()
            }
        }
        return BayerFrame(sensorW, sensorH, data)
    }

    private fun measured(shifts: List<Pair<Float, Float>>): FloatArray {
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(frame(0f, 0f))
        for ((sx, sy) in shifts) acc.add(frame(sx, sy), fieldFor(sx, sy))
        return acc.finish().interiorHolesPerChannel
    }

    private fun predicted(shifts: List<Pair<Float, Float>>) =
        SubPixelCoverage.predict(shifts.map { (sx, sy) -> fieldFor(sx, sy) }, cfa)

    // --- against the accumulator -------------------------------------------

    @Test
    fun aTripodBurstIsPredictedExactly() {
        val shifts = listOf(0f to 0f, 0f to 0f, 0f to 0f)
        val p = predicted(shifts)
        val m = measured(shifts)
        for (c in 0 until 3) assertThat(p.perChannel[c]).isWithin(1e-3f).of(m[c])
        // The reference alone reaches five of the sixteen cells in a red
        // period and ten of sixteen in green's.
        assertThat(p.perChannel[0]).isWithin(1e-3f).of(11f / 16f)
        assertThat(p.perChannel[1]).isWithin(1e-3f).of(6f / 16f)
        assertThat(p.worthAttempting).isFalse()
    }

    @Test
    fun aQuadShiftIsPredictedExactly() {
        val shifts = listOf(0.5f to 0f, 0f to 0.5f, 0.5f to 0.5f)
        val p = predicted(shifts)
        val m = measured(shifts)
        for (c in 0 until 3) assertThat(p.perChannel[c]).isWithin(0.01f).of(m[c])
        // Green closes; red and blue keep the quarter §3.6.3 measured.
        assertThat(p.perChannel[1]).isWithin(1e-3f).of(0f)
        assertThat(p.perChannel[0]).isWithin(1e-3f).of(0.25f)
        assertThat(p.perChannel[2]).isWithin(1e-3f).of(0.25f)
    }

    @Test
    fun aFullLatticeClosesEveryChannel() {
        val shifts = ArrayList<Pair<Float, Float>>()
        for (sy in 0 until 4) for (sx in 0 until 4) {
            if (sx == 0 && sy == 0) continue
            shifts.add(sx * 0.5f to sy * 0.5f)
        }
        val p = predicted(shifts)
        val m = measured(shifts)
        for (c in 0 until 3) {
            assertThat(p.perChannel[c]).isWithin(1e-3f).of(0f)
            assertThat(m[c]).isWithin(1e-3f).of(0f)
        }
        assertThat(p.worthAttempting).isTrue()
    }

    // --- the decision ------------------------------------------------------

    @Test
    fun aHandheldBurstOfEightIsWorthAttempting() {
        // Offsets a hand produces: a fraction of a pixel in each axis, spread
        // over the period. Deterministic, and the values are not special.
        val rng = Random(7)
        val shifts = List(7) { rng.nextFloat() * 2f - 1f to rng.nextFloat() * 2f - 1f }
        val p = predicted(shifts)
        assertThat(p.frames).isEqualTo(8)
        assertThat(p.perChannel[1]).isAtMost(SubPixelCoverage.MAX_GREEN_HOLES)
        // Red and blue are a quarter as densely sampled and this is where the
        // chroma bound earns its looseness.
        assertThat(p.perChannel[0]).isAtMost(SubPixelCoverage.MAX_CHROMA_HOLES)
        assertThat(p.worthAttempting).isTrue()
    }

    @Test
    fun moreFramesCoverMore() {
        val rng = Random(11)
        val all = List(23) { rng.nextFloat() * 2f - 1f to rng.nextFloat() * 2f - 1f }
        val eight = predicted(all.take(7))
        val twentyFour = predicted(all)
        for (c in 0 until 3) assertThat(twentyFour.perChannel[c]).isAtMost(eight.perChannel[c])
    }

    @Test
    fun aSingleFrameIsNotACanvas() {
        val p = SubPixelCoverage.predict(emptyList(), cfa)
        assertThat(p.frames).isEqualTo(1)
        assertThat(p.worthAttempting).isFalse()
    }

    @Test
    fun aWholeProxyPixelOfShiftChangesNothing() {
        // The integer part moves a sample by four cells, which is the period.
        val still = predicted(listOf(0f to 0f))
        val moved = predicted(listOf(2f to 0f, 0f to -4f))
        for (c in 0 until 3) assertThat(moved.perChannel[c]).isWithin(1e-4f).of(still.perChannel[c])
    }

    @Test
    fun theFractionWrapsAtThePeriod() {
        // Half a proxy pixel either way is the same phase.
        val plus = predicted(listOf(1f to 1f))
        val minus = predicted(listOf(-1f to -1f))
        for (c in 0 until 3) assertThat(plus.perChannel[c]).isWithin(1e-4f).of(minus.perChannel[c])
    }

    @Test
    fun theMeasuredBoundIsTheSameRule() {
        assertThat(SubPixelCoverage.Prediction.acceptable(0.2f, 0.01f, 0.2f)).isTrue()
        assertThat(SubPixelCoverage.Prediction.acceptable(0.2f, 0.03f, 0.2f)).isFalse()
        assertThat(SubPixelCoverage.Prediction.acceptable(0.3f, 0.01f, 0.2f)).isFalse()
        assertThat(SubPixelCoverage.Prediction.acceptable(0.2f, 0.01f, 0.3f)).isFalse()
    }
}
