package dev.multiframe.camera.ui

import android.Manifest
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.CameraScreen
import dev.multiframe.camera.pipeline.AppSettings
import dev.multiframe.camera.pipeline.CaptureMode
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RangefinderConsoleDeviceTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val compose = createComposeRule()

    @Test fun consoleAndPreviewHaveIdenticalBoundsInBothProStates() {
        compose.setContent { MultiframeTheme { CameraScreen(Modifier.fillMaxSize()) } }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val consoleClosed = compose.onNodeWithTag("camera-control-band").fetchSemanticsNode().boundsInRoot
        val previewClosed = compose.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("rangefinder-pro").performClick()
        compose.waitForIdle()
        val consoleOpen = compose.onNodeWithTag("camera-control-band").fetchSemanticsNode().boundsInRoot
        val previewOpen = compose.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot
        assertThat(consoleOpen.height).isEqualTo(consoleClosed.height)
        assertThat(consoleOpen).isEqualTo(consoleClosed)
        assertThat(previewOpen).isEqualTo(previewClosed)
    }

    @Test fun focusTabEnablesTheLivePreviewShaderAndManualFocus() {
        assumeTrue("Pixel 11 Pro manual-focus check", Build.MODEL == "Pixel 11 Pro")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = AppSettings.load(context)
        val mounted = mutableStateOf(true)
        lateinit var root: View
        try {
            compose.setContent {
                root = LocalView.current.rootView
                MultiframeTheme { if (mounted.value) CameraScreen(Modifier.fillMaxSize()) }
            }
            fun renderer(view: View): PeakingPreviewView? = when (view) {
                is PeakingPreviewView -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { renderer(view.getChildAt(it)) }
                else -> null
            }
            var gpu: PeakingPreviewView? = null
            compose.waitUntil(30_000) {
                compose.runOnUiThread { gpu = renderer(root) }
                gpu != null && compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()
            }
            assertThat(gpu!!.peakingEnabled).isFalse()
            compose.onNodeWithTag("rangefinder-pro").performClick()
            compose.onNodeWithTag("mechanical-tab-focus").performScrollTo().performClick()
            compose.onNodeWithTag("revision05-focus-manual").performScrollTo().performClick()
            compose.waitUntil(5_000) { gpu!!.peakingEnabled }
            compose.onNodeWithTag("revision05-replacement-panel").assertExists()
            // Aim at a textured object around 1 m. Entering FOCUS should
            // hold the AF distance; nudge the ruler and return to that stop.
            compose.onNodeWithContentDescription("Previous dial detent").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Next dial detent").performScrollTo().performClick()
            fun readPreview(): Bitmap {
                val view = gpu!!
                val before = view.renderedFrames
                compose.waitUntil(5_000) { view.renderedFrames >= before + 5 }
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val done = CountDownLatch(1)
                var copyStatus = -1
                PixelCopy.request(view, image, { copyStatus = it; done.countDown() }, Handler(Looper.getMainLooper()))
                check(done.await(5, TimeUnit.SECONDS))
                assertThat(copyStatus).isEqualTo(PixelCopy.SUCCESS)
                return image
            }
            fun cyanPixels(image: Bitmap): Int {
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                var count = 0
                for (pixel in pixels) {
                    if (Color.green(pixel) - Color.red(pixel) > 12 &&
                        Color.blue(pixel) - Color.red(pixel) > 12) count++
                }
                return count
            }
            val withPeaking = readPreview()
            compose.onNodeWithTag("mechanical-tab-exp").performClick()
            compose.waitUntil(5_000) { !gpu!!.peakingEnabled }
            val withoutPeaking = readPreview()
            compose.onNodeWithTag("mechanical-tab-focus").performClick()
            compose.waitUntil(5_000) { gpu!!.peakingEnabled }
            val activeCount = cyanPixels(withPeaking)
            val inactiveCount = cyanPixels(withoutPeaking)
            File(context.cacheDir, "manual-focus-peaking-on.png").outputStream().use {
                withPeaking.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(context.cacheDir, "manual-focus-peaking-off.png").outputStream().use {
                withoutPeaking.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "live manual focus: cyan on=$activeCount off=$inactiveCount\n")
            })
            withPeaking.recycle()
            withoutPeaking.recycle()
            assertThat(activeCount).isGreaterThan(inactiveCount + 25)
            compose.onNodeWithTag("rangefinder-pro").performClick()
            compose.waitUntil(5_000) { !gpu!!.peakingEnabled }
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            AppSettings.save(context, saved)
        }
    }

    @Test fun captureScreenCanFlipToSelfieAndBackWithoutMovingConsole() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = AppSettings.load(context)
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val rear = manager.cameraIdList.first { manager.getCameraCharacteristics(it)
            .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK }
        // The previous screen may legitimately have persisted Selfie. This test
        // exercises rear -> front -> rear, then restores that stored selection.
        AppSettings.save(context, saved.copy(lensId = rear, captureMode = CaptureMode.PHOTO))
        val mounted = mutableStateOf(true)
        try {
            compose.setContent { if (mounted.value) MultiframeTheme { CameraScreen(Modifier.fillMaxSize()) } }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("Switch camera: Selfie").fetchSemanticsNodes().isNotEmpty()
            }
            val console = compose.onNodeWithTag("camera-control-band").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Switch camera: Selfie").assertIsDisplayed().performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("Switch camera: Rear").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Switch camera: Rear").assertIsDisplayed().performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("Switch camera: Selfie").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("rangefinder-pro").performClick()
                compose.onNodeWithContentDescription("Switch camera: Selfie").assertIsDisplayed().performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithContentDescription("Switch camera: Rear").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Switch camera: Rear").assertIsDisplayed().performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithContentDescription("Switch camera: Selfie").fetchSemanticsNodes().isNotEmpty()
                }
            }
            assertThat(compose.onNodeWithTag("camera-control-band").fetchSemanticsNode().boundsInRoot)
                .isEqualTo(console)
        } finally {
            mounted.value = false
            compose.waitForIdle()
            AppSettings.save(context, saved)
        }
    }

    @Test fun cameraXFallbackAlsoRendersThroughTheFrameLatchedShader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ProcessCameraProvider.getInstance(context).get()
        val request = mutableStateOf<SurfaceRequest?>(null)
        lateinit var lifecycleOwner: LifecycleOwner
        lateinit var root: View
        try {
            compose.setContent {
                root = LocalView.current.rootView
                lifecycleOwner = LocalLifecycleOwner.current
                request.value?.let {
                    CameraXPeakingPreview(it, 4f / 3f, 0, true, false,
                        focusInset = FocusAssistInset(.62f, .05f, .32f, .32f, .5f, .5f),
                        modifier = Modifier.fillMaxSize())
                }
            }
            compose.runOnUiThread {
                provider.unbindAll()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request.value = it }
                }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }
            fun renderer(view: View): PeakingPreviewView? = when (view) {
                is PeakingPreviewView -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { renderer(view.getChildAt(it)) }
                else -> null
            }
            compose.waitUntil(30_000) {
                var gpu: PeakingPreviewView? = null
                compose.runOnUiThread { gpu = renderer(root) }
                (gpu?.renderedFrames ?: 0L) > 20L && gpu?.peakingEnabled == true &&
                    gpu?.focusInsetEnabled == true
            }
        } finally {
            compose.runOnUiThread { provider.unbindAll() }
        }
    }

    @Test fun photoStartsWithZebraAndLabsControlsThePreviewShader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = AppSettings.load(context)
        val mounted = mutableStateOf(true)
        lateinit var root: View
        AppSettings.save(context, saved.copy(captureMode = CaptureMode.PHOTO))
        try {
            compose.setContent {
                root = LocalView.current.rootView
                MultiframeTheme { if (mounted.value) CameraScreen(Modifier.fillMaxSize()) }
            }
            fun renderer(view: View): PeakingPreviewView? = when (view) {
                is PeakingPreviewView -> view
                is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { renderer(view.getChildAt(it)) }
                else -> null
            }
            var gpu: PeakingPreviewView? = null
            compose.waitUntil(30_000) {
                compose.runOnUiThread { gpu = renderer(root) }
                gpu?.zebraEnabled == true
            }
            compose.onNodeWithTag("titanium-labs-button").performClick()
            compose.onNodeWithContentDescription("Zebra highlights ON").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Close settings").performClick()
            compose.waitUntil(5_000) { gpu?.zebraEnabled == false }
            compose.onNodeWithTag("titanium-labs-button").performClick()
            compose.onNodeWithContentDescription("Zebra highlights OFF").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Close settings").performClick()
            compose.waitUntil(5_000) { gpu?.zebraEnabled == true }
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            AppSettings.save(context, saved)
        }
    }
}
