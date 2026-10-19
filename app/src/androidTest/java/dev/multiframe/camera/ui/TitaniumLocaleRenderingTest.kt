package dev.multiframe.camera.ui

import android.content.res.Configuration
import android.hardware.camera2.CameraMetadata
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.R
import dev.multiframe.camera.pipeline.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.ceil

/** Active R05 panels and production readout formatting; declared sensor-free capabilities. */
class TitaniumLocaleRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishRegionsKeepReadableWhiteBalanceAndCompatibilityColourResources() {
        var locale by mutableStateOf(Locale.UK)
        compose.setContent { Localized(locale) {
            Revision05ConsoleFixture(open = true, tab = RangefinderTab.COLOR, caps = colorCaps(),
                settings = ManualSettings(whiteBalanceMode = WhiteBalanceMode.MANUAL, kelvin = 5000))
        } }
        for ((region, spelling) in listOf(Locale.UK to "COLOUR", Locale.US to "COLOR")) {
            compose.runOnIdle { locale = region }
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val config = Configuration(base.resources.configuration).apply { setLocale(region) }
            val resources = base.createConfigurationContext(config).resources
            assertThat(resources.getString(R.string.rangefinder_color_tab)).isEqualTo(spelling)
            assertThat(resources.getString(R.string.rangefinder_color_footer)).isEqualTo("OPTICAL $spelling")
            compose.onNodeWithTag("mechanical-tab-color").assertIsSelected().assertIsDisplayed()
            fits(compose.onNodeWithText("• White balance", useUnmergedTree = true))
            compose.onNodeWithTag("revision05-wb-manual").assertIsSelected()
            fits(compose.onNodeWithText("5000 K"))
            // R05 uses White balance in both regions, not the retired COLOR dial.
            compose.onNodeWithTag("revision05-kelvin").assertExists()
            compose.onNodeWithTag("revision05-tint").assertExists()
            val panel = compose.onNodeWithTag("revision05-replacement-panel").fetchSemanticsNode().boundsInRoot
            val tab = compose.onNodeWithTag("mechanical-tab-color").fetchSemanticsNode().boundsInRoot
            assertThat(tab.left).isAtLeast(panel.left)
            assertThat(tab.right).isAtMost(panel.right)
        }
    }

    @Test fun frenchConfigurationFormatsCaptureReadoutEvenWhenProcessLocaleDiffers() {
        val previous = Locale.getDefault()
        var exposure by mutableLongStateOf(500_000_000L)
        try {
            Locale.setDefault(Locale.FRANCE)
            compose.setContent { Localized(Locale.FRANCE) {
                Revision05ConsoleFixture(info = true,
                    summary = NextCaptureSummary.of(ManualSettings(manualExposure = true, iso = 100,
                        exposureTimeNs = exposure), 28, true, 1.5f, false, LocalResources.current.configuration.locales[0]))
            } }
            compose.onNodeWithTag("revision05-capture-summary")
                .assertTextEquals("ISO 100 · 0,5s · Burst: 28 frames · DNG: On")
            fits(compose.onNodeWithTag("revision05-capture-summary"))
            Locale.setDefault(Locale.US)
            compose.runOnIdle { exposure = 6_000_000_000L }
            compose.onNodeWithTag("revision05-capture-summary")
                .assertTextEquals("ISO 100 · 6,0s · Burst: 28 frames · DNG: On")
            fits(compose.onNodeWithTag("revision05-capture-summary"))
        } finally { Locale.setDefault(previous) }
    }

    private fun fits(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts).isNotEmpty()
        for (layout in layouts) {
            assertThat(layout.didOverflowHeight).isFalse()
            for (line in 0 until layout.lineCount) {
                assertThat(layout.isLineEllipsized(line)).isFalse()
                assertThat(layout.getLineLeft(line)).isAtLeast(0f)
                assertThat(ceil(layout.getLineRight(line)).toInt()).isAtMost(layout.size.width)
                assertThat(layout.getLineBottom(line)).isAtMost(layout.size.height.toFloat())
            }
            assertThat(layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                .isEqualTo(layout.layoutInput.text.length)
        }
    }

    @Composable private fun Localized(locale: Locale, content: @Composable () -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config),
            LocalConfiguration provides config, content = content)
    }

    private fun colorCaps() = CameraCapabilities(true, 50, 12800, 100_000L, 6_000_000_000L,
        10f, listOf(CameraMetadata.CONTROL_AWB_MODE_OFF, CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
        listOf(0), emptyList(), emptyList(), -3, 3, .33f, 90, true, emptySet(),
        supportsAwbLock = true, manualPostProcessing = true,
        whiteBalanceCalibration = ColorScience.Calibration(Mat3.IDENTITY, Mat3.IDENTITY,
            illuminant1Kelvin = 2850, illuminant2Kelvin = 6500))
}
