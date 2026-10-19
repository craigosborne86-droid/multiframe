package dev.multiframe.camera.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.hardware.camera2.CaptureResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real repeating requests: returning from a restored manual profile must revive the guard. */
class Revision05ProfileGuardTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val ui = createEmptyComposeRule()
    private fun settle(seconds: Int) { repeat(seconds*5) { Thread.sleep(200); ui.mainClock.advanceTimeBy(200) } }
    @Test fun returnToAutoRestartsHighlightGuardFromRestoredManualProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = AppSettings.load(context)
        AppSettings.save(context,saved.copy(lensId="0", manual=ManualSettings(manualExposure=true, iso=100,
            exposureTimeNs=10_000_000), captureMode=CaptureMode.PHOTO, highlightGuard=true,
            burstFrames=8, manualBurst=true, timerSeconds=0, fullResolution=false))
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(30_000) { CaptureUiInspection.stream?.latestResult() != null }
            settle(3)
            assertEquals(0,CaptureUiInspection.stream!!.latestResult()!!.get(CaptureResult.CONTROL_AE_MODE))
            ui.onNodeWithTag("rangefinder-pro").performClick()
            ui.onNodeWithTag("revision05-return-auto").performClick()
            settle(8)
            assertEquals(1,CaptureUiInspection.stream!!.latestResult()!!.get(CaptureResult.CONTROL_AE_MODE))
            val bins=IntArray(64)
            assertTrue(CaptureUiInspection.stream!!.histogram(bins))
            val analysis=ExposureStrategy.analyse(bins)
            val expected=ExposureStrategy.nextGuard(analysis,8)
            val folder=File(context.getExternalFilesDir(null),"revision05-profile-guard").apply { mkdirs() }
            File(folder,"actual.txt").writeText("analysis=$analysis; expected=$expected; actualPull=${CaptureUiInspection.stream!!.pullStops}\n")
            assertEquals("Auto must apply the existing scene-based guard after a manual profile", expected.pullStops,
                CaptureUiInspection.stream!!.pullStops, .001f)
        } finally { scenario.close();AppSettings.save(context,saved) }
    }
}
