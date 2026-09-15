package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "MosaicBlend"

/**
 * The three-band blend against the one-feather blend it replaced.
 *
 * ### The null first
 *
 * With one tile the three-band output must be the one-band output to the
 * byte: every level's two canvases hold the tile's own Gaussian level, so the
 * corrections cancel exactly. Until that holds, a seam measurement says
 * nothing about the blend and everything about the arithmetic.
 *
 * ### Then the seam
 *
 * Two tiles of one textured scene, the right one a quarter stop brighter and
 * placed a pixel off where its content says it is -- the two disagreements a
 * real sweep always has. One feather has to choose: wide, and the step in
 * level is spread but the misregistered texture is averaged into a double
 * image; narrow, and the texture is kept but the step shows. The bands keep
 * the texture *and* spread the step, which is what both measurements below
 * say together.
 */
@RunWith(AndroidJUnit4::class)
class MosaicBlendDeviceTest {

    private val tileW = 480
    private val tileH = 320
    private val overlapStart = 300  // the right tile starts here on the canvas
    private val canvasW = overlapStart + tileW
    private val canvasH = tileH

    /** Scene luminance at a canvas position: a gradient with texture at two scales. */
    private fun scene(x: Float, y: Float): Float {
        val gx = x / canvasW
        val texture = 18f * kotlin.math.sin(x * 0.37f + y * 0.21f) +
            12f * kotlin.math.sin(x * 0.09f - y * 0.13f) +
            8f * kotlin.math.sin(x * 1.1f + y * 0.7f)
        return 90f + 60f * gx + texture
    }

    /** A tile whose pixel (i, j) shows the scene at canvas (ox + i + shift, oy + j), times [gain]. */
    private fun tile(ox: Int, oy: Int, gain: Float, shift: Float): Bitmap {
        val bmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val px = IntArray(tileW * tileH)
        for (j in 0 until tileH) {
            for (i in 0 until tileW) {
                val v = (scene(ox + i + shift, (oy + j).toFloat()) * gain).toInt().coerceIn(0, 255)
                px[j * tileW + i] = Color.rgb(v, v, v)
            }
        }
        bmp.setPixels(px, 0, tileW, 0, 0, tileW, tileH)
        return bmp
    }

    private fun render(c: MosaicCanvas): Bitmap {
        val out = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
        assertThat(c.renderInto(out)).isTrue()
        return out
    }

    private fun composite(bands: Int, feathers: FloatArray, gain: Float, shift: Float): Bitmap {
        val c = MosaicCanvas.create(canvasW, canvasH, bands)!!
        return c.use {
            val left = tile(0, 0, 1f, 0f)
            val right = tile(overlapStart, 0, gain, shift)
            assertThat(it.addTile(left, Homography.translation(0.0, 0.0), feathers)).isGreaterThan(0L)
            assertThat(it.addTile(right, Homography.translation(overlapStart.toDouble(), 0.0), feathers)).isGreaterThan(0L)
            left.recycle(); right.recycle()
            render(it)
        }
    }

    /** Luminance of a row, one value per column. */
    private fun row(bmp: Bitmap, y: Int): FloatArray = FloatArray(bmp.width) { x -> Color.red(bmp.getPixel(x, y)).toFloat() }

    /** The mean over rows of the column profile, which is the level; texture averages out over rows. */
    private fun columnMeans(bmp: Bitmap): FloatArray {
        val out = FloatArray(bmp.width)
        for (y in 0 until bmp.height) {
            val r = row(bmp, y)
            for (x in r.indices) out[x] += r[x] / bmp.height
        }
        return out
    }

    /** Largest change in the level between columns eight apart, across the overlap. */
    private fun steepestStep(bmp: Bitmap): Float {
        val means = columnMeans(bmp)
        // Smoothed over sixteen columns so texture does not read as a step.
        val smooth = FloatArray(means.size) { x ->
            var s = 0f; var n = 0
            for (d in -8..8) { val i = x + d; if (i in means.indices) { s += means[i]; n++ } }
            s / n
        }
        var worst = 0f
        for (x in overlapStart + 8 until tileW - 8) worst = maxOf(worst, abs(smooth[x] - smooth[x - 8]))
        return worst
    }

