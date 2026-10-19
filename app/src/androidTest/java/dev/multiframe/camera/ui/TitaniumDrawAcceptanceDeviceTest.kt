package dev.multiframe.camera.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.AppSettings
import dev.multiframe.camera.pipeline.CaptureLog
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.CaptureRecord
import dev.multiframe.camera.pipeline.ManualSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

private const val DRAW_TAG = "TitaniumDrawAcceptance"
private const val TWO_FRAME = "MF_20261001_110907_zsl_2f"
private const val TWENTY_EIGHT_FRAME = "MF_20261001_110852_zsl_28f"

/** Owns a real activity, preserves settings, and fails clearly if the screen locks. */
private class DrawSession(private val compose: ComposeTestRule) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    private val saved = AppSettings.load(context)
    private val recentPrefs = context.getSharedPreferences("multiframe.recent", Context.MODE_PRIVATE)
    private val recentSaved = recentPrefs.all.toMap()
    private var recentCleared = false
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    lateinit var scenario: ActivityScenario<MainActivity>
        private set

    fun requireAwake() {
        check(power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
            "Unlock the phone and leave its screen on before Titanium draw acceptance"
        }
    }

    fun launch() {
        requireAwake()
        AppSettings.save(context, saved.copy(manual = ManualSettings(), timerSeconds = 0,
            lensId = null, captureMode = CaptureMode.PHOTO, fullResolution = false, burstFrames = 8))
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            awaitOrientation(Configuration.ORIENTATION_PORTRAIT)
        } catch (failure: Throwable) {
            snapshotFailure("launch", failure)
            throw failure
        }
    }

    /** Model a deleted last-shot pointer without touching any captured media. */
    fun clearRecent() {
        check(recentPrefs.edit().clear().commit()) { "Could not clear the recent pointer for gallery test" }
        recentCleared = true
    }

    fun setPro(engaged: Boolean) {
        // R05 selected=true also means a retained manual profile. Visibility is separate.
        fun open() = compose.onAllNodesWithTag("mechanical-tab-exp").fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("rangefinder-pro").fetchSemanticsNodes().isNotEmpty() }
        if (open() != engaged) compose.onNodeWithTag("rangefinder-pro").performClick()
        compose.waitUntil(5_000) { open() == engaged }
    }

    fun awaitOrientation(orientation: Int) {
        compose.waitUntil(20_000) {
            requireAwake()
            var ready = false
            scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ready = it.hasWindowFocus() &&
                    it.resources.configuration.orientation == orientation &&
                    it.window.decorView.width > 0 && it.window.decorView.height > 0
            }
            ready
        }
    }

    /** Two display callbacks after Compose has become idle. */
    fun awaitDraw() {
        compose.waitForIdle()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { latch.countDown() }
            }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "The activity did not draw two display frames" }
        requireAwake()
    }

    fun screenshot(): Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
        "UiAutomation could not capture the unlocked foreground activity"
    }

    fun save(name: String, bitmap: Bitmap) {
        val file = File(context.cacheDir, "titanium-draw-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        note("PNG ${file.absolutePath}")
    }

    fun snapshotFailure(name: String, failure: Throwable) {
        note("$name failed: ${failure.javaClass.simpleName}: ${failure.message}")
        runCatching { screenshot().useBitmap { save("$name-failure", it) } }
            .onFailure { note("$name failure screenshot unavailable: ${it.message}") }
    }

    fun note(message: String) {
        Log.i(DRAW_TAG, message)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }

    override fun close() {
        try {
            if (::scenario.isInitialized) scenario.close()
        } finally {
            try {
                AppSettings.save(context, saved)
            } finally {
                if (recentCleared) {
                    val editor = recentPrefs.edit().clear()
                    recentSaved.forEach { (key, value) ->
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                            else -> error("Unsupported recent preference $key: ${value?.javaClass}")
                        }
                    }
                    check(editor.commit()) { "Could not restore the original recent pointer" }
                }
            }
        }
    }
}

private data class ImageEvidence(val spread: Int, val occupiedBins: Int, val lit: Int, val samples: Int) {
    val isPhotograph: Boolean get() = spread >= 18 && occupiedBins >= 5 && lit >= samples / 20
}

