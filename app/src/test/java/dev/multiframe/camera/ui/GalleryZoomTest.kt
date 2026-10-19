package dev.multiframe.camera.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The clamp that stops a zoomed photograph being panned off the screen.
 *
 * Without it there is no gesture that brings the picture back, which reads as
 * the review having lost it. The arithmetic is small and entirely
 * off-by-one-able, which is what makes it worth pinning: the travel available
 * at scale `s` is exactly half the overhang, and getting the halving wrong
 * either strands the picture or lets the edges drift inside the frame.
 */
class GalleryZoomTest {

    private val frame = IntSize(1000, 2000)

    /**
     * Compared component-wise rather than with `isEqualTo`.
     *
     * `Offset` is a value class, so Truth compares the boxes, and clamping a
     * negative input against a zero-width range yields `-0.0f` -- equal to
     * `0.0f` by `==` and by every use this makes of it, but a different box.
     */
    private fun assertOffset(actual: Offset, x: Float, y: Float) {
        assertThat(actual.x).isEqualTo(x)
        assertThat(actual.y).isEqualTo(y)
    }

    @Test fun letterboxedImageCannotPanIntoAdditionalBlackSpace() {
        assertOffset(clampOffset(Offset(999f, 999f), 2f, frame, IntSize(4000, 3000)), 500f, 0f)
    }

    @Test
    fun atFitToScreenThereIsNowhereToPan() {
        // Scale one means the picture exactly fills its box, so every offset
        // collapses to centred.
        assertOffset(clampOffset(Offset(300f, -400f), scale = 1f, frame), 0f, 0f)
    }

    /**
     * At scale s the picture is s times the frame, so it overhangs by
     * (s-1) frames in total and half of that in each direction.
     */
    @Test
    fun theTravelIsHalfTheOverhang() {
        val clamped = clampOffset(Offset(10_000f, 10_000f), scale = 2f, frame)
        assertThat(clamped.x).isEqualTo(500f)   // 1000 * (2-1) / 2
        assertThat(clamped.y).isEqualTo(1000f)  // 2000 * (2-1) / 2
    }

    @Test
    fun theTravelIsSymmetric() {
        val positive = clampOffset(Offset(10_000f, 10_000f), scale = 4f, frame)
        val negative = clampOffset(Offset(-10_000f, -10_000f), scale = 4f, frame)
        assertThat(negative.x).isEqualTo(-positive.x)
        assertThat(negative.y).isEqualTo(-positive.y)
    }

    @Test
    fun anOffsetInsideTheTravelIsLeftAlone() {
        assertOffset(clampOffset(Offset(100f, -200f), scale = 4f, frame), 100f, -200f)
    }

    @Test
    fun moreZoomAllowsMoreTravel() {
        val near = clampOffset(Offset(10_000f, 0f), scale = 2f, frame).x
        val far = clampOffset(Offset(10_000f, 0f), scale = 8f, frame).x
        assertThat(far).isGreaterThan(near)
    }

    /**
     * Before the overlay has been laid out there is no frame to clamp against,
     * and clamping to a zero-sized one would pin the picture at centre and make
     * the first gesture after opening do nothing.
     */
    @Test
    fun anUnmeasuredFrameDoesNotClamp() {
        val offset = Offset(300f, 400f)
        assertOffset(clampOffset(offset, scale = 4f, IntSize.Zero), 300f, 400f)
        assertOffset(clampOffset(offset, scale = 4f, IntSize(0, 2000)), 300f, 400f)
        assertOffset(clampOffset(offset, scale = 4f, IntSize(1000, 0)), 300f, 400f)
    }
}
