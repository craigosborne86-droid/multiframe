package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Lens shading correction.
 *
 * Raw is defined as uncorrected, so a raw-first pipeline inherits a stop and a
 * half of corner falloff that the camera's own JPEG path removes. Getting the
 * interpolation wrong is worse than not correcting at all: a coarse grid
 * sampled without interpolation puts visible steps across a clear sky.
 */
class LensShadingTest {

    /** A vignette: gain 1 at the centre rising toward the corners. */
    private fun vignette(columns: Int = 5, rows: Int = 5, corner: Float = 2.4f): ShadingMap {
        val gains = FloatArray(columns * rows * 4)
        val cx = (columns - 1) / 2f
        val cy = (rows - 1) / 2f
        val maxDistance = kotlin.math.sqrt(cx * cx + cy * cy)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val dx = c - cx
                val dy = r - cy
                val t = kotlin.math.sqrt(dx * dx + dy * dy) / maxDistance
                val gain = 1f + (corner - 1f) * t * t
                for (channel in 0 until 4) {
                    gains[(r * columns + c) * 4 + channel] = gain
                }
            }
        }
        return ShadingMap(columns, rows, gains)
    }

    @Test
    fun `an identity map changes nothing`() {
        val map = ShadingMap.identity()

        for (x in 0 until 100 step 17) {
            for (y in 0 until 80 step 13) {
                assertThat(map.gainAt(x, y, 100, 80, 0)).isWithin(1e-5f).of(1f)
            }
        }
        assertThat(map.falloffStops).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `corners are boosted more than the centre`() {
        // The correction that stops every photograph having a dingy border.
        val map = vignette()
        val width = 400
        val height = 300

        val centre = map.gainAt(width / 2, height / 2, width, height, 0)
        val corner = map.gainAt(width - 1, height - 1, width, height, 0)

        assertThat(centre).isWithin(0.02f).of(1f)
        assertThat(corner).isGreaterThan(2f)
        println("shading: centre gain %.2f, corner gain %.2f, falloff %.2f stops"
            .format(centre, corner, map.falloffStops))
    }

    @Test
    fun `gain varies smoothly rather than in steps`() {
        // A 17-cell grid across 4080 pixels is 240 pixels per cell. Nearest
        // neighbour would put a visible brightness step across the sky every
        // 240 pixels; this is the test that would catch it.
        val map = vignette()
        val width = 1000

        var worstStep = 0f
        var previous = map.gainAt(0, 150, width, 300, 0)
        for (x in 1 until width) {
            val g = map.gainAt(x, 150, width, 300, 0)
            worstStep = maxOf(worstStep, kotlin.math.abs(g - previous))
            previous = g
        }
        println("largest gain step between neighbouring pixels: %.5f".format(worstStep))

        assertThat(worstStep).isLessThan(0.01f)
    }

    @Test
    fun `interpolation matches the grid exactly at cell centres`() {
        // If this drifts, the correction is applying a different curve than the
        // camera measured.
        val map = vignette(columns = 3, rows = 3)
        val width = 5
        val height = 5

        // With 3 columns over 5 pixels, x=0, 2 and 4 land on grid nodes.
        assertThat(map.gainAt(0, 0, width, height, 0)).isWithin(1e-4f).of(map.cell(0, 0, 0))
        assertThat(map.gainAt(2, 2, width, height, 0)).isWithin(1e-4f).of(map.cell(1, 1, 0))
        assertThat(map.gainAt(4, 4, width, height, 0)).isWithin(1e-4f).of(map.cell(2, 2, 0))
    }

    @Test
    fun `channels are corrected independently`() {
        // Falloff is not neutral: colour filters and microlenses respond
        // differently by angle, so corners are a different colour as well as
        // darker. Correcting all channels equally would leave the colour cast.
        val columns = 3
        val rows = 3
        val gains = FloatArray(columns * rows * 4) { 1f }
        // Blue corner needs much more gain than red, as is typical.
        gains[(2 * columns + 2) * 4 + 0] = 1.5f
        gains[(2 * columns + 2) * 4 + 3] = 2.5f
        val map = ShadingMap(columns, rows, gains)

        val red = map.gainAt(99, 99, 100, 100, 0)
        val blue = map.gainAt(99, 99, 100, 100, 3)

        assertThat(blue).isGreaterThan(red)
    }

    @Test
    fun `the channel for a pixel is its position in the CFA cell`() {
        val map = ShadingMap.identity()

        assertThat(map.channelFor(0, 0)).isEqualTo(0)
        assertThat(map.channelFor(1, 0)).isEqualTo(1)
        assertThat(map.channelFor(0, 1)).isEqualTo(2)
        assertThat(map.channelFor(1, 1)).isEqualTo(3)
    }

    @Test
    fun `sampling outside the frame is clamped rather than extrapolated`() {
        // Extrapolating a vignette past the corner produces absurd gains.
        val map = vignette()

        val inside = map.gainAt(399, 299, 400, 300, 0)
        val past = map.gainAt(10_000, 10_000, 400, 300, 0)

        assertThat(past).isWithin(1e-4f).of(inside)
    }

    @Test
    fun `a degenerate frame size does not divide by zero`() {
        val map = vignette()

        assertThat(map.gainAt(0, 0, 1, 1, 0)).isEqualTo(1f)
        assertThat(map.gainAt(0, 0, 0, 0, 0)).isEqualTo(1f)
    }

    @Test
    fun `a map that does not match its grid is rejected at construction`() {
        // Silently accepting it would read past the end of the array later.
        try {
            ShadingMap(4, 4, FloatArray(10))
            throw AssertionError("expected a rejection")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("grid")
        }
    }

    @Test
    fun `falloff is reported in stops`() {
        val mild = vignette(corner = 1.4f)
        val severe = vignette(corner = 4f)

        assertThat(severe.falloffStops).isGreaterThan(mild.falloffStops)
        assertThat(severe.falloffStops).isWithin(0.1f).of(2f)
        println("falloff: mild %.2f stops, severe %.2f stops"
            .format(mild.falloffStops, severe.falloffStops))
    }
}