/** Center crop excludes top chrome and letterbox bars; the preserved scenes contain structure here. */
private fun centerEvidence(bitmap: Bitmap): ImageEvidence {
    val luma = IntArray(40 * 24)
    val bins = BooleanArray(16)
    var lit = 0
    var n = 0
    for (row in 0 until 24) for (col in 0 until 40) {
        val x = ((col + .5f) / 40f * .60f + .20f) * bitmap.width
        val y = ((row + .5f) / 24f * .24f + .38f) * bitmap.height
        val pixel = bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1),
            y.toInt().coerceIn(0, bitmap.height - 1))
        val value = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
        luma[n++] = value
        bins[value / 16] = true
        if (value >= 32) lit++
    }
    luma.sort()
    return ImageEvidence(luma[luma.size * 9 / 10] - luma[luma.size / 10],
        bins.count { it }, lit, luma.size)
}

/** Inspect the middle of a square proof-sheet cell, away from its border and bottom badges. */
private fun cellEvidence(screen: Bitmap, bounds: Rect): ImageEvidence {
    val left = bounds.left.toInt().coerceIn(0, screen.width - 1)
    val top = bounds.top.toInt().coerceIn(0, screen.height - 1)
    val right = bounds.right.toInt().coerceIn(left + 1, screen.width)
    val bottom = bounds.bottom.toInt().coerceIn(top + 1, screen.height)
    return Bitmap.createBitmap(screen, left, top, right - left, bottom - top).useBitmap(::centerEvidence)
}

/** Compare the displayed scene's spatial light pattern with its own preserved JPEG. */
private fun sceneCorrelation(screen: Bitmap, source: Bitmap): Double {
    val fit = minOf(screen.width.toDouble() / source.width, screen.height.toDouble() / source.height)
    val fittedWidth = source.width * fit
    val fittedHeight = source.height * fit
    val left = (screen.width - fittedWidth) / 2.0
    val top = (screen.height - fittedHeight) / 2.0
    val fromFile = ArrayList<Double>(280)
    val fromScreen = ArrayList<Double>(280)
    for (row in 0 until 14) for (col in 0 until 20) {
        val u = .22 + (col + .5) / 20.0 * .56
        val v = .24 + (row + .5) / 14.0 * .52
        fun luminance(pixel: Int) =
            (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) / 256.0
        fromFile += luminance(source.getPixel((u * source.width).toInt().coerceIn(0, source.width - 1),
            (v * source.height).toInt().coerceIn(0, source.height - 1)))
        fromScreen += luminance(screen.getPixel((left + u * fittedWidth).toInt().coerceIn(0, screen.width - 1),
            (top + v * fittedHeight).toInt().coerceIn(0, screen.height - 1)))
    }
    val a = fromFile.average()
    val b = fromScreen.average()
    var numerator = 0.0
    var aa = 0.0
    var bb = 0.0
    for (i in fromFile.indices) {
        val da = fromFile[i] - a
        val db = fromScreen[i] - b
        numerator += da * db
        aa += da * da
        bb += db * db
    }
    return if (aa < 1.0 || bb < 1.0) 0.0 else numerator / kotlin.math.sqrt(aa * bb)
}

private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }

