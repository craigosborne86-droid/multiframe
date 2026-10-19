package dev.multiframe.camera.ui

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.camera2.CaptureResult
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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

/** Real main-clock capture without a Compose test scheduler; same APIs as frozen APK. */
class Revision05NativeCaptureTest {
    @get:Rule val permission=GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private fun await(condition: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+45_000
        while(!condition()) { check(SystemClock.uptimeMillis()<end);SystemClock.sleep(50) }
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if(root == null) emptyList() else listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    @Test fun threeRealCapturesWithoutVirtualUiClock() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments();check(args.getString("sceneConfirmed")=="true")
        check(!context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        val folder=File(context.getExternalFilesDir(null),"revision05-captures-${args.getString("phase")}").apply { mkdirs() }
        val prefs=context.getSharedPreferences("multiframe.settings",Context.MODE_PRIVATE);val saved=prefs.all.toMap()
        val edit=prefs.edit()
        mapOf("manualExposure" to "false","manualFocus" to "false","manualColor" to "false","wbMode" to "AUTO", "awb" to "1", "mode" to "PHOTO", "guard" to "true", "burst" to "8", "manualBurst" to "true", "timer" to "0", "fullResolution" to "false", "histogram" to "false", "dng" to "true", "ev" to "0").forEach { (k,v)->edit.putString(k,v) };edit.remove("lens").commit()
        val sampling=AtomicBoolean(true)
        val sampler=Thread {
            while(sampling.get()) {
                val status=context.getSystemService(PowerManager::class.java).currentThermalStatus
                File(folder,"thermal-memory.csv").appendText("${System.currentTimeMillis()},$status,${Debug.getPss()},${Debug.getNativeHeapAllocatedSize()}\n")
                SystemClock.sleep(1000)
            }
        }.apply { start() }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await { CaptureUiInspection.stream?.latestResult() != null }
            SystemClock.sleep(8000)
            repeat(3) { index ->
                await { (CaptureUiInspection.stream?.readyFrames() ?: 0)>=8 }
                val result=CaptureUiInspection.stream!!.latestResult()!!
                val before=CaptureLog.records(context).map { it.baseName }.toSet()
                val release=nodes(instrumentation.uiAutomation.rootInActiveWindow).first { it.contentDescription?.toString()=="Mechanical shutter" }
                val r=android.graphics.Rect();release.getBoundsInScreen(r)
                val start=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true));event.recycle();SystemClock.sleep(50)
                }
                await { CaptureLog.records(context).any { it.baseName !in before && it.jpeg != null && it.dng != null } }
                val record=CaptureLog.records(context).first { it.baseName !in before && it.jpeg != null && it.dng != null }
                assertEquals(8,record.frameCount)
                val saveMillis=SystemClock.uptimeMillis()-start
                record.files.forEach { file -> context.contentResolver.openInputStream(file.uri!!).use { input ->
                    File(folder,"shot-${index+1}-${file.displayName}").outputStream().use { input!!.copyTo(it) }
                } }
                File(folder,"captures.csv").appendText("${index+1},${record.baseName},8,$saveMillis,${result.get(CaptureResult.SENSOR_SENSITIVITY)},${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)},${result.get(CaptureResult.COLOR_CORRECTION_GAINS)},EV=${result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)},guardPull=${CaptureUiInspection.stream!!.pullStops}\n")
                SystemClock.sleep(1500)
            }
        } finally {
            sampling.set(false);sampler.join(1500);scenario.close()
            val restore=prefs.edit().clear()
            saved.forEach { (k,v)->when(v) { is String->restore.putString(k,v);is Boolean->restore.putBoolean(k,v);is Int->restore.putInt(k,v);is Float->restore.putFloat(k,v);is Long->restore.putLong(k,v) } };restore.commit()
        }
    }
}
