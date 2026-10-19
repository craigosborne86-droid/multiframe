package dev.multiframe.camera.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses native window/accessibility observations, independent of Compose's idle clock. */
class Revision05SweepLifecycleTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if(root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    private fun freshNodes(): List<AccessibilityNodeInfo> {
        // Scroll events can leave UiAutomation's node cache at the previous viewport.
        // Read current native geometry rather than repeatedly swiping from stale nodes.
        instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun find(label: String) = freshNodes()
        .firstOrNull { it.text?.toString() == label || it.contentDescription?.toString().let { description ->
            description == label || description?.startsWith("$label:") == true || description?.startsWith("$label ") == true || description?.endsWith(": $label") == true
        } }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while(!condition()) { check(SystemClock.uptimeMillis() < deadline) { "Native UI condition timed out" }; SystemClock.sleep(100) }
    }
    private fun click(label: String) {
        repeat(15) {
            val node = find(label)
            if(node?.isVisibleToUser == true) {
                val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                val now = SystemClock.uptimeMillis()
                for(action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    check(instrumentation.uiAutomation.injectInputEvent(event,true))
                    event.recycle(); SystemClock.sleep(50)
                }
                SystemClock.sleep(500); return
            }
            freshNodes().firstOrNull { it.isScrollable }?.let { scroll ->
                val r = android.graphics.Rect(); scroll.getBoundsInScreen(r)
                File(instrumentation.targetContext.getExternalFilesDir(null), "revision05-sweep-scroll.txt").appendText("bounds=$r; actions=${scroll.actionList}\n")
                val start = SystemClock.uptimeMillis()
                for(step in 0..12) {
                    val action = if(step == 0) android.view.MotionEvent.ACTION_DOWN else if(step == 12) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE
                    val event = android.view.MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                        r.exactCenterX(), r.top + r.height() * (.8f - .6f * step/12), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.uiAutomation.injectInputEvent(event,true); event.recycle(); SystemClock.sleep(20)
                }
            }
            SystemClock.sleep(250)
        }
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "revision05-native-sweep-lifecycle").apply { mkdirs() }
        File(folder,"unreachable-control.txt").writeText("$label\n" + freshNodes().joinToString("\n") { "${it.text} | ${it.contentDescription} | visible=${it.isVisibleToUser} scroll=${it.isScrollable}" })
        error("Control not reachable: $label")
    }
    private fun preview(view: View): PeakingPreviewView? {
        if(view is PeakingPreviewView) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) preview(view.getChildAt(index))?.let { return it }
        return null
    }
    @Test fun armedSweepRotationRestoresAndLandscapeCanRearmAndExit() {
        val context = instrumentation.targetContext
        val saved = AppSettings.load(context)
        val folder = File(context.getExternalFilesDir(null), "revision05-native-sweep-lifecycle").apply { mkdirs() }
        AppSettings.save(context, saved.copy(lensId = "0", manual = ManualSettings(), captureMode = CaptureMode.NIGHT, timerSeconds = 3, manualBurst = true, burstFrames = 8, fullResolution = false))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await { CaptureUiInspection.stream?.latestResult() != null && find("Open Settings") != null }
            val before = CaptureUiInspection.manual
            click("Open Settings"); click("TECHNOLOGY PREVIEWS")
            click("ARM BRENIZER SWEEP"); await { find("Exit sweep") != null }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await {
                var landscape = false
                scenario.onActivity { landscape = it.window.decorView.width > it.window.decorView.height }
                landscape && find("Exit sweep") == null && CaptureUiInspection.stream?.lensId == "0" && CaptureUiInspection.stream?.latestResult() != null
            }
            assertEquals(before, CaptureUiInspection.manual)
            assertEquals(CaptureMode.NIGHT, AppSettings.load(context).captureMode)
            assertEquals(3, AppSettings.load(context).timerSeconds)
            click("Open Settings"); click("TECHNOLOGY PREVIEWS"); click("ARM BRENIZER SWEEP")
            await { find("Exit sweep") != null }
            scenario.onActivity {
                val p = preview(it.window.decorView)!!
                assertEquals(4f/3f, p.width.toFloat()/p.height, .002f)
                File(folder,"native-window.txt").writeText("window=${it.window.decorView.width}x${it.window.decorView.height} px; preview=${p.width}x${p.height} px\n")
            }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(folder,"landscape-dedicated-sweep.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            click("Exit sweep"); await { find("Exit sweep") == null && CaptureUiInspection.stream?.lensId == "0" }
            assertEquals(before, CaptureUiInspection.manual)
        } finally { scenario.close(); AppSettings.save(context,saved) }
    }
}