/** Decoded-size semantics alone did not catch a black review screenshot. */
class TitaniumLightboxPixelsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun knownCapturesRenderThenPinchClampAndPage() {
        assumeTrue("Preserved Pixel 11 Pro photographs", Build.MODEL == "Pixel 11 Pro")
        val session = DrawSession(compose)
        try {
            val records = CaptureLog.records(session.context)
            val newest = records.indexOfFirst { it.baseName == TWO_FRAME && it.jpeg?.uri != null }
            val older = records.indexOfFirst { it.baseName == TWENTY_EIGHT_FRAME && it.jpeg?.uri != null }
            check(newest >= 0 && older == newest + 1) {
                "Expected adjacent preserved JPEG captures $TWO_FRAME then $TWENTY_EIGHT_FRAME; " +
                    "indices=$newest,$older. This test never creates or deletes captures."
            }
            session.launch()
            session.setPro(false)
            compose.waitUntil(20_000) {
                session.requireAwake()
                compose.onAllNodesWithContentDescription("Last shot").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithContentDescription("Open gallery").fetchSemanticsNodes().isNotEmpty()
            }
            val entry = if (compose.onAllNodesWithContentDescription("Last shot").fetchSemanticsNodes().isNotEmpty())
                "Last shot" else "Open gallery"
            compose.onNodeWithContentDescription(entry).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("lightbox-cell-$TWO_FRAME").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("lightbox-cell-$TWO_FRAME").performClick()
            rendered(session, TWO_FRAME, checkNotNull(records[newest].jpeg?.uri))
            verifyInfo(session, records[newest])
            compose.onNode(hasContentDescription("INFO") and
                hasAnyAncestor(hasTestTag("lightbox-review-$TWO_FRAME")),
                useUnmergedTree = true).performClick()

            val review = compose.onNodeWithTag("lightbox-review-$TWO_FRAME")
            review.performTouchInput {
                down(0, Offset(width * .4f, height * .5f))
                down(1, Offset(width * .6f, height * .5f))
                moveTo(0, Offset(width * .15f, height * .5f), delayMillis = 200)
                moveTo(1, Offset(width * .85f, height * .5f), delayMillis = 200)
                up(0); up(1)
            }
            compose.waitUntil(5_000) { review.fetchSemanticsNode().config[GalleryScale] > 1.5f }
            val zoom = review.fetchSemanticsNode().config
            assertThat(abs(zoom[GalleryOffset].x)).isLessThan(10f)
            review.performTouchInput {
                swipe(Offset(width * .4f, height * .4f), Offset(width * .95f, height * .95f), 250)
            }
            val panned = review.fetchSemanticsNode().config
            assertThat(clampOffset(panned[GalleryOffset], panned[GalleryScale],
                panned[GalleryFrameSize], panned[GalleryImageSize])).isEqualTo(panned[GalleryOffset])
            session.awaitDraw()
            session.screenshot().useBitmap { session.save("lightbox-2f-zoom-clamped", it) }
            session.note("2f scale=${panned[GalleryScale]} offset=${panned[GalleryOffset]} " +
                "frame=${panned[GalleryFrameSize]} image=${panned[GalleryImageSize]}")

            review.performTouchInput { doubleClick() }
            compose.waitUntil(5_000) { review.fetchSemanticsNode().config[GalleryScale] == 1f }
            compose.onNodeWithTag("lightbox-pager").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) {
                val nodes = compose.onAllNodesWithTag("lightbox-review-$TWENTY_EIGHT_FRAME").fetchSemanticsNodes()
                nodes.any { abs(it.boundsInRoot.center.x - it.boundsInRoot.width / 2f) <= 3f }
            }
            rendered(session, TWENTY_EIGHT_FRAME, checkNotNull(records[older].jpeg?.uri))
            verifyInfo(session, records[older])
            session.note("Paged from exact $TWO_FRAME to exact $TWENTY_EIGHT_FRAME")
        } catch (failure: Throwable) {
            session.snapshotFailure("lightbox", failure)
            throw failure
        } finally {
            session.close()
        }
    }

    private fun rendered(session: DrawSession, baseName: String, uri: Uri) {
        val review = compose.onNodeWithTag("lightbox-review-$baseName")
        compose.waitUntil(10_000) { review.fetchSemanticsNode().config[GalleryImageSize].width > 0 }
        val decoded = review.fetchSemanticsNode().config[GalleryImageSize]
        val source = session.context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        } ?: error("Cannot decode preserved JPEG for $baseName: $uri")
        source.useBitmap { reference ->
            session.screenshot().useBitmap { bitmap ->
                session.save("$baseName-initial", bitmap)
                session.note("$baseName initial center=${centerEvidence(bitmap)} " +
                    "sceneCorrelation=${sceneCorrelation(bitmap, reference)} decoded=$decoded")
            }
            session.awaitDraw()
            var settled: ImageEvidence? = null
            var correlation = 0.0
            compose.waitUntil(15_000) {
                session.requireAwake()
                session.screenshot().useBitmap { bitmap ->
                    val evidence = centerEvidence(bitmap)
                    val match = sceneCorrelation(bitmap, reference)
                    if (evidence.isPhotograph && match >= .35) {
                        settled = evidence
                        correlation = match
                        session.save("$baseName-settled", bitmap)
                        true
                    } else false
                }
            }
            assertThat(settled!!.isPhotograph).isTrue()
            assertThat(correlation).isAtLeast(.35)
            session.note("$baseName rendered center=$settled sceneCorrelation=$correlation decoded=$decoded")
        }
    }

    private fun verifyInfo(session: DrawSession, record: CaptureRecord) {
        compose.onNode(hasContentDescription("INFO") and
            hasAnyAncestor(hasTestTag("lightbox-review-${record.baseName}")),
            useUnmergedTree = true).performClick()
        val description = record.description.uppercase()
        compose.onNodeWithText(description, useUnmergedTree = true).assertIsDisplayed()
        val rawBadge = compose.onNodeWithText("JPEG + DNG", substring = true, useUnmergedTree = true)
        if (record.hasRaw) rawBadge.assertIsDisplayed() else rawBadge.assertDoesNotExist()
        session.awaitDraw()
        session.screenshot().useBitmap { session.save("${record.baseName}-info-settled", it) }
        session.note("${record.baseName} INFO description=$description JPEG+DNG=${record.hasRaw}")
    }
}

