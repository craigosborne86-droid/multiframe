package dev.multiframe.camera.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest as Request
import android.hardware.camera2.CaptureResult as Result
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Acceptance actions go through the visible production controls. Camera2
 * results, saved captures and rendered GPU frames provide independent evidence.
 */
class TitaniumAcceptanceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private fun tag(name: String) = compose.onNodeWithTag(name)
    private fun stream() = checkNotNull(CaptureUiInspection.stream)
    private fun note(message: String) = instrumentation.sendStatus(0, android.os.Bundle().apply {
        putString("stream", "$message\n")
    })
    private fun shot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(context.cacheDir, "titanium-$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
    private fun renderer(): PeakingPreviewView? {
        fun find(view: View): PeakingPreviewView? = when (view) {
            is PeakingPreviewView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        var result: PeakingPreviewView? = null
        scenario.onActivity { result = find(it.window.decorView) }
        return result
    }
    private fun frames(count: Long = 12) {
        val gpu = checkNotNull(renderer())
        val before = gpu.renderedFrames
        compose.waitUntil(10_000) { gpu.renderedFrames >= before + count }
    }
    private fun tap(x: Float, y: Float) = tag("camera-preview").performTouchInput {
        click(Offset(width * x, height * y))
    }
    private fun withCamera(body: () -> Unit) {
        assumeTrue("Physical Pixel 11 Pro only", Build.MODEL == "Pixel 11 Pro")
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        check(power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
            "Unlock the Pixel before running real-camera acceptance"
        }
        val saved = AppSettings.load(context)
        AppSettings.save(context, saved.copy(manual = ManualSettings(), timerSeconds = 0,
            lensId = null, captureMode = CaptureMode.PHOTO, fullResolution = false, burstFrames = 28))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            compose.waitUntil(40_000) {
                var focusedPortrait = false
                scenario.onActivity { focusedPortrait = it.hasWindowFocus() &&
                    it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                focusedPortrait && CaptureUiInspection.stream?.latestResult() != null &&
                    compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()
            }
            frames()
            body()
        } finally {
            scenario.close() // lifecycle turns the torch off and closes the stream
            AppSettings.save(context, saved)
        }
    }

    @Test fun productionMeteringAndManualExposure() = withCamera {
        assertThat(renderer()!!.zebraEnabled).isTrue()
        shot("auto-ready")
        fun meteringTap(x: Float, y: Float): android.graphics.Rect {
            val previous = stream().latestTapTriggerResult()?.frameNumber
            tap(x, y)
            compose.waitUntil(10_000) {
                stream().latestTapTriggerResult()?.let { it.frameNumber != previous } == true
            }
            val result = stream().latestTapTriggerResult()!!
            val request = result.request
            val af = request.get(Request.CONTROL_AF_REGIONS)!![0].rect
            val ae = request.get(Request.CONTROL_AE_REGIONS)!![0].rect
            assertThat(af).isEqualTo(ae)
            assertThat(request.get(Request.CONTROL_AF_TRIGGER)).isEqualTo(Request.CONTROL_AF_TRIGGER_START)
            assertThat(request.get(Request.CONTROL_AE_PRECAPTURE_TRIGGER)).isEqualTo(Request.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            note("UI tap($x,$y) frame=${result.frameNumber} AF=$af AE=$ae AF_START + AE_START")
            return af
        }
        val first = meteringTap(.25f, .30f)
        val second = meteringTap(.75f, .65f)
        assertThat(first).isNotEqualTo(second)
        shot("tap-metering")
        tag("rangefinder-pro").performClick()
        tag("mechanical-tab-exp").performScrollTo().performClick()
        tag("mechanical-subtab-iso").performClick()
        val initialIso = CaptureUiInspection.manual!!.iso
        compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
        compose.waitUntil(5_000) { CaptureUiInspection.manual?.iso != initialIso }
        compose.waitUntil(5_000) {
            stream().latestResult()?.request?.get(Request.SENSOR_SENSITIVITY) == CaptureUiInspection.manual?.iso
        }
        val iso = CaptureUiInspection.manual!!.iso
        note("ISO dial requested=$iso sensor=${stream().latestResult()?.get(Result.SENSOR_SENSITIVITY)}")
        shot("iso-live")
        tag("mechanical-subtab-shutter").performClick()
        val initialExposure = CaptureUiInspection.manual!!.exposureTimeNs
        compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick()
        compose.waitUntil(5_000) { CaptureUiInspection.manual?.exposureTimeNs != initialExposure }
        val exposure = CaptureUiInspection.manual!!.exposureTimeNs
        val previewExposure = PreviewExposure.time(exposure)
        note("Shutter dial storedStill=$exposure previewTarget=$previewExposure " +
            "initial=$initialExposure latestRequest=${stream().latestResult()?.request?.get(Request.SENSOR_EXPOSURE_TIME)}")
        compose.waitUntil(5_000) {
            stream().latestResult()?.request?.get(Request.SENSOR_EXPOSURE_TIME) == previewExposure
        }
        val previewResult = stream().latestResult()!!
        assertThat(previewResult.request.get(Request.CONTROL_AE_MODE)).isEqualTo(Request.CONTROL_AE_MODE_OFF)
        assertThat(previewResult.request.get(Request.SENSOR_SENSITIVITY))
            .isAtLeast(CaptureUiInspection.manual!!.iso)
        note("Shutter dial storedStill=$exposure previewRequest=${previewResult.request.get(Request.SENSOR_EXPOSURE_TIME)} " +
            "previewISO=${previewResult.request.get(Request.SENSOR_SENSITIVITY)} " +
            "sensor=${previewResult.get(Result.SENSOR_EXPOSURE_TIME)} AE_OFF")
    }

    /** Priming the shutter must not silently move a deliberately placed AF/AE target. */
    @Test fun shutterHalfPressKeepsOffCentreMeteringWithoutCapture() = withCamera {
        val activeStream = stream()
        val targetX = .76f
        val targetY = .67f
        val previousTrigger = activeStream.latestTapTriggerResult()?.frameNumber
        tap(targetX, targetY)
        compose.waitUntil(10_000) {
            activeStream.latestTapTriggerResult()?.let { it.frameNumber != previousTrigger } == true
        }
        val tapped = checkNotNull(activeStream.latestTapTriggerResult())
        val targetAf = checkNotNull(tapped.request.get(Request.CONTROL_AF_REGIONS))[0].rect
        val targetAe = checkNotNull(tapped.request.get(Request.CONTROL_AE_REGIONS))[0].rect
        assertThat(targetAf).isEqualTo(targetAe)
        compose.waitUntil(10_000) {
            activeStream.latestResult()?.request?.get(Request.CONTROL_AF_REGIONS)?.firstOrNull()?.rect == targetAf &&
                activeStream.latestResult()?.request?.get(Request.CONTROL_AE_REGIONS)?.firstOrNull()?.rect == targetAe
        }
        shot("tap-off-centre")
        val beforeRecords = CaptureLog.records(context).map { it.baseName }.toSet()
        val beforeManual = CaptureUiInspection.manual
        val shutter = tag("rangefinder-shutter").assertIsDisplayed()
        var held = false
        try {
            shutter.performTouchInput { down(center) }
            held = true
            compose.waitUntil(10_000) {
                activeStream.latestTapTriggerResult()?.let { it.frameNumber != tapped.frameNumber } == true
            }
            val primed = checkNotNull(activeStream.latestTapTriggerResult())
            val primedAf = checkNotNull(primed.request.get(Request.CONTROL_AF_REGIONS))[0].rect
            val primedAe = checkNotNull(primed.request.get(Request.CONTROL_AE_REGIONS))[0].rect
            note("Shutter half-press tapped=($targetX,$targetY) AF=$targetAf AE=$targetAe " +
                "primed AF=$primedAf AE=$primedAe frame=${primed.frameNumber}")
            assertThat(primedAf).isEqualTo(targetAf)
            assertThat(primedAe).isEqualTo(targetAe)
            assertThat(CaptureUiInspection.manual).isEqualTo(beforeManual)
        } finally {
            if (held) shutter.performTouchInput { cancel() } // ACTION_CANCEL: never release-click the shutter
            frames(5)
            assertThat(CaptureLog.records(context).map { it.baseName }.toSet()).isEqualTo(beforeRecords)
            assertThat(CaptureUiInspection.merge).isNull()
        }
    }

    @Test fun productionColourFocusAndLoupe() = withCamera {
        val bounds = tag("camera-preview").fetchSemanticsNode().boundsInRoot
        tag("rangefinder-pro").performClick()
        tag("mechanical-tab-color").performClick()
        tag("revision05-wb-mode").performClick()
        tag("revision05-wb-manual").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            stream().latestResult()?.request?.get(Request.CONTROL_AWB_MODE) == Request.CONTROL_AWB_MODE_OFF &&
                stream().latestResult()?.request?.get(Request.COLOR_CORRECTION_GAINS) != null
        }
        note("Calibrated manual WB ${CaptureUiInspection.manual!!.kelvin} K, request AWB_OFF with gains")
        shot("colour-manual")
        // Auto WB is an explicit independent action, not an effect of hiding Pro.
        tag("revision05-wb-mode").performClick()
        tag("revision05-wb-auto").performScrollTo().performClick()
        tag("mechanical-tab-focus").performClick()
        tag("revision05-focus-manual").performScrollTo().performClick()
        compose.waitUntil(5_000) { renderer()?.peakingEnabled == true }
        repeat(12) { compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick() }
        frames(25)
        note("Near focus requested=${CaptureUiInspection.manual?.focusDiopters} result=${stream().latestResult()?.get(Result.LENS_FOCUS_DISTANCE)}")
        shot("focus-near")
        repeat(12) { compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick() }
        frames(25)
        assertThat(CaptureUiInspection.manual!!.focusDiopters).isEqualTo(0f)
        note("Infinity focus result=${stream().latestResult()?.get(Result.LENS_FOCUS_DISTANCE)}")
        shot("focus-infinity")
        compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick()
        frames(20)
        tag("rangefinder-focus-assist-toggle").performClick()
        tap(.25f, .35f)
        frames()
        assertThat(renderer()!!.focusInsetEnabled).isTrue()
        shot("loupe-target-a")
        tap(.70f, .73f)
        frames()
        shot("loupe-target-b")
        val before = stream().latestTapTriggerResult()?.frameNumber
        val manualBefore = CaptureUiInspection.manual
        tap(.2f, .70f)
        frames()
        assertThat(stream().latestTapTriggerResult()?.frameNumber).isEqualTo(before)
        assertThat(CaptureUiInspection.manual).isEqualTo(manualBefore)
        assertThat(stream().latestResult()!!.request.get(Request.CONTROL_AF_MODE)).isEqualTo(Request.CONTROL_AF_MODE_OFF)
        note("Manual focus/exposure protected from tap: no new trigger, AF_OFF, settings unchanged")
        tag("mechanical-tab-exp").performClick()
        compose.waitUntil(5_000) { renderer()?.peakingEnabled == false && renderer()?.focusInsetEnabled == false }
        val hiddenProfile = CaptureUiInspection.manual
        tag("rangefinder-pro").performClick()
        assertThat(CaptureUiInspection.manual).isEqualTo(hiddenProfile)
        tag("rangefinder-pro").assertIsSelected()
        tag("rangefinder-shutter").assertIsDisplayed()
        tag("rangefinder-pro").performClick()
        tag("revision05-return-auto").performClick()
        compose.waitUntil(5_000) { CaptureUiInspection.manual?.manualExposure == false &&
            CaptureUiInspection.manual?.manualFocus == false && CaptureUiInspection.manual?.manualColor == false }
        compose.waitUntil(5_000) {
            stream().latestResult()?.request?.get(Request.CONTROL_AE_MODE) == Request.CONTROL_AE_MODE_ON &&
                stream().latestResult()?.request?.get(Request.CONTROL_AF_MODE) != Request.CONTROL_AF_MODE_OFF &&
                stream().latestResult()?.request?.get(Request.CONTROL_AWB_MODE) == Request.CONTROL_AWB_MODE_AUTO
        }
        note("Explicit Return to Auto restored AE_ON and AF auto; independent Auto WB remained active")
        assertThat(tag("camera-preview").fetchSemanticsNode().boundsInRoot).isEqualTo(bounds)
    }

    @Test fun productionTorchAndSelfie() = withCamera {
        val rearId = stream().lensId
        tag("rangefinder-torch").performClick()
        compose.waitUntil(5_000) { stream().latestResult()?.get(Result.FLASH_MODE) == Request.FLASH_MODE_TORCH }
        note("Torch Camera2 result FLASH_MODE_TORCH")
        shot("torch-on")
        tag("rangefinder-torch").performClick()
        compose.waitUntil(5_000) { stream().latestResult()?.get(Result.FLASH_MODE) == Request.FLASH_MODE_OFF }
        tag("rangefinder-camera-flip").performClick()
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val frontIds = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }
        compose.waitUntil(30_000) {
            (CaptureUiInspection.stream == null || CaptureUiInspection.stream?.lensId in frontIds) &&
                renderer()?.renderedFrames?.let { it > 20 } == true
        }
        frames(20)
        compose.onNodeWithContentDescription("Switch to rear camera").assertIsDisplayed()
        tag("rangefinder-torch").assertDoesNotExist()
        tag("mechanical-lens-0").assertDoesNotExist()
        val frontBounds = tag("camera-preview").fetchSemanticsNode().boundsInRoot
        assertThat(frontBounds.width).isWithin(1.5f).of(frontBounds.height * .75f)
        shot("selfie")
        note("Front camera stream=${CaptureUiInspection.stream?.lensId ?: "CameraX"}, physical front IDs=$frontIds")
        tag("rangefinder-camera-flip").performClick()
        compose.waitUntil(30_000) { CaptureUiInspection.stream?.let { it.lensId == rearId && it.latestResult() != null } == true }
        frames()
        shot("rear-restored")
    }

    @Test fun productionTurretLabelsAndLongExposureMode() = withCamera {
        for (index in listOf(2, 4, 1)) {
            tag("mechanical-lens-$index").performClick()
            compose.waitUntil(30_000) {
                tag("mechanical-lens-$index").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.Selected] &&
                    CaptureUiInspection.stream?.latestResult() != null &&
                    compose.onAllNodesWithTag("rangefinder-subpixel-label").fetchSemanticsNodes()
                        .isNotEmpty() == (index == 2 || index == 4)
            }
            frames(20)
            tag("mechanical-lens-$index").assertIsSelected()
            val preview = tag("camera-preview").fetchSemanticsNode().boundsInRoot
            assertThat(preview.width).isWithin(1.5f).of(preview.height * .75f)
            tag("revision05-info").performClick()
            tag("revision05-capture-summary").performScrollTo().assertTextContains("Camera:")
            note("Active crop=${stream().latestResult()?.request?.get(Request.SCALER_CROP_REGION)}")
            tag("revision05-info").performClick()
            note("Turret stop=$index stream=${stream().lensId} preview=$preview")
            shot("turret-$index")
        }
        tag("rangefinder-mode-photo").assertIsDisplayed()
        tag("rangefinder-mode-night").assertIsDisplayed()
        tag("mechanical-mode-2").assertDoesNotExist()
        tag("rangefinder-pro").performClick()
        tag("mechanical-tab-exp").performScrollTo().performClick()
        tag("mechanical-subtab-shutter").performClick()
        repeat(26) { compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick() }
        val still = CaptureUiInspection.manual!!.exposureTimeNs
        assertThat(still).isAtLeast(1_000_000_000L)
        compose.waitUntil(5_000) {
            stream().latestResult()?.request?.get(Request.SENSOR_EXPOSURE_TIME) == PreviewExposure.time(still)
        }
        frames()
        tag("rangefinder-pro").assertIsSelected()
        assertThat(stream().latestResult()!!.request.get(Request.SENSOR_EXPOSURE_TIME)).isAtMost(33_333_333L)
        tag("rangefinder-shutter").assertIsDisplayed()
        note("Long shutter remains in Pro: stored=$still ns; preview <=1/30 s; no still captured")
        shot("long-exp")
        tag("revision05-return-auto").performClick()
        compose.waitUntil(5_000) { CaptureUiInspection.manual?.manualExposure == false }
        tag("rangefinder-pro").performClick()
        tag("rangefinder-mode-photo").performClick()
        tag("rangefinder-mode-photo").assertIsSelected()
    }

    @Test fun productionLandscapeTurretLabelsFitWithoutChangingFrameBounds() = withCamera {
        val before = CaptureLog.records(context).map { it.baseName }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(20_000) {
            var ready = false
            scenario.onActivity {
                ready = it.hasWindowFocus() && it.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            ready && tag("camera-preview").fetchSemanticsNode().boundsInRoot.let { it.width > it.height }
        }
        frames()
        val preview = tag("camera-preview").fetchSemanticsNode().boundsInRoot
        for (index in listOf(2, 4, 1)) {
            tag("mechanical-lens-$index").performTouchInput { click() }
            compose.waitUntil(30_000) {
                tag("mechanical-lens-$index").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.Selected] &&
                    CaptureUiInspection.stream?.latestResult() != null &&
                    compose.onAllNodesWithTag("rangefinder-subpixel-label").fetchSemanticsNodes()
                        .isNotEmpty() == (index == 2 || index == 4)
            }
            frames(20)
            compose.waitForIdle()
            assertThat(tag("camera-preview").fetchSemanticsNode().boundsInRoot).isEqualTo(preview)
            if (index == 1) {
                tag("rangefinder-subpixel-label").assertDoesNotExist()
                tag("rangefinder-superres-badge").assertDoesNotExist()
            } else {
                val turret = tag("rangefinder-turret").fetchSemanticsNode().boundsInRoot
                val label = tag("rangefinder-subpixel-label").fetchSemanticsNode().boundsInRoot
                val badge = tag("rangefinder-superres-badge").fetchSemanticsNode().boundsInRoot
                assertThat(label.left).isAtLeast(turret.left)
                assertThat(label.right).isAtMost(badge.left)
                assertThat(badge.right).isAtMost(turret.right)
                assertThat(label.top).isAtLeast(turret.top)
                assertThat(label.bottom).isAtMost(turret.bottom)
                for (name in listOf("rangefinder-subpixel-label", "rangefinder-superres-badge")) {
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    tag(name).performSemanticsAction(
                        androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
                    ) { it(layouts) }
                    val layout = layouts.single()
                    assertThat(layout.lineCount).isEqualTo(1)
                    assertThat(layout.isLineEllipsized(0)).isFalse()
                    assertThat(layout.getLineEnd(0, visibleEnd = true)).isEqualTo(layout.layoutInput.text.length)
                    assertThat(kotlin.math.ceil(layout.getLineRight(0).toDouble()).toInt())
                        .isAtMost(layout.size.width)
                    assertThat(layout.didOverflowHeight).isFalse()
                }
                note("Landscape turret=$index preview=$preview label=$label badge=$badge")
            }
            shot("landscape-turret-$index")
        }
        assertThat(CaptureLog.records(context).map { it.baseName }).isEqualTo(before)
    }

    @Test fun visibleTwentyEightFrameCaptureProcessingAndLightbox() = withCamera {
        check(InstrumentationRegistry.getArguments().getString("sceneConfirmed") == "true") {
            "Capture acceptance requires Craig's confirmed fixed scene; pass sceneConfirmed=true"
        }
        tag("rangefinder-mode-night").performClick() // NIGHT has the supported manual ceiling; PHOTO keeps its cap.
        tag("rangefinder-pro").performClick()
        tag("mechanical-tab-frames").performClick()
        repeat(8) { compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick() }
        assertThat(CaptureUiInspection.frames).isEqualTo(28)
        compose.onNodeWithText("28f").assertExists()
        compose.waitUntil(15_000) { stream().readyFrames() >= 28 }
        val before = CaptureLog.records(context).map { it.baseName }.toSet()
        shot("capture-28-ready")
        tag("rangefinder-shutter").performTouchInput { down(center); advanceEventTime(160); up() }
        var sawMerge = false
        var savedProcessing = false
        val samples = linkedSetOf<String>()
        compose.waitUntil(60_000) {
            CaptureUiInspection.merge?.let { progress ->
                if (!progress.finished && progress.total == 28) sawMerge = true
                samples += "${progress.processed}/${progress.total} finished=${progress.finished}"
            }
            if (!savedProcessing && compose.onAllNodesWithTag("merge-processing-indicator").fetchSemanticsNodes().isNotEmpty()) {
                shot("capture-28-processing"); savedProcessing = true
            }
            CaptureLog.records(context).any { it.baseName !in before && it.jpeg != null }
        }
        assertThat(sawMerge).isTrue()
        assertThat(savedProcessing).isTrue()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("merge-processing-indicator").fetchSemanticsNodes().isEmpty() }
        val first = CaptureLog.records(context).first { it.baseName !in before }
        note("28-frame UI capture saved=${first.baseName} progress=$samples")
        shot("capture-28-complete")
        repeat(8) { compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick() }
        compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
        compose.waitUntil(5_000) { CaptureUiInspection.frames == 2 }
        assertThat(CaptureUiInspection.frames).isEqualTo(2)
        val prior = CaptureLog.records(context).map { it.baseName }.toSet()
        tag("rangefinder-shutter").performTouchInput { click() }
        compose.waitUntil(45_000) { CaptureLog.records(context).any { it.baseName !in prior && it.jpeg != null } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("merge-processing-indicator").fetchSemanticsNodes().isEmpty() }
        val second = CaptureLog.records(context).first { it.baseName !in prior }
        assertThat(second.baseName).contains("_2f")
        note("2-frame UI capture saved=${second.baseName}")
        tag("rangefinder-pro").performClick()
        compose.onNodeWithContentDescription("Last shot").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("lightbox-cell-${second.baseName}").fetchSemanticsNodes().isNotEmpty() }
        tag("lightbox-cell-${second.baseName}").performClick()
        fun review(record: CaptureRecord) = tag("lightbox-review-${record.baseName}")
        compose.waitUntil(10_000) { review(second).fetchSemanticsNode().config[GalleryImageSize].width > 0 }
        shot("lightbox-review-newest")
        review(second).performTouchInput {
            down(0, Offset(width * .4f, height * .5f)); down(1, Offset(width * .6f, height * .5f))
            moveTo(0, Offset(width * .15f, height * .5f), delayMillis = 200)
            moveTo(1, Offset(width * .85f, height * .5f), delayMillis = 200)
            up(0); up(1)
        }
        compose.waitForIdle()
        val zoom = review(second).fetchSemanticsNode().config
        assertThat(zoom[GalleryScale]).isGreaterThan(1.5f)
        assertThat(kotlin.math.abs(zoom[GalleryOffset].x)).isLessThan(10f)
        review(second).performTouchInput { swipe(Offset(width * .4f, height * .4f), Offset(width * .95f, height * .95f), 250) }
        val pan = review(second).fetchSemanticsNode().config
        assertThat(clampOffset(pan[GalleryOffset], pan[GalleryScale], pan[GalleryFrameSize], pan[GalleryImageSize])).isEqualTo(pan[GalleryOffset])
        note("Lightbox pinch scale=${pan[GalleryScale]} clampedOffset=${pan[GalleryOffset]} image=${pan[GalleryImageSize]}")
        shot("lightbox-zoom-clamped")
        review(second).performTouchInput { doubleClick() }
        tag("lightbox-pager").performTouchInput { swipeLeft() }
        review(first).assertIsDisplayed()
        note("Lightbox paged from ${second.baseName} to ${first.baseName}")
        compose.onNode(hasContentDescription("INFO") and
            hasAnyAncestor(hasTestTag("lightbox-review-${first.baseName}"))).performClick()
        shot("lightbox-previous-info")
    }

    @Test fun serviceManualAndLabsExplanationsAreReachable() = withCamera {
        tag("titanium-labs-button").performClick()
        compose.onNodeWithText("Protects bright highlights during capture.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lifts dark areas while preserving contrast and texture.").performScrollTo().assertIsDisplayed()
        shot("settings-explanations")
        compose.onNodeWithText("TECHNOLOGY PREVIEWS").performScrollTo().performClick()
        compose.onNodeWithText("Experimental. Best on tripods or scenes > 5m.").performScrollTo().assertIsDisplayed()
        shot("labs-warning")
        compose.onNodeWithContentDescription("Close settings").performClick()
        tag("rangefinder-shutter").assertIsDisplayed()
    }
}
