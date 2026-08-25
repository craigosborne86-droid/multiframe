package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * Defective sensor sites.
 *
 * The tension here is that removing defects and removing real detail are the
 * same operation applied to different pixels, so most of these tests are about
 * what must survive rather than what must go.
 */
class HotPixelsTest {

    private val width = 64
    private val height = 48

    /** A smooth field, which is where a defect is most visible. */
    private fun smooth(level: Float = 0.4f) = FloatArray(width * height) { level }

    private fun at(plane: FloatArray, x: Int, y: Int) = plane[y * width + x]

    // ------------------------------------------------------------------

    @Test
    fun `a stuck bright site is replaced`() {
        val plane = smooth()
        plane[20 * width + 30] = 0.95f

        val replaced = HotPixels.suppress(plane, width, height)

        assertThat(replaced).isEqualTo(1)
        assertThat(at(plane, 30, 20)).isWithin(1e-4f).of(0.4f)
    }

    @Test
    fun `a stuck dark site is replaced`() {
        // Dead pixels are as common as hot ones and just as visible.
        val plane = smooth()
        plane[20 * width + 30] = 0.0f

        val replaced = HotPixels.suppress(plane, width, height)

        assertThat(replaced).isEqualTo(1)
        assertThat(at(plane, 30, 20)).isWithin(1e-4f).of(0.4f)
    }

    @Test
    fun `many separated defects are all found`() {
        // Hundreds is normal on a fifty-megapixel sensor.
        val plane = smooth()
        val sites = buildList {
            for (y in 6 until height - 6 step 6) {
                for (x in 6 until width - 6 step 6) add(x to y)
            }
        }
        for ((x, y) in sites) plane[y * width + x] = 0.99f

        val replaced = HotPixels.suppress(plane, width, height)

        assertThat(replaced).isEqualTo(sites.size)
        for ((x, y) in sites) assertThat(at(plane, x, y)).isLessThan(0.5f)
    }

    @Test
    fun `two defects two sites apart shield each other`() {
        // An honest limitation rather than a bug, and the direct cost of the
        // conservative rule: a defect that is a same-colour neighbour of
        // another raises the local maximum, so neither exceeds it. The same
        // property is what lets a genuine two-pixel highlight survive, and one
        // cannot be had without the other. Adjacent defects are rare; real
        // highlights are not.
        val plane = smooth()
        plane[20 * width + 30] = 0.99f
        plane[20 * width + 32] = 0.99f

        val replaced = HotPixels.suppress(plane, width, height)

        assertThat(replaced).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // What must survive.
    // ------------------------------------------------------------------

    @Test
    fun `a smooth gradient is untouched`() {
        // The commonest content in a photograph. Any change here is damage.
        val plane = FloatArray(width * height) { i -> (i % width) / width.toFloat() }
        val before = plane.copyOf()

        val replaced = HotPixels.suppress(plane, width, height)

        assertThat(replaced).isEqualTo(0)
        for (i in plane.indices) assertThat(plane[i]).isWithin(1e-6f).of(before[i])
    }

    @Test
    fun `an edge is not mistaken for a row of defects`() {
        // An edge is an outlier in one direction and not the other, which is
        // precisely why the rule requires a pixel to exceed all four
        // same-colour neighbours.
        val plane = FloatArray(width * height) { i ->
            if ((i % width) < width / 2) 0.2f else 0.8f
        }
        val before = plane.copyOf()

        val replaced = HotPixels.suppress(plane, width, height)

        assertThat(replaced).isEqualTo(0)
        for (i in plane.indices) assertThat(plane[i]).isWithin(1e-6f).of(before[i])
    }

    @Test
    fun `a real highlight shared with a neighbour survives`() {
        // A specular glint or a distant light spans more than one site. Taking
        // a star out of an astrophotograph would be a worse failure than leaving
        // a dot in.
        val plane = smooth()
        plane[20 * width + 30] = 0.95f
        plane[20 * width + 32] = 0.95f      // the next site of the same colour

        val replaced = HotPixels.suppress(plane, width, height)

        assertThat(replaced).isEqualTo(0)
        assertThat(at(plane, 30, 20)).isWithin(1e-4f).of(0.95f)
    }

    @Test
    fun `ordinary noise is not treated as defective`() {
        // Otherwise the correction would rewrite most of a high-ISO frame.
        val rnd = Random(5)
        val plane = FloatArray(width * height) { 0.4f + (rnd.nextFloat() - 0.5f) * 0.06f }

        val replaced = HotPixels.suppress(plane, width, height)

        println("sites replaced in noise: $replaced of ${width * height}")
        assertThat(replaced).isEqualTo(0)
    }

    @Test
    fun `fine detail at the sampling limit survives`() {
        // Alternating sites of the same colour are legitimate image content at
        // the resolution limit, not defects.
        val plane = FloatArray(width * height) { i ->
            if (((i % width) / 2) % 2 == 0) 0.3f else 0.5f
        }
        val before = plane.copyOf()

        HotPixels.suppress(plane, width, height)

        for (i in plane.indices) assertThat(plane[i]).isWithin(1e-6f).of(before[i])
    }

    // ------------------------------------------------------------------

    @Test
    fun `the border is left alone rather than guessed at`() {
        // Same-colour neighbours do not exist there, and inventing them would
        // corrupt the edge of every frame.
        val plane = smooth()
        plane[0] = 0.99f
        plane[1 * width + 1] = 0.99f

        HotPixels.suppress(plane, width, height)

        assertThat(plane[0]).isWithin(1e-4f).of(0.99f)
    }

    @Test
    fun `a tiny plane is not a crash`() {
        val tiny = FloatArray(9) { 0.5f }

        assertThat(HotPixels.suppress(tiny, 3, 3)).isEqualTo(0)
    }

    @Test
    fun `an undersized plane is refused rather than overrunning`() {
        try {
            HotPixels.suppress(FloatArray(10), width, height)
            throw AssertionError("expected a rejection")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("too small")
        }
    }

    @Test
    fun `the defect rate is reported as a proportion`() {
        // A healthy sensor sits far below a tenth of a percent; well above that
        // means the threshold is wrong rather than the sensor being unusual.
        assertThat(HotPixels.defectRate(300, 8160, 6144)).isLessThan(0.0001f)
        assertThat(HotPixels.defectRate(0, 0, 0)).isEqualTo(0f)
    }

    @Test
    fun `a higher threshold replaces fewer sites`() {
        val plane = smooth()
        plane[20 * width + 30] = 0.55f

        val gentle = HotPixels.suppress(plane.copyOf(), width, height, threshold = 0.05f)
        val strict = HotPixels.suppress(plane.copyOf(), width, height, threshold = 0.30f)

        assertThat(gentle).isEqualTo(1)
        assertThat(strict).isEqualTo(0)
    }
}