/** A missing recent thumbnail must not strand previously saved photographs. */
class TitaniumGalleryEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun galleryRemainsReachableWithoutRecentThumbnail() {
        val session = DrawSession(compose)
        try {
            val records = CaptureLog.records(session.context)
            val known = listOf(TWO_FRAME, TWENTY_EIGHT_FRAME).map { baseName ->
                records.firstOrNull { it.baseName == baseName && it.jpeg?.uri != null }
                    ?: error("The preserved $baseName JPEG is required; no new capture is made")
            }
            session.clearRecent()
            session.launch()
            session.setPro(false)
            compose.waitUntil(10_000) {
                session.requireAwake()
                compose.onAllNodesWithTag("rangefinder-shutter").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Open gallery").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                known.all { record ->
                    compose.onAllNodesWithTag("lightbox-cell-${record.baseName}").fetchSemanticsNodes().isNotEmpty()
                }
            }
            known.forEach { compose.onNodeWithTag("lightbox-cell-${it.baseName}").assertIsDisplayed() }
            session.awaitDraw()
            var settled: List<ImageEvidence>? = null
            compose.waitUntil(15_000) {
                session.requireAwake()
                val bounds = known.map { record ->
                    compose.onNodeWithTag("lightbox-cell-${record.baseName}").fetchSemanticsNode().boundsInRoot
                }
                session.screenshot().useBitmap { bitmap ->
                    val evidence = bounds.map { cellEvidence(bitmap, it) }
                    if (evidence.all { it.isPhotograph }) {
                        settled = evidence
                        session.save("gallery-without-recent", bitmap)
                        true
                    } else false
                }
            }
            known.zip(checkNotNull(settled)).forEach { (record, evidence) ->
                session.note("proof-sheet ${record.baseName} rendered center=$evidence")
            }
        } catch (failure: Throwable) {
            session.snapshotFailure("gallery-entry", failure)
            throw failure
        } finally {
            session.close()
        }
    }
}

