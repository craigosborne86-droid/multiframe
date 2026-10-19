package dev.multiframe.camera.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Real capture screen on a HAL whose RAW stream requires sequential capture. */
class Revision05FallbackScreenTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var saved: AppSettings
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val folder get() = File(context.getExternalFilesDir(null), "revision05-fallback-final").apply { mkdirs() }

    @Before fun start() {
        val manager = context.getSystemService(CameraManager::class.java)
        val rear = manager.cameraIdList.first { manager.getCameraCharacteristics(it)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val ch = manager.getCameraCharacteristics(rear)
        val raw = android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in
            (ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
        assumeTrue("Sequential-camera integration only", ZslPolicy.evaluate(raw,
            ZslRawStream.rawConfigs(ch)) is ZslDecision.Fallback)
        saved = AppSettings.load(context)
        OnboardingState.markSeen(context)
        AppSettings.save(context, saved.copy(lensId = rear, manual = ManualSettings(),
            manualBurst = false, captureMode = CaptureMode.PHOTO, histogram = false,
            fullResolution = false, timerSeconds = 0))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitFrames()
        assertNull("Stalled RAW must stay on the actual sequential path", CaptureUiInspection.stream)
    }

    @After fun finish() {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "last-state.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (::scenario.isInitialized) scenario.close()
        if (::saved.isInitialized) AppSettings.save(context, saved)
    }
    private fun renderer(v: View): PeakingPreviewView? {
        if (v is PeakingPreviewView) return v
        if (v is ViewGroup) repeat(v.childCount) { renderer(v.getChildAt(it))?.let { found -> return found } }
        return null
    }
    private fun awaitFrames() {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(15_000) {
            var frames = 0L
            scenario.onActivity { frames = renderer(it.window.decorView)?.renderedFrames ?: 0L }
            frames >= 5
        }
    }
    private fun rect(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun click(tag: String) {
        ui.onNodeWithTag(tag, useUnmergedTree = true).performClick(); ui.waitForIdle()
    }
    private fun record(name: String): Rect {
        ui.waitForIdle()
        val image = rect("camera-preview")
        assertEquals(image, rect("camera-metering-region"))
        assertEquals(image, rect("rangefinder-view-reticle"))
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(if (root.width > root.height) 4f/3f else .75f, image.width/image.height, .002f)
        var source = ""
        scenario.onActivity {
            val view = renderer(it.window.decorView)!!
            fun crop(name: String) = view.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(view)
            assertEquals(1f,crop("cropX"),.002f); assertEquals(1f,crop("cropY"),.002f)
            source = "${crop("cropX")},${crop("cropY")},frames=${view.renderedFrames}"
        }
        File(folder,"bounds.csv").appendText("$name,${image.left},${image.top},${image.right},${image.bottom},$source\n")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        ui.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
        return image
    }
    @Test fun coldStartHasRealFramesAndPanelsCannotReframeThem() {
        val baseline = record("portrait-auto")
        click("rangefinder-pro")
        for (tab in listOf("exp","focus","frames","color")) {
            click("mechanical-tab-$tab")
            assertEquals(baseline,record("portrait-pro-$tab"))
        }
        click("rangefinder-pro")
        click("revision05-auto-wb")
        assertEquals(baseline,record("portrait-auto-wb"))
        click("revision05-auto-wb")
        click("revision05-rgb")
        assertEquals(baseline,record("portrait-preview-rgb"))
        click("titanium-labs-button")
        clickByDescription("Close settings")
        assertEquals(baseline,record("portrait-settings-return"))
    }
    private fun clickByDescription(description: String) {
        ui.onNodeWithContentDescription(description).performClick(); ui.waitForIdle()
    }
    @Test fun bothLandscapeDirectionsPreserveProfileAndFraming() {
        click("rangefinder-pro")
        click("revision05-manual")
        ui.waitUntil(5_000) { CaptureUiInspection.manual?.manualExposure == true }
        val manual = CaptureUiInspection.manual
        for ((name,orientation) in listOf("landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "opposite-landscape" to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            scenario.onActivity { it.requestedOrientation = orientation }
            ui.waitUntil(15_000) { ui.onRoot().fetchSemanticsNode().boundsInRoot.let { it.width > it.height } }
            awaitFrames()
            val bounds = record("$name-pro")
            assertEquals(manual,CaptureUiInspection.manual)
            for (tab in listOf("exp","focus","frames","color")) {
                click("mechanical-tab-$tab"); assertEquals(bounds,record("$name-pro-$tab"))
                val selected = ui.onNodeWithTag("mechanical-tab-$tab").getUnclippedBoundsInRoot()
                val panel = ui.onNodeWithTag("revision05-replacement-panel").getUnclippedBoundsInRoot()
                assertTrue("Selected tab left must stay inside its bounded panel", selected.left >= panel.left)
                assertTrue("Selected tab right must stay inside its bounded panel", selected.right <= panel.right)
            }
            click("rangefinder-pro")
            assertEquals(bounds,record("$name-auto"))
            click("rangefinder-pro")
        }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(15_000) { ui.onRoot().fetchSemanticsNode().boundsInRoot.let { it.height > it.width } }
        awaitFrames(); assertEquals(manual,CaptureUiInspection.manual); record("portrait-returned")
    }
    @Test fun nativeConsoleTapsCannotReachTheCameraMeteringSurface() {
        for (tag in listOf("rangefinder-mode-photo","rangefinder-mode-night","rangefinder-mode-photo",
            "rangefinder-pro","mechanical-tab-exp","mechanical-tab-focus","rangefinder-pro",
            "rangefinder-timer","revision05-auto-wb","revision05-auto-wb","revision05-rgb")) {
            ui.onNodeWithTag(tag,useUnmergedTree=true).performTouchInput { click() }
            ui.waitForIdle()
            ui.onNodeWithTag("rangefinder-tap-focus-reticle").assertDoesNotExist()
        }
        ui.onNodeWithTag("camera-preview").performTouchInput { click(center* .6f) }
        ui.waitUntil(3_000) { ui.onAllNodesWithTag("rangefinder-tap-focus-reticle").fetchSemanticsNodes().isNotEmpty() }
        record("image-tap-focus")
    }

    @Test fun photoZebraDefaultAndSingleLevelStayConnectedToTheNativeRenderer() {
        fun zebra(): Boolean {
            var value = false
            scenario.onActivity { value = renderer(it.window.decorView)!!.zebraEnabled }
            return value
        }
        assertTrue(zebra())
        ui.onAllNodesWithTag("rangefinder-tilt-readout").assertCountEquals(1)
        click("rangefinder-mode-night")
        assertFalse(zebra())
        click("rangefinder-mode-photo")
        assertTrue(zebra())
        click("titanium-labs-button")
        ui.onAllNodesWithContentDescription("Zebra highlights ON").assertCountEquals(1)
        ui.onNodeWithContentDescription("Zebra highlights ON").performScrollTo().performClick()
        clickByDescription("Close settings")
        assertFalse(zebra())
        ui.onAllNodesWithTag("rangefinder-tilt-readout").assertCountEquals(1)
        record("zebra-explicitly-off")
    }
}
