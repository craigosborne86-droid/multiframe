package dev.multiframe.camera.ui

import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.R
import dev.multiframe.camera.pipeline.AppSettings
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.ManualSettings
import dev.multiframe.camera.pipeline.PreviewExposure
import dev.multiframe.camera.pipeline.WhiteBalanceMode
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Physical production-screen acceptance for the offered EXP and COLOR endpoints.
 * All state changes go through visible controls; no shutter is pressed.
 */
class ColorExposureEndpointDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private fun tag(name: String) = compose.onNodeWithTag(name)
    private fun stream() = checkNotNull(CaptureUiInspection.stream) { "RAW stream unavailable" }
    private fun manual() = checkNotNull(CaptureUiInspection.manual) { "Manual UI state unavailable" }

    private fun note(message: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "$message\n")
    })

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        val directory = File(context.getExternalFilesDir(null), "endpoint-acceptance").apply { check(mkdirs() || isDirectory) }
        val file = File(directory, "$name.png")
        try {
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            note("PNG ${file.absolutePath}")
        } finally {
            image.recycle()
        }
    }

    private fun requireAwake() {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
            "Unlock the phone and keep the screen on for endpoint acceptance"
        }
    }

    private fun awaitOrientation(wanted: Int) {
        compose.waitUntil(20_000) {
            requireAwake()
            var ready = false
            scenario.onActivity {
                ready = it.hasWindowFocus() && it.resources.configuration.orientation == wanted &&
                    it.window.decorView.width > 0 && it.window.decorView.height > 0
            }
            ready && CaptureUiInspection.stream?.latestResult() != null
        }
    }

    private fun withCamera(body: () -> Unit) {
        assumeTrue("Physical Pixel 11 Pro only", Build.MODEL == "Pixel 11 Pro")
        requireAwake()
        val saved = AppSettings.load(context)
        try {
            AppSettings.save(context, saved.copy(manual = ManualSettings(), timerSeconds = 0,
                lensId = null, captureMode = CaptureMode.PHOTO, fullResolution = false, burstFrames = 8))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            awaitOrientation(Configuration.ORIENTATION_PORTRAIT)
            compose.waitUntil(40_000) {
                CaptureUiInspection.stream?.latestResult() != null &&
                    compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()
            }
            body()
        } finally {
            if (::scenario.isInitialized) scenario.close()
            AppSettings.save(context, saved)
        }
    }

    private fun setPro(wanted: Boolean) {
        fun open() = compose.onAllNodesWithTag("mechanical-tab-exp").fetchSemanticsNodes().isNotEmpty()
        if (open() != wanted) tag("rangefinder-pro").performClick()
        compose.waitUntil(5_000) { open() == wanted }
    }

    private fun characteristics(): CameraCharacteristics = context
        .getSystemService(CameraManager::class.java)
        .getCameraCharacteristics(stream().lensId)

    private fun awaitRequest(check: (CaptureRequest) -> Boolean) {
        compose.waitUntil(10_000) { stream().latestResult()?.request?.let(check) == true }
    }

    @Test fun exposureArrowsReachAndClampAtOfferedSensorEndpoints() = withCamera {
        val camera = characteristics()
        val isoRange = checkNotNull(camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
        val shutterRange = checkNotNull(camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))
        val isoStops = listOf(64, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)
            .filter { it in isoRange.lower..isoRange.upper }
        val shutterStops = listOf(1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100,
            80, 60, 50, 40, 30, 15, 8, 4, 2).map { 1_000_000_000L / it } +
            listOf(1L, 2L, 4L, 6L).map { it * 1_000_000_000L }
        val offeredShutters = shutterStops.filter { it in shutterRange.lower..shutterRange.upper }
        check(isoStops.size >= 2 && offeredShutters.size >= 2) { "Sensor has too few offered dial stops" }

        setPro(true)
        tag("mechanical-tab-exp").performClick()
        tag("mechanical-subtab-iso").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("ISO inertial control dial").fetchSemanticsNodes().isNotEmpty() }
        // First leave either initial endpoint. A live AE value may lie between or outside engraved stops.
        compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
        repeat(isoStops.size) { compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick() }
        assertThat(manual().iso).isEqualTo(isoStops.first())
        compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick()
        assertThat(manual().iso).isEqualTo(isoStops.first())
        val lowIsoExposure = manual().exposureTimeNs
        awaitRequest { it.get(CaptureRequest.CONTROL_AE_MODE) == CameraMetadata.CONTROL_AE_MODE_OFF &&
            it.get(CaptureRequest.SENSOR_SENSITIVITY) == PreviewExposure.iso(isoStops.first(), lowIsoExposure, isoRange.upper) }
        repeat(isoStops.size) { compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick() }
        assertThat(manual().iso).isEqualTo(isoStops.last())
        compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
        assertThat(manual().iso).isEqualTo(isoStops.last())
        val highIsoExposure = manual().exposureTimeNs
        awaitRequest { it.get(CaptureRequest.SENSOR_SENSITIVITY) == PreviewExposure.iso(isoStops.last(), highIsoExposure, isoRange.upper) }
        note("ISO offered endpoints ${isoStops.first()}..${isoStops.last()} inside physical ${isoRange.lower}..${isoRange.upper}; both arrows clamp")

        tag("mechanical-subtab-shutter").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("SHUTTER inertial control dial").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
        repeat(offeredShutters.size) { compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick() }
        assertThat(manual().exposureTimeNs).isEqualTo(offeredShutters.first())
        compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick()
        assertThat(manual().exposureTimeNs).isEqualTo(offeredShutters.first())
        awaitRequest { it.get(CaptureRequest.CONTROL_AE_MODE) == CameraMetadata.CONTROL_AE_MODE_OFF &&
            it.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == PreviewExposure.time(offeredShutters.first()) }
        repeat(offeredShutters.size) { compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick() }
        assertThat(manual().exposureTimeNs).isEqualTo(offeredShutters.last())
        compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
        assertThat(manual().exposureTimeNs).isEqualTo(offeredShutters.last())
        val still = offeredShutters.last()
        awaitRequest { it.get(CaptureRequest.CONTROL_AE_MODE) == CameraMetadata.CONTROL_AE_MODE_OFF &&
            it.get(CaptureRequest.SENSOR_EXPOSURE_TIME) == PreviewExposure.time(still) &&
            it.get(CaptureRequest.SENSOR_SENSITIVITY) == PreviewExposure.iso(isoStops.last(), still, isoRange.upper) }
        note("Shutter offered endpoints ${offeredShutters.first()}..$still ns inside physical ${shutterRange.lower}..${shutterRange.upper}; preview capped at ${PreviewExposure.time(still)} ns")
    }

    @Test fun whiteBalanceAutoLockManualAndAdvertisedPresetsFitBothOrientations() = withCamera {
        fun localized(locale: Locale, id: Int): String {
            val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
            return context.createConfigurationContext(config).getString(id)
        }
        assertThat(localized(Locale.UK, R.string.rangefinder_color_tab)).isEqualTo("COLOUR")
        assertThat(localized(Locale.US, R.string.rangefinder_color_tab)).isEqualTo("COLOR")
        val camera = characteristics()
        val modes = checkNotNull(camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).toSet()
        val presets = ManualSettings.AWB_LABELS.filterKeys { it != CameraMetadata.CONTROL_AWB_MODE_AUTO && it in modes }
        assumeTrue("AWB lock is required for the physical lock endpoint", camera.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true)
        val automaticProfile = manual()
        tag("revision05-auto-wb").performClick()
        tag("revision05-wb-lock").performScrollTo().performClick()
        awaitRequest { it.get(CaptureRequest.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO &&
            it.get(CaptureRequest.CONTROL_AWB_LOCK) == true }
        assertThat(manual().manualExposure).isEqualTo(automaticProfile.manualExposure)
        assertThat(manual().manualFocus).isEqualTo(automaticProfile.manualFocus)
        checkWhiteBalanceLayout("Locked WB")
        screenshot("wb-lock-auto-portrait")

        tag("revision05-wb-manual").performScrollTo().assertIsEnabled().performClick()
        awaitRequest { it.get(CaptureRequest.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_OFF &&
            it.get(CaptureRequest.COLOR_CORRECTION_GAINS) != null }
        assertThat(manual().manualExposure).isEqualTo(automaticProfile.manualExposure)
        assertThat(manual().manualFocus).isEqualTo(automaticProfile.manualFocus)
        tag("revision05-tint").performScrollTo().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(10f) }
        val storedWb = manual()
        checkWhiteBalanceLayout("${storedWb.kelvin} K")
        screenshot("wb-manual-auto-portrait")

        setPro(true)
        tag("mechanical-tab-color").performScrollTo().performClick()
        assertThat(manual().kelvin).isEqualTo(storedWb.kelvin)
        assertThat(manual().tint).isEqualTo(10)
        assertThat(manual().whiteBalanceMode).isEqualTo(WhiteBalanceMode.MANUAL)
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            scenario.onActivity { it.requestedOrientation = orientation }
            awaitOrientation(if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE)
            checkWhiteBalanceLayout("${storedWb.kelvin} K")
            assertThat(manual().kelvin).isEqualTo(storedWb.kelvin)
            assertThat(manual().tint).isEqualTo(10)
            tag("rangefinder-shutter").assertIsDisplayed()
            screenshot("wb-manual-$orientation")
        }
        if (presets.isEmpty()) {
            tag("revision05-wb-presets").assertDoesNotExist()
            note("No HAL AWB presets advertised; Auto, Lock and calibrated Manual WB still exercised")
        } else {
            tag("revision05-wb-presets").performScrollTo().performClick()
            for ((mode, name) in presets) {
                tag("revision05-wb-preset-$mode").performScrollTo().performClick()
                awaitRequest { it.get(CaptureRequest.CONTROL_AWB_MODE) == mode &&
                    it.get(CaptureRequest.CONTROL_AWB_LOCK) == false }
                checkTextFits(tag("revision05-wb-preset-$mode"))
                note("Advertised WB preset $name: request=$mode result=${stream().latestResult()?.get(CaptureResult.CONTROL_AWB_MODE)}")
            }
            val longest = presets.maxBy { it.value.length }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
            tag("revision05-wb-preset-${longest.key}").performScrollTo().assertIsDisplayed()
            checkTextFits(tag("revision05-wb-preset-${longest.key}"))
        }
        tag("revision05-wb-lock").performScrollTo().performClick()
        awaitRequest { it.get(CaptureRequest.CONTROL_AWB_LOCK) == true }
        val profileBeforeHide = manual()
        setPro(false)
        assertThat(manual()).isEqualTo(profileBeforeHide)
        tag("rangefinder-pro").assertIsSelected() // retained manual profile, hidden panel
        setPro(true)
        tag("revision05-return-auto").performScrollTo().performClick()
        assertThat(manual().manualExposure).isFalse()
        assertThat(manual().manualFocus).isFalse()
        assertThat(manual().whiteBalanceMode).isEqualTo(WhiteBalanceMode.LOCK)
        tag("revision05-wb-auto").performScrollTo().performClick()
        awaitRequest { it.get(CaptureRequest.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO &&
            it.get(CaptureRequest.CONTROL_AWB_LOCK) == false }
        assertThat(manual().kelvin).isEqualTo(storedWb.kelvin)
        assertThat(manual().tint).isEqualTo(10)
        note("Explicit Return to Auto restores exposure/focus; independent Auto WB unlocks balance; stored Kelvin/tint retained")
    }

    private fun checkWhiteBalanceLayout(reading: String) {
        tag("revision05-wb-value").performScrollTo().assertTextEquals(reading)
        val panel = tag("revision05-replacement-panel").fetchSemanticsNode().boundsInRoot
        val value = tag("revision05-wb-value").fetchSemanticsNode().boundsInRoot
        assertThat(value.left).isAtLeast(panel.left)
        assertThat(value.right).isAtMost(panel.right)
        checkTextFits(tag("revision05-wb-value"))
        note("WB reading=$reading bounds=$value panel=$panel")
    }

    private fun checkTextFits(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts).isNotEmpty()
        for (layout in layouts) {
            for (line in 0 until layout.lineCount) {
                assertThat(layout.isLineEllipsized(line)).isFalse()
                assertThat(kotlin.math.ceil(layout.getLineRight(line).toDouble()).toInt()).isAtMost(layout.size.width)
            }
            assertThat(layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)).isEqualTo(layout.layoutInput.text.length)
            assertThat(layout.didOverflowHeight).isFalse()
        }
    }
}