/** The saved burst count must be visible beside each preserved photograph. */
class TitaniumGalleryFrameCountDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun knownSavedFrameCountsAppearInGalleryAndInfo() {
        assumeTrue("Preserved Pixel 11 Pro photographs", Build.MODEL == "Pixel 11 Pro")
        val session = DrawSession(compose)
        try {
            val known = listOf(TWO_FRAME to 2, TWENTY_EIGHT_FRAME to 28).map { (baseName, count) ->
                val record = CaptureLog.records(session.context).firstOrNull {
                    it.baseName == baseName && it.jpeg?.uri != null && it.dng?.uri != null
                } ?: error("Preserved JPEG + DNG pair $baseName is required; no capture is made")
                record to count
            }
            session.launch()
            session.setPro(false)
            compose.waitUntil(20_000) {
                session.requireAwake()
                compose.onAllNodesWithContentDescription("Last shot").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithContentDescription("Open gallery").fetchSemanticsNodes().isNotEmpty()
            }
            val entry = if (compose.onAllNodesWithContentDescription("Last shot").fetchSemanticsNodes().isNotEmpty())
                "Last shot" else "Open gallery"
            compose.onNodeWithContentDescription(entry).performClick()
            compose.waitUntil(10_000) {
                known.all { (record, _) ->
                    compose.onAllNodesWithTag("lightbox-cell-${record.baseName}").fetchSemanticsNodes().isNotEmpty()
                }
            }
            known.forEach { (record, count) ->
                val cell = hasTestTag("lightbox-cell-${record.baseName}")
                compose.onNode(cell).assertIsDisplayed()
                compose.onNode(hasText("${count} FRAMES") and hasAnyAncestor(cell),
                    useUnmergedTree = true).assertIsDisplayed()
                compose.onNode(hasText("DNG") and hasAnyAncestor(cell),
                    useUnmergedTree = true).assertIsDisplayed()
                session.note("${record.baseName} gallery badge: $count FRAMES + DNG")
            }
            session.awaitDraw()
            session.screenshot().useBitmap { session.save("gallery-frame-counts", it) }

            known.forEach { (record, count) ->
                compose.onNodeWithTag("lightbox-cell-${record.baseName}").performClick()
                val review = hasTestTag("lightbox-review-${record.baseName}")
                compose.waitUntil(10_000) {
                    compose.onAllNodes(review).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasContentDescription("INFO") and hasAnyAncestor(review),
                    useUnmergedTree = true).performClick()
                compose.onNode(hasText("${count} FRAMES", substring = true) and hasAnyAncestor(review),
                    useUnmergedTree = true).assertIsDisplayed()
                compose.onNode(hasText("JPEG + DNG", substring = true) and hasAnyAncestor(review),
                    useUnmergedTree = true).assertIsDisplayed()
                session.awaitDraw()
                session.screenshot().useBitmap { session.save("${record.baseName}-frame-info", it) }
                session.note("${record.baseName} INFO: $count FRAMES + JPEG + DNG")
                compose.onNode(hasContentDescription("CLOSE") and hasAnyAncestor(review),
                    useUnmergedTree = true).performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("lightbox-cell-${record.baseName}").fetchSemanticsNodes().isNotEmpty()
                }
            }
        } catch (failure: Throwable) {
            session.snapshotFailure("gallery-frame-count", failure)
            throw failure
        } finally {
            session.close()
        }
    }
}

