package dev.multiframe.camera.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.AppSettings
import dev.multiframe.camera.pipeline.CaptureLog
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.ManualSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in physical acceptance, with a person holding a near/far scene and panning.
 * The assertions prove real requests, motion and fresh GPU frames. Image review
 * and a human judgement are still needed for focal-plane discrimination/trails.
 */
class PeakingPanDeviceTest {
    @get:Rule val permission = org.junit.rules.TestRule { base, description ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) base
        else GrantPermissionRule.grant(Manifest.permission.CAMERA).apply(base, description)
    }
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun liveCameraPan() {
        assumeTrue(Build.MODEL == "Pixel 11 Pro")
        assumeTrue(InstrumentationRegistry.getArguments().getString("panCheck") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val saved = AppSettings.load(context)
        AppSettings.save(context, saved.copy(lensId = null, captureMode = CaptureMode.PHOTO,
            manual = ManualSettings(), timerSeconds = 0, fullResolution = false, burstFrames = 2))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val sensors = context.getSystemService(SensorManager::class.java)
        var last = 0L
        val motion = AtomicReference(0.0)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                if (last != 0L) {
                    val speed = sqrt(e.values.sumOf { (it * it).toDouble() })
                    motion.updateAndGet { it + speed * ((e.timestamp - last) * 1e-9) }
                }
                last = e.timestamp
            }
        }
        fun note(message: String) = instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "$message\n")
        })
        fun cue(message: String) {
            note(message)
            scenario.onActivity { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
        }
        try {
            lateinit var root: View
            scenario.onActivity {
                root = it.window.decorView
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            fun preview(v: View): PeakingPreviewView? = when (v) {
                is PeakingPreviewView -> v
                is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { preview(v.getChildAt(it)) }
                else -> null
            }
            var renderer: PeakingPreviewView? = null
            compose.waitUntil(40_000) {
                var focusedPortrait = false
                scenario.onActivity {
                    focusedPortrait = it.hasWindowFocus() && it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                    renderer = preview(root)
                }
                focusedPortrait && (renderer?.renderedFrames ?: 0L) > 20 && CaptureUiInspection.stream?.latestResult() != null
            }
            val gpu = renderer!!
            fun frames(count: Long = 30) {
                val before = gpu.renderedFrames
                compose.waitUntil(8_000) { gpu.renderedFrames >= before + count }
            }
            fun shot(name: String) {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "physical-$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            fun detent(next: Boolean) = compose.onNodeWithContentDescription(
                if (next) "Next dial detent" else "Previous dial detent").performScrollTo().performClick()
            compose.onNodeWithTag("rangefinder-pro").performClick()
            compose.onNodeWithTag("mechanical-tab-focus").performScrollTo().performClick()
            compose.onNodeWithTag("revision05-focus-manual").performScrollTo().performClick()
            compose.waitUntil(5_000) { gpu.peakingEnabled }
            compose.onNodeWithTag("rangefinder-focus-assist-toggle").performClick()
            compose.waitUntil(5_000) { gpu.focusInsetEnabled }
            cue("Hold still: checking near focus, then the distant background")
            repeat(12) { detent(false) }
            frames(60)
            val near = CaptureUiInspection.manual!!.focusDiopters
            assertThat(near).isGreaterThan(0f)
            compose.waitUntil(5_000) {
                val actual = CaptureUiInspection.stream?.latestResult()?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                actual != null && kotlin.math.abs(actual - near) < .1f
            }
            note("Near focus requested=$near sensor=${CaptureUiInspection.stream?.latestResult()?.get(CaptureResult.LENS_FOCUS_DISTANCE)} peaking=${gpu.peakingEnabled} loupe=${gpu.focusInsetEnabled}")
            shot("focus-near")
            repeat(12) { detent(true) }
            frames(60)
            assertThat(CaptureUiInspection.manual!!.focusDiopters).isEqualTo(0f)
            compose.waitUntil(5_000) { CaptureUiInspection.stream?.latestResult()?.get(CaptureResult.LENS_FOCUS_DISTANCE) == 0f }
            note("Far focus sensor=0.0 peaking=${gpu.peakingEnabled} loupe=${gpu.focusInsetEnabled}")
            shot("focus-far")
            // Begin from the verified infinity endpoint, not an unknown live AF value.
            repeat(6) { detent(false) }
            frames(30)
            val focus = CaptureUiInspection.manual!!.focusDiopters
            assertThat(focus).isEqualTo(1f)
            val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            assumeTrue("Physical pan needs a gyroscope", gyro != null)
            sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper()))
            cue("PAN NOW: slowly sweep left and right for 20 seconds")
            val firstFrame = gpu.renderedFrames
            val firstTimestamp = gpu.renderedTimestamp
            val start = SystemClock.elapsedRealtime()
            repeat(5) { sample ->
                compose.waitUntil(6_000) { SystemClock.elapsedRealtime() - start >= (sample + 1) * 4000L }
                val bitmap = Bitmap.createBitmap(gpu.width, gpu.height, Bitmap.Config.ARGB_8888)
                val latch = CountDownLatch(1)
                var status = -1
                PixelCopy.request(gpu, bitmap, { status = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                check(latch.await(5, TimeUnit.SECONDS))
                assertThat(status).isEqualTo(PixelCopy.SUCCESS)
                File(context.cacheDir, "peaking-pan-$sample.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
            val frames = gpu.renderedFrames - firstFrame
            val degrees = motion.get() * 180.0 / Math.PI
            note("panDegrees=$degrees frames=$frames seconds=$seconds fps=${frames / seconds} firstTimestamp=$firstTimestamp lastTimestamp=${gpu.renderedTimestamp} focusDiopters=$focus")
            assertThat(gpu.renderedTimestamp).isGreaterThan(firstTimestamp)
            assertThat(frames).isGreaterThan(100)
            assertThat(degrees).isGreaterThan(15.0)
            cue("Stop panning. Feel four dial clicks, then one shutter release")
            repeat(2) { detent(true); frames(10) }
            repeat(2) { detent(false); frames(10) }
            val stream = checkNotNull(CaptureUiInspection.stream)
            compose.waitUntil(10_000) { stream.readyFrames() >= 2 }
            val before = CaptureLog.records(context).map { it.baseName }.toSet()
            compose.onNodeWithTag("rangefinder-shutter").performTouchInput {
                down(center); advanceEventTime(160); up()
            }
            compose.waitUntil(40_000) { CaptureLog.records(context).any { it.baseName !in before && it.jpeg != null } }
            note("Physical haptic-check capture saved=${CaptureLog.records(context).first { it.baseName !in before && it.jpeg != null }.baseName}; tactile judgement still required")
        } finally {
            sensors.unregisterListener(listener)
            scenario.close()
            AppSettings.save(context, saved)
        }
    }
}
