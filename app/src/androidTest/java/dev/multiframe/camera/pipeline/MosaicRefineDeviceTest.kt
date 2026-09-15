package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/**
 * The tile refinement: a tile placed wrongly on a canvas that already holds
 * its neighbour is measured against that neighbour, and the measurement is
 * the error.
 *
 * The scene is one textured field; the left tile is drawn where it belongs,
 * the right tile is offered at a placement some way off, and the refinement
 * has to say by how much. Canvas coordinates throughout, as the canvas keeps
 * them.
 */
@RunWith(AndroidJUnit4::class)
class MosaicRefineDeviceTest {

    private val tileW = 1024
    private val tileH = 768
    private val overlapStart = 640
    private val canvasW = overlapStart + tileW + 400
    private val canvasH = tileH + 400

    /** A hash of a lattice point, in -1..1: the value noise below is built on it. */
    private fun hash(ix: Int, iy: Int): Float {
        var h = ix * 374761393 + iy * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /** Smooth value noise at a lattice spacing of [cell] canvas pixels. */
    private fun noise(x: Float, y: Float, cell: Float): Float {
        val fx = x / cell
        val fy = y / cell
        val ix = kotlin.math.floor(fx).toInt()
        val iy = kotlin.math.floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val top = hash(ix, iy) * (1f - sx) + hash(ix + 1, iy) * sx
        val bottom = hash(ix, iy + 1) * (1f - sx) + hash(ix + 1, iy + 1) * sx
        return top * (1f - sy) + bottom * sy
    }

    /**
     * Texture at several scales and nowhere periodic, like a wall or a
     * tree: a periodic scene has many places that look alike, and a
     * refinement that finds the wrong one is the test failing to ask
     * the right question.
     */
    private fun scene(x: Float, y: Float): Float {
        val texture = 40f * noise(x, y, 160f) + 28f * noise(x, y, 41f) + 18f * noise(x, y, 11f) + 10f * noise(x, y, 3f)
        return 120f + texture
    }

    private fun tile(ox: Float, oy: Float): Bitmap {
        val bmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val px = IntArray(tileW * tileH)
        for (j in 0 until tileH) for (i in 0 until tileW) {
            val v = scene(ox + i, oy + j).toInt().coerceIn(0, 255)
            px[j * tileW + i] = Color.rgb(v, v, v)
        }
        bmp.setPixels(px, 0, tileW, 0, 0, tileW, tileH)
        return bmp
    }

    private fun refineAt(errorX: Double, errorY: Double): MosaicPlacement.Correction? {
        assumeTrue(NativeMerge.isAvailable())
        val canvas = MosaicCanvas.create(canvasW, canvasH, 3) ?: return null
        return canvas.use { c ->
            val left = tile(200f, 200f)
            val right = tile(200f + overlapStart, 200f)
            try {
                assertThat(c.addTile(left, Homography.translation(200.0, 200.0), MosaicCanvas.BLEND_FEATHERS)).isGreaterThan(0L)
                // The right tile belongs at (200 + overlapStart, 200); it is offered off by the error.
                val wrong = Homography.translation(200.0 + overlapStart + errorX, 200.0 + errorY)
                val t0 = System.currentTimeMillis()
                val matches = c.refine(right, wrong)
                val ms = System.currentTimeMillis() - t0
                val corners = listOf(
                    wrong.apply(0f, 0f), wrong.apply(tileW.toFloat(), 0f),
                    wrong.apply(0f, tileH.toFloat()), wrong.apply(tileW.toFloat(), tileH.toFloat()),
                )
                val correction = MosaicPlacement.correctionFor(matches, corners)
                android.util.Log.i("Multiframe", "refine test: error (%.1f, %.1f) -> %d blocks in %dms: %s".format(
                    errorX, errorY, matches.size, ms, correction))
                correction
            } finally {
                left.recycle(); right.recycle()
            }
        }
    }

    /** How far the corrected placement's centre is from where the tile truly belongs. */
    private fun residualOf(correction: MosaicPlacement.Correction?, errorX: Double, errorY: Double): Float {
        assertThat(correction).isNotNull()
        val wrong = Homography.translation(200.0 + overlapStart + errorX, 200.0 + errorY)
        val corrected = correction!!.transform.times(wrong)
        val truth = Homography.translation(200.0 + overlapStart, 200.0)
        var worst = 0f
        for ((x, y) in listOf(0f to 0f, tileW.toFloat() to 0f, 0f to tileH.toFloat(), tileW.toFloat() to tileH.toFloat())) {
            val a = corrected.apply(x, y)
            val b = truth.apply(x, y)
            worst = maxOf(worst, hypot(a[0] - b[0], a[1] - b[1]))
        }
        return worst
    }

    @Test
    fun aTilePlacedAHundredPixelsOffIsPutBackToWithinAPixel() {
        val residual = residualOf(refineAt(97.0, -63.0), 97.0, -63.0)
        assertThat(residual).isLessThan(1.5f)
    }

    @Test
    fun aTilePlacedAQuarterOfATileOffIsStillFound() {
        val residual = residualOf(refineAt(-240.0, 150.0), -240.0, 150.0)
        assertThat(residual).isLessThan(2f)
    }

    @Test
    fun aTilePlacedRightIsLeftAlone() {
        val correction = refineAt(0.0, 0.0)
        // Null, or a correction under a pixel: nothing that would move it.
        if (correction != null) assertThat(correction.shiftPx).isLessThan(1f)
    }

    @Test
    fun theFirstTileHasNothingToRefineAgainst() {
        assumeTrue(NativeMerge.isAvailable())
        MosaicCanvas.create(canvasW, canvasH, 3)!!.use { c ->
            val t = tile(200f, 200f)
            try {
                assertThat(c.refine(t, Homography.translation(200.0, 200.0))).isEmpty()
            } finally {
                t.recycle()
            }
        }
    }
}
