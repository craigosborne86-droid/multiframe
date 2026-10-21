package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Lucas-Kanade refinement of [Aligner.refineSubPixel], on the Kotlin
 * reference. The scenes are sums of sinusoids, so a shifted frame is the
 * scene evaluated at shifted coordinates -- an exact displacement, not an
 * interpolated one -- quantised to the proxy's eight bits, with seeded noise.
 */
class SubPixelRefinementTest {

    private val width = 256
    private val height = 192
    private val tilesX = 8
    private val tilesY = 6

    /** A band-limited scene: six sinusoids up to 0.15 cycles/pixel, centred on mid-grey. */
    private class Scene(seed: Int) {
        private val rnd = Random(seed)
        private val waves = List(6) {
            val f = 0.03 + 0.12 * rnd.nextDouble()
            val angle = PI * rnd.nextDouble()
            doubleArrayOf(f * kotlin.math.cos(angle), f * kotlin.math.sin(angle), 2 * PI * rnd.nextDouble(), 12 + 6 * rnd.nextDouble())
        }
        fun at(x: Double, y: Double) = 128 + waves.sumOf { w -> w[3] * sin(2 * PI * (w[0] * x + w[1] * y) + w[2]) }
    }

    /** The scene displaced by ([sx], [sy]): alt(x + s) = ref(x), with Gaussian noise of [sigma] codes. */
    private fun frame(scene: Scene, sx: Double, sy: Double, sigma: Double, seed: Int): ByteArray {
        val rnd = java.util.Random(seed.toLong())
        return ByteArray(width * height) { i ->
            val v = scene.at((i % width) - sx, (i / width) - sy) + sigma * rnd.nextGaussian()
            v.roundToInt().coerceIn(0, 255).toByte()
        }
    }

    private fun align(ref: ByteArray, alt: ByteArray) = Aligner.align(
        Aligner.buildPyramid(Plane(width, height, ref)),
        Aligner.buildPyramid(Plane(width, height, alt)),
        tilesX, tilesY,
    )

    private fun interior() = (1 until tilesY - 1).flatMap { ty -> (1 until tilesX - 1).map { ty * tilesX + it } }

    /**
     * The property it exists for: a known fractional shift recovered to a
     * twentieth of a proxy pixel on every interior tile, at the low noise of a
     * well-lit burst. The fit it replaced held to a fifth on the field mean.
     */
    @Test
    fun knownShiftsAreRecoveredWithinAFifthOfATenthOfAPixel() {
        val shifts = listOf(0.3 to -0.45, 1.7 to 0.25, -2.5 to 3.1, 0.5 to 0.5, -0.12 to 0.88)
        shifts.forEachIndexed { k, (sx, sy) ->
            val scene = Scene(11 + k)
            val field = align(frame(scene, 0.0, 0.0, 1.0, 100 + k), frame(scene, sx, sy, 1.0, 200 + k))
            for (i in interior()) {
                assertThat(field.hasSubPixelAt(i)).isTrue()
                assertThat((field.dx[i] + field.fracDx[i]).toDouble()).isWithin(0.05).of(sx)
                assertThat((field.dy[i] + field.fracDy[i]).toDouble()).isWithin(0.05).of(sy)
            }
        }
    }

    /** The merge reads the integers alone; they are still the search's, within a pixel of the truth. */
    @Test
    fun theIntegerWinnersAreTheSearchesAndTheFractionStaysWithinAPixel() {
        val scene = Scene(3)
        val field = align(frame(scene, 0.0, 0.0, 1.0, 1), frame(scene, 2.6, -1.4, 1.0, 2))
        for (i in interior()) {
            assertThat(abs(field.dx[i] - 2.6)).isAtMost(1.0)
            assertThat(abs(field.dy[i] + 1.4)).isAtMost(1.0)
            assertThat(abs(field.fracDx[i])).isAtMost(1f)
            assertThat(abs(field.fracDy[i])).isAtMost(1f)
        }
    }

    /** Byte-identical frames: no residual, no step, a fraction of exactly nothing. */
    @Test
    fun byteIdenticalFramesRefineToZero() {
        val ref = frame(Scene(5), 0.0, 0.0, 1.0, 9)
        val field = align(ref, ref.copyOf())
        for (i in 0 until tilesX * tilesY) {
            assertThat(field.dx[i]).isEqualTo(0)
            assertThat(field.dy[i]).isEqualTo(0)
            assertThat(field.fracDx[i]).isWithin(0.01f).of(0f)
            assertThat(field.fracDy[i]).isWithin(0.01f).of(0f)
        }
    }

    /** A flat tile has no Hessian to invert: the fit declines and the field borrows for it. */
    @Test
    fun aFlatTileDeclines() {
        val flat = ByteArray(width * height) { 128.toByte() }
        val field = align(flat, flat.copyOf())
        assertThat(field.hasSubPixel).isFalse()

        val out = FloatArray(2) { 9f }
        Aligner.refineSubPixel(Plane(width, height, flat), Plane(width, height, flat), 64, 64, 32, 32, 0, 0, out)
        assertThat(out.toList()).containsExactly(0f, 0f)
    }

    /**
     * Stripes that vary along x only: the translation along y is not
     * determined, and the fit declines both axes rather than invent one.
     */
    @Test
    fun aOneDirectionalTileDeclines() {
        val stripes = { s: Double -> ByteArray(width * height) { i -> (128 + 60 * sin(2 * PI * ((i % width) - s) / 9.0)).roundToInt().toByte() } }
        val out = FloatArray(2) { 9f }
        Aligner.refineSubPixel(Plane(width, height, stripes(0.0)), Plane(width, height, stripes(0.4)), 64, 64, 32, 32, 0, 0, out)
        assertThat(out.toList()).containsExactly(0f, 0f)
    }

    /** Pure noise: every other test passes, the gradient is no stronger than the residual, and the fit declines. */
    @Test
    fun aTileOfNoiseDeclines() {
        val rnd = java.util.Random(4)
        val noise = { ByteArray(width * height) { (128 + 4 * rnd.nextGaussian()).roundToInt().toByte() } }
        val out = FloatArray(2) { 9f }
        Aligner.refineSubPixel(Plane(width, height, noise()), Plane(width, height, noise()), 64, 64, 32, 32, 0, 0, out)
        assertThat(out.toList()).containsExactly(0f, 0f)
    }

    /** Started three pixels from the truth, the fit leaves its pixel of reach and declines; it does not report a fraction. */
    @Test
    fun aWrongIntegerIsNotPassedOffAsAFraction() {
        val scene = Scene(8)
        val ref = Plane(width, height, frame(scene, 0.0, 0.0, 1.0, 1))
        val alt = Plane(width, height, frame(scene, 3.3, 0.0, 1.0, 2))
        val out = FloatArray(2) { 9f }
        Aligner.refineSubPixel(ref, alt, 96, 64, 32, 32, 0, 0, out)
        assertThat(out.toList()).containsExactly(0f, 0f)
        // From the right integer the same tile resolves.
        Aligner.refineSubPixel(ref, alt, 96, 64, 32, 32, 3, 0, out)
        assertThat(3 + out[0].toDouble()).isWithin(0.05).of(3.3)
    }
}
