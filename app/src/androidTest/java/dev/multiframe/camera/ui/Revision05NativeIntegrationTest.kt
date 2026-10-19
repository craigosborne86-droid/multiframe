package dev.multiframe.camera.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real MainActivity, real native preview. This class never presses the shutter. */
class Revision05NativeIntegrationTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var saved: AppSettings
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val folder get() = File(context.getExternalFilesDir(null), "revision05-native-${InstrumentationRegistry.getArguments().getString("r05Label") ?: "normal"}").apply { mkdirs() }
    @Before fun start() {
        saved = AppSettings.load(context)
        OnboardingState.markSeen(context)
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val rear = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 1 }
        AppSettings.save(context, saved.copy(lensId = rear, manual = ManualSettings(), manualBurst = false,
            captureMode = CaptureMode.PHOTO, histogram = false, fullResolution = false, timerSeconds = 0))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitPreview()
    }
    @After fun finish() { if (::scenario.isInitialized) scenario.close(); if(::saved.isInitialized) AppSettings.save(context, saved) }
    private fun awaitPreview() {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        ui.waitUntil(20_000) { CaptureUiInspection.stream?.latestResult() != null }
    }
    private fun rect(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun previewView(v: View): PeakingPreviewView? {
        if (v is PeakingPreviewView) return v
        if (v is ViewGroup) repeat(v.childCount) { previewView(v.getChildAt(it))?.let { p -> return p } }
        return null
    }
    private fun record(state: String): Rect {
        ui.waitForIdle()
        val image = rect("camera-preview")
        val input = rect("camera-metering-region")
        val reticle = rect("rangefinder-view-reticle")
        assertEquals(image, input); assertEquals(image, reticle)
        var crop = ""
        scenario.onActivity {
            val view = previewView(it.window.decorView)!!
            val cx = view.javaClass.getDeclaredField("cropX").apply { isAccessible = true }.getFloat(view)
            val cy = view.javaClass.getDeclaredField("cropY").apply { isAccessible = true }.getFloat(view)
            assertEquals(1f, cx, .002f); assertEquals(1f, cy, .002f)
            crop = "$cx,$cy"
        }
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(if(root.width > root.height) 4f/3f else .75f, image.width/image.height, .002f)
        File(folder,"bounds.csv").appendText("$state,${root.width},${root.height},${image.left},${image.top},${image.right},${image.bottom},$crop\n")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder,"$state.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return image
    }
    private fun click(tag: String) { ui.onNodeWithTag(tag, useUnmergedTree = true).performClick(); ui.waitForIdle() }
    @Test fun portraitPanelsKeepBoundsProfileAndShutter() {
        val baseline = record("portrait-auto")
        val stream = CaptureUiInspection.stream
        click("rangefinder-pro")
        for (tab in listOf("exp","focus","frames","color")) {
            click("mechanical-tab-$tab")
            ui.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
            ui.onNodeWithTag("rangefinder-pro").assertIsDisplayed()
            assertEquals(baseline, record("portrait-pro-$tab"))
            assertSame(stream, CaptureUiInspection.stream)
        }
        click("mechanical-tab-exp")
        val manualSupported = android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in
            (stream!!.characteristics.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("revision05-manual").fetchSemanticsNodes().isNotEmpty() == manualSupported
        }
        if (manualSupported) {
            ui.onNodeWithTag("revision05-manual").performScrollTo().assertIsDisplayed().performClick()
            ui.waitUntil(10_000) { CaptureUiInspection.manual?.manualExposure == true }
        } else ui.onNodeWithTag("revision05-manual").assertDoesNotExist()
        val stored = CaptureUiInspection.manual!!
        click("rangefinder-pro")
        assertEquals(stored, CaptureUiInspection.manual)
        click("rangefinder-mode-night")
        assertEquals(stored, CaptureUiInspection.manual)
        click("rangefinder-mode-photo")
        click("revision05-auto-wb")
        assertEquals(baseline, record("portrait-auto-wb-tint"))
        click("revision05-wb-lock")
        val wb = CaptureUiInspection.manual!!
        click("rangefinder-pro")
        click("revision05-return-auto")
        ui.waitUntil(10_000) { CaptureUiInspection.manual?.manualExposure == false }
        assertEquals(wb.whiteBalanceMode, CaptureUiInspection.manual!!.whiteBalanceMode)
        assertEquals(stored.iso, CaptureUiInspection.manual!!.iso)
        click("rangefinder-pro")
        click("revision05-rgb")
        assertEquals(baseline, record("portrait-preview-rgb"))
        click("titanium-labs-button")
        record("portrait-settings")
        ui.onNodeWithContentDescription("Close settings").performClick()
        assertEquals(baseline, record("portrait-settings-closed"))
        scenario.onActivity { ViewCompat.getRootWindowInsets(it.window.decorView)?.let { insets ->
            assertFalse("Shooting status bar must be hidden", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
        } }
    }
    @Test fun landscapeBothDirectionsRetainStateAndStablePreview() {
        click("rangefinder-pro")
        if (!ui.onNodeWithTag("revision05-manual").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)) click("revision05-manual")
        val manual = CaptureUiInspection.manual
        for ((name,orientation) in listOf("landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "opposite-landscape" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            scenario.onActivity { it.requestedOrientation = orientation }
            ui.waitUntil(15_000) { val r = ui.onRoot().fetchSemanticsNode().boundsInRoot; r.width > r.height }
            awaitPreview()
            ui.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
            ui.onNodeWithTag("rangefinder-pro").assertIsDisplayed()
            assertEquals(manual, CaptureUiInspection.manual)
            val baseline = record("$name-pro")
            for (tab in listOf("focus","frames","color","exp")) {
                click("mechanical-tab-$tab"); assertEquals(baseline, record("$name-pro-$tab"))
            }
            click("rangefinder-pro")
            assertEquals(baseline, record("$name-auto"))
            click("revision05-auto-wb")
            assertEquals(baseline, record("$name-wb"))
            click("revision05-auto-wb")
            click("rangefinder-pro")
        }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(15_000) { val r = ui.onRoot().fetchSemanticsNode().boundsInRoot; r.height > r.width }
        awaitPreview(); assertEquals(manual, CaptureUiInspection.manual); record("portrait-returned")
    }
    @Test fun consoleTapsStayOutOfMeteringAndHistogramDoesNotReframe() {
        val stream = CaptureUiInspection.stream!!
        val before = stream.latestTapTriggerResult()?.frameNumber
        for (tag in listOf("rangefinder-mode-photo", "rangefinder-mode-night", "rangefinder-timer", "rangefinder-pro")) {
            ui.onNodeWithTag(tag).performTouchInput { click() }; ui.waitForIdle()
            assertEquals(before, stream.latestTapTriggerResult()?.frameNumber)
        }
        click("rangefinder-pro")
        val baseline = rect("camera-preview")
        fun frames(): Long { var count = 0L; scenario.onActivity { count = previewView(it.window.decorView)!!.renderedFrames }; return count }
        val start = frames(); Thread.sleep(5000); val offFrames = frames() - start
        click("revision05-rgb")
        val enabled = frames(); Thread.sleep(5000); val onFrames = frames() - enabled
        assertEquals(baseline, record("preview-rgb-performance"))
        assertTrue("Preview must continue with histogram on", onFrames > 20)
        File(folder,"preview-performance.txt").writeText("5-second windows: histogramOff=$offFrames frames; histogramOn=$onFrames frames. Device: ${android.os.Build.MODEL}. These are presented GL frames, not sensor timestamps; emulator results are not Pixel measurements.\n")
    }

    @Test fun transientSystemBarsAndNativeWhiteBalanceKeepFramingAndAutoExposure() {
        val baseline = record("system-ui-hidden")
        scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                .show(WindowInsetsCompat.Type.statusBars())
        }
        instrumentation.waitForIdleSync()
        assertEquals(baseline, record("system-ui-revealed"))
        scenario.onActivity { it.setShootingUi(true) }
        click("revision05-auto-wb")
        ui.waitForIdle()
        val available = ui.onAllNodesWithTag("revision05-wb-manual").fetchSemanticsNodes().isNotEmpty()
        if (!available) {
            File(folder, "wb-hardware.txt").writeText("Calibrated manual white balance is not advertised by this sensor. UI hid it.\n")
            return
        }
        val stream = CaptureUiInspection.stream!!
        val original = CaptureUiInspection.manual!!
        click("revision05-wb-manual")
        ui.onNodeWithTag("revision05-kelvin").performScrollTo().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(5500f) }
        ui.waitUntil(10_000) { CaptureUiInspection.manual?.kelvin == 5500 }
        ui.onNodeWithTag("revision05-tint").performScrollTo().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(10f) }
        ui.waitUntil(10_000) { CaptureUiInspection.manual?.kelvin == 5500 && CaptureUiInspection.manual?.tint == 10 }
        ui.waitUntil(10_000) { stream.latestResult()?.get(CaptureResult.CONTROL_AWB_MODE) == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF }
        val changed = CaptureUiInspection.manual!!
        assertEquals(original.manualExposure, changed.manualExposure)
        assertEquals(original.manualFocus, changed.manualFocus)
        assertEquals(5500, changed.kelvin); assertEquals(10, changed.tint)
        assertSame(stream, CaptureUiInspection.stream)
        val actual = stream.latestResult()!!
        File(folder,"wb-hardware.txt").writeText("Native preview result: AWB=${actual.get(CaptureResult.CONTROL_AWB_MODE)}, gains=${actual.get(CaptureResult.COLOR_CORRECTION_GAINS)}, transform=${actual.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)}, ISO=${actual.get(CaptureResult.SENSOR_SENSITIVITY)}, exposureNs=${actual.get(CaptureResult.SENSOR_EXPOSURE_TIME)}. Requested 5500 K / tint +10; exposure/focus manual flags unchanged. Saved-photo colour accuracy is a separate scene test.\n")
        assertEquals(baseline, record("portrait-auto-manual-wb"))
        click("revision05-wb-auto")
        assertEquals(5500, CaptureUiInspection.manual!!.kelvin)
        assertEquals(10, CaptureUiInspection.manual!!.tint)
    }

    @Test fun timerWordingAndPanelControlsRemainReachable() {
        for ((index, label) in listOf("Timer: Off", "Timer: 3 seconds", "Timer: 10 seconds", "Timer: Off").withIndex()) {
            ui.onNodeWithTag("rangefinder-timer").assertIsDisplayed().assert(hasText(label, substring = true))
            if (index < 3) click("rangefinder-timer")
        }
        click("rangefinder-pro")
        for (tab in listOf("exp", "focus", "frames", "color")) {
            ui.onNodeWithTag("mechanical-tab-$tab").performScrollTo().performClick()
            ui.onNodeWithTag("revision05-return-auto").performScrollTo()
            record("scrolled-$tab")
            ui.onNodeWithTag("revision05-return-auto").assertIsDisplayed()
            ui.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
            ui.onNodeWithTag("rangefinder-camera-flip").assertIsDisplayed()
        }
        record("portrait-controls-scrolled")
    }

    @Test fun focalChoicesUseRealCameraRequestsAndArmedSweepRestoresConfiguration() {
        for (index in 0..4) {
            val control = ui.onNodeWithTag("mechanical-lens-$index")
            if (control.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)) {
                File(folder,"focals.txt").appendText("Index $index unavailable; native control disabled.\n")
                continue
            }
            control.performClick()
            ui.waitForIdle(); Thread.sleep(2500); awaitPreview()
            val stream = CaptureUiInspection.stream!!
            val result = stream.latestResult()!!
            if (index == 2 || index == 4) {
                ui.waitUntil(10_000) { ui.onAllNodesWithTag("rangefinder-subpixel-label").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("revision05-compact-summary").assertTextContains("Burst: 16 frames",substring=true)
            }
            File(folder,"focals.txt").appendText("Index $index: camera=${stream.lensId}, RAW=${stream.config.width}x${stream.config.height}, crop=${result.get(CaptureResult.SCALER_CROP_REGION)}, rawCrop=${result.get(CaptureResult.SCALER_RAW_CROP_REGION)}, focalMm=${result.get(CaptureResult.LENS_FOCAL_LENGTH)}. Optical FOV is not inferred from UI labels.\n")
            record("focal-$index")
        }
        ui.onNodeWithTag("mechanical-lens-1").performClick(); awaitPreview(); Thread.sleep(1500)
        click("rangefinder-mode-night"); click("rangefinder-timer")
        val manual = CaptureUiInspection.manual!!
        val lens = CaptureUiInspection.stream!!.lensId
        fun arm(): Boolean {
            click("titanium-labs-button")
            ui.onNodeWithText("TECHNOLOGY PREVIEWS").performScrollTo().performClick()
            val launch = ui.onAllNodesWithText("ARM BRENIZER SWEEP").fetchSemanticsNodes()
            if (launch.isEmpty()) {
                ui.onNodeWithContentDescription("Close settings").performClick()
                File(folder,"sweep.txt").writeText("Real sweep capability is unavailable on this device; no simulated sweep offered.\n")
                return false
            }
            ui.onNodeWithText("ARM BRENIZER SWEEP").performScrollTo().performClick()
            ui.waitUntil(20_000) { ui.onAllNodesWithTag("revision05-exit-sweep").fetchSemanticsNodes().isNotEmpty() }
            awaitPreview(); Thread.sleep(1500)
            return true
        }
        if (arm()) {
            record("portrait-dedicated-sweep")
            click("revision05-exit-sweep"); awaitPreview()
            ui.waitUntil(15_000) { CaptureUiInspection.stream?.lensId == lens }
            assertEquals(manual, CaptureUiInspection.manual)
            ui.onNodeWithTag("rangefinder-mode-night").assertIsSelected()
            ui.onNodeWithTag("rangefinder-timer").assert(hasText("3 seconds", substring = true))
            // Rotation of an armed sweep has its own native accessibility/window test.
            // Compose/Espresso can retain a pending dialog layout root across recreation.
        }
    }

    @Test fun timerCancellationReturnsToAvailableWithoutSaving() {
        val before = CaptureLog.records(context).map { it.baseName }.toSet()
        click("rangefinder-timer")
        click("rangefinder-shutter")
        ui.waitUntil(3000) { ui.onAllNodesWithTag("revision05-cancel").fetchSemanticsNodes().isNotEmpty() }
        click("revision05-cancel")
        Thread.sleep(3500)
        assertEquals(before, CaptureLog.records(context).map { it.baseName }.toSet())
        ui.onNodeWithTag("rangefinder-shutter").assertIsEnabled().assertIsDisplayed()
    }

    @Test fun realImageTapsUpdateAfAeAndCannotOverrideManualControl() {
        val stream = CaptureUiInspection.stream!!
        val array = stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val orientation = stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)!!
        val report = StringBuilder()
        for (point in listOf(androidx.compose.ui.geometry.Offset(.2f,.2f), androidx.compose.ui.geometry.Offset(.8f,.35f))) {
            val previous = stream.latestTapTriggerResult()?.frameNumber
            ui.onNodeWithTag("camera-metering-region").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*point.x,height*point.y)) }
            ui.waitUntil(15_000) { stream.latestTapTriggerResult()?.frameNumber?.let { it != previous } == true }
            val result = stream.latestTapTriggerResult()!!
            val request = result.request
            val expected = TouchFocus.regionAt(point.x,point.y,orientation,SensorRect.from(array)).toRect()
            assertEquals(expected,request.get(android.hardware.camera2.CaptureRequest.CONTROL_AF_REGIONS)!!.single().rect)
            assertEquals(expected,request.get(android.hardware.camera2.CaptureRequest.CONTROL_AE_REGIONS)!!.single().rect)
            assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START,request.get(android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER))
            assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START,request.get(android.hardware.camera2.CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER))
            report.append("point=$point frame=${result.frameNumber} AF/AE=$expected; AF_START + AE_START\n")
        }
        click("rangefinder-pro"); click("revision05-manual"); click("mechanical-tab-focus")
        ui.onNodeWithTag("revision05-focus-manual").performScrollTo().performClick()
        ui.waitUntil(10_000) { CaptureUiInspection.manual?.manualFocus == true && stream.latestResult()?.get(CaptureResult.CONTROL_AF_MODE) == 0 }
        val manual = CaptureUiInspection.manual!!
        val prior = stream.latestTapTriggerResult()?.frameNumber
        ui.onNodeWithTag("camera-metering-region").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*.7f,height*.25f)) }
        ui.waitForIdle(); Thread.sleep(500)
        assertEquals(manual,CaptureUiInspection.manual); assertEquals(prior,stream.latestTapTriggerResult()?.frameNumber)
        assertEquals(0,stream.latestResult()?.get(CaptureResult.CONTROL_AF_MODE)); assertEquals(0,stream.latestResult()?.get(CaptureResult.CONTROL_AE_MODE))
        report.append("Manual ISO=${manual.iso} exposure=${manual.exposureTimeNs} focus=${manual.focusDiopters}; tap leaves profile/AF/AE/trigger unchanged\n")
        File(folder,"real-tap-af-ae.txt").writeText(report.toString())
        record("manual-tap-protection")
    }

    @Test fun focusBurstDialAndLoupeUseTheRealSession() {
        val stream = CaptureUiInspection.stream!!
        var renderer: PeakingPreviewView? = null
        scenario.onActivity { renderer = previewView(it.window.decorView) }
        val p = renderer!!
        assertFalse(p.peakingEnabled); assertFalse(p.focusInsetEnabled)
        click("rangefinder-pro"); click("mechanical-tab-focus")
        ui.onNodeWithTag("revision05-focus-manual").performScrollTo().performClick()
        ui.waitUntil(10_000) { p.peakingEnabled && stream.latestResult()?.get(CaptureResult.CONTROL_AF_MODE) == 0 }
        val fixed = record("focus-real-peaking")
        repeat(16) { ui.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick() }
        ui.waitForIdle()
        val near = CaptureUiInspection.manual!!.focusDiopters
        assertTrue(near > 0f)
        try {
            ui.waitUntil(10_000) { stream.latestResult()?.request?.get(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE) == near &&
                (stream.latestResult()?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f) > 0f }
            val actual = stream.latestResult()!!
            assertEquals(near,actual.request.get(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE))
            val response = actual.get(CaptureResult.LENS_FOCUS_DISTANCE)!!
            val maximum = stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)!!
            assertTrue(response.isFinite() && response in 0f..maximum)
            // APPROXIMATE calibration does not promise equality of requested and
            // reported optical distance. Prove the exact command and a real lens
            // response, retaining both values rather than widening an arbitrary
            // comparison tolerance.
        } finally {
            val actual = stream.latestResult()
            File(folder,"focus-endpoint-metadata.txt").writeText("requested=$near; request=${actual?.request?.get(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE)}; result=${actual?.get(CaptureResult.LENS_FOCUS_DISTANCE)}; calibration=${stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)}; minimum=${stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)}\n")
        }
        record("focus-real-near-endpoint")
        repeat(16) { ui.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick() }
        ui.waitUntil(10_000) { stream.latestResult()?.get(CaptureResult.LENS_FOCUS_DISTANCE) == 0f }
        assertEquals(0f,CaptureUiInspection.manual!!.focusDiopters)
        val ruler = ui.onNodeWithTag("rangefinder-dial-ruler")
        ruler.performScrollTo().performTouchInput { click(center) }
        assertEquals(0f,CaptureUiInspection.manual!!.focusDiopters)
        click("rangefinder-focus-assist-toggle")
        ui.waitUntil(10_000) { p.focusInsetEnabled }
        val image = rect("camera-preview")
        val inset = rect("rangefinder-focus-inset")
        assertTrue(image.contains(inset.topLeft)); assertTrue(image.contains(inset.bottomRight - androidx.compose.ui.geometry.Offset(1f,1f)))
        val field = p.javaClass.getDeclaredField("focusInset").apply { isAccessible = true }
        val before = field.get(p) as FocusAssistInset
        ui.onNodeWithTag("camera-metering-region").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*.2f,height*.2f)) }
        ui.waitUntil(5_000) { (field.get(p) as FocusAssistInset).focusX != before.focusX }
        val tapped = field.get(p) as FocusAssistInset
        assertEquals(.2f,tapped.focusX,.001f); assertEquals(.2f,tapped.focusY,.001f)
        ui.onNodeWithTag("rangefinder-focus-target").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(80f,40f)); up() }
        ui.waitUntil(5_000) { (field.get(p) as FocusAssistInset).focusX > tapped.focusX }
        assertEquals(fixed,record("focus-real-loupe-tracked"))
        click("mechanical-tab-frames")
        assertFalse(p.peakingEnabled); assertFalse(p.focusInsetEnabled)
        repeat(12) { ui.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick() }
        ui.waitForIdle()
        val observed = mutableListOf(CaptureUiInspection.frames)
        for (expected in FrameCountPolicy.stepsUpTo(stream.maxBurst).drop(1)) {
            ui.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
            ui.waitUntil(5_000) { CaptureUiInspection.frames == expected }
            observed.add(CaptureUiInspection.frames)
        }
        assertEquals(FrameCountPolicy.stepsUpTo(stream.maxBurst),observed)
        ui.onNodeWithContentDescription("Next dial detent").performClick(); assertEquals(28,CaptureUiInspection.frames)
        assertEquals(fixed,record("burst-28-detents"))
        File(folder,"focus-burst-session.txt").writeText("AF_OFF/infinity confirmed in real CaptureResult; centre tap preserves selected value; loupe anchor tap/drag=$tapped; detents=$observed; GL timestamps=${p.renderedTimestamp}. Actual sharp/blurred-structure visual quality is a separate photographic gate.\n")
    }

    @Test fun torchSelfieAndRearRestorationUseActualHardware() {
        val rear = CaptureUiInspection.stream!!
        click("rangefinder-torch")
        ui.waitUntil(10_000) { rear.latestResult()?.request?.get(android.hardware.camera2.CaptureRequest.FLASH_MODE) == android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH }
        record("torch-request-on")
        click("rangefinder-camera-flip")
        ui.waitUntil(30_000) { AppSettings.load(context).lensId?.let { id -> context.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 0 } == true }
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
        ui.onNodeWithTag("rangefinder-torch").assertDoesNotExist()
        record("selfie-native-preview")
        click("rangefinder-camera-flip"); awaitPreview()
        ui.waitUntil(15_000) { CaptureUiInspection.stream?.lensId == rear.lensId }
        ui.onNodeWithTag("rangefinder-torch").assertIsNotSelected()
        assertEquals(android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF,CaptureUiInspection.stream!!.latestResult()!!.request.get(android.hardware.camera2.CaptureRequest.FLASH_MODE))
        record("rear-restored-torch-off")
    }

    @Test fun savedPhotographsRenderPageZoomAndClampInTheRealLightbox() {
        val records = CaptureLog.records(context).filter { it.jpeg?.uri != null }
        assertTrue("Two preserved saved photographs are required",records.size >= 2)
        click("rangefinder-gallery-entry")
        val first = records[0]; val second = records[1]
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("lightbox-cell-${first.baseName}").fetchSemanticsNodes().isNotEmpty() }
        click("lightbox-cell-${first.baseName}")
        val review = ui.onNodeWithTag("lightbox-review-${first.baseName}")
        ui.waitUntil(15_000) { review.fetchSemanticsNode().config[GalleryImageSize].width > 0 }
        recordLightbox("lightbox-saved-review")
        review.performTouchInput {
            down(0,androidx.compose.ui.geometry.Offset(width*.4f,height*.5f)); down(1,androidx.compose.ui.geometry.Offset(width*.6f,height*.5f))
            moveTo(0,androidx.compose.ui.geometry.Offset(width*.15f,height*.5f),delayMillis=200)
            moveTo(1,androidx.compose.ui.geometry.Offset(width*.85f,height*.5f),delayMillis=200); up(0); up(1)
        }
        ui.waitUntil(5_000) { review.fetchSemanticsNode().config[GalleryScale] > 1.5f }
        var state = review.fetchSemanticsNode().config
        assertTrue(kotlin.math.abs(state[GalleryOffset].x)<10f)
        review.performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width*.4f,height*.4f),androidx.compose.ui.geometry.Offset(width*.95f,height*.95f),250) }
        state = review.fetchSemanticsNode().config
        assertEquals(clampOffset(state[GalleryOffset],state[GalleryScale],state[GalleryFrameSize],state[GalleryImageSize]),state[GalleryOffset])
        recordLightbox("lightbox-zoom-clamped")
        review.performTouchInput { doubleClick() }
        ui.waitUntil(5_000) { review.fetchSemanticsNode().config[GalleryScale] == 1f }
        ui.onNodeWithTag("lightbox-pager").performTouchInput { swipeLeft() }
        val next = ui.onNodeWithTag("lightbox-review-${second.baseName}")
        ui.waitUntil(10_000) { next.fetchSemanticsNode().config[GalleryImageSize].width > 0 }
        recordLightbox("lightbox-paged-saved-review")
        File(folder,"lightbox-records.txt").writeText("Known saved photographs: ${first.baseName} -> ${second.baseName}; frame counts=${first.frameCount}/${second.frameCount}; URI decode + centred pinch + exact pan clamp + pager gesture checked. No media created/deleted.\n")
    }

    private fun recordLightbox(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }

    @Test fun replacementPanelCannotCoverTheTurretOrPersistentControls() {
        val before = rect("camera-preview")
        click("rangefinder-pro")
        val panel = rect("revision05-replacement-panel")
        val turret = rect("rangefinder-turret")
        assertTrue("Pro panel bottom=$panel must dock above turret=$turret",panel.bottom <= turret.top)
        assertEquals(before,record("portrait-panel-above-turret"))
        for (tag in listOf("rangefinder-shutter","rangefinder-gallery-entry","rangefinder-pro","rangefinder-camera-flip")) {
            ui.onNodeWithTag(tag).assertIsDisplayed()
            assertFalse("Panel must not intersect $tag",panel.overlaps(rect(tag)))
        }
    }

    @Test fun landscapeManualScaleHasAFullNativeTouchTarget() {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(20_000) { ui.onRoot().fetchSemanticsNode().boundsInRoot.width > ui.onRoot().fetchSemanticsNode().boundsInRoot.height }
        awaitPreview(); val image = rect("camera-preview")
        click("rangefinder-pro")
        ui.onNodeWithTag("rangefinder-dial-ruler").performScrollTo().assertIsDisplayed()
        var minimum = 0f
        scenario.onActivity { minimum = 48f * it.resources.displayMetrics.density }
        val ruler = rect("rangefinder-dial-ruler")
        assertTrue("Visible manual scale height=$ruler needs $minimum px",ruler.height >= minimum)
        assertEquals(image,record("landscape-scale-touch-target"))
        assertFalse(rect("revision05-replacement-panel").overlaps(rect("rangefinder-shutter")))
    }

    @Test fun exposureDetentsCommandTheSensorAndLongShutterStaysOutOfPreview() {
        val stream = CaptureUiInspection.stream!!
        click("rangefinder-pro"); click("mechanical-tab-exp"); click("revision05-manual")
        ui.waitUntil(10_000) { stream.latestResult()?.get(CaptureResult.CONTROL_AE_MODE) == 0 }
        val offered = listOf(64,100,200,400,800,1600,3200,6400,12800,25600)
            .filter { it in stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!! }
        repeat(12) { ui.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick() }
        ui.waitForIdle(); assertEquals(offered.first(),CaptureUiInspection.manual!!.iso)
        for (iso in offered.drop(1)) {
            ui.onNodeWithContentDescription("Next dial detent").performClick()
            ui.waitUntil(10_000) { CaptureUiInspection.manual?.iso == iso && stream.latestResult()?.get(CaptureResult.SENSOR_SENSITIVITY) == iso }
        }
        ui.onNodeWithContentDescription("Next dial detent").performClick(); ui.waitForIdle()
        assertEquals(offered.last(),CaptureUiInspection.manual!!.iso)
        click("mechanical-subtab-shutter")
        repeat(35) { ui.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick() }
        ui.waitForIdle()
        val requested = CaptureUiInspection.manual!!.exposureTimeNs
        assertTrue("A long still exposure must be retained",requested >= 1_000_000_000L)
        ui.waitUntil(10_000) { (stream.latestResult()?.request?.get(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME) ?: Long.MAX_VALUE) <= 1_000_000_000L/30 }
        val actual = stream.latestResult()!!
        record("manual-long-shutter-preview-fluid")
        click("rangefinder-pro"); click("revision05-info")
        ui.onNodeWithTag("revision05-capture-summary").assertTextContains(CaptureUiInspection.manual!!.shutterLabel(),substring=true)
        ui.onNodeWithTag("revision05-preview-reading").assertTextContains("Preview:",substring=true)
        File(folder,"exposure-detents-sensor.txt").writeText("ISO detents=$offered, exact real CaptureResult values checked. Requested still=${requested}ns, repeating request=${actual.request.get(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)}ns, actual=${actual.get(CaptureResult.SENSOR_EXPOSURE_TIME)}ns. No still exposure taken by this test.\n")
    }
}
