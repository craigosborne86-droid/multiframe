package dev.multiframe.camera.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import androidx.camera.core.impl.CameraCaptureCallback
import androidx.camera.core.impl.CameraCaptureResult
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.*
import org.junit.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Actual MainActivity/CameraScreen, emulated Camera2 HAL; no replacement UI. */
class CaptureScreenEmulatorIntegrationTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var saved: AppSettings
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val results = CopyOnWriteArrayList<CaptureResult>()
    private val infos = mutableListOf<CameraInfoInternal>()
    private val callback = object : CameraCaptureCallback() {
        override fun onCaptureCompleted(captureConfigId: Int, result: CameraCaptureResult) {
            result.captureResult?.let { results += it }
        }
    }

    @Before fun launchRealCaptureScreen() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "Emulator only; never run this class on Craig's phone"
        }
        saved = AppSettings.load(context)
        OnboardingState.markSeen(context)
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val rear = manager.cameraIdList.first { manager.getCameraCharacteristics(it)
            .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK }
        AppSettings.save(context, saved.copy(manual = ManualSettings(), lensId = rear,
            captureMode = CaptureMode.PHOTO, fullResolution = false, timerSeconds = 0))
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        infos += provider.availableCameraInfos.map { (it as CameraInfoInternal).implementation }
        infos.forEach { it.addSessionCaptureCallback(java.util.concurrent.Executor { r -> r.run() }, callback) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        try { compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().isNotEmpty() &&
                (CaptureUiInspection.stream?.latestResult() != null || results.size >= 3)
        } } catch (e: Throwable) {
            report("STARTUP: stream=${CaptureUiInspection.stream} result=${CaptureUiInspection.stream?.latestResult()} callbacks=${results.size}\n${compose.onRoot().printToString()}")
            screenshot("startup-failed")
            throw e
        }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        if (::scenario.isInitialized) scenario.close()
        compose.mainClock.autoAdvance = true
        infos.forEach { it.removeSessionCaptureCallback(callback) }
        if (::saved.isInitialized) AppSettings.save(context, saved)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun report(text: String) = instrumentation.sendStatus(0, android.os.Bundle().apply {
        putString("stream", "$text\n")
    })
    private fun screenshot(name: String, selectedTag: String? = null) {
        // Semantics can advance before SurfaceFlinger presents the new tab.
        // Wait for the submitted native pixels, rather than saving a mislabeled frame.
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        var bitmap: Bitmap? = null
        compose.waitUntil(5_000) {
            val candidate = instrumentation.uiAutomation.takeScreenshot()
            val ready = if (selectedTag == null) true else {
                val rect = bounds(selectedTag)
                val left = rect.left.toInt().coerceAtLeast(0)
                val top = rect.top.toInt().coerceAtLeast(0)
                val width = rect.width.toInt().coerceAtMost(candidate.width - left)
                val height = rect.height.toInt().coerceAtMost(candidate.height - top)
                val pixels = IntArray(width * height)
                candidate.getPixels(pixels, 0, width, left, top, width, height)
                pixels.any { it == TitaniumTokens.OpticalAmber.toArgb() }
            }
            if (ready) bitmap = candidate else candidate.recycle()
            ready
        }
        val committed = checkNotNull(bitmap)
        val dir = File(context.getExternalFilesDir(null), "capture-screen-integration").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { committed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        committed.recycle()
    }

    @Test fun portraitImageFitsScreenWidthWithNoCropAndSharedReticleBounds() {
        val image = bounds("camera-preview")
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val console = bounds("camera-control-band")
        val reticle = bounds("rangefinder-view-reticle")
        report("PORTRAIT root=$root image=$image controls=$console reticle=$reticle")
        screenshot("portrait-auto")
        assertThat(image.width).isWithin(1f).of(root.width)
        assertThat(image.width / image.height).isWithin(.001f).of(.75f)
        assertThat(bounds("camera-metering-region")).isEqualTo(image)
        assertThat(reticle).isEqualTo(image)
        assertThat(reticle.center).isEqualTo(image.center)
        assertThat(image.bottom).isAtMost(console.top)
        assertThat(image.overlaps(console)).isFalse()
        checkNativeFraming(image)
    }

    private fun findPreview(view: android.view.View): PeakingPreviewView? {
        if (view is PeakingPreviewView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
            findPreview(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun checkNativeFraming(image: Rect) {
        compose.waitUntil(10_000) {
            var ready = false
            scenario.onActivity { activity ->
                val view = findPreview(activity.window.decorView)
                ready = view != null && view.renderedFrames > 0 &&
                    view.holder.surfaceFrame.width() == image.width.toInt() &&
                    view.holder.surfaceFrame.height() == image.height.toInt()
            }
            ready
        }
        scenario.onActivity { activity ->
            val view = findPreview(activity.window.decorView)!!
            val visible = android.graphics.Rect()
            assertThat(view.getGlobalVisibleRect(visible)).isTrue()
            assertThat(visible).isEqualTo(android.graphics.Rect(image.left.toInt(), image.top.toInt(), image.right.toInt(), image.bottom.toInt()))
            fun field(name: String) = PeakingPreviewView::class.java.getDeclaredField(name).apply { isAccessible = true }
            val cropX = field("cropX").getFloat(view)
            val cropY = field("cropY").getFloat(view)
            assertThat(cropX).isWithin(.001f).of(1f)
            assertThat(cropY).isWithin(.001f).of(1f)
            val buffer = field("bufferCrop").get(view) as FloatArray
            assertThat(buffer.toList()).containsExactly(0f, 0f, 1f, 1f).inOrder()
            val matrix = field("textureMatrix").get(view) as FloatArray
            val xSpan = kotlin.math.hypot(matrix[0], matrix[1])
            val ySpan = kotlin.math.hypot(matrix[4], matrix[5])
            assertThat(xSpan).isAtLeast(.995f)
            assertThat(ySpan).isAtLeast(.995f)
            report("NATIVE GL bounds=$visible shaderCrop=$cropX,$cropY textureSpan=$xSpan,$ySpan bufferCrop=${buffer.joinToString()}")
        }
    }
    private fun nativeReady() {
        compose.waitUntil(20_000) {
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
            latestResult() != null
        }
    }
    private fun latestResult(): CaptureResult? = CaptureUiInspection.stream?.latestResult() ?: results.lastOrNull()
    private fun tapTrigger(): CaptureResult? = CaptureUiInspection.stream?.latestTapTriggerResult() ?: results.lastOrNull {
        it.request.get(CaptureRequest.CONTROL_AF_TRIGGER) == CaptureRequest.CONTROL_AF_TRIGGER_START ||
            it.request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER) == CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
    }
    private fun pointerTap(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click() }
        // Rotation tests own the clock; dispatch the resulting composition too.
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun proAvailable(): Boolean = compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()
    private fun openPro() {
        check(proAvailable()) { "R05 Pro panel must remain accessible even with unsupported sensor parameters" }
        pointerTap("rangefinder-pro")
        compose.waitUntil(5_000) {
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("revision05-replacement-panel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rangefinder-pro").assertIsSelected()
    }
    private fun settleFrames() {
        val before = latestResult()?.frameNumber ?: error("No live session result")
        compose.waitUntil(5_000) { latestResult()?.frameNumber?.let { it >= before + 4 } == true }
    }

    @Test fun consolePointerTapsDoNotMeterWhileImageTapDoes() {
        nativeReady()
        val tags = listOf("rangefinder-mode-photo", "rangefinder-mode-night",
            "rangefinder-pro", "mechanical-lens-0", "mechanical-lens-1", "mechanical-lens-2",
            "mechanical-lens-3", "mechanical-lens-4", "rangefinder-torch", "rangefinder-timer")
        for (tag in tags) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
                report("NOT TESTED absent capability-gated control: $tag")
                continue
            }
            val trigger = tapTrigger()?.frameNumber
            pointerTap(tag)
            settleFrames()
            assertThat(tapTrigger()?.frameNumber).isEqualTo(trigger)
            compose.onNodeWithTag("rangefinder-tap-focus-reticle").assertDoesNotExist()
            report("NO METERING: $tag")
            if (compose.onAllNodesWithTag("revision05-replacement-panel").fetchSemanticsNodes().isNotEmpty()) pointerTap("rangefinder-pro")
        }
        pointerTap("mechanical-lens-1")
        if (compose.onAllNodesWithTag("rangefinder-camera-flip").fetchSemanticsNodes().isNotEmpty()) {
            pointerTap("rangefinder-camera-flip")
            compose.onNodeWithTag("rangefinder-tap-focus-reticle").assertDoesNotExist()
            report("NO FOCUS SQUARE: SELFIE")
            results.clear()
            pointerTap("rangefinder-camera-flip")
            nativeReady()
            assertThat(tapTrigger()).isNull()
        } else report("NOT TESTED: SELFIE absent")
        // Hiding a panel preserves a profile. Explicit Auto is required before
        // image taps may issue autofocus/auto-exposure commands.
        openPro()
        if (compose.onAllNodesWithTag("revision05-return-auto").fetchSemanticsNodes().isNotEmpty()) pointerTap("revision05-return-auto")
        pointerTap("rangefinder-pro")
        val image = bounds("camera-preview")
        val point = Offset(image.left + image.width * .3f, image.top + image.height * .35f)
        compose.onRoot().performTouchInput { click(point) }
        compose.onNodeWithTag("rangefinder-tap-focus-reticle").assertIsDisplayed()
        settleFrames()
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val ch = manager.getCameraCharacteristics(CaptureUiInspection.stream?.lensId ?: manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        })
        val afSupported = (ch.get(android.hardware.camera2.CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        val aeSupported = (ch.get(android.hardware.camera2.CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
        if (afSupported || aeSupported) {
            compose.waitUntil(5_000) { tapTrigger() != null }
            val request = tapTrigger()!!.request
            if (afSupported) {
                assertThat(request.get(CaptureRequest.CONTROL_AF_REGIONS)).isNotEmpty()
                assertThat(request.get(CaptureRequest.CONTROL_AF_TRIGGER)).isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            if (aeSupported) assertThat(request.get(CaptureRequest.CONTROL_AE_REGIONS)).isNotEmpty()
            if (CaptureUiInspection.stream != null && aeSupported)
                assertThat(request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER)).isEqualTo(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            report("IMAGE TAP: supported real session metering request=$request")
        } else report("NOT TESTED live AF/AE: virtual HAL MAX_REGIONS_AF=AE=0; full coordinate/request oracle runs separately")
        screenshot("image-tap")
    }

    @Test fun letterboxedCornersBuildCorrectAfAeRequestsOutsidePointsBuildNone() {
        nativeReady()
        val image = bounds("camera-preview")
        // No submission: exercise framework AF/AE request construction independently
        // of this virtual sensor's MAX_REGIONS_AF/AE=0 restriction.
        scenario.close()
        instrumentation.runOnMainSync { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val latch = java.util.concurrent.CountDownLatch(1)
        var device: android.hardware.camera2.CameraDevice? = null
        var failure: String? = null
        manager.openCamera(manager.cameraIdList.first(), context.mainExecutor,
            object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) { device = camera; latch.countDown() }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { failure = "disconnected"; camera.close(); latch.countDown() }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) { failure = "error $error"; camera.close(); latch.countDown() }
            })
        check(latch.await(15, TimeUnit.SECONDS) && device != null) { "Builder fixture unavailable: $failure" }
        try {
            val crop = SensorRect(100, 200, 4100, 3200)
            val points = listOf(image.topLeft, Offset(image.right - 1f, image.top),
                Offset(image.left, image.bottom - 1f), Offset(image.right - 1f, image.bottom - 1f),
                Offset(image.left + image.width * .25f, image.top + image.height * .35f))
            for (rotation in listOf(0, 90, 180, 270)) for (point in points) {
                val normalized = imagePoint(image, point)!!
                // Oracle starts from screen pixels, without calling the
                // production point/rotation helpers to derive expectations.
                val nx = (point.x - image.left) / image.width
                val ny = (point.y - image.top) / image.height
                assertThat(normalized).isEqualTo(Offset(nx, ny))
                val expectedSensor = when (rotation) {
                    90 -> (100 + kotlin.math.round(ny * 4000).toInt()) to (200 + kotlin.math.round((1f - nx) * 3000).toInt())
                    180 -> (100 + kotlin.math.round((1f - nx) * 4000).toInt()) to (200 + kotlin.math.round((1f - ny) * 3000).toInt())
                    270 -> (100 + kotlin.math.round((1f - ny) * 4000).toInt()) to (200 + kotlin.math.round(nx * 3000).toInt())
                    else -> (100 + kotlin.math.round(nx * 4000).toInt()) to (200 + kotlin.math.round(ny * 3000).toInt())
                }
                val sensor = TouchFocus.toSensorPoint(normalized.x, normalized.y, rotation, crop)
                assertThat(sensor).isEqualTo(expectedSensor)
                val region = TouchFocus.regionAt(normalized.x, normalized.y, rotation, crop)
                val expectedLeft = (expectedSensor.first - 300).coerceIn(crop.left, crop.right - 600)
                val expectedTop = (expectedSensor.second - 225).coerceIn(crop.top, crop.bottom - 450)
                assertThat(region).isEqualTo(SensorRect(expectedLeft, expectedTop, expectedLeft + 600, expectedTop + 450))
                val builder = device!!.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                val rectangles = arrayOf(TouchFocus.meteringRectangle(region))
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, rectangles)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, rectangles)
                val request = builder.build()
                assertThat(request.get(CaptureRequest.CONTROL_AF_REGIONS)!!.single().rect).isEqualTo(region.toRect())
                assertThat(request.get(CaptureRequest.CONTROL_AE_REGIONS)!!.single().rect).isEqualTo(region.toRect())
                report("PURE BUILDER point=$point normalized=(${normalized.x},${normalized.y}) sensor=$sensor AF=AE=$region rotation=$rotation")
            }
            for (point in listOf(Offset(image.center.x, image.top - 1f), Offset(image.center.x, image.bottom),
                Offset(image.left - 1f, image.center.y), Offset(image.right, image.center.y))) {
                val builder = device!!.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                assertThat(imagePoint(image, point)).isNull()
                // Some HAL templates include an inert zero-size/zero-weight
                // default rectangle. No outside point may add an active region.
                for (key in listOf(CaptureRequest.CONTROL_AF_REGIONS, CaptureRequest.CONTROL_AE_REGIONS))
                    assertThat(builder.build().get(key).orEmpty().all { it.meteringWeight == 0 || it.rect.isEmpty }).isTrue()
            }
        } finally { device?.close() }
    }

    @Test fun proTabsHoldGeometryAndExplicitManualAutoConfigureSupportedRequests() {
        val image = bounds("camera-preview")
        val console = bounds("camera-control-band")
        val shutter = bounds("rangefinder-shutter")
        val initial = CaptureUiInspection.manual!!
        openPro()
        compose.onNodeWithTag("rangefinder-pro").assertIsSelected()
        assertThat(CaptureUiInspection.manual).isEqualTo(initial)
        val manualButton = compose.onNodeWithTag("revision05-manual").assertIsDisplayed()
        val supported = !manualButton.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        if (supported) {
            manualButton.performClick()
            compose.waitUntil(5_000) { latestResult()?.request?.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF }
            val manual = CaptureUiInspection.manual!!
            val request = latestResult()!!.request
            assertThat(manual.manualExposure).isTrue()
            assertThat(manual.whiteBalanceMode).isEqualTo(initial.whiteBalanceMode)
            assertThat(request.get(CaptureRequest.SENSOR_SENSITIVITY)).isEqualTo(manual.iso)
            assertThat(request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)).isEqualTo(PreviewExposure.time(manual.exposureTimeNs))
        } else {
            manualButton.assertIsNotEnabled()
            assertThat(CaptureUiInspection.manual).isEqualTo(initial)
            report("NOT TESTED manual sensor actuation: virtual HAL unsupported; explicit manual/auto builder gate is separate")
        }
        for (tab in listOf("exp", "focus", "frames", "color")) {
            pointerTap("mechanical-tab-$tab")
            assertThat(bounds("camera-preview")).isEqualTo(image)
            assertThat(bounds("camera-metering-region")).isEqualTo(image)
            assertThat(bounds("rangefinder-view-reticle")).isEqualTo(image)
            assertThat(bounds("camera-control-band")).isEqualTo(console)
            assertThat(bounds("rangefinder-shutter")).isEqualTo(shutter)
            compose.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
            screenshot("portrait-pro-$tab")
        }
        val retained = CaptureUiInspection.manual
        pointerTap("rangefinder-pro")
        assertThat(CaptureUiInspection.manual).isEqualTo(retained)
        if (supported) compose.onNodeWithTag("rangefinder-pro").assertIsSelected()
        openPro()
        if (supported) compose.onNodeWithTag("revision05-return-auto").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { CaptureUiInspection.manual?.let { !it.manualExposure && !it.manualFocus } == true }
        val auto = CaptureUiInspection.manual!!
        assertThat(auto.manualExposure).isFalse()
        assertThat(auto.manualFocus).isFalse()
        assertThat(auto.whiteBalanceMode).isEqualTo(initial.whiteBalanceMode)
        compose.waitUntil(5_000) { latestResult()?.request?.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_ON }
        assertThat(latestResult()!!.request.get(CaptureRequest.CONTROL_AWB_MODE)).isEqualTo(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        pointerTap("rangefinder-pro")
        compose.onNodeWithTag("rangefinder-pro").assertIsNotSelected()
    }

    @Test fun rotationKeepsControlsReachableShutterVisibleAndModeState() {
        pointerTap("rangefinder-mode-night")
        val hadPro = proAvailable()
        if (hadPro) { openPro(); pointerTap("mechanical-tab-color") }
        // Keep the clock running while configuration changes recreate the
        // Activity and its Compose root. The earlier paused-clock harness could
        // leave that new root undrawn; control/geometry assertions stay intact.
        compose.mainClock.autoAdvance = true
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            val landscape = orientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            scenario.onActivity { it.requestedOrientation = orientation }
            compose.waitUntil(20_000) {
                compose.mainClock.advanceTimeByFrame()
                runCatching { compose.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().any {
                    (it.boundsInRoot.width > it.boundsInRoot.height) == landscape
                } }.getOrDefault(false)
            }
            nativeReady()
            val image = bounds("camera-preview")
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertThat(bounds("camera-metering-region")).isEqualTo(image)
            assertThat(bounds("rangefinder-view-reticle")).isEqualTo(image)
            assertThat(image.width / image.height).isWithin(.002f).of(if (landscape) 4f / 3f else .75f)
            val controls = bounds("camera-control-band")
            assertThat(image.overlaps(controls)).isFalse()
            for (tag in listOf("rangefinder-shutter", "rangefinder-camera-flip",
                "rangefinder-mode-photo", "rangefinder-mode-night") +
                if (hadPro) listOf("rangefinder-pro") else emptyList()) {
                try { compose.onNodeWithTag(tag).assertIsDisplayed() } catch (failure: Throwable) {
                    report("ROTATION FAILURE orientation=$orientation tag=$tag bounds=${runCatching { bounds(tag) }.getOrNull()} root=$root controls=$controls image=$image")
                    screenshot("rotation-failure-$orientation-$tag")
                    throw failure
                }
                val r = bounds(tag)
                assertThat(r.left).isAtLeast(root.left)
                assertThat(r.top).isAtLeast(root.top)
                assertThat(r.right).isAtMost(root.right)
                assertThat(r.bottom).isAtMost(root.bottom)
            }
            report("ROTATION landscape=$landscape image=$image controls=$controls offered controls contained; NIGHT preserved; PRO available=$hadPro")
            if (hadPro) {
                compose.onNodeWithTag("rangefinder-pro").assertIsSelected()
                compose.onNodeWithTag("mechanical-tab-color").assertIsSelected()
                pointerTap("rangefinder-pro")
            }
            compose.onNodeWithTag("rangefinder-mode-night").assertIsSelected()
            compose.onNodeWithTag("rangefinder-timer").assertIsDisplayed()
            compose.onNodeWithTag("rangefinder-turret").assertIsDisplayed()
            screenshot(when (orientation) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "landscape-night"
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "opposite-landscape-night"
                else -> "portrait-return-night"
            })
            if (hadPro && landscape) { openPro(); pointerTap("mechanical-tab-color") }
        }
    }

    @Test fun landscapeHudPlateAndOpticalAidsStaySeparateWithoutReframing() {
        for ((name, orientation) in listOf("landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "opposite-landscape" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            scenario.onActivity { it.requestedOrientation = orientation }
            compose.waitUntil(20_000) { bounds("camera-preview").let { it.width > it.height } }
            nativeReady()
            // CaptureResult can outlive the old Activity. Wait for the new
            // frame-latched view to draw before sampling its window screenshot.
            compose.waitUntil(15_000) {
                var drawn = false
                scenario.onActivity { drawn = (findPreview(it.window.decorView)?.renderedFrames ?: 0) >= 5 }
                drawn
            }
            compose.waitForIdle()
            val image = bounds("camera-preview")
            val deck = bounds("revision05-top-deck")
            val level = bounds("rangefinder-reticle")
            val label = bounds("rangefinder-tilt-readout")
            // Await the submitted window pixels too: layout/semantics can
            // update before PixelCopy receives the next reverse-rotation draw.
            // The second line is shorter than the first, leaving this corner
            // inside the readout's background and clear of text glyphs.
            var backing = 0
            var samples = 0
            val drawStart = android.os.SystemClock.elapsedRealtime()
            compose.waitUntil(5_000) {
                val pixels = compose.onNodeWithTag("revision05-compact-summary").captureToImage().toPixelMap()
                backing = pixels[pixels.width - 1, pixels.height - 1].toArgb()
                samples++
                backing == TitaniumTokens.PlateSurface.toArgb()
            }
            val plate = backing == TitaniumTokens.PlateSurface.toArgb()
            report("HUD sample $name pixel=${Integer.toHexString(backing)} samples=$samples drawWaitMs=${android.os.SystemClock.elapsedRealtime() - drawStart}")
            report("HUD $name image=$image deck=$deck level=$level readout=$label plate=$plate pixel=${Integer.toHexString(backing)}")
            screenshot("hud-$name-auto")
            Assert.assertTrue("Telemetry needs opaque Titanium plate over bright preview", plate)
            Assert.assertFalse("Level marker must stay below HUD", deck.overlaps(level))
            Assert.assertFalse("Level readout must stay below HUD", deck.overlaps(label))
            pointerTap("revision05-rgb")
            val histogram = bounds("camera-histogram")
            Assert.assertFalse("Preview RGB must not overlap HUD", deck.overlaps(histogram))
            pointerTap("revision05-rgb")
            openPro(); pointerTap("mechanical-tab-focus")
            compose.onNodeWithTag("revision05-focus-manual").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { CaptureUiInspection.manual?.manualFocus == true }
            compose.onNodeWithTag("rangefinder-focus-assist-toggle").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("rangefinder-focus-inset").fetchSemanticsNodes().isNotEmpty() }
            val inset = bounds("rangefinder-focus-inset")
            Assert.assertFalse("Focus assist must not cover HUD controls", deck.overlaps(inset))
            val panel = bounds("revision05-replacement-panel")
            report("HUD $name panel=$panel inset=$inset overlap=${panel.overlaps(inset)}")
            Assert.assertFalse("Focus assist must not be hidden by the manual panel", panel.overlaps(inset))
            assertThat(bounds("camera-preview")).isEqualTo(image)
            assertThat(bounds("camera-metering-region")).isEqualTo(image)
            assertThat(bounds("rangefinder-view-reticle")).isEqualTo(image)
            checkNativeFraming(image)
            report("HUD $name focus inset=$inset; image/touch/aids/source unchanged")
            screenshot("hud-$name-focus-loupe")
            pointerTap("revision05-rgb")
            val combinedHistogram = bounds("camera-histogram")
            report("HUD $name combined histogram=$combinedHistogram inset=$inset panel=$panel")
            Assert.assertFalse("Preview RGB and focus loupe must stay separate", combinedHistogram.overlaps(inset))
            Assert.assertFalse("Preview RGB must stay clear of manual controls", combinedHistogram.overlaps(panel))
            screenshot("hud-$name-focus-loupe-rgb")
            pointerTap("revision05-rgb")
            pointerTap("rangefinder-focus-assist-toggle")
            compose.onNodeWithTag("revision05-focus-auto").performScrollTo().performClick()
            pointerTap("rangefinder-pro")
        }
    }

    @Test fun proCorrectionKeepsEveryTabInsideConsoleAndPreservesFraming() {
        for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "opposite-landscape" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            scenario.onActivity { it.requestedOrientation = orientation }
            compose.waitUntil(20_000) { bounds("camera-preview").let {
                (it.width > it.height) == (orientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) } }
            nativeReady()
            val image = bounds("camera-preview")
            val console = bounds("camera-control-band")
            val shutter = bounds("rangefinder-shutter")
            val pro = bounds("rangefinder-pro")
            report("PRO REVIEW $name auto image=$image console=$console shutter=$shutter")
            screenshot("pro-review-$name-auto")
            openPro()
            for (tab in listOf("exp", "focus", "frames", "color")) {
                pointerTap("mechanical-tab-$tab")
                val panel = bounds("revision05-replacement-panel")
                report("PRO REVIEW $name $tab image=${bounds("camera-preview")} panel=$panel console=$console")
                screenshot("pro-review-$name-$tab", "mechanical-tab-$tab")
                Assert.assertFalse("Pro must not cover the photograph", image.overlaps(panel))
                Assert.assertTrue("Pro must be inside the shooting console", panel.left >= console.left &&
                    panel.top >= console.top && panel.right <= console.right && panel.bottom <= console.bottom)
                assertThat(bounds("camera-preview")).isEqualTo(image)
                assertThat(bounds("camera-metering-region")).isEqualTo(image)
                assertThat(bounds("rangefinder-shutter")).isEqualTo(shutter)
                assertThat(bounds("rangefinder-pro")).isEqualTo(pro)
                compose.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
                if (tab != "color" && compose.onAllNodesWithTag("rangefinder-dial-ruler").fetchSemanticsNodes().isNotEmpty()) {
                    val ruler = bounds("rangefinder-dial-ruler")
                    Assert.assertTrue("Full adjustment scale must fit", ruler.top >= panel.top && ruler.bottom <= panel.bottom)
                    compose.onNodeWithTag("rangefinder-dial-ruler").assertIsDisplayed()
                    val value = bounds("revision05-selected-control-value")
                    Assert.assertTrue("Full selected value must fit", value.left >= panel.left && value.right <= panel.right &&
                        value.top >= panel.top && value.bottom <= panel.bottom)
                }
                checkNativeFraming(image)
            }
            pointerTap("rangefinder-pro")
            assertThat(bounds("camera-preview")).isEqualTo(image)
            pointerTap("revision05-auto-wb")
            val wb = bounds("revision05-replacement-panel")
            Assert.assertFalse("Auto WB must not cover the photograph", image.overlaps(wb))
            screenshot("pro-review-$name-auto-wb")
            assertThat(bounds("camera-preview")).isEqualTo(image)
            assertThat(bounds("camera-metering-region")).isEqualTo(image)
            assertThat(bounds("rangefinder-view-reticle")).isEqualTo(image)
            assertThat(bounds("rangefinder-shutter")).isEqualTo(shutter)
            checkNativeFraming(image)
            pointerTap("revision05-auto-wb")
        }
    }

    @Test fun realScreenRegionResourcesAndWhiteBalancePanelHaveReadableLabels() {
        openPro()
        pointerTap("mechanical-tab-color")
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val original = manager.applicationLocales
        try {
            for ((locale, spelling) in listOf("en-GB" to "COLOUR", "en-US" to "COLOR")) {
                manager.applicationLocales = android.os.LocaleList.forLanguageTags(locale)
                compose.waitUntil(15_000) {
                    var correct = false
                    scenario.onActivity { correct = it.resources.getString(dev.multiframe.camera.R.string.rangefinder_color_tab) == spelling }
                    correct && compose.onAllNodesWithTag("mechanical-tab-color").fetchSemanticsNodes().isNotEmpty()
                }
                val tab = compose.onNodeWithTag("mechanical-tab-color").assertIsSelected().assertIsDisplayed()
                val panel = bounds("revision05-replacement-panel")
                assertThat(tab.fetchSemanticsNode().boundsInRoot.left).isAtLeast(panel.left)
                assertThat(tab.fetchSemanticsNode().boundsInRoot.right).isAtMost(panel.right)
                pointerTap("revision05-wb-mode")
                val buttons = listOf("auto", "lock", "manual").map {
                    compose.onNodeWithTag("revision05-wb-$it").performScrollTo().fetchSemanticsNode().touchBoundsInRoot
                }
                for (index in 0..1) assertThat(buttons[index].right).isAtMost(buttons[index+1].left)
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("revision05-wb-value").performScrollTo().performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertThat(layouts).isNotEmpty()
                for (layout in layouts) {
                    assertThat(layout.didOverflowHeight).isFalse()
                    for (line in 0 until layout.lineCount) {
                        assertThat(layout.isLineEllipsized(line)).isFalse()
                        assertThat(kotlin.math.ceil(layout.getLineRight(line)).toInt()).isAtMost(layout.size.width)
                    }
                }
                pointerTap("revision05-wb-mode")
                report("$locale resource=$spelling; active White balance panel fits with separate button hit regions")
                screenshot("locale-$locale")
            }
        } finally { manager.applicationLocales = original }
    }

    @Test fun captureHasOneTiltReadoutAndNoDuplicateZebraControl() {
        val tiltCount = compose.onAllNodesWithTag("rangefinder-tilt-readout").fetchSemanticsNodes().size
        report("Capture-screen tilt readouts=$tiltCount")
        assertThat(tiltCount).isEqualTo(1)
        val tags = compose.onAllNodes(hasContentDescription("Zebra", substring = true)).fetchSemanticsNodes()
        report("Capture-screen zebra controls=${tags.size}")
        // The Titanium design puts its one zebra toggle in Labs. The paired
        // Labs test verifies that control; there must not be a duplicate here.
        assertThat(tags).isEmpty()
    }

    @Test fun labsHasExactlyOneZebraToggle() {
        pointerTap("titanium-labs-button")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Zebra highlights").fetchSemanticsNodes().size == 1 }
        val row = compose.onNode(hasContentDescription("Zebra highlights", substring = true))
        row.performScrollTo().assertIsSelected().performTouchInput { click() }
        row.assertIsNotSelected()
        assertThat(compose.onAllNodesWithText("Zebra highlights").fetchSemanticsNodes()).hasSize(1)
        screenshot("labs-zebra")
    }

}
