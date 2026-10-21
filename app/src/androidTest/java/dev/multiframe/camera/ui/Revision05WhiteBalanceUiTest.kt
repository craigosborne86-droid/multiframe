package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native controls with declared synthetic capability data, not Pixel output evidence. */
class Revision05WhiteBalanceUiTest {
    @get:Rule val ui = createComposeRule()
    private val caps = CameraCapabilities(true, 50, 6400, 1000, 6_000_000_000, 10f, listOf(0,1,5),
        listOf(0,4), emptyList(), emptyList(), -6,6,.333f,90,true,emptySet(), supportsAwbLock = true,
        manualPostProcessing = true, whiteBalanceCalibration = ColorScience.Calibration(Mat3.IDENTITY, Mat3.IDENTITY,
            illuminant1Kelvin = 2850, illuminant2Kelvin = 6500))
    @Test fun continuousKelvinTintNumericValidationAndAutoRetention() {
        var settings by mutableStateOf(ManualSettings())
        ui.setContent { MultiframeTheme {
            Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                WhiteBalancePanel(settings, caps, { settings = it })
            }
        } }
        ui.onNodeWithTag("revision05-wb-manual").performClick()
        assertFalse(settings.manualExposure); assertFalse(settings.manualFocus)
        val original = settings.kelvin
        ui.onNodeWithTag("revision05-kelvin").performScrollTo().performTouchInput { click(center) }
        assertEquals(original, settings.kelvin)
        ui.onNodeWithTag("revision05-kelvin").performTouchInput { swipe(centerRight, centerLeft, 300) }
        assertTrue(settings.kelvin > original)
        ui.onNodeWithTag("revision05-kelvin-input").assertTextEquals(settings.kelvin.toString())
        ui.onNodeWithTag("revision05-tint").performScrollTo().performTouchInput { swipe(centerRight, centerLeft, 300) }
        assertTrue(settings.tint > 0)
        ui.onNodeWithTag("revision05-kelvin-input").performScrollTo().performTextReplacement("999")
        ui.onNodeWithTag("revision05-kelvin-apply").performClick()
        ui.onNodeWithText("Enter 2850–6500 K").assertExists()
        ui.onNodeWithTag("revision05-kelvin-input").performTextReplacement("5500")
        ui.onNodeWithTag("revision05-kelvin-apply").performClick()
        assertEquals(5500, settings.kelvin)
        val tint = settings.tint
        ui.onNodeWithTag("revision05-wb-auto").performScrollTo().performClick()
        assertEquals(5500,settings.kelvin); assertEquals(tint,settings.tint)
        ui.onNodeWithTag("revision05-wb-manual").performClick()
        assertEquals(5500,settings.kelvin); assertEquals(tint,settings.tint)
    }
    @Test fun resetRequiresExplicitConfirmation() {
        var resets = 0
        ui.setContent { MultiframeTheme { ServiceCaptureSettings(caps, true, {}, true, {}, OutputFormat.JPEG, {}, onReset = { resets++ }) } }
        ui.onNodeWithText("RESET ALL SETTINGS").performClick()
        assertEquals(0,resets)
        ui.onNodeWithTag("revision05-reset-cancel").performClick()
        assertEquals(0,resets)
        ui.onNodeWithText("RESET ALL SETTINGS").performClick()
        ui.onNodeWithTag("revision05-reset-confirm").performClick()
        assertEquals(1,resets)
    }
}
