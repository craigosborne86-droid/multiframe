package dev.multiframe.camera.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.PixelCopy
import android.view.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Synthetic frames exercise the actual external-texture shader without a camera. */
class RangefinderOverlayEmulatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rawPreviewCompensatesForLandscapeDisplayRotation() {
        var displayDegrees by mutableIntStateOf(0)
        var input: Surface? = null
        var renderer: PeakingPreviewView? = null
        compose.setContent {
            key(displayDegrees) {
                AndroidView(factory = { context ->
                    PeakingPreviewView(context, Size(256, 256), 1f, displayDegrees) { input = it }
                        .also { renderer = it }
                }, modifier = Modifier.size(256.dp), onRelease = { it.releaseCameraSurface() })
            }
        }
        val frame = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) {
            frame.setPixel(x, y, when {
                x < 128 && y < 128 -> Color.RED
                x >= 128 && y < 128 -> Color.GREEN
                x < 128 -> Color.BLUE
                else -> Color.YELLOW
            })
        }
        fun rendered(): Bitmap {
            compose.waitUntil(10_000) { input != null && renderer != null }
            val view = renderer!!
            val before = view.renderedTimestamp
            val canvas = input!!.lockCanvas(null)
            canvas.drawBitmap(frame, 0f, 0f, null)
            input!!.unlockCanvasAndPost(canvas)
            compose.waitUntil(5_000) { view.renderedTimestamp != before }
            return copy(view)
        }
        val portrait = rendered()
        val previous = renderer
        compose.runOnIdle { input = null; displayDegrees = 90 }
        compose.waitUntil(10_000) { renderer !== previous && input != null }
        val landscape = rendered()
        fun sample(bitmap: Bitmap, x: Int, y: Int) = bitmap.getPixel(x, y)
        fun sameColour(actual: Int, expected: Int) {
            assertThat(Color.red(actual)).isWithin(8).of(Color.red(expected))
            assertThat(Color.green(actual)).isWithin(8).of(Color.green(expected))
            assertThat(Color.blue(actual)).isWithin(8).of(Color.blue(expected))
        }
        // Camera2's texture is natural-orientation; the display's 90° turn
        // requires the visible image to rotate 90° the other way.
        sameColour(sample(landscape, 64, 64), sample(portrait, 192, 64))
        sameColour(sample(landscape, 192, 64), sample(portrait, 192, 192))
        sameColour(sample(landscape, 64, 192), sample(portrait, 64, 64))
        sameColour(sample(landscape, 192, 192), sample(portrait, 64, 192))
        portrait.recycle()
        landscape.recycle()
        frame.recycle()
    }

    @Test fun zebraAndPeakingAreDrawnOnlyUnderTheirTriggers() {
        var pro by mutableStateOf(false)
        var tab by mutableStateOf(RangefinderTab.SHUTTER)
        var zebra by mutableStateOf(false)
        lateinit var renderer: PeakingPreviewView
        var input: Surface? = null
        compose.setContent {
            val side = with(LocalDensity.current) { 256.toDp() }
            Column {
                Text("LABS", Modifier.clickable { zebra = !zebra }.testTag("labs"))
                Text("PRO", Modifier.clickable { pro = !pro }.testTag("pro"))
                Text("FOCUS", Modifier.clickable { tab = RangefinderTab.FOCUS }.testTag("focus"))
                AndroidView(factory = { context ->
                    PeakingPreviewView(context, Size(256, 256)) { input = it }.also { renderer = it }
                }, update = { it.configure(false, RangefinderOverlayPolicy.peaking(pro, tab), zebra) },
                    modifier = Modifier.size(side), onRelease = { it.releaseCameraSurface() })
            }
        }
        compose.waitUntil(10_000) { input != null }
        val frame = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) {
            frame.setPixel(x, y, if (y < 128) {
                val v = if (x < 128) 40 else 210
                Color.rgb(v, v, v)
            } else Color.rgb(255, 150, 150))
        }
        val source = input!!
        val before = renderer.renderedFrames
        val canvas = source.lockCanvas(null)
        canvas.drawBitmap(frame, 0f, 0f, null)
        source.unlockCanvasAndPost(canvas)
        compose.waitUntil(5_000) { renderer.renderedFrames > before }
        val baseline = copy(renderer)
        assertThat(cyanPixels(baseline)).isEqualTo(0)

        fun toggle(tag: String, expectRender: Boolean = true): Bitmap {
            val previous = renderer.renderedFrames
            compose.onNodeWithTag(tag).performClick()
            if (expectRender) compose.waitUntil(5_000) { renderer.renderedFrames > previous }
            else compose.waitForIdle()
            return copy(renderer)
        }
        val proOnly = toggle("pro", expectRender = false)
        assertThat(cyanPixels(proOnly)).isEqualTo(0)
        val focusOpen = toggle("focus")
        assertThat(cyanPixels(focusOpen)).isGreaterThan(40)
        val focusClosed = toggle("pro")
        assertThat(cyanPixels(focusClosed)).isEqualTo(0)

        val zebraOn = toggle("labs")
        assertThat(brightenedClippedPixels(baseline, zebraOn)).isGreaterThan(100)
        val zebraOff = toggle("labs")
        assertThat(brightenedClippedPixels(baseline, zebraOff)).isEqualTo(0)
        frame.recycle()
        listOf(baseline, proOnly, focusOpen, focusClosed, zebraOn, zebraOff).forEach(Bitmap::recycle)
    }

    @Test fun magnifiedInsetUsesTheSameLatchedFrameAndPeakingShader() {
        var input: Surface? = null
        lateinit var renderer: PeakingPreviewView
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
        val frame = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) {
            val value = if (x < 128) 40 else 210
            frame.setPixel(x, y, Color.rgb(value, value, value))
        }
        val before = renderer.renderedFrames
        val canvas = input!!.lockCanvas(null)
        canvas.drawBitmap(frame, 0f, 0f, null)
        input!!.unlockCanvasAndPost(canvas)
        compose.waitUntil(5_000) { renderer.renderedFrames > before }
        val frameTimestamp = renderer.renderedTimestamp
        val mainOnly = copy(renderer)
        val inset = FocusAssistInset(.62f, .05f, .32f, .32f, .5f, .5f)
        val firstDraw = renderer.renderedFrames
        renderer.configureFocusInset(inset)
        compose.waitUntil(5_000) { renderer.renderedFrames > firstDraw }
        val focused = copy(renderer)
        assertThat(renderer.renderedTimestamp).isEqualTo(frameTimestamp)
        assertThat(cyanPixelsIn(mainOnly, 160, 15, 238, 90)).isEqualTo(0)
        assertThat(cyanPixelsIn(focused, 160, 15, 238, 90)).isGreaterThan(20)

        val secondDraw = renderer.renderedFrames
        renderer.configureFocusInset(inset.copy(focusX = .05f))
        compose.waitUntil(5_000) { renderer.renderedFrames > secondDraw }
        val shifted = copy(renderer)
        assertThat(renderer.renderedTimestamp).isEqualTo(frameTimestamp)
        assertThat(cyanPixelsIn(shifted, 160, 15, 238, 90)).isEqualTo(0)

        renderer.configureFocusInset(inset)
        val blurredFrame = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) {
            val value = (40 + 170 / (1 + exp(-(x - 128).toDouble() / 5))).toInt()
            blurredFrame.setPixel(x, y, Color.rgb(value, value, value))
        }
        val beforeBlur = renderer.renderedTimestamp
        val blurCanvas = input!!.lockCanvas(null)
        blurCanvas.drawBitmap(blurredFrame, 0f, 0f, null)
        input!!.unlockCanvasAndPost(blurCanvas)
        compose.waitUntil(5_000) { renderer.renderedTimestamp != beforeBlur }
        val blurred = copy(renderer)
        assertThat(cyanPixelsIn(blurred, 160, 15, 238, 90)).isEqualTo(0)

        val thirdDraw = renderer.renderedFrames
        renderer.configure(false, false)
        compose.waitUntil(5_000) { renderer.renderedFrames > thirdDraw }
        val disabled = copy(renderer)
        assertThat(cyanPixels(disabled)).isEqualTo(0)
        listOf(frame, mainOnly, focused, shifted, blurredFrame, blurred, disabled).forEach(Bitmap::recycle)
    }

    @Test fun movingSharpBlurredAndFlatFramesLeaveNoStalePeakingInMainOrLoupe() {
        var input: Surface? = null
        lateinit var renderer: PeakingPreviewView
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONArray()
        // Reuse the established sharp/defocused fixtures and edge positions from the Pixel test.
        // This checks the rendering path, not real camera latency or focus-plane selectivity.
        for (edge in listOf(40, 128, 200, 75)) {
            renderer.configureFocusInset(FocusAssistInset(.62f, .05f, .32f, .32f,
                edge / 256f, .5f))
            for (kind in listOf("sharp", "blurred", "flat")) {
                val pixels = IntArray(256 * 256) { index ->
                    val x = index % 256
                    val value = when (kind) {
                        "sharp" -> if (x < edge) 40 else 210
                        "blurred" -> (40 + 170 / (1 + exp(-(x - edge).toDouble() / 5))).toInt()
                        else -> 100
                    }
                    Color.rgb(value, value, value)
                }
                val source = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
                val before = renderer.renderedTimestamp
                val canvas = input!!.lockCanvas(null)
                canvas.drawBitmap(source, 0f, 0f, null)
                input!!.unlockCanvasAndPost(canvas)
                compose.waitUntil(5_000) { renderer.renderedTimestamp != before }
                val image = copy(renderer)
                try {
                    val mainMarks = mutableListOf<Int>()
                    // The loupe occupies the upper right; inspect the main image below it.
                    for (y in 180 until 230) for (x in 8 until 248) {
                        val colour = image.getPixel(x, y)
                        if (Color.green(colour) - Color.red(colour) > 12 &&
                            Color.blue(colour) - Color.red(colour) > 12) mainMarks.add(x)
                    }
                    val loupeMarks = cyanPixelsIn(image, 160, 15, 238, 90)
                    val name = "titanium-peaking-transition-$edge-$kind"
                    File(context.cacheDir, "$name.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    report.put(JSONObject().put("edge", edge).put("kind", kind)
                        .put("previousTimestamp", before).put("timestamp", renderer.renderedTimestamp)
                        .put("mainMarks", mainMarks.size).put("loupeMarks", loupeMarks))
                    File(context.cacheDir, "titanium-peaking-transitions.json")
                        .writeText(report.toString(2))
                    if (kind == "sharp") {
                        assertThat(mainMarks).isNotEmpty()
                        assertThat(mainMarks.all { abs(it - edge) <= 2 }).isTrue()
                        assertThat(loupeMarks).isGreaterThan(20)
                    } else {
                        assertThat(mainMarks).isEmpty()
                        assertThat(loupeMarks).isEqualTo(0)
                    }
                } finally {
                    source.recycle()
                    image.recycle()
                }
            }
        }
    }

    @Test fun offCentreLoupeMatchesDisplayedTargetAcrossRotationAndMirroring() {
        var input: Surface? = null
        lateinit var renderer: PeakingPreviewView
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
        val frame = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val quadrants = listOf(40, 90, 160, 210)
        for (y in 0 until 256) for (x in 0 until 256) {
            val value = quadrants[(y / 128) * 2 + x / 128]
            frame.setPixel(x, y, Color.rgb(value, value, value))
        }
        val canvas = input!!.lockCanvas(null)
        canvas.drawBitmap(frame, 0f, 0f, null)
        input!!.unlockCanvasAndPost(canvas)
        compose.waitUntil(5_000) { renderer.renderedFrames > 0 }
        for (rotation in listOf(0, 90)) for (mirrored in listOf(false, true)) {
            val before = renderer.renderedFrames
            renderer.configureFocusInset(null)
            renderer.configureTransform(rotation, android.graphics.Rect(0, 0, 256, 256), mirrored)
            compose.waitUntil(5_000) { renderer.renderedFrames > before }
            val main = copy(renderer)
            for (x in listOf(.25f, .75f)) for (y in listOf(.25f, .75f)) {
                val first = renderer.renderedFrames
                renderer.configureFocusInset(FocusAssistInset(.62f, .05f, .32f, .32f, x, y))
                compose.waitUntil(5_000) { renderer.renderedFrames > first }
                val inset = copy(renderer)
                val expected = Color.red(main.getPixel((x * 256).toInt(), (y * 256).toInt()))
                val actual = Color.red(inset.getPixel(199, 53))
                assertThat(actual.toFloat()).isWithin(3f).of(expected.toFloat())
                inset.recycle()
            }
            main.recycle()
        }
        // A non-square inset must magnify a square equally on both axes.
        frame.eraseColor(Color.rgb(40, 40, 40))
        for (y in 124 until 132) for (x in 124 until 132) frame.setPixel(x, y, Color.rgb(210, 210, 210))
        renderer.configureTransform(0, android.graphics.Rect(0, 0, 256, 256), false)
        renderer.configureFocusInset(FocusAssistInset(.62f, .05f, .32f, .24f, .5f, .5f))
        val previous = renderer.renderedTimestamp
        val squareCanvas = input!!.lockCanvas(null)
        squareCanvas.drawBitmap(frame, 0f, 0f, null)
        input!!.unlockCanvasAndPost(squareCanvas)
        compose.waitUntil(5_000) { renderer.renderedTimestamp != previous }
        val square = copy(renderer)
        val bright = buildList {
            for (y in 15 until 72) for (x in 160 until 238)
                // The bright square and cyan token share the green channel,
                // so the peaking blend cannot shrink this geometric measure.
                if (Color.green(square.getPixel(x, y)) > 180) add(x to y)
        }
        assertThat(bright).isNotEmpty()
        val width = bright.maxOf { it.first } - bright.minOf { it.first } + 1
        val height = bright.maxOf { it.second } - bright.minOf { it.second } + 1
        assertThat(width.toFloat()).isWithin(2f).of(24f)
        assertThat(height.toFloat()).isWithin(2f).of(24f)
        square.recycle()
        frame.recycle()
    }

    private fun cyanPixelsIn(image: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = image.getPixel(x, y)
            if (Color.green(pixel) - Color.red(pixel) > 12 &&
                Color.blue(pixel) - Color.red(pixel) > 12) count++
        }
        return count
    }

    private fun cyanPixels(image: Bitmap): Int {
        var count = 0
        for (y in 8 until 248) for (x in 8 until 248) {
            val pixel = image.getPixel(x, y)
            if (Color.green(pixel) - Color.red(pixel) > 12 &&
                Color.blue(pixel) - Color.red(pixel) > 12) count++
        }
        return count
    }

    private fun brightenedClippedPixels(base: Bitmap, current: Bitmap): Int {
        var count = 0
        for (y in 8 until 248) for (x in 8 until 248) {
            val original = base.getPixel(x, y)
            if (Color.red(original) > 245 && Color.green(original) in 125..190 &&
                Color.green(current.getPixel(x, y)) > Color.green(original) + 8) count++
        }
        return count
    }

    private fun copy(renderer: PeakingPreviewView): Bitmap {
        val drawn = CountDownLatch(1)
        renderer.queueEvent { android.opengl.GLES20.glFinish(); drawn.countDown() }
        check(drawn.await(5, TimeUnit.SECONDS))
        val result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = -1
        PixelCopy.request(renderer, result, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        check(copied.await(5, TimeUnit.SECONDS))
        assertThat(status).isEqualTo(PixelCopy.SUCCESS)
        return result
    }
}