/** Live MainActivity window state, orientation geometry, and expanded Compose touch targets. */
class TitaniumWindowAndTouchDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun transparentSystemBarsAndEdgeToEdgeRootInBothOrientations() {
        val session = DrawSession(compose)
        try {
            session.launch()
            checkWindow(session, "portrait")
            session.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            session.awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
            checkWindow(session, "landscape")
        } catch (failure: Throwable) {
            session.snapshotFailure("window", failure)
            throw failure
        } finally {
            session.close()
        }
    }

    private fun checkWindow(session: DrawSession, label: String) {
        session.awaitDraw()
        session.screenshot().useBitmap { image ->
            session.save("window-$label", image)
            session.scenario.onActivity { activity ->
                val window = activity.window
                val decor = window.decorView
                val root = decor.findViewById<android.view.View>(android.R.id.content)
                val at = IntArray(2).also { root.getLocationOnScreen(it) }
                val insets = decor.rootWindowInsets
                val status = insets?.getInsets(WindowInsets.Type.statusBars())
                val navigation = insets?.getInsets(WindowInsets.Type.navigationBars())
                val cutout = insets?.getInsets(WindowInsets.Type.displayCutout())
                val controller = WindowCompat.getInsetsController(window, decor)
                session.note("$label bars status=${window.statusBarColor} nav=${window.navigationBarColor} " +
                    "contrast=${window.isStatusBarContrastEnforced}/${window.isNavigationBarContrastEnforced} " +
                    "light=${controller.isAppearanceLightStatusBars}/${controller.isAppearanceLightNavigationBars} " +
                    "decorFits=${decor.fitsSystemWindows} rootFits=${root.fitsSystemWindows} " +
                    "root=${at.contentToString()} ${root.width}x${root.height} screen=${image.width}x${image.height} " +
                    "insets status=$status nav=$navigation cutout=$cutout " +
                    "visible=${insets?.isVisible(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())} " +
                    "uiFlags=${decor.systemUiVisibility} cutoutMode=${window.attributes.layoutInDisplayCutoutMode}")
                assertThat(window.statusBarColor).isEqualTo(Color.TRANSPARENT)
                assertThat(window.navigationBarColor).isEqualTo(Color.TRANSPARENT)
                assertThat(window.isStatusBarContrastEnforced).isFalse()
                assertThat(window.isNavigationBarContrastEnforced).isFalse()
                assertThat(controller.isAppearanceLightStatusBars).isFalse()
                assertThat(controller.isAppearanceLightNavigationBars).isFalse()
                assertThat(decor.fitsSystemWindows).isFalse()
                assertThat(root.fitsSystemWindows).isFalse()
                assertThat(at[0]).isWithin(2).of(0)
                assertThat(at[1]).isWithin(2).of(0)
                assertThat(root.width).isWithin(2).of(image.width)
                assertThat(root.height).isWithin(2).of(image.height)
                assertThat(window.attributes.layoutInDisplayCutoutMode).isEqualTo(
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
                assertThat(status).isNotNull()
                assertThat(navigation).isNotNull()
            }
        }
    }

    @Test fun visibleControlTouchBoundsMeetFortyEightDpAndReportOverlaps() {
        val session = DrawSession(compose)
        try {
            session.launch()
            checkTouchOrientation(session, "portrait")
            session.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            session.awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
            checkTouchOrientation(session, "landscape")
        } catch (failure: Throwable) {
            session.snapshotFailure("touch", failure)
            throw failure
        } finally {
            session.close()
        }
    }

    private fun checkTouchOrientation(session: DrawSession, label: String) {
        session.setPro(false)
        compose.waitUntil(10_000) {
            session.requireAwake()
            compose.onAllNodesWithTag("rangefinder-mode-photo").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("mechanical-lens-0").fetchSemanticsNodes().isNotEmpty()
        }
        measure(session, "$label-idle", listOf("rangefinder-shutter", "rangefinder-pro",
            "rangefinder-torch", "rangefinder-camera-flip"))
        diagnoseHitAreas(session, "$label-turret-and-mode",
            (0..4).map { "mechanical-lens-$it" } + listOf("rangefinder-mode-photo", "rangefinder-mode-night"))
        session.setPro(true)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("mechanical-tab-exp").fetchSemanticsNodes().isNotEmpty()
        }
        measure(session, "$label-pro", listOf("rangefinder-shutter", "rangefinder-pro",
            "mechanical-tab-exp", "mechanical-tab-focus", "mechanical-tab-frames",
            "mechanical-tab-color", "mechanical-subtab-iso", "mechanical-subtab-shutter"))
        measureDescriptions(session, "$label-pro-arrows", listOf("Previous dial detent", "Next dial detent"))
        diagnoseHitAreas(session, "$label-raw-ruler", listOf("rangefinder-dial-ruler"))
        session.awaitDraw()
        session.screenshot().useBitmap { session.save("touch-pro-$label", it) }
    }

    /** Turret cells retain their specified 48dp touch geometry; record actual touch area. */
    private fun diagnoseHitAreas(session: DrawSession, state: String, tags: List<String>) {
        val density = session.context.resources.displayMetrics.density
        tags.forEach { tag ->
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            if (node == null) {
                session.note("$state $tag absent")
                return@forEach
            }
            val visual = node.boundsInRoot
            val touch = node.touchBoundsInRoot
            val disabled = node.config.getOrNull(SemanticsProperties.Disabled) != null
            session.note("$state $tag disabled=$disabled " +
                "visual=${visual.width / density}x${visual.height / density}dp $visual " +
                "touch=${touch.width / density}x${touch.height / density}dp $touch")
        }
    }

    private fun measure(session: DrawSession, state: String, tags: List<String>) {
        val density = session.context.resources.displayMetrics.density
        val targets = tags.mapNotNull { tag ->
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            if (node == null) {
                check(tag == "rangefinder-torch" || tag == "rangefinder-camera-flip") {
                    "Required $state control $tag is missing"
                }
                return@mapNotNull null
            }
            if (node.config.getOrNull(SemanticsProperties.Disabled) != null) return@mapNotNull null
            tag to node.touchBoundsInRoot
        }
        targets.forEach { (name, bounds) ->
            val w = bounds.width / density
            val h = bounds.height / density
            session.note("$state touch $name ${"%.1f".format(w)}x${"%.1f".format(h)}dp $bounds")
            assertThat(w).isAtLeast(47.5f)
            assertThat(h).isAtLeast(47.5f)
        }
        reportOverlaps(session, state, targets)
    }

    private fun measureDescriptions(session: DrawSession, state: String, descriptions: List<String>) {
        val density = session.context.resources.displayMetrics.density
        val targets = descriptions.map { description ->
            description to compose.onNodeWithContentDescription(description).fetchSemanticsNode().touchBoundsInRoot
        }
        targets.forEach { (name, bounds) ->
            session.note("$state touch $name ${bounds.width / density}x${bounds.height / density}dp $bounds")
            assertThat(bounds.width / density).isAtLeast(47.5f)
            assertThat(bounds.height / density).isAtLeast(47.5f)
        }
        reportOverlaps(session, state, targets)
    }

    private fun reportOverlaps(session: DrawSession, state: String, targets: List<Pair<String, Rect>>) {
        for (a in targets.indices) for (b in a + 1 until targets.size) {
            if (targets[a].second.overlaps(targets[b].second)) {
                session.note("$state expanded touch bounds overlap: ${targets[a].first} / ${targets[b].first}")
            }
        }
    }
}

