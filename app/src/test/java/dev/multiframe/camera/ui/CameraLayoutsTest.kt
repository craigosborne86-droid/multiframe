package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The layout decision, for every window and hand the app meets: a phone
 * upright and on its side, a tablet, a foldable flat and half-opened.
 */
class CameraLayoutsTest {

    @Test
    fun `a phone upright is the portrait strip whatever its aspect`() {
        for ((w, h) in listOf(360f to 640f, 393f to 852f, 412f to 915f, 384f to 896f)) {
            val layout = CameraLayouts.decide(w, h, 0, null)
            assertThat(layout.posture).isEqualTo(Posture.PORTRAIT)
            assertThat(layout.windowClass).isEqualTo(WindowClass.PHONE)
            assertThat(layout.handRotation).isEqualTo(0)
            assertThat(layout.turned).isFalse()
        }
    }

    @Test
    fun `a phone on its side keeps its window and turns the controls' contents`() {
        val left = CameraLayouts.decide(412f, 915f, 90, null)
        assertThat(left.posture).isEqualTo(Posture.HAND_LANDSCAPE)
        assertThat(left.handRotation).isEqualTo(90)
        assertThat(left.turned).isTrue()
        val right = CameraLayouts.decide(412f, 915f, 270, null)
        assertThat(right.handRotation).isEqualTo(270)
        // Upside down is not sideways: nothing turns.
        assertThat(CameraLayouts.decide(412f, 915f, 180, null).posture).isEqualTo(Posture.PORTRAIT)
        // Degrees wrap.
        assertThat(CameraLayouts.decide(412f, 915f, 450, null).handRotation).isEqualTo(90)
    }

    @Test
    fun `a wide window gets the sidebar, a third of it, and its width class`() {
        val tablet = CameraLayouts.decide(1280f, 800f, 0, null)
        assertThat(tablet.posture).isEqualTo(Posture.SIDEBAR)
        assertThat(tablet.windowClass).isEqualTo(WindowClass.WIDE)
        assertThat(tablet.railWidth.value).isWithin(0.5f).of(1280f / 3f)
        // The hand does not turn a sidebar's controls: the window is already the right way up.
        assertThat(CameraLayouts.decide(1280f, 800f, 90, null).handRotation).isEqualTo(0)
        val small = CameraLayouts.decide(720f, 540f, 0, null)
        assertThat(small.windowClass).isEqualTo(WindowClass.EXPANDED)
        assertThat(small.posture).isEqualTo(Posture.SIDEBAR)
    }

    @Test
    fun `a foldable half-opened on a table splits at the hinge, and flat it does not`() {
        val hinge = FoldState(halfOpened = true, horizontalHinge = true, hingeTopPx = 1180, hingeBottomPx = 1220)
        val tabletop = CameraLayouts.decide(840f, 900f, 0, hinge)
        assertThat(tabletop.posture).isEqualTo(Posture.TABLETOP)
        assertThat(tabletop.paneSplitTopPx).isEqualTo(1180)
        assertThat(tabletop.paneSplitBottomPx).isEqualTo(1220)
        // Flat: an ordinary (square-ish, portrait) window.
        val flat = CameraLayouts.decide(840f, 900f, 0, hinge.copy(halfOpened = false))
        assertThat(flat.posture).isEqualTo(Posture.PORTRAIT)
        assertThat(flat.windowClass).isEqualTo(WindowClass.WIDE)
        // A vertical hinge half-opened (book posture) is not tabletop; the window decides.
        val book = CameraLayouts.decide(900f, 840f, 0, hinge.copy(horizontalHinge = false))
        assertThat(book.posture).isEqualTo(Posture.SIDEBAR)
    }

    @Test
    fun `the width classes are the reference's breakpoints`() {
        assertThat(CameraLayouts.windowClassFor(599f)).isEqualTo(WindowClass.PHONE)
        assertThat(CameraLayouts.windowClassFor(600f)).isEqualTo(WindowClass.EXPANDED)
        assertThat(CameraLayouts.windowClassFor(839f)).isEqualTo(WindowClass.EXPANDED)
        assertThat(CameraLayouts.windowClassFor(840f)).isEqualTo(WindowClass.WIDE)
    }
}
