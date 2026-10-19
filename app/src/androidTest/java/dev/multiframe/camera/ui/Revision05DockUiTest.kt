package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Declared capability fixtures verify controls, not Pixel sensor/colour output. */
class Revision05DockUiTest {
    @get:Rule val ui = createComposeRule()
    private val caps = CameraCapabilities(true, 50, 6400, 1000, 6_000_000_000, 10f,
        listOf(0, 1, 5), listOf(0, 4), emptyList(), emptyList(), -6, 6, .333f,
        90, true, emptySet(), supportsAwbLock = true, manualPostProcessing = true,
        whiteBalanceCalibration = ColorScience.Calibration(Mat3.IDENTITY, Mat3.IDENTITY,
            illuminant1Kelvin = 2850, illuminant2Kelvin = 6500))

    private fun chooseWb(mode: String) {
        ui.onNodeWithTag("revision05-wb-mode").performClick()
        ui.onNodeWithTag("revision05-wb-$mode").performScrollTo().performClick()
    }

    @Test fun compactWbUsesSameStateValidatesInputAndPreservesAutoExposureFocus() {
        var settings by mutableStateOf(ManualSettings())
        var visible by mutableStateOf(true)
        ui.setContent { MultiframeTheme {
            if (visible) Box(Modifier.width(320.dp).height(108.dp)) {
                Column { WhiteBalancePanel(settings, caps, { settings = it }, previewKelvin = 5200, compact = true) }
            }
        } }
        val initial = settings
        chooseWb("manual")
        assertEquals(initial.manualExposure, settings.manualExposure)
        assertEquals(initial.manualFocus, settings.manualFocus)
        val kelvin = settings.kelvin
        ui.onNodeWithTag("revision05-kelvin").performTouchInput { click(center) }
        assertEquals(kelvin, settings.kelvin)
        ui.onNodeWithTag("revision05-kelvin").performTouchInput { swipe(centerRight, centerLeft, 300) }
        assertTrue(settings.kelvin > kelvin)
        ui.onNodeWithTag("revision05-wb-value").performClick()
        ui.onNodeWithTag("revision05-kelvin-input").performTextReplacement("999")
        ui.onNodeWithTag("revision05-kelvin-apply").performClick()
        ui.onNodeWithText("Enter 2850–6500 K").assertExists()
        ui.onNodeWithTag("revision05-kelvin-input").performTextReplacement("5500")
        ui.onNodeWithTag("revision05-kelvin-apply").performClick()
        assertEquals(5500, settings.kelvin)
        ui.onNodeWithTag("revision05-wb-tint-tab").performScrollTo().performClick()
        ui.onNodeWithTag("revision05-tint").performTouchInput { swipe(centerRight, centerLeft, 300) }
        assertTrue(settings.tint > 0)
        ui.onNodeWithTag("revision05-wb-value").performClick()
        ui.onNodeWithTag("revision05-tint-input").performTextReplacement("101")
        ui.onNodeWithTag("revision05-kelvin-apply").performClick()
        ui.onNodeWithText("Enter −100 to +100").assertExists()
        ui.onNodeWithTag("revision05-tint-input").performTextReplacement("-12")
        ui.onNodeWithTag("revision05-kelvin-apply").performClick()
        assertEquals(-12, settings.tint)
        chooseWb("auto")
        assertEquals(5500, settings.kelvin)
        assertEquals(-12, settings.tint)
        ui.runOnIdle { visible = false }
        ui.runOnIdle { visible = true }
        chooseWb("manual")
        assertEquals(5500, settings.kelvin)
        assertEquals(-12, settings.tint)
        assertEquals(initial.manualExposure, settings.manualExposure)
        assertEquals(initial.manualFocus, settings.manualFocus)
    }

    @Test fun unsupportedWbDoesNotOfferFakeKelvinAndAdvertisedPresetStillWorks() {
        var settings by mutableStateOf(ManualSettings())
        ui.setContent { MultiframeTheme {
            Column(Modifier.width(320.dp)) {
                WhiteBalancePanel(settings, caps.copy(whiteBalanceCalibration = null), { settings = it }, compact = true)
            }
        } }
        ui.onNodeWithTag("revision05-kelvin").assertDoesNotExist()
        ui.onNodeWithTag("revision05-wb-mode").performClick()
        ui.onNodeWithTag("revision05-wb-manual").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("revision05-wb-preset-5").performScrollTo().performClick()
        assertEquals(5, settings.awbMode)
        assertFalse(settings.manualExposure)
        assertFalse(settings.manualFocus)
    }

    @Test fun dialSettlesImmediatelyWhenSystemMotionIsDisabled() {
        org.junit.Assume.assumeFalse(android.animation.ValueAnimator.areAnimatorsEnabled())
        ui.mainClock.autoAdvance = false
        var settings by mutableStateOf(ManualSettings())
        ui.setContent {
            RangefinderDial(settings, caps, 8, 28, RangefinderTab.SHUTTER, {},
                { settings = it }, {}, modifier = Modifier.width(320.dp))
        }
        val ruler = ui.onNodeWithTag("rangefinder-dial-ruler")
        ruler.performTouchInput { swipe(centerRight, centerLeft, 96) }
        val released = ruler.fetchSemanticsNode().config[DialTravelPx]
        assertTrue(kotlin.math.abs(ruler.fetchSemanticsNode().config[DialOffsetPx]) < .01f)
        ui.mainClock.advanceTimeBy(800)
        assertEquals(released, ruler.fetchSemanticsNode().config[DialTravelPx])
        ui.mainClock.autoAdvance = true
    }

    @Test fun activeUnderlineDoesNotExpandButtonAndPressedStateIsVisible() {
        var clicks = 0
        ui.setContent { MultiframeTheme {
            Row(Modifier.width(320.dp)) {
                InstrumentButton("Auto", "Autofocus", true, true, "selected", { clicks++ })
                InstrumentButton("Manual", "Manual focus", false, true, "next", {})
            }
        } }
        val button = ui.onNodeWithTag("selected").assertIsSelected()
        val rect = button.fetchSemanticsNode().boundsInRoot
        val next = ui.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot
        val density = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue(rect.width < 120 * density)
        assertTrue(rect.height >= 48 * density)
        assertTrue(rect.right <= next.left)
        val before = button.captureToImage()
        button.performTouchInput { down(center) }
        ui.waitForIdle()
        val pressed = button.captureToImage()
        assertFalse("Press must change native pixels", before.toPixelMap()[before.width / 2, before.height / 8] == pressed.toPixelMap()[pressed.width / 2, pressed.height / 8])
        button.performTouchInput { up() }
        assertEquals(1, clicks)
    }
}
