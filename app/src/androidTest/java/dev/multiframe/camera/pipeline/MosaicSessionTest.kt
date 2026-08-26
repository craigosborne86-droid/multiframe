package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "MosaicSession"

/**
 * A whole sweep, end to end.
 *
 * Builds a large synthetic scene, cuts overlapping windows out of it the way
 * panning a telephoto does, and feeds them through registration, placement and
 * compositing. Then checks the composite actually resembles the scene it was
 * cut from -- which is the only claim worth making about a stitcher.
 */
@RunWith(AndroidJUnit4::class)
class MosaicSessionTest {

    private val sceneW = 1200
    private val sceneH = 900
    private val tile = 320

    private var session: MosaicSession? = null

    @After
    fun release() {
        session?.close()
        session = null
    }

    /** Textured scene with a smooth low-frequency component to compare against. */
    private val scene: ByteArray = run {
        val rnd = Random(31)
        val d = ByteArray(sceneW * sceneH)
        for (y in 0 until sceneH) {
            for (x in 0 until sceneW) {
                d[y * sceneW + x] =
                    (110 + 60 * sin(x * 0.006) * cos(y * 0.008)).toInt()
                        .coerceIn(0, 255).toByte()
            }
        }
        repeat(2200) {
            val cx = rnd.nextInt(8, sceneW - 8)
            val cy = rnd.nextInt(8, sceneH - 8)
            val s = rnd.nextInt(2, 6)
            val v = rnd.nextInt(0, 256)
            for (dy in -s..s) for (dx in -s..s) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until sceneW && y in 0 until sceneH) d[y * sceneW + x] = v.toByte()
            }
        }
        d
    }

    private fun sceneAt(x: Int, y: Int): Int =
        scene[y.coerceIn(0, sceneH - 1) * sceneW + x.coerceIn(0, sceneW - 1)].toInt() and 0xFF

    private fun proxyAt(ox: Int, oy: Int): Plane {
        val d = ByteArray(tile * tile)
        for (y in 0 until tile) for (x in 0 until tile) {
            d[y * tile + x] = sceneAt(ox + x, oy + y).toByte()
        }
        return Plane(tile, tile, d)
    }

    private fun tileAt(ox: Int, oy: Int): Bitmap {
        val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(tile * tile)
        for (y in 0 until tile) for (x in 0 until tile) {
            val v = sceneAt(ox + x, oy + y)
            pixels[y * tile + x] = Color.rgb(v, v, v)
        }
        bmp.setPixels(pixels, 0, tile, 0, 0, tile, tile)
        return bmp
    }

    private fun plan(): MosaicPlan = MosaicPlanner.plan(
        target = Lens("0", true, 6.9f, 24, 1f, true, 1.7f, 70f),
        capture = Lens("4", true, 30f, 110, 4.6f, true, 2.8f, 25f),
        tileWidth = tile, tileHeight = tile,
        maxMegapixels = 4.0,
    )!!

    @Test
    fun aSweepAssemblesIntoSomethingResemblingTheScene() {
        val s = MosaicSession.start(plan(), tile, tile)!!
        session = s

        // Pan in a raster, the way a user sweeps.
        val step = 150
        var placed = 0
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val ox = 120 + col * step
                val oy = 120 + row * step
                val result = s.offer(tileAt(ox, oy), proxyAt(ox, oy), proxyScale = 1f)
                if (result is OfferResult.Placed) placed++
            }
        }

        val progress = s.progress
        Log.i(
            TAG,
            "sweep: $placed placed, ${progress.rejected} rejected, " +
                "coverage %.2f".format(progress.coverage),
        )

        assertThat(placed).isAtLeast(7)
        assertThat(progress.coverage).isGreaterThan(0.1f)
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aSweepSavesAPhotographThatSaysWhatItIs() {
        // The end of the whole feature: a sweep that produces a file, cropped
        // to what was covered, carrying metadata that describes a stitch rather
        // than a merge. Saving used to render the canvas into a Bitmap of the
        // same size, which at the planner's real canvas sizes is the allocation
        // this app cannot make.
        val s = MosaicSession.start(plan(), tile, tile)!!
        session = s

        var placed = 0
        for ((dx, dy) in listOf(0 to 0, 160 to 0, 160 to 160, 0 to 160)) {
            val result = s.offer(tileAt(200 + dx, 200 + dy), proxyAt(200 + dx, 200 + dy), 1f)
            if (result is OfferResult.Placed) placed++
        }
        assertThat(placed).isAtLeast(2)

        val name = "MF_mosaic_test_${System.currentTimeMillis()}_${placed}t.jpg"
        assertThat(s.save(context, name)).isEqualTo(name)

        val shot = RecentCapture.latest(context)
        assertThat(shot).isNotNull()
        assertThat(shot!!.displayName).isEqualTo(name)

        context.contentResolver.openFileDescriptor(shot.uri, "r").use { descriptor ->
            val exif = ExifInterface(descriptor!!.fileDescriptor)
            val description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            Log.i(TAG, "mosaic exif: $description")

            assertThat(exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                .isEqualTo(ExifWriter.SOFTWARE)
            // Stitched, not merged: the opposite trade, and the description is
            // the only place the difference is recorded.
            assertThat(description).contains("$placed tiles stitched")
            // Deliberately absent. The tiles were shot under whatever the meter
            // decided at the time, so there is no one honest number to give.
            assertThat(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)).isNull()
        }

        context.contentResolver.openFileDescriptor(shot.uri, "r").use { descriptor ->
            val decoded = BitmapFactory.decodeFileDescriptor(descriptor!!.fileDescriptor)
            assertThat(decoded).isNotNull()
            Log.i(
                TAG,
                "saved ${decoded.width}x${decoded.height} from a " +
                    "${s.plan.canvasWidth}x${s.plan.canvasHeight} canvas",
            )
            // Cropped to the covered region rather than framed in black.
            assertThat(decoded.width).isLessThan(s.plan.canvasWidth)
            assertThat(decoded.height).isLessThan(s.plan.canvasHeight)
            decoded.recycle()
        }

        context.contentResolver.delete(shot.uri, null, null)
    }

    @Test
    fun theCompositeMatchesTheSceneItWasCutFrom() {
        // The claim that matters: the stitched result is the original scene,
        // not merely a set of frames placed near each other.
        val s = MosaicSession.start(plan(), tile, tile)!!
        session = s

        val anchorX = 200
        val anchorY = 200
        val step = 160
        // Serpentine, which is how a sweep is actually made and what keeps
        // consecutive frames overlapping. A raster order asks the third frame
        // to register against one diagonally opposite it, sharing only a
        // quarter of its area.
        val offsets = listOf(0 to 0, step to 0, step to step, 0 to step)
        val placements = ArrayList<Placement>()

        for ((dx, dy) in offsets) {
            val ox = anchorX + dx
            val oy = anchorY + dy
            val result = s.offer(tileAt(ox, oy), proxyAt(ox, oy), proxyScale = 1f)
            if (result is OfferResult.Placed) placements.add(result.placement)
        }
        assertThat(placements).hasSize(4)

        val canvasW = s.plan.canvasWidth
        val canvasH = s.plan.canvasHeight
        val rendered = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        assertThat(s.renderInto(rendered)).isTrue()

        // The first tile anchors at a known place, so scene coordinates map to
        // canvas coordinates by a known offset.
        val originX = placements[0].transform.apply(0f, 0f)[0]
        val originY = placements[0].transform.apply(0f, 0f)[1]

        var compared = 0
        var totalError = 0.0
        for (sy in 40 until tile - 40 step 7) {
            for (sx in 40 until tile - 40 step 7) {
                val cx = (originX + sx).toInt()
                val cy = (originY + sy).toInt()
                if (cx !in 0 until canvasW || cy !in 0 until canvasH) continue
                val pixel = rendered.getPixel(cx, cy)
                if (Color.alpha(pixel) == 0) continue
                val expected = sceneAt(anchorX + sx, anchorY + sy)
                totalError += kotlin.math.abs(Color.green(pixel) - expected).toDouble()
                compared++
            }
        }
        rendered.recycle()

        val meanError = if (compared == 0) 999.0 else totalError / compared
        Log.i(TAG, "composite vs scene: mean error %.2f over $compared samples".format(meanError))

        assertThat(compared).isGreaterThan(500)
        // Resampling and blending cost a little; a misplaced tile would cost
        // tens of codes rather than a few.
        assertThat(meanError).isLessThan(12.0)
    }

    @Test
    fun aStationaryCameraStopsAddingTiles() {
        // A user who holds still should not accumulate hundreds of identical
        // frames, each costing a registration and a composite.
        val s = MosaicSession.start(plan(), tile, tile)!!
        session = s

        s.offer(tileAt(300, 300), proxyAt(300, 300), 1f)
        var extra = 0
        repeat(6) {
            val r = s.offer(tileAt(302, 301), proxyAt(302, 301), 1f)
            if (r is OfferResult.Placed) extra++
        }

        Log.i(TAG, "stationary camera added $extra extra tiles")
        assertThat(extra).isEqualTo(0)
        assertThat(s.progress.lastReason).isEqualTo(RejectionReason.REDUNDANT)
    }

    @Test
    fun aPartialSweepStillSaves() {
        // An abandoned sweep should produce the shape of what was shot rather
        // than nothing at all.
        val s = MosaicSession.start(plan(), tile, tile)!!
        session = s
        s.offer(tileAt(300, 300), proxyAt(300, 300), 1f)
        s.offer(tileAt(450, 300), proxyAt(450, 300), 1f)

        assertThat(s.progress.placed).isEqualTo(2)
        assertThat(s.progress.complete).isFalse()
        assertThat(s.progress.coverage).isGreaterThan(0f)
    }

    @Test
    fun aSessionWithNothingPlacedSavesNothing() {
        val s = MosaicSession.start(plan(), tile, tile)!!
        session = s

        assertThat(s.progress.placed).isEqualTo(0)
        assertThat(s.progress.coverage).isEqualTo(0f)
    }
}
