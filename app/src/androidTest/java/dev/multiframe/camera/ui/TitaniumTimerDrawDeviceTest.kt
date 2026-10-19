package dev.multiframe.camera.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.AppSettings
import dev.multiframe.camera.pipeline.CaptureLog
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.ManualSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Checks the actual timer glyph, which can be absent from a screenshot while semantics survives. */
class TitaniumTimerDrawDeviceTest {
    @get:Rule val permission = org.junit.rules.TestRule { base, description ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) base
        else GrantPermissionRule.grant(Manifest.permission.CAMERA).apply(base, description)
    }
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val results = JSONArray()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Test fun landscapeTimerGlyphRemainsDrawnAcrossProStates() {
        assumeTrue("Pixel camera acceptance", Build.MODEL.contains("Pixel"))
        requireAwake()
        val capturesBefore = CaptureLog.records(context).map { it.baseName }
        val saved = AppSettings.load(context)
        AppSettings.save(context, saved.copy(
            manual = ManualSettings(), timerSeconds = 0, lensId = null,
            captureMode = CaptureMode.PHOTO, fullResolution = false,
        ))
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                originalOrientation = it.requestedOrientation
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(20_000) {
                requireAwake()
                var ready = false
                scenario.onActivity {
                    ready = it.hasWindowFocus() &&
                        it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                }
                ready && compose.onAllNodesWithTag("rangefinder-timer").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty()
            }

            val closed = inspect("closed", null)
            compose.onNodeWithTag("rangefinder-pro").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("revision05-replacement-panel").fetchSemanticsNodes().isNotEmpty()
            }
            val exp = compose.onNodeWithTag("mechanical-tab-exp")
            if (!exp.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Selected])
                exp.performClick()
            inspect("exp", closed)

            compose.onNodeWithTag("mechanical-tab-focus").performScrollTo().performClick()
            compose.onNodeWithTag("revision05-focus-manual").performScrollTo().performClick()
            compose.onNodeWithTag("mechanical-tab-focus").assertIsSelected()
            inspect("focus", closed)
            compose.onNodeWithTag("rangefinder-focus-assist-toggle").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("rangefinder-focus-inset").fetchSemanticsNodes().isNotEmpty()
            }
            inspect("focus-loupe", closed)
            compose.onNodeWithTag("rangefinder-focus-assist-toggle").performClick()

            compose.onNodeWithTag("mechanical-tab-frames").performClick()
            compose.onNodeWithTag("mechanical-tab-frames").assertIsSelected()
            inspect("frames", closed)
            compose.onNodeWithTag("mechanical-tab-color").performClick()
            compose.onNodeWithTag("mechanical-tab-color").assertIsSelected()
            inspect("color", closed)

            compose.onNodeWithTag("rangefinder-pro").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("revision05-replacement-panel").fetchSemanticsNodes().isEmpty()
            }
            inspect("closed-again", closed)
            check(CaptureLog.records(context).map { it.baseName } == capturesBefore) {
                "Timer draw test must not create or delete a capture"
            }
        } catch (failure: Throwable) {
            Log.e("TitaniumTimerDraw", "Timer draw failed", failure)
            results.put(JSONObject().put("failure", "${failure.javaClass.simpleName}: ${failure.message}"))
            runCatching {
                val image = screenshot()
                try { savePng("failure", image) } finally { image.recycle() }
            }
            throw failure
        } finally {
            saveJson()
            if (::scenario.isInitialized) {
                try {
                    scenario.onActivity { it.requestedOrientation = originalOrientation }
                } finally {
                    scenario.close()
                }
            }
            AppSettings.save(context, saved)
        }
    }

    private data class Glyph(
        val plateBounds: Rect,
        val textBounds: Rect,
        val region: AndroidRect,
        val brightPixels: Int,
        val mask: BooleanArray,
    )

    private fun inspect(state: String, baseline: Glyph?): Glyph {
        requireAwake()
        compose.waitForIdle()
        repeat(2) { awaitTwoFrames() }
        val timer = compose.onNodeWithTag("rangefinder-timer").fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val textNode = compose.onNode(
            hasText("Timer: Off") and hasAnyAncestor(hasTestTag("rangefinder-timer")),
            useUnmergedTree = true,
        )
        val textBounds = textNode.fetchSemanticsNode().boundsInRoot
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        check(layouts.isNotEmpty()) { "$state timer has no text layout" }
        val layout = layouts.single()
        val origin = IntArray(2)
        scenario.onActivity {
            it.window.decorView.findViewById<android.view.View>(android.R.id.content)
                .getLocationOnScreen(origin)
        }
        val density = context.resources.displayMetrics.density
        val inset = (6f * density).roundToInt().coerceAtLeast(3)
        val bitmap = screenshot()
        try {
            val innerPlate = AndroidRect(
                (floor(timer.left).toInt() + origin[0] + inset).coerceIn(0, bitmap.width),
                (floor(timer.top).toInt() + origin[1] + inset).coerceIn(0, bitmap.height),
                (ceil(timer.right).toInt() + origin[0] - inset).coerceIn(0, bitmap.width),
                (ceil(timer.bottom).toInt() + origin[1] - inset).coerceIn(0, bitmap.height),
            )
            val region = AndroidRect(
                maxOf(innerPlate.left, floor(textBounds.left).toInt() + origin[0]),
                maxOf(innerPlate.top, floor(textBounds.top).toInt() + origin[1]),
                minOf(innerPlate.right, ceil(textBounds.right).toInt() + origin[0]),
                minOf(innerPlate.bottom, ceil(textBounds.bottom).toInt() + origin[1]),
            )
            check(region.width() > 0 && region.height() > 0) {
                "$state timer text has no visible interior: plate=$timer text=$textBounds"
            }
            val mask = BooleanArray(region.width() * region.height())
            var bright = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                val pixel = bitmap.getPixel(x, y)
                val lit = Color.red(pixel) >= 125 && Color.green(pixel) >= 125 && Color.blue(pixel) >= 125
                mask[(y - region.top) * region.width() + x - region.left] = lit
                if (lit)
                    bright++
            }
            val glyph = Glyph(timer, textBounds, region, bright, mask)
            val matchingPixels = baseline?.let { reference ->
                if (reference.region == region) reference.mask.indices.count { reference.mask[it] && mask[it] }
                else 0
            }
            val visibleLine = if (layout.lineCount > 0)
                "left=${layout.getLineLeft(0)},right=${layout.getLineRight(0)}," +
                    "end=${layout.getLineEnd(0, visibleEnd = true)},length=${layout.layoutInput.text.length}"
            else "no lines"
            val png = savePng(state, bitmap)
            val record = JSONObject()
                .put("state", state)
                .put("timerBoundsInRoot", "${timer.left},${timer.top},${timer.right},${timer.bottom}")
                .put("textBoundsInRoot", "${textBounds.left},${textBounds.top},${textBounds.right},${textBounds.bottom}")
                .put("screenRegion", "${region.left},${region.top},${region.right},${region.bottom}")
                .put("brightGlyphPixels", bright)
                .put("baselineBrightGlyphPixels", baseline?.brightPixels ?: JSONObject.NULL)
                .put("matchingBaselineGlyphPixels", matchingPixels ?: JSONObject.NULL)
                .put("textLayoutSize", "${layout.size.width}x${layout.size.height}")
                .put("visibleLine", visibleLine)
                .put("textOverflow", "${layout.didOverflowWidth}/${layout.didOverflowHeight}")
                .put("png", png.absolutePath)
            results.put(record)
            saveJson()
            Log.i("TitaniumTimerDraw", record.toString())
            check(layout.lineCount == 1 && !layout.isLineEllipsized(0) &&
                layout.getLineEnd(0, visibleEnd = true) == layout.layoutInput.text.length &&
                layout.getLineLeft(0) >= 0f &&
                ceil(layout.getLineRight(0)).toInt() <= layout.size.width &&
                layout.size.width <= layout.layoutInput.constraints.maxWidth &&
                layout.getLineTop(0) >= 0f &&
                layout.getLineBottom(0) <= layout.size.height.toFloat() &&
                !layout.didOverflowHeight &&
                textBounds.left >= timer.left && textBounds.top >= timer.top &&
                textBounds.right <= timer.right && textBounds.bottom <= timer.bottom) {
                "$state timer text is clipped or outside its plate: text=$textBounds plate=$timer size=${layout.size}"
            }
            if (baseline == null) {
                check(bright >= 20) { "Closed timer has no visible glyph: $bright bright pixels" }
            } else {
                check(timer == baseline.plateBounds && textBounds == baseline.textBounds && region == baseline.region) {
                    "$state timer/text moved: $timer/$textBounds/$region vs " +
                        "${baseline.plateBounds}/${baseline.textBounds}/${baseline.region}"
                }
                check(bright >= baseline.brightPixels / 2) {
                    "$state timer plate lost its visible glyph: $bright vs ${baseline.brightPixels} closed pixels"
                }
                check(checkNotNull(matchingPixels) >= baseline.brightPixels / 2) {
                    "$state timer glyph no longer matches closed: $matchingPixels of ${baseline.brightPixels} pixels"
                }
            }
            return glyph
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitTwoFrames() {
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { latch.countDown() }
            }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "Timer draw did not pass two display callbacks" }
        requireAwake()
    }

    private fun requireAwake() {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
            "Unlock the phone and keep its screen on before timer draw acceptance"
        }
    }

    private fun screenshot(): Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
        "UiAutomation could not capture the foreground camera"
    }

    private fun savePng(state: String, bitmap: Bitmap): File =
        File(context.cacheDir, "titanium-timer-draw-$state.png").also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i("TitaniumTimerDraw", "PNG ${file.absolutePath}")
        }

    private fun saveJson() {
        File(context.cacheDir, "titanium-timer-draw-diagnostic.json").writeText(results.toString(2))
    }
}