    /** RMS of the fine texture in the middle of the overlap: what a double image loses. */
    private fun textureContrast(bmp: Bitmap): Float {
        val x0 = overlapStart + 60
        val x1 = tileW - 60
        var sum = 0.0
        var n = 0
        for (y in 40 until canvasH - 40 step 3) {
            val r = row(bmp, y)
            // High-pass: the pixel less the mean of its eight-column neighbourhood.
            for (x in x0 until x1) {
                var m = 0f
                for (d in -4..4) m += r[x + d]
                val hp = r[x] - m / 9f
                sum += hp * hp; n++
            }
        }
        return sqrt(sum / n).toFloat()
    }

    @Test
    fun oneTileIsTheSameToTheByteInOneBandAndThree() {
        val one = MosaicCanvas.create(canvasW, canvasH, 1)!!
        val three = MosaicCanvas.create(canvasW, canvasH, 3)!!
        try {
            val t = tile(100, 0, 1f, 0f)
            val h = Homography.translation(100.0, 0.0)
            assertThat(one.addTile(t, h, MosaicCanvas.BLEND_FEATHERS)).isGreaterThan(0L)
            assertThat(three.addTile(t, h, MosaicCanvas.BLEND_FEATHERS)).isGreaterThan(0L)
            t.recycle()
            val a = render(one)
            val b = render(three)
            var differing = 0
            for (y in 0 until canvasH) for (x in 0 until canvasW) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
            }
            Log.i(TAG, "A/A null: $differing of ${canvasW * canvasH} pixels differ between one band and three")
            assertThat(differing).isEqualTo(0)
            a.recycle(); b.recycle()
        } finally {
            one.close(); three.close()
        }
    }

    @Test
    fun theBandsSpreadTheStepAndKeepTheTexture() {
        val gain = 1.19f      // a quarter of a stop
        val shift = 1.0f      // a pixel of registration error
        // Feathers scaled to this tile: 12 / 60 / 160 of 480, as 24 / 160 / 600 of 4080.
        val narrow = floatArrayOf(12f, 12f, 12f)
        val wide = floatArrayOf(160f, 160f, 160f)
        val bands = floatArrayOf(12f, 60f, 160f)

        val oneNarrow = composite(1, narrow, gain, shift)
        val oneWide = composite(1, wide, gain, shift)
        val three = composite(3, bands, gain, shift)
        // The right tile alone, for what the texture's contrast should be.
        val alone = MosaicCanvas.create(canvasW, canvasH, 1)!!.use {
            val t = tile(overlapStart, 0, gain, shift)
            it.addTile(t, Homography.translation(overlapStart.toDouble(), 0.0), narrow)
            t.recycle()
            render(it)
        }

        val stepNarrow = steepestStep(oneNarrow)
        val stepWide = steepestStep(oneWide)
        val stepBands = steepestStep(three)
        val texNarrow = textureContrast(oneNarrow)
        val texWide = textureContrast(oneWide)
        val texBands = textureContrast(three)
        val texAlone = textureContrast(alone)
        Log.i(TAG, "step across the seam (codes per 8 columns): one narrow %.2f, one wide %.2f, three bands %.2f".format(
            stepNarrow, stepWide, stepBands))
        Log.i(TAG, "texture contrast in the overlap (rms codes): alone %.2f, one narrow %.2f, one wide %.2f, three bands %.2f".format(
            texAlone, texNarrow, texWide, texBands))
        DeviceKind.warnIfNotAPhone(TAG)

        // The narrow feather steps; the bands spread the step at least as
        // well as the wide feather did.
        assertThat(stepBands).isLessThan(stepNarrow * 0.6f)
        assertThat(stepBands).isAtMost(stepWide * 1.15f)
        // The wide feather doubles the misregistered texture and loses
        // contrast; the bands keep most of what one tile alone has.
        assertThat(texWide).isLessThan(texAlone * 0.9f)
        assertThat(texBands).isGreaterThan(texWide)
        assertThat(texBands).isGreaterThan(texAlone * 0.85f)

        oneNarrow.recycle(); oneWide.recycle(); three.recycle(); alone.recycle()
    }

    // --- the seam --------------------------------------------------------

    /**
     * A grey tile with a distinct fine feature: a disc of two-pixel stripes at
     * (discX, discY) in tile coordinates, whose mean is the grey around it.
     * Fine, so that it lives in the detail band alone: the coarse bands,
     * which feather by weight, carry nothing of it, and what the picture
     * shows of it is what the seam decided.
     */
    private fun stripedDiscTile(level: Int, discX: Int, discY: Int, radius: Int, amplitude: Int): Bitmap {
        val bmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val px = IntArray(tileW * tileH)
        for (j in 0 until tileH) for (i in 0 until tileW) {
            val d = kotlin.math.hypot((i - discX).toDouble(), (j - discY).toDouble())
            val v = if (d <= radius) (if ((i / 2) % 2 == 0) level + amplitude else level - amplitude) else level
            px[j * tileW + i] = Color.rgb(v, v, v)
        }
        bmp.setPixels(px, 0, tileW, 0, 0, tileW, tileH)
        return bmp
    }

    /**
     * Two textured tiles a pixel apart in the overlap: with the seam the fine
     * texture there is one tile's, not the average of two -- no double image,
     * so its contrast is a single tile's, not eighty-five percent of it.
     */
    @Test
    fun theSeamKeepsTheOverlapsTextureAtOneTilesContrast() {
        val bands = floatArrayOf(12f, 60f, 160f)
        val three = composite(3, bands, gain = 1f, shift = 1.0f)
        val alone = MosaicCanvas.create(canvasW, canvasH, 1)!!.use {
            val t = tile(overlapStart, 0, 1f, 1.0f)
            it.addTile(t, Homography.translation(overlapStart.toDouble(), 0.0), floatArrayOf(12f, 12f, 12f))
            t.recycle()
            render(it)
        }
        val texSeam = textureContrast(three)
        val texAlone = textureContrast(alone)
        Log.i(TAG, "seam: texture contrast in the overlap %.2f against one tile alone %.2f".format(texSeam, texAlone))
        assertThat(texSeam).isGreaterThan(texAlone * 0.97f)
        assertThat(texSeam).isLessThan(texAlone * 1.03f)
        three.recycle(); alone.recycle()
    }

    /**
     * The seam goes where the tiles agree. Two flat grey tiles, the second
     * with a disc of fine stripes inside the overlap straddling the line the
     * depths alone would cut on. A cut through the disc costs its whole
     * edge; a cut around it costs nothing, so the disc comes through whole
     * or not at all -- never as a half-moon.
     */
    @Test
    fun theSeamRoutesAroundWhatTheTilesDisagreeOn() {
        val bands = floatArrayOf(12f, 60f, 160f)
        val c = MosaicCanvas.create(canvasW, canvasH, 3)!!
        // The overlap runs from canvas x = 300 to 480; the equidistant line is
        // at about 390. The disc sits on it, in the right tile only.
        val discCanvasX = 390
        val discCanvasY = canvasH / 2
        val radius = 30
        val out = c.use {
            val left = stripedDiscTile(level = 100, discX = -1000, discY = -1000, radius = 1, amplitude = 0)
            val right = stripedDiscTile(level = 100, discX = discCanvasX - overlapStart, discY = discCanvasY, radius = radius, amplitude = 60)
            assertThat(it.addTile(left, Homography.translation(0.0, 0.0), bands)).isGreaterThan(0L)
            assertThat(it.addTile(right, Homography.translation(overlapStart.toDouble(), 0.0), bands)).isGreaterThan(0L)
            left.recycle(); right.recycle()
            render(it)
        }
        // The stripes' contrast on each side of the equidistant line: a cut
        // along it would leave one side striped and the other flat.
        fun stripeContrast(x0: Int, x1: Int): Float {
            var hi = 0; var lo = 255
            for (y in discCanvasY - 12..discCanvasY + 12) for (x in x0..x1) {
                val v = Color.red(out.getPixel(x, y))
                hi = maxOf(hi, v); lo = minOf(lo, v)
            }
            return (hi - lo).toFloat()
        }
        val leftHalf = stripeContrast(discCanvasX - 22, discCanvasX - 6)
        val rightHalf = stripeContrast(discCanvasX + 6, discCanvasX + 22)
        Log.i(TAG, "seam: stripe contrast left of the line %.0f, right of it %.0f (a cut along the line would leave one side flat)".format(leftHalf, rightHalf))
        // Whole, or absent: both halves striped, or neither.
        val striped = { v: Float -> v > 80f }
        val flat = { v: Float -> v < 20f }
        assertThat((striped(leftHalf) && striped(rightHalf)) || (flat(leftHalf) && flat(rightHalf))).isTrue()
        // And where the tiles agree, the flat grey is exactly one tile's grey.
        assertThat(Color.red(out.getPixel(350, 40))).isWithin(1).of(100)
        out.recycle()
    }
}