/** Real pointer routing at the narrow ruler and the shared EXP row boundary. */
class TitaniumRulerHitDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun rulerEdgeSwipesAndSharedTabBoundaryUseRealTouch() {
        val session = DrawSession(compose)
        try {
            session.launch()
            probe(session, "portrait")
            session.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            session.awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
            probe(session, "landscape")
        } catch (failure: Throwable) {
            session.snapshotFailure("ruler-hit", failure)
            throw failure
        } finally {
            session.close()
        }
    }

    private fun probe(session: DrawSession, label: String) {
        session.setPro(true)
        compose.onNodeWithTag("mechanical-tab-exp").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            session.requireAwake()
            compose.onAllNodesWithTag("mechanical-subtab-iso").fetchSemanticsNodes().isNotEmpty() &&
                CaptureUiInspection.stream?.latestResult() != null
        }
        compose.onNodeWithTag("mechanical-subtab-iso").performTouchInput { click() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("ISO inertial control dial").fetchSemanticsNodes().isNotEmpty()
        }
        val originalTrigger = CaptureUiInspection.stream?.latestTapTriggerResult()?.frameNumber
        val outcomes = listOf(-23f, 23f, 0f).map { deltaDp ->
            tuneIsoToEightHundred()
            swipeRuler(session, label, deltaDp)
        }
        val center = outcomes.single { it.deltaDp == 0f }
        assertThat(center.afterIso).isGreaterThan(center.beforeIso)
        outcomes.filter { it.deltaDp != 0f && it.eligible }.forEach {
            check(it.afterIso > it.beforeIso) {
                "$label ruler swipe at centerY${it.deltaDp}dp missed ISO: ${it.beforeIso} -> ${it.afterIso}"
            }
        }

        tuneIsoToEightHundred()
        val exp = compose.onNodeWithTag("mechanical-tab-exp").fetchSemanticsNode().boundsInRoot
        val iso = compose.onNodeWithTag("mechanical-subtab-iso").fetchSemanticsNode().boundsInRoot
        val density = session.context.resources.displayMetrics.density
        val topPoint = Offset(exp.center.x, exp.bottom - density)
        val bottomPoint = Offset(exp.center.x, iso.top + density)
        session.note("$label EXP/ISO shared boundary exp=$exp iso=$iso topTap=$topPoint bottomTap=$bottomPoint")
        check(exp.contains(topPoint) && iso.contains(bottomPoint)) {
            "$label EXP/ISO boundary taps are outside their visible targets"
        }
        val beforeTabsIso = checkNotNull(CaptureUiInspection.manual?.iso)
        tapRoot(topPoint)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("SHUTTER inertial control dial").fetchSemanticsNodes().isNotEmpty()
        }
        session.note("$label upper boundary tap selected SHUTTER")
        tapRoot(bottomPoint)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("ISO inertial control dial").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(CaptureUiInspection.manual?.iso).isEqualTo(beforeTabsIso)
        val finalTrigger = CaptureUiInspection.stream?.latestTapTriggerResult()?.frameNumber
        session.note("$label AF/AE tap trigger frame before=$originalTrigger after=$finalTrigger")
        assertThat(finalTrigger).isEqualTo(originalTrigger)
        session.awaitDraw()
        session.screenshot().useBitmap { session.save("ruler-hit-$label", it) }
    }

    private data class SwipeOutcome(
        val deltaDp: Float,
        val beforeIso: Int,
        val afterIso: Int,
        val eligible: Boolean,
    )

    private fun swipeRuler(session: DrawSession, label: String, deltaDp: Float): SwipeOutcome {
        val node = compose.onNodeWithTag("rangefinder-dial-ruler").fetchSemanticsNode()
        val visual = node.boundsInRoot
        val touch = node.touchBoundsInRoot
        val step = node.config[DialTickStepPx]
        val density = session.context.resources.displayMetrics.density
        val distance = minOf(step * 2.25f, visual.width * .50f)
        val start = Offset(visual.center.x + distance / 2f, visual.center.y + deltaDp * density)
        val end = Offset(visual.center.x - distance / 2f, start.y)
        val directControls = listOf("mechanical-tab-exp", "mechanical-tab-focus",
            "mechanical-tab-frames", "mechanical-tab-color", "mechanical-subtab-iso",
            "mechanical-subtab-shutter", "rangefinder-shutter", "rangefinder-pro")
            .mapNotNull { tag ->
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.let { tag to it }
            }
        val visibleCompetitors = directControls.filter { it.second.boundsInRoot.contains(start) }
            .map { it.first }
        val expandedCompetitors = directControls.filter { it.second.touchBoundsInRoot.contains(start) }
            .map { it.first }
        val eligible = expandedCompetitors.isEmpty()
        if (!eligible) session.note("$label ruler centerY${deltaDp}dp eligibility boundary: " +
            "expanded start intersects $expandedCompetitors")
        val before = checkNotNull(CaptureUiInspection.manual?.iso)
        session.note("$label ruler startY=${deltaDp}dp visual=$visual touch=$touch " +
            "step=$step start=$start end=$end inVisual=${visual.contains(start)} " +
            "inTouch=${touch.contains(start)} otherVisibleControls=$visibleCompetitors " +
            "otherExpandedControls=$expandedCompetitors initialISO=$before")
        swipeRoot(start, end)
        compose.waitForIdle()
        session.awaitDraw()
        val after = checkNotNull(CaptureUiInspection.manual?.iso)
        session.note("$label ruler startY=${deltaDp}dp initialISO=$before finalISO=$after " +
            "eligible=$eligible")
        return SwipeOutcome(deltaDp, before, after, eligible)
    }

    private fun tuneIsoToEightHundred() {
        if (compose.onAllNodesWithTag("mechanical-subtab-iso").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("mechanical-tab-exp").performTouchInput { click() }
        }
        compose.onNodeWithTag("mechanical-subtab-iso").performTouchInput { click() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("ISO inertial control dial").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(12) {
            val current = checkNotNull(CaptureUiInspection.manual?.iso)
            if (current == 800) return
            val direction = if (current < 800) "Next dial detent" else "Previous dial detent"
            compose.onNodeWithContentDescription(direction).performTouchInput { click() }
            compose.waitUntil(3_000) { CaptureUiInspection.manual?.iso != current }
        }
        check(CaptureUiInspection.manual?.iso == 800) {
            "Could not select midrange ISO 800; current=${CaptureUiInspection.manual?.iso}"
        }
    }

    private fun swipeRoot(start: Offset, end: Offset) {
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { swipe(start - origin, end - origin, 250) }
    }

    private fun tapRoot(point: Offset) {
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(point - origin) }
    }
}
