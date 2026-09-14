package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The colour denoise, held to its three promises: the speckle goes, the
 * luminance does not move, and colour does not cross a brightness edge.
 */
class ChromaSmoothTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun luma(p: Int) = ChromaSmooth.luma(red(p), green(p), blue(p))

    /** A grey wall with per-pixel colour speckle, as a high-ISO frame has. */
    private fun speckledGrey(w: Int, h: Int, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) {
            // Colour noise about grey 128, luminance held near 128 by construction.
            val dr = rnd.nextInt(-30, 31)
            val db = rnd.nextInt(-30, 31)
            val r = 128 + dr
            val b = 128 + db
            val g = ((128f - 0.2126f * r - 0.0722f * b) / 0.7152f).toInt()
            argb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }

    private fun chromaSpread(pixels: IntArray): Float {
        var sum = 0f
        for (p in pixels) {
            val y = luma(p)
            sum += abs(red(p) - y) + abs(blue(p) - y)
        }
        return sum / pixels.size
    }

    @Test
    fun `colour speckle on a grey wall is wiped and the wall stays grey`() {
        val w = 64
        val h = 64
        val pixels = speckledGrey(w, h)
        val before = chromaSpread(pixels)
        val lumaBefore = FloatArray(pixels.size) { luma(pixels[it]) }
        ChromaSmooth.apply(pixels, w, h)
        val after = chromaSpread(pixels)
        // A 5x5 mean of independent speckle is a fifth of its spread.
        assertThat(after).isLessThan(before * 0.3f)
        // And the luminance of every pixel is what it was, to the rounding.
        for (i in pixels.indices) {
            assertThat(luma(pixels[i])).isWithin(1.5f).of(lumaBefore[i])
        }
    }

    @Test
    fun `colour does not cross a brightness edge`() {
        // A dark red field beside a bright green one: the edge is a luminance
        // edge, and the filter must not pull green into the red or red into
        // the green.
        val w = 32
        val h = 16
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) argb(120, 20, 20) else argb(60, 220, 60) }
        val before = pixels.copyOf()
        ChromaSmooth.apply(pixels, w, h)
        // Well inside each field nothing changed; at the edge each side kept
        // its own colour, since the other side's luminance is far away.
        for (y in 0 until h) {
            val leftEdge = pixels[y * w + w / 2 - 1]
            val rightEdge = pixels[y * w + w / 2]
            assertThat(red(leftEdge)).isWithin(3).of(red(before[y * w + w / 2 - 1]))
            assertThat(green(leftEdge)).isWithin(3).of(green(before[y * w + w / 2 - 1]))
            assertThat(green(rightEdge)).isWithin(3).of(green(before[y * w + w / 2]))
            assertThat(red(rightEdge)).isWithin(3).of(red(before[y * w + w / 2]))
        }
    }

    @Test
    fun `a flat colour is untouched and a zero amount does nothing`() {
        val w = 16
        val h = 16
        val flat = IntArray(w * h) { argb(180, 90, 40) }
        val copy = flat.copyOf()
        ChromaSmooth.apply(flat, w, h)
        for (i in flat.indices) {
            assertThat(red(flat[i])).isWithin(1).of(red(copy[i]))
            assertThat(green(flat[i])).isWithin(1).of(green(copy[i]))
            assertThat(blue(flat[i])).isWithin(1).of(blue(copy[i]))
        }
        val speckled = speckledGrey(w, h)
        val untouched = speckled.copyOf()
        ChromaSmooth.apply(speckled, w, h, ChromaSmooth.Params(amount = 0f))
        assertThat(speckled).isEqualTo(untouched)
    }

    @Test
    fun `the weight is one at no difference, follows a gaussian, and reaches zero`() {
        assertThat(ChromaSmooth.weight(0f, 12f)).isEqualTo(1f)
        // Near a Gaussian of the same width at one and two sigmas...
        assertThat(ChromaSmooth.weight(12f, 12f)).isWithin(0.11f).of(0.6065f)
        assertThat(ChromaSmooth.weight(24f, 12f)).isWithin(0.02f).of(0.135f)
        // ...and exactly zero from two and a half on, so nothing crosses an edge.
        assertThat(ChromaSmooth.weight(30f, 12f)).isEqualTo(0f)
        assertThat(ChromaSmooth.weight(255f, 12f)).isEqualTo(0f)
        // Symmetric.
        assertThat(ChromaSmooth.weight(-12f, 12f)).isEqualTo(ChromaSmooth.weight(12f, 12f))
    }
}

/**
 * The rendering adapted to the gain: the daylight rendering is untouched, and
 * by ISO 3200 the sharpen is gone and the colour smoothing twice as wide.
 */
class NoiseAdaptiveDevelopTest {
    @Test
    fun `below ISO 800 nothing changes, and above it the sharpen fades and the smoothing widens`() {
        val base = DevelopParams()
        assertThat(base.adaptedTo(null)).isEqualTo(base)
        assertThat(base.adaptedTo(100)).isEqualTo(base)
        assertThat(base.adaptedTo(800)).isEqualTo(base)
        val mid = base.adaptedTo(2000)
        assertThat(mid.sharpen.amount).isWithin(1e-4f).of(base.sharpen.amount * 0.5f)
        assertThat(mid.chroma.lumaSigma).isWithin(1e-4f).of(base.chroma.lumaSigma * 1.5f)
        val high = base.adaptedTo(3200)
        assertThat(high.sharpen.amount).isEqualTo(0f)
        assertThat(high.sharpen.enabled).isFalse()
        assertThat(high.chroma.lumaSigma).isWithin(1e-4f).of(base.chroma.lumaSigma * 2f)
        // Past the ceiling it stays there.
        assertThat(base.adaptedTo(6400)).isEqualTo(high)
        // And nothing else moved.
        assertThat(high.copy(sharpen = base.sharpen, chroma = base.chroma)).isEqualTo(base)
    }
}
