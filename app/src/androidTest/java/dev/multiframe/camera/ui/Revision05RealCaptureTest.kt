package dev.multiframe.camera.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.os.Debug
import android.os.PowerManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.CaptureLog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs only after a human has confirmed the stationary neutral/text scene.
 * The baseline mode deliberately uses APIs that also exist in the frozen APK.
 */
class Revision05RealCaptureTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val ui = createEmptyComposeRule()
    /** Camera callbacks use real time; composition's guard delay uses the test clock.
     * Advance both together so a UI animation cannot bias exposure warm-up. */
    private fun settledDelay(millis: Long) {
        var remaining = millis
        while(remaining > 0) {
            val step = minOf(200L, remaining)
            Thread.sleep(step)
            ui.mainClock.advanceTimeBy(step)
            remaining -= step
        }
    }
    @Test fun realCaptureMergeSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("sceneConfirmed") == "true") { "Confirm the fixed test scene before invoking captures" }
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive) { "Secure lock screen blocks this test: unlock the phone" }
        val phase = args.getString("phase") ?: "current"
        val night = phase == "night"
        val wb = phase == "wb" || phase == "wb-clock"
        val shots = if (night) 6 else if (wb) 2 else 3
        val frames = if (night) 28 else 8
        val folder = File(context.getExternalFilesDir(null), "revision05-captures-$phase").apply { mkdirs() }
        val prefs = context.getSharedPreferences("multiframe.settings", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val edit = prefs.edit()
        mapOf("manualExposure" to "false", "manualFocus" to "false", "manualColor" to "false",
            "wbMode" to "AUTO", "awb" to "1", "mode" to if (night) "NIGHT" else "PHOTO",
            "burst" to frames.toString(), "manualBurst" to "true", "timer" to "0", "fullResolution" to "false",
            "dng" to "true", "format" to "JPEG", "histogram" to night.toString(), "ev" to "0").forEach { (k,v) -> edit.putString(k,v) }
        edit.remove("lens").commit()
        val sampling = AtomicBoolean(true)
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = Thread {
            while(sampling.get()) {
                val rss = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
                File(folder,"thermal-memory.csv").appendText("${System.currentTimeMillis()},${power.currentThermalStatus},${Debug.getPss()},${Debug.getNativeHeapAllocatedSize()},$rss\n")
                Thread.sleep(1000)
            }
        }.apply { name = "r05-thermal-sampler"; start() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            ui.waitUntil(40_000) { ui.onAllNodesWithTag("rangefinder-shutter").fetchSemanticsNodes().isNotEmpty() && CaptureUiInspection.stream?.latestResult() != null }
            settledDelay(8000)
            File(folder,"effective-preferences.xml").writeText(prefs.all.toString())
            if (wb) {
                ui.onNodeWithTag("revision05-auto-wb").performClick()
                ui.onNodeWithTag("revision05-wb-mode").performClick()
                ui.onNodeWithTag("revision05-wb-manual").performScrollTo().assertIsEnabled().performClick()
                ui.onNodeWithTag("revision05-kelvin").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(5500f) }
                ui.waitUntil(10_000) { CaptureUiInspection.manual?.kelvin == 5500 }
                ui.onNodeWithTag("revision05-wb-tint-tab").performScrollTo().performClick()
                ui.onNodeWithTag("revision05-tint").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(10f) }
                ui.waitUntil(10_000) { CaptureUiInspection.manual?.kelvin == 5500 && CaptureUiInspection.manual?.tint == 10 }
                ui.waitUntil(10_000) { CaptureUiInspection.stream?.latestResult()?.get(CaptureResult.CONTROL_AWB_MODE) == 0 }
                ui.onNodeWithTag("revision05-auto-wb").performClick()
            }
            repeat(shots) { index ->
                if (wb && index == 1) {
                    ui.onNodeWithTag("revision05-auto-wb").performClick()
                    ui.onNodeWithTag("revision05-wb-mode").performClick()
                    ui.onNodeWithTag("revision05-wb-lock").performScrollTo().performClick()
                    ui.onNodeWithTag("revision05-auto-wb").performClick()
                }
                ui.waitUntil(30_000) { (CaptureUiInspection.stream?.readyFrames() ?: 0) >= frames }
                // Allow a changed repeating WB request to replace every ring slot before capture.
                settledDelay(1500)
                val before = CaptureLog.records(context).map { it.baseName }.toSet()
                val result = CaptureUiInspection.stream!!.latestResult()!!
                if(wb) {
                    assertEquals(1, result.get(CaptureResult.CONTROL_AE_MODE))
                    assertTrue(result.get(CaptureResult.CONTROL_AF_MODE) != 0)
                    if(index == 0) assertEquals(0, result.get(CaptureResult.CONTROL_AWB_MODE))
                    else assertEquals(true, result.get(CaptureResult.CONTROL_AWB_LOCK))
                }
                val start = System.currentTimeMillis()
                ui.onNodeWithTag("rangefinder-shutter").assertIsEnabled().performClick()
                ui.waitUntil(45_000) { CaptureLog.records(context).any { it.baseName !in before && it.jpeg != null && it.dng != null } }
                val record = CaptureLog.records(context).first { it.baseName !in before && it.jpeg != null && it.dng != null }
                assertEquals(frames, record.frameCount)
                val saveMillis = System.currentTimeMillis() - start
                record.files.forEach { file ->
                    val destination = File(folder,"shot-${index + 1}-${file.displayName}")
                    context.contentResolver.openInputStream(file.uri!!).use { input -> destination.outputStream().use { output -> input!!.copyTo(output) } }
                    assertTrue("Saved file must contain real bytes", destination.length() > 1024)
                }
                File(folder,"captures.csv").appendText("${index+1},${record.baseName},${record.frameCount},${saveMillis},${result.get(CaptureResult.SENSOR_SENSITIVITY)},${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)},${result.get(CaptureResult.COLOR_CORRECTION_GAINS)},${result.get(CaptureResult.CONTROL_AWB_MODE)},EV=${result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)},guardPull=${CaptureUiInspection.stream!!.pullStops},AWBLock=${result.get(CaptureResult.CONTROL_AWB_LOCK)},requestedWB=${CaptureUiInspection.manual?.whiteBalanceMode}/${CaptureUiInspection.manual?.kelvin}/${CaptureUiInspection.manual?.tint}\n")
                val screen = instrumentation.uiAutomation.takeScreenshot()
                File(folder,"capture-${index+1}.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG,100,it) }; screen.recycle()
                if(night) Thread.sleep((5000 - (System.currentTimeMillis()-start)).coerceAtLeast(0))
            }
        } finally {
            sampling.set(false); thermal.join(1500)
            scenario.close()
            val restore = prefs.edit().clear()
            saved.forEach { (k,v) -> when(v) {
                is String -> restore.putString(k,v); is Boolean -> restore.putBoolean(k,v)
                is Int -> restore.putInt(k,v); is Long -> restore.putLong(k,v); is Float -> restore.putFloat(k,v)
            } }
            restore.commit()
        }
    }
}
