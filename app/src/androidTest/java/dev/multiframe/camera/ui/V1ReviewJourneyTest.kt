package dev.multiframe.camera.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiframe.camera.MainActivity
import dev.multiframe.camera.pipeline.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Native MainActivity + real MediaStore. Generated chart is UI evidence only. */
class V1ReviewJourneyTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val ui = createEmptyComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private lateinit var saved: AppSettings
    private lateinit var log: Map<String, *>
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val created = mutableListOf<Uri>()
    private val folder get() = File(context.getExternalFilesDir(null), "v1-recovery-${InstrumentationRegistry.getArguments().getString("phase") ?: "candidate"}").apply { mkdirs() }
    private val name = "MF_V1_UI_FIXTURE_${System.nanoTime()}_zsl_8f"
    @Before fun start() {
        check(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")) { "Emulator-only generated-fixture test" }
        saved = AppSettings.load(context)
        log = context.getSharedPreferences("multiframe.captures", Context.MODE_PRIVATE).all.toMap()
        OnboardingState.markSeen(context)
        val camera = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val rear = camera.cameraIdList.first { camera.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 1 }
        AppSettings.save(context, saved.copy(lensId=rear, manual=ManualSettings(), manualBurst=false,
            captureMode=CaptureMode.PHOTO, histogram=false, fullResolution=false, timerSeconds=0))
        val bitmap = Bitmap.createBitmap(960,1280,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(95,104,109))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=42f }
        canvas.drawText("MULTIFRAME UI TEST CHART",60f,110f,paint)
        canvas.drawText("Generated fixture / not a photograph",60f,175f,paint)
        for(i in 0..11) { paint.color=Color.rgb(i*21,i*21,i*21);canvas.drawRect(60f+i*70,260f,130f+i*70,540f,paint) }
        paint.color=Color.WHITE
        for(i in 1..20) canvas.drawLine(70f,650f+i*20,880f,650f+i*20,paint)
        val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,"$name.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MultiframeTest") }
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;created+=uri
        context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) };bitmap.recycle()
        CaptureLog.record(context,uri,"$name.jpg")
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("camera-preview").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }
    @After fun finish() {
        if(::scenario.isInitialized)scenario.close()
        try { created.forEach { context.contentResolver.delete(it,null,null) } } finally {
        if(::log.isInitialized) { val e=context.getSharedPreferences("multiframe.captures",Context.MODE_PRIVATE).edit().clear();log.forEach { (k,v)->if(v is String)e.putString(k,v) };e.commit() }
        if(::saved.isInitialized)AppSettings.save(context,saved)
        }
    }
    private fun tap(tag:String) { ui.onNodeWithTag(tag,useUnmergedTree=true).performClick();ui.waitForIdle() }
    private fun shot(label:String) {
        inst.uiAutomation.waitForIdle(300,5000)
        var bitmap: Bitmap? = null
        val chartExpected=label=="02-review" || label=="04-share-cancelled" || label.startsWith("rotation-") || label=="06-dng-detail" || label=="07-share-unavailable"
        ui.waitUntil(5_000) {
            bitmap?.recycle()
            bitmap=inst.uiAutomation.takeScreenshot()
            if (!chartExpected) true else {
                val image=bitmap!!
                var matches=0
                for(y in 0 until image.height step 24) for(x in 0 until image.width step 24) {
                    val pixel=image.getPixel(x,y)
                    if(kotlin.math.abs(Color.red(pixel)-95)<5 && kotlin.math.abs(Color.green(pixel)-104)<5 && kotlin.math.abs(Color.blue(pixel)-109)<5)matches++
                }
                matches>30
            }
        }
        File(folder,"$label.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap!!.recycle()
    }
    private fun review() {
        tap("rangefinder-gallery-entry")
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("lightbox-cell-$name").fetchSemanticsNodes().isNotEmpty() }
        tap("lightbox-cell-$name")
        ui.waitUntil(15_000) { ui.onNodeWithTag("lightbox-review-$name").fetchSemanticsNode().config[GalleryImageSize].width>0 }
    }
    private fun back() { inst.uiAutomation.executeShellCommand("input keyevent 4").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    @Test(timeout = 120_000) fun sharePhotoReturnsToTheSameShootingPlanAndFraming() {
        val bounds=ui.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot
        tap("rangefinder-pro");tap("mechanical-tab-frames")
        val settings=CaptureUiInspection.manual
        shot("01-compose-pro");review();shot("02-review")
        ui.onNodeWithTag("lightbox-share-photo").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("intentresolver")==true }
        shot("03-system-sharesheet")
        back()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("lightbox-review-$name").fetchSemanticsNodes().isNotEmpty() }
        shot("04-share-cancelled")
        back();ui.waitUntil(5_000) { ui.onAllNodesWithTag("lightbox-pager").fetchSemanticsNodes().isEmpty() };back()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("lightbox-review-$name").fetchSemanticsNodes().isEmpty() && ui.onNodeWithTag("rangefinder-shutter").isDisplayed() }
        ui.onNodeWithContentDescription("Close gallery").assertDoesNotExist()
        assertEquals(bounds,ui.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot)
        assertEquals(settings,CaptureUiInspection.manual)
        ui.onNodeWithTag("mechanical-tab-frames").assertIsSelected()
        shot("05-return-to-shooting")
    }
    @Test(timeout = 120_000) fun reviewSurvivesBothRotationsAndActivityRecreation() {
        review();shot("rotation-portrait")
        for((orientation,label) in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to "opposite-landscape",ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait")) {
            scenario.onActivity { it.requestedOrientation=orientation }
            ui.waitForIdle()
            ui.waitUntil(15_000) {
                val bounds=ui.onRoot().fetchSemanticsNode().boundsInRoot
                (bounds.width>bounds.height)==(orientation!=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) &&
                    ui.onAllNodesWithTag("lightbox-review-$name").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithTag("lightbox-share-photo").assertIsDisplayed()
            val share=ui.onNodeWithTag("lightbox-share-photo").fetchSemanticsNode().boundsInRoot
            val root=ui.onRoot().fetchSemanticsNode()
            assertTrue(share.height/root.layoutInfo.density.density>=48f)
            assertTrue(share.left>=0 && share.right<=root.boundsInRoot.right && share.bottom<=root.boundsInRoot.bottom)
            shot("rotation-$label")
        }
        ui.onNodeWithTag("lightbox-review-$name").performTouchInput { doubleClick() }
        ui.waitForIdle()
        val buttons=listOf("CLOSE","INFO","DELETE").map { ui.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
        for(a in buttons.indices) for(b in a+1 until buttons.size)assertFalse(buttons[a].overlaps(buttons[b]))
        scenario.recreate();ui.waitForIdle()
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("lightbox-review-$name").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("lightbox-share-photo").assertIsDisplayed()
    }
    private fun payload(extension: String, mime: String): CaptureFile {
        val filename="$name.$extension"
        val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,filename);put(MediaStore.Images.Media.MIME_TYPE,mime);put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MultiframeTest") }
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;created+=uri
        // Transport fixture only: neither a photographic DNG nor a HEIF encode.
        context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(73,73,42,0,8,0,0,0,1,2,3,4)) }
        return CaptureFile(uri.toString(),filename,System.currentTimeMillis())
    }
    @Test(timeout = 120_000) fun sharePayloadsUseTheOriginalUriSpecificMimeAndReadOnlyGrant() {
        val jpeg=CaptureLog.records(context).first { it.baseName==name }.jpeg!!
        for(file in listOf(jpeg,payload("dng","image/x-adobe-dng"),payload("heic","image/heic"))) {
            val before=context.contentResolver.openInputStream(file.uri!!)!!.use { it.readBytes() }
            val chooser=PhotoShare.prepare(context,file)
            assertEquals(android.content.Intent.ACTION_CHOOSER,chooser.action)
            val send=chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT,android.content.Intent::class.java)!!
            assertEquals(android.content.Intent.ACTION_SEND,send.action)
            assertEquals(context.contentResolver.getType(file.uri!!),send.type)
            assertEquals(file.uri,send.getParcelableExtra(android.content.Intent.EXTRA_STREAM,Uri::class.java))
            for(intent in listOf(send,chooser)) {
                assertEquals(file.uri,intent.clipData!!.getItemAt(0).uri)
                assertEquals(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assertEquals(0,intent.flags and (android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            }
            assertArrayEquals(before,context.contentResolver.openInputStream(file.uri!!)!!.use { it.readBytes() })
        }
        assertThrows(java.io.FileNotFoundException::class.java) {
            PhotoShare.prepare(context,CaptureFile("file:///not-a-content-uri.jpg","bad.jpg",0))
        }
    }
    @Test(timeout = 120_000) fun dngSharingIsSecondaryAndMissingFileDoesNotLeaveReview() {
        val raw=payload("dng","image/x-adobe-dng")
        CaptureLog.record(context,raw.uri!!,raw.displayName)
        review()
        ui.onNodeWithTag("lightbox-share-dng").assertDoesNotExist()
        ui.onNodeWithContentDescription("INFO").performClick();ui.waitForIdle()
        ui.onNodeWithTag("lightbox-share-dng").performScrollTo().assertIsDisplayed()
        ui.waitForIdle()
        val dngButton=ui.onNodeWithTag("lightbox-share-dng").fetchSemanticsNode().boundsInRoot
        val density=ui.onRoot().fetchSemanticsNode().layoutInfo.density.density
        assertTrue("Entire 48dp DNG action must be visible, actual=$dngButton", dngButton.height/density>=48f)
        shot("06-dng-detail")
        // Remove only this test's JPEG after opening it to exercise provider failure.
        context.contentResolver.delete(created.removeAt(0),null,null)
        tap("lightbox-share-photo")
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("lightbox-share-error").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("lightbox-share-photo").assertIsEnabled()
        ui.onNodeWithTag("lightbox-review-$name").assertIsDisplayed()
        shot("07-share-unavailable")
    }
    @Test(timeout = 120_000) fun virtualCameraCaptureSavesReviewsAndReturnsToShooting() {
        AppSettings.save(context,AppSettings.load(context).copy(manualBurst=true,burstFrames=2,dngEnabled=false))
        scenario.recreate();ui.waitForIdle()
        fun previewFrames(view: android.view.View): Long {
            if (view is PeakingPreviewView) return view.renderedFrames
            if (view is android.view.ViewGroup) for(i in 0 until view.childCount) {
                val count=previewFrames(view.getChildAt(i));if(count>0)return count
            }
            return 0
        }
        ui.waitUntil(30_000) {
            var frames=0L;scenario.onActivity { frames=previewFrames(it.window.decorView) }
            frames>=5 && CaptureUiInspection.frames==2
        }
        CaptureUiInspection.stream?.let { stream -> ui.waitUntil(15_000) { stream.readyFrames()>=2 } }
        val before=CaptureLog.records(context).map { it.baseName }.toSet()
        val rect=ui.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot
        shot("08-before-virtual-capture")
        ui.mainClock.autoAdvance=false
        val start=android.os.SystemClock.uptimeMillis()
        try {
            ui.onNodeWithTag("rangefinder-shutter").performTouchInput { down(center);advanceEventTime(100);up() }
            ui.waitUntil(45_000) {
                ui.mainClock.advanceTimeByFrame()
                CaptureLog.records(context).any { it.baseName !in before && it.jpeg!=null }
            }
        } finally { ui.mainClock.autoAdvance=true }
        val savedCapture=CaptureLog.records(context).first { it.baseName !in before && it.jpeg!=null }
        created+=savedCapture.files.mapNotNull { it.uri }
        val duration=android.os.SystemClock.uptimeMillis()-start
        val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(savedCapture.jpeg!!.uri!!)!!.use { android.graphics.BitmapFactory.decodeStream(it,null,options) }
        assertTrue(options.outWidth>0 && options.outHeight>0)
        assertEquals(2,CaptureUiInspection.frames)
        savedCapture.frameCount?.let { assertEquals(2,it) }
        shot("09-virtual-capture-saved")
        tap("rangefinder-gallery-entry")
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("lightbox-cell-${savedCapture.baseName}").fetchSemanticsNodes().isNotEmpty() }
        tap("lightbox-cell-${savedCapture.baseName}")
        ui.waitUntil(15_000) { ui.onNodeWithTag("lightbox-review-${savedCapture.baseName}").fetchSemanticsNode().config[GalleryImageSize].width>0 }
        ui.onNodeWithTag("lightbox-share-photo").assertIsDisplayed()
        shot("10-virtual-capture-reviewed")
        back();ui.waitUntil(5_000) { ui.onAllNodesWithTag("lightbox-pager").fetchSemanticsNodes().isEmpty() };back()
        ui.waitUntil(10_000) { ui.onNodeWithTag("rangefinder-shutter").isDisplayed() }
        assertEquals(rect,ui.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot)
        File(folder,"virtual-capture.txt").writeText("Virtual Camera2, not Pixel: ${savedCapture.baseName}, ${options.outWidth}x${options.outHeight}, ${savedCapture.frameCount} frames, test-observed save latency ${duration}ms. Not a photographic quality or physical latency result.\n")
    }

    @Test(timeout = 120_000) fun deletedRestoredReviewReturnsToShootingWithOneBack() {
        review()
        context.contentResolver.delete(created.removeAt(0),null,null)
        scenario.recreate();ui.waitForIdle()
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Close gallery").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("lightbox-review-$name").assertDoesNotExist()
        back();ui.waitForIdle()
        ui.onNodeWithContentDescription("Close gallery").assertDoesNotExist()
        ui.onNodeWithTag("rangefinder-shutter").assertIsDisplayed()
    }

}
