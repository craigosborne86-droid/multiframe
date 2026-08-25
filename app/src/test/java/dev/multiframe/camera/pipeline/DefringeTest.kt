package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * Colour fringe suppression.
 *
 * Almost every test here is about what must *not* change. Removing a fringe and
 * desaturating a real object are the same operation applied to different
 * pixels, and getting the distinction wrong is far more damaging than leaving
 * fringes in.
 */
class DefringeTest {

    private val width = 64
    private val height = 48

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /** A neutral step edge, optionally with a coloured rim along it. */
    private fun edge(fringe: Boolean): IntArray {
        val pixels = IntArray(width * height)
        val mid = width / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = if (x < mid) 40 else 210
                var r = base
                var g = base
                var b = base
                if (fringe) {
                    // Purple on the dark side, green on the bright side, which
                    // is what lateral colour error actually looks like.
                    if (x == mid - 1) { r = base + 55; b = base + 55 }
                    if (x == mid) { g = (base + 45).coerceAtMost(255) }
                }
                pixels[y * width + x] = argb(
                    r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255),
                )
            }
        }
        return pixels
    }

    /**
     * Suppression tested with the radial gate off, so the two concerns are
     * separated: whether a fringe is removed, and where the correction is
     * allowed to look. The gate has its own test.
     */
    private val everywhere = Defringe.Params(enabled = true, innerRadius = 0f)

    private fun chromaAt(pixels: IntArray, x: Int, y: Int): Int {
        val p = pixels[y * width + x]
        return maxOf(abs(red(p) - green(p)), abs(blue(p) - green(p)))
    }

    // ------------------------------------------------------------------

    @Test
    fun `a fringe on a neutral edge is suppressed`() {
        val pixels = edge(fringe = true)
        val before = chromaAt(pixels, width / 2 - 1, height / 2)

        val altered = Defringe.apply(pixels, width, height, everywhere)
        val after = chromaAt(pixels, width / 2 - 1, height / 2)

        println("fringe chroma $before -> $after, $altered pixels altered")
        assertThat(altered).isGreaterThan(0)
        assertThat(after).isLessThan(before / 2)
    }

    @Test
    fun `a clean edge is left exactly as it was`() {
        // The commonest case by far: most edges have no fringe.
        val pixels = edge(fringe = false)
        val before = pixels.copyOf()

        val altered = Defringe.apply(pixels, width, height, Defringe.Params(enabled = true))

        assertThat(altered).isEqualTo(0)
        assertThat(pixels).isEqualTo(before)
    }

    @Test
    fun `a genuinely coloured object keeps its colour`() {
        // The distinction the whole design rests on: a fringe exists only at
        // the edge, while a coloured object carries its colour away from its
        // own edges too.
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] =
                    if (x < width / 2) argb(30, 30, 30) else argb(220, 40, 40)
            }
        }
        val before = pixels.copyOf()

        Defringe.apply(pixels, width, height, Defringe.Params(enabled = true))

        // The middle of the red field is red as well, so the edge has evidence
        // that red belongs there.
        for (y in 4 until height - 4) {
            for (x in width / 2 until width - 4) {
                assertThat(red(pixels[y * width + x]))
                    .isWithin(6).of(red(before[y * width + x]))
            }
        }
    }

    @Test
    fun `a flat field is untouched`() {
        val pixels = IntArray(width * height) { argb(120, 130, 140) }
        val before = pixels.copyOf()

        assertThat(Defringe.apply(pixels, width, height, Defringe.Params(enabled = true))).isEqualTo(0)
        assertThat(pixels).isEqualTo(before)
    }

    @Test
    fun `the centre of the frame is left alone`() {
        // Lateral colour error is zero at the optical centre by definition, so
        // whatever colour is there is not aberration and treating it as such
        // could only damage real detail.
        val pixels = edge(fringe = true)
        val before = pixels.copyOf()

        Defringe.apply(pixels, width, height, Defringe.Params(enabled = true))

        val centre = (height / 2) * width + (width / 2 - 1)
        assertThat(pixels[centre]).isEqualTo(before[centre])
    }

    @Test
    fun `a neutral edge stays neutral`() {
        // The correction must not introduce a cast of its own.
        val pixels = edge(fringe = true)

        Defringe.apply(pixels, width, height, everywhere)

        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                if (abs(x - width / 2) <= 1) continue
                val p = pixels[y * width + x]
                assertThat(abs(red(p) - green(p))).isAtMost(1)
                assertThat(abs(blue(p) - green(p))).isAtMost(1)
            }
        }
    }

    @Test
    fun `green is left alone entirely`() {
        // Green is where a Bayer sensor has most of its samples and least of
        // its own error, so it is the reference the other two are pulled toward
        // rather than something to adjust.
        val pixels = edge(fringe = true)
        val before = pixels.copyOf()

        Defringe.apply(pixels, width, height, everywhere)

        for (i in pixels.indices) {
            assertThat(green(pixels[i])).isEqualTo(green(before[i]))
        }
    }

    @Test
    fun `strength controls how much is removed`() {
        fun remaining(strength: Float): Int {
            val pixels = edge(fringe = true)
            Defringe.apply(
                pixels, width, height, everywhere.copy(strength = strength),
            )
            return chromaAt(pixels, width / 2 - 1, height / 2)
        }

        assertThat(remaining(1f)).isLessThan(remaining(0.4f))
        // Zero disables it completely.
        val untouched = edge(fringe = true)
        val before = untouched.copyOf()
        Defringe.apply(untouched, width, height, everywhere.copy(strength = 0f))
        assertThat(untouched).isEqualTo(before)
    }

    @Test
    fun `values stay in range`() {
        val pixels = edge(fringe = true)

        Defringe.apply(pixels, width, height, everywhere)

        for (p in pixels) {
            assertThat(red(p)).isIn(0..255)
            assertThat(green(p)).isIn(0..255)
            assertThat(blue(p)).isIn(0..255)
            assertThat((p ushr 24) and 0xFF).isEqualTo(255)
        }
    }

    @Test
    fun `an area that is entirely edge is left alone`() {
        // Dense texture gives no non-edge neighbours, so there is no evidence
        // about what colour belongs there and altering it would be a guess.
        val pixels = IntArray(width * height) { i ->
            if (((i % width) + (i / width)) % 2 == 0) argb(20, 20, 20) else argb(230, 200, 240)
        }
        val before = pixels.copyOf()

        Defringe.apply(pixels, width, height, Defringe.Params(enabled = true))

        assertThat(pixels).isEqualTo(before)
    }

    @Test
    fun `the border is left alone rather than guessed at`() {
        val pixels = edge(fringe = true)
        val before = pixels.copyOf()

        Defringe.apply(pixels, width, height, Defringe.Params(enabled = true))

        for (x in 0 until width) {
            assertThat(pixels[x]).isEqualTo(before[x])
            assertThat(pixels[(height - 1) * width + x])
                .isEqualTo(before[(height - 1) * width + x])
        }
    }

    @Test
    fun `ordinary chroma noise is not treated as fringing`() {
        // Found on device: without a chroma gate the rule fired on 5.9 million
        // pixels of a 12.5 megapixel frame, quietly becoming chroma noise
        // reduction applied to half the image. At high gain, noise produces
        // gradients everywhere and slightly-off chroma everywhere.
        val rnd = kotlin.random.Random(9)
        val pixels = IntArray(width * height) {
            val base = 120 + rnd.nextInt(-40, 41)
            argb(
                (base + rnd.nextInt(-8, 9)).coerceIn(0, 255),
                base.coerceIn(0, 255),
                (base + rnd.nextInt(-8, 9)).coerceIn(0, 255),
            )
        }

        val altered = Defringe.apply(pixels, width, height, Defringe.Params(enabled = true))

        println("pixels altered in chroma noise: $altered of ${pixels.size}")
        assertThat(altered).isEqualTo(0)
    }

    @Test
    fun `it is off unless asked for`() {
        // Its benefit has not been seen on a real photograph and its cost has
        // been measured, so it is not on by default.
        val pixels = edge(fringe = true)
        val before = pixels.copyOf()

        assertThat(Defringe.apply(pixels, width, height)).isEqualTo(0)
        assertThat(pixels).isEqualTo(before)
    }

    @Test
    fun `a tiny image is not a crash`() {
        val tiny = IntArray(9) { argb(100, 100, 100) }

        assertThat(Defringe.apply(tiny, 3, 3, Defringe.Params(enabled = true))).isEqualTo(0)
    }

    @Test
    fun `an undersized buffer is refused rather than overrunning`() {
        try {
            Defringe.apply(IntArray(10), width, height, Defringe.Params(enabled = true))
            throw AssertionError("expected a rejection")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("too small")
        }
    }
}
