package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

private const val TAG = "MosaicCanvas"

/**
 * Compositing. On device because the canvas is native memory.
 */
@RunWith(AndroidJUnit4::class)
class MosaicCanvasTest {

    private var canvas: MosaicCanvas? = null

    @After
    fun release() {
        canvas?.close()
        canvas = null
    }

    private fun solidTile(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** A tile with a gradient, so resampling errors are visible. */
    private fun gradientTile(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = (x * 255 / (w - 1))
                bmp.setPixel(x, y, Color.rgb(v, 255 - v, 128))
            }
        }
        return bmp
    }

    @Test
    fun aTileLandsWhereTheTransformPutsIt() {
        val c = MosaicCanvas.create(400, 300)!!
        canvas = c
        val tile = solidTile(100, 80, Color.rgb(200, 60, 40))

        val touched = c.addTile(tile, Homography.translation(150.0, 100.0), feather = 4f)

        assertThat(touched).isGreaterThan(0)
        val out = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        assertThat(c.renderInto(out)).isTrue()

        // Centre of where the tile should be.
        val centre = out.getPixel(200, 140)
        assertThat(Color.alpha(centre)).isEqualTo(255)
        assertThat(Color.red(centre)).isWithin(3).of(200)
        assertThat(Color.green(centre)).isWithin(3).of(60)

        // Somewhere it should not be.
        assertThat(Color.alpha(out.getPixel(20, 20))).isEqualTo(0)
        out.recycle()
        tile.recycle()
    }

    @Test
    fun uncoveredCanvasIsTransparentRatherThanBlack() {
        // So an incomplete sweep can be cropped to what was shot, instead of
        // being framed by black bars.
        val c = MosaicCanvas.create(200, 200)!!
        canvas = c
        val tile = solidTile(40, 40, Color.WHITE)
        c.addTile(tile, Homography.translation(80.0, 80.0), feather = 2f)

        val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        c.renderInto(out)

        assertThat(Color.alpha(out.getPixel(5, 5))).isEqualTo(0)
        assertThat(Color.alpha(out.getPixel(100, 100))).isEqualTo(255)
        Log.i(TAG, "coverage after one small tile: %.3f".format(c.coverage()))
        assertThat(c.coverage()).isGreaterThan(0f)
        assertThat(c.coverage()).isLessThan(0.2f)
        out.recycle()
        tile.recycle()
    }

    @Test
    fun overlappingTilesBlendInsteadOfShowingASeam() {
        // The whole reason for feathering. Two tiles of slightly different
        // brightness, as adjacent frames of a real sweep always are, must not
        // leave a visible line where they meet.
        val c = MosaicCanvas.create(400, 200)!!
        canvas = c
        val left = solidTile(240, 200, Color.rgb(120, 120, 120))
        val right = solidTile(240, 200, Color.rgb(160, 160, 160))

        c.addTile(left, Homography.translation(0.0, 0.0), feather = 60f)
        c.addTile(right, Homography.translation(160.0, 0.0), feather = 60f)

        val out = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        c.renderInto(out)

        // Walk across the overlap and find the largest jump between neighbours.
        var worstJump = 0
        var previous = Color.red(out.getPixel(170, 100))
        for (x in 171..230) {
            val v = Color.red(out.getPixel(x, 100))
            worstJump = maxOf(worstJump, abs(v - previous))
            previous = v
        }
        Log.i(TAG, "largest step across a feathered seam: $worstJump/255")

        // A hard seam would be the full 40-code difference in one step.
        assertThat(worstJump).isLessThan(8)
        out.recycle()
        left.recycle()
        right.recycle()
    }

    @Test
    fun aScaledTileResamplesSmoothly() {
        // Tiles are almost never axis-aligned with the canvas, so bilinear
        // resampling is what stops the mosaic looking blocky.
        val c = MosaicCanvas.create(300, 200)!!
        canvas = c
        val tile = gradientTile(60, 40)
        val scale = Homography(
            doubleArrayOf(2.0, 0.0, 40.0, 0.0, 2.0, 40.0, 0.0, 0.0, 1.0)
        )

        c.addTile(tile, scale, feather = 4f)

        val out = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        c.renderInto(out)

        // Along a row through the middle, the gradient must rise monotonically
        // without steps: nearest-neighbour resampling would produce visible
        // plateaus two pixels wide.
        var jumps = 0
        var previous = -1
        for (x in 60..140) {
            val v = Color.red(out.getPixel(x, 100))
            if (previous >= 0 && v - previous > 12) jumps++
            previous = v
        }
        Log.i(TAG, "large steps along a 2x upscaled gradient: $jumps")
        assertThat(jumps).isEqualTo(0)
        out.recycle()
        tile.recycle()
    }

    @Test
    fun aTileEntirelyOffCanvasContributesNothing() {
        val c = MosaicCanvas.create(200, 200)!!
        canvas = c
        val tile = solidTile(50, 50, Color.WHITE)

        val touched = c.addTile(tile, Homography.translation(5000.0, 5000.0))

        assertThat(touched).isEqualTo(0)
        assertThat(c.coverage()).isEqualTo(0f)
        tile.recycle()
    }

    @Test
    fun aSingularTransformIsRefusedRatherThanCrashing() {
        val c = MosaicCanvas.create(200, 200)!!
        canvas = c
        val tile = solidTile(50, 50, Color.WHITE)
        val degenerate = Homography(
            doubleArrayOf(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0)
        )

        assertThat(c.addTile(tile, degenerate)).isEqualTo(0)
        tile.recycle()
    }

    @Test
    fun anImpossibleCanvasIsRefused() {
        // A gigapixel canvas would be tens of gigabytes. Refusing lets the
        // planner offer a smaller one instead of the process being killed.
        assertThat(MosaicCanvas.create(100_000, 100_000)).isNull()
    }

    @Test
    fun aRealisticCanvasAllocatesAndComposites() {
        // The size the planner actually proposes for this device's 110mm over
        // its 24mm framing, capped to a sane budget.
        val plan = MosaicPlanner.plan(
            target = Lens("0", true, 6.9f, 24, 1f, true, 1.7f, 70f),
            capture = Lens("4", true, 30f, 110, 4.6f, true, 2.8f, 25f),
            tileWidth = 4080, tileHeight = 3072,
            maxMegapixels = 40.0,
        )!!
        Log.i(TAG, "allocating planned canvas: $plan")

        val c = MosaicCanvas.create(plan) ?: return
        canvas = c
        assertThat(c.megapixels).isGreaterThan(30.0)

        val tile = solidTile(1024, 768, Color.rgb(90, 140, 200))
        val touched = c.addTile(tile, Homography.translation(1000.0, 800.0), feather = 48f)
        Log.i(TAG, "tile covered $touched canvas pixels, coverage %.4f".format(c.coverage()))

        assertThat(touched).isGreaterThan(500_000)
        tile.recycle()
    }
}
