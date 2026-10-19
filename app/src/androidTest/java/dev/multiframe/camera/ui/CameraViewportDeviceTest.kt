package dev.multiframe.camera.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.AppSettings
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Measures the production capture screen in whichever orientation the device is set to. */
class CameraViewportDeviceTest {
    @get:Rule val permission = org.junit.rules.TestRule { base, description ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) base
        else GrantPermissionRule.grant(Manifest.permission.CAMERA).apply(base, description)
    }
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun portraitProductionPreviewAndConsoleStayPixelIdenticalAcrossProTabs() =
        measureOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, false)

    @Test fun landscapeProductionPreviewAndConsoleStayPixelIdenticalAcrossProTabs() =
        measureOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, true)

    private fun measureOrientation(orientation: Int, landscape: Boolean) {
        assumeTrue("Pixel devices only", Build.MODEL.contains("Pixel"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        check(keyguard?.isKeyguardLocked != true) { "Unlock the Pixel before viewport acceptance" }
        val saved = AppSettings.load(context)
        AppSettings.save(context, saved.copy(manual = dev.multiframe.camera.pipeline.ManualSettings(),
            lensId = null, captureMode = dev.multiframe.camera.pipeline.CaptureMode.PHOTO,
            fullResolution = false))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.requestedOrientation = orientation
            }
            compose.waitUntil(30_000) {
                var focused = false
                scenario.onActivity { focused = it.hasWindowFocus() }
                focused && compose.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().any {
                    (it.boundsInRoot.width > it.boundsInRoot.height) == landscape
                } &&
                    compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("camera-raw-container").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val closed = anchors()
            assertThat(closed.preview.width).isWithin(1.5f)
                .of(closed.preview.height * if (landscape) 4f / 3f else 3f / 4f)
            check("Closed", closed)
            checkNativeSurface(scenario, closed)
            // Probe a real unused plate point, excluding every expanded touch target.
            // This preserves the old header's unhandled-tap regression check without
            // inventing a header that R05 deliberately removed.
            val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.touchBoundsInRoot }
            val plate = closed.console
            val point = (1..19).flatMap { row -> (1..19).map { column ->
                Offset(plate.left + plate.width * column / 20f, plate.top + plate.height * row / 20f)
            } }.firstOrNull { candidate -> targets.none { it.contains(candidate) } }
                ?: error("No unused console point available for the unhandled-tap gate")
            compose.onRoot().performTouchInput { click(point) }
            compose.waitForIdle()
            assertThat(compose.onAllNodesWithTag("rangefinder-tap-focus-reticle").fetchSemanticsNodes()).isEmpty()
            // Use real pointer taps: semantics performClick bypasses the root
            // gesture recognizer and missed the reported PHOTO/NIGHT failure.
            for (mode in listOf("night", "photo")) {
                val stream = CaptureUiInspection.stream
                val beforeTap = stream?.latestTapTriggerResult()?.frameNumber
                val beforeFrame = stream?.latestResult()?.frameNumber
                compose.onNodeWithTag("rangefinder-mode-$mode").performTouchInput { click() }
                compose.onNodeWithTag("rangefinder-mode-$mode").assertIsSelected()
                if (stream != null && beforeFrame != null) compose.waitUntil(5_000) {
                    stream.latestResult()?.frameNumber?.let { it >= beforeFrame + 6 } == true
                }
                assertThat(compose.onAllNodesWithTag("rangefinder-tap-focus-reticle")
                    .fetchSemanticsNodes()).isEmpty()
                assertThat(stream?.latestTapTriggerResult()?.frameNumber).isEqualTo(beforeTap)
            }

            compose.onNodeWithTag("rangefinder-pro").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("revision05-replacement-panel").fetchSemanticsNodes().isNotEmpty()
            }
            check("EXP", closed)
            checkNativeSurface(scenario, closed)
            for (tab in listOf("focus", "frames", "color")) {
                compose.onNodeWithTag("mechanical-tab-$tab").performClick()
                check(tab.uppercase(), closed)
                checkNativeSurface(scenario, closed)
                if (tab == "focus") {
                    compose.onNodeWithTag("revision05-focus-manual").performScrollTo().performClick()
                    compose.onNodeWithTag("rangefinder-focus-assist-toggle").performClick()
                    compose.waitForIdle()
                    val loupe = compose.onNodeWithTag("rangefinder-focus-inset").fetchSemanticsNode().boundsInRoot
                    assertThat(loupe.left).isAtLeast(closed.preview.left)
                    assertThat(loupe.top).isAtLeast(closed.preview.top)
                    assertThat(loupe.right).isAtMost(closed.preview.right)
                    assertThat(loupe.bottom).isAtMost(closed.preview.bottom)
                    check("FOCUS loupe", closed)
                    compose.onNodeWithTag("rangefinder-focus-assist-toggle").performClick()
                }
            }
            compose.onNodeWithTag("rangefinder-pro").performClick()
            check("Closed again", closed)
            checkNativeSurface(scenario, closed)
        } finally {
            scenario.close()
            AppSettings.save(context, saved)
        }
    }

    private data class Anchors(val preview: Rect, val console: Rect, val shutter: Rect, val pro: Rect)

    private fun findPreview(view: android.view.View): PeakingPreviewView? {
        if (view is PeakingPreviewView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
            findPreview(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun checkNativeSurface(scenario: ActivityScenario<MainActivity>, anchors: Anchors) {
        compose.waitUntil(30_000) {
            var ready = false
            scenario.onActivity { activity ->
                val preview = findPreview(activity.window.decorView)
                ready = preview != null && preview.holder.surfaceFrame.width() > 0 &&
                    preview.holder.surfaceFrame.height() > 0 && preview.renderedFrames > 0
            }
            ready
        }
        scenario.onActivity { activity ->
            val preview = findPreview(activity.window.decorView)
                ?: throw AssertionError("Native GL preview missing")
            val native = android.graphics.Rect()
            assertThat(preview.getGlobalVisibleRect(native)).isTrue()
            val surfaceFrame = preview.holder.surfaceFrame
            val result = CaptureUiInspection.stream?.latestResult()
            val geometry = "GL view=${preview.width}x${preview.height} visible=$native " +
                "holder=$surfaceFrame requestCrop=${result?.request?.get(CaptureRequest.SCALER_CROP_REGION)} " +
                "requestZoom=${result?.request?.get(CaptureRequest.CONTROL_ZOOM_RATIO)} " +
                "resultCrop=${result?.get(CaptureResult.SCALER_CROP_REGION)} " +
                "rawCrop=${if (Build.VERSION.SDK_INT >= 34) result?.get(CaptureResult.SCALER_RAW_CROP_REGION) else null}"
            val matrix = PeakingPreviewView::class.java.getDeclaredField("textureMatrix").apply {
                isAccessible = true
            }.get(preview) as FloatArray
            val cropX = PeakingPreviewView::class.java.getDeclaredField("cropX").apply {
                isAccessible = true
            }.getFloat(preview)
            val cropY = PeakingPreviewView::class.java.getDeclaredField("cropY").apply {
                isAccessible = true
            }.getFloat(preview)
            val bufferCrop = PeakingPreviewView::class.java.getDeclaredField("bufferCrop").apply {
                isAccessible = true
            }.get(preview) as FloatArray
            val sourceXSpan = kotlin.math.hypot(matrix[0], matrix[1])
            val sourceYSpan = kotlin.math.hypot(matrix[4], matrix[5])
            val textureGeometry = "texture span=$sourceXSpan,$sourceYSpan " +
                "shaderCrop=$cropX,$cropY bufferCrop=${bufferCrop.joinToString()} " +
                "matrix=${matrix.joinToString()}"
            Log.i("TitaniumGeometry", geometry)
            Log.i("TitaniumGeometry", textureGeometry)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "$geometry\n$textureGeometry\n")
            })
            assertThat(cropX).isAtLeast(.98f)
            assertThat(cropY).isAtLeast(.98f)
            assertThat(sourceXSpan).isAtLeast(.98f)
            assertThat(sourceYSpan).isAtLeast(.98f)
            assertThat(surfaceFrame.width()).isWithin(1).of(preview.width)
            assertThat(surfaceFrame.height()).isWithin(1).of(preview.height)
            val composeRoot = activity.window.decorView.findViewById<android.view.View>(android.R.id.content)
            val rootOrigin = IntArray(2)
            composeRoot.getLocationOnScreen(rootOrigin)
            val nativeInRoot = Rect(
                (native.left - rootOrigin[0]).toFloat(),
                (native.top - rootOrigin[1]).toFloat(),
                (native.right - rootOrigin[0]).toFloat(),
                (native.bottom - rootOrigin[1]).toFloat(),
            )
            assertThat(nativeInRoot.left).isWithin(1.5f).of(anchors.preview.left)
            assertThat(nativeInRoot.top).isWithin(1.5f).of(anchors.preview.top)
            assertThat(nativeInRoot.right).isWithin(1.5f).of(anchors.preview.right)
            assertThat(nativeInRoot.bottom).isWithin(1.5f).of(anchors.preview.bottom)
            assertThat(nativeInRoot.overlaps(anchors.console)).isFalse()
        }
    }

    private fun anchors() = Anchors(
        compose.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot,
        compose.onNodeWithTag("camera-control-band").fetchSemanticsNode().boundsInRoot,
        compose.onNodeWithTag("rangefinder-shutter").fetchSemanticsNode().boundsInRoot,
        compose.onNodeWithTag("rangefinder-pro").fetchSemanticsNode().boundsInRoot,
    )

    private fun check(state: String, baseline: Anchors) {
        compose.waitForIdle()
        val actual = anchors()
        report(state, actual)
        assertThat(actual.preview).isEqualTo(baseline.preview)
        assertThat(actual.console).isEqualTo(baseline.console)
        assertThat(actual.shutter).isEqualTo(baseline.shutter)
        assertThat(actual.pro).isEqualTo(baseline.pro)
        if (state.startsWith("Closed")) {
            // Root bounds alone cannot detect a Text node measured into too
            // little remaining height in the fixed landscape console.
            for (node in listOf(compose.onNodeWithTag("rangefinder-tilt-readout"),
                if (compose.onAllNodesWithTag("revision05-compact-summary").fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithTag("revision05-compact-summary") else compose.onNodeWithTag("revision05-capture-summary"))) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                    it(layouts)
                }
                assertThat(layouts).isNotEmpty()
                layouts.forEach { text ->
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "Footer ${text.layoutInput.text}: size=${text.size} " +
                            "paragraph=${text.multiParagraph.width}x${text.multiParagraph.height} " +
                            "overflowWidth=${text.didOverflowWidth} overflowHeight=${text.didOverflowHeight} " +
                            "constraints=${text.layoutInput.constraints}\n")
                    })
                }
                // Paragraph width can retain the incoming maximum width while
                // Text shrinks to its intrinsic line width. Check actual lines,
                // not that unused paragraph area, for horizontal clipping.
                layouts.forEach { text ->
                    assertThat(text.didOverflowHeight).isFalse()
                    for (line in 0 until text.lineCount) {
                        assertThat(kotlin.math.ceil(text.getLineRight(line)).toInt()).isAtMost(text.size.width)
                        assertThat(text.getLineLeft(line)).isAtLeast(0f)
                        assertThat(text.getLineBottom(line)).isAtMost(text.size.height.toFloat())
                    }
                }
            }
        }
        val telemetry = compose.onNodeWithTag("revision05-top-deck").fetchSemanticsNode().boundsInRoot
        val turret = compose.onNodeWithTag("rangefinder-turret").fetchSemanticsNode().boundsInRoot
        assertThat(actual.preview.overlaps(actual.console)).isFalse()
        if (actual.preview.width > actual.preview.height) {
            // R05 puts Settings/RGB at the safe image corner in landscape;
            // this overlay must remain separate from the docked focal rail.
            // Source crop and the full image/touch/aid bounds are still exact.
            assertThat(telemetry.overlaps(turret)).isFalse()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertThat(telemetry.left).isAtLeast(root.left)
            assertThat(telemetry.top).isAtLeast(root.top)
            assertThat(telemetry.right).isAtMost(root.right)
            assertThat(telemetry.bottom).isAtMost(root.bottom)
        } else {
            assertThat(actual.preview.overlaps(telemetry)).isFalse()
            assertThat(telemetry.bottom).isAtMost(actual.preview.top)
            assertThat(actual.preview.bottom).isAtMost(turret.top)
        }
    }

    private fun report(state: String, anchors: Anchors) {
        val measurement = "${if (anchors.preview.width > anchors.preview.height) "Landscape" else "Portrait"} " +
            "$state preview=${anchors.preview} console=${anchors.console} shutter=${anchors.shutter} pro=${anchors.pro}"
        Log.i("TitaniumGeometry", measurement)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "$measurement\n")
        })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = if (anchors.preview.width > anchors.preview.height) "landscape" else "portrait"
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(instrumentation.targetContext.cacheDir,
                "titanium-$prefix-${state.lowercase().replace(' ', '-')}.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }
}
