package dev.multiframe.camera.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.PixelCopy
import android.view.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/** Real external texture + fragment shader + SurfaceView readback on the Pixel GPU. */
class PeakingPreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movingSharpEdgesStayAttachedAndBlurredEdgesAreRejected() {
        assumeTrue("Pixel 11 Pro only", Build.MODEL == "Pixel 11 Pro")
        lateinit var renderer: PeakingPreviewView
        var input: Surface? = null
        compose.setContent {
            val side = with(LocalDensity.current) { 256.toDp() }
            AndroidView(factory = { context ->
                PeakingPreviewView(context, Size(256, 256)) { input = it }.also {
                    renderer = it
                    it.configure(false, true)
                }
            }, modifier = Modifier.size(side), onRelease = { it.releaseCameraSurface() })
        }
        compose.waitUntil(10_000) { input != null }
        val positions = listOf(40, 160, 75, 200, 110, 35, 180, 90)
        for (edge in positions) {
            val pixels = IntArray(256 * 256) { i ->
                val x = i % 256
                // Top: a sharp boundary. Bottom: equally strong contrast,
                // spread over a broad defocused boundary (sigma-like width 5).
                val value = if (i / 256 < 128) {
                    if (x < edge) 40 else 210
                } else (40 + 170 / (1 + exp(-(x - edge).toDouble() / 5))).toInt()
                Color.rgb(value, value, value)
            }
            val source = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
            val before = renderer.renderedTimestamp
            val surface = input!!
            val canvas = surface.lockCanvas(null)
            canvas.drawBitmap(source, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
            compose.waitUntil(5_000) { renderer.renderedTimestamp != before }
            // Fence behind the frame draw before asking SurfaceFlinger to copy.
            val done = CountDownLatch(1)
            renderer.queueEvent { android.opengl.GLES20.glFinish(); done.countDown() }
            check(done.await(5, TimeUnit.SECONDS))
            val result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var copyStatus = -1
            PixelCopy.request(renderer, result, { copyStatus = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            check(copied.await(5, TimeUnit.SECONDS))
            assertThat(copyStatus).isEqualTo(PixelCopy.SUCCESS)
            val marked = mutableListOf<Int>()
            var blurredMarks = 0
            var maxTint = 0
            for (y in 10 until 246) for (x in 8 until 248) {
                val p = result.getPixel(x, y)
                val tint = Color.green(p) - Color.red(p)
                if (tint > 12 && Color.blue(p) - Color.red(p) > 12) {
                    // SurfaceTexture's producer transform determines vertical
                    // orientation; identify the sharp half by its count below.
                    if (y < 110) marked.add(x)
                    if (y > 146) blurredMarks++
                    maxTint = maxOf(maxTint, tint)
                }
            }
            assertThat(marked.size).isGreaterThan(50)
            assertThat(marked.all { abs(it - edge) <= 2 }).isTrue()
            assertThat(blurredMarks).isEqualTo(0)
            // The Titanium cyan is #00D2FF; 28% of its G-R difference is
            // 58.8 code values. Derive the bound from the active palette.
            val maxGreenTint = kotlin.math.ceil(255f * .28f *
                (TitaniumTokens.SignalCyan.green - TitaniumTokens.SignalCyan.red)).toInt()
            assertThat(maxTint).isAtMost(maxGreenTint)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "edge=$edge sharpMarks=${marked.size} blurredMarks=$blurredMarks maxTint=$maxTint timestamp=${renderer.renderedTimestamp}\n")
            })
            source.recycle()
            result.recycle()
        }
    }
}
