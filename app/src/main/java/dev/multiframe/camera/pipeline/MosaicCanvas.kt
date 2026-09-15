package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.util.Log

private const val TAG = "Multiframe"

/**
 * The surface a mosaic is assembled on.
 *
 * Native for the same reason the raw ring is: an 80 megapixel canvas is 610 MB
 * against a 256 MB managed heap cap, and the uncapped pairing on this device
 * would be 2 GB. Tiles are projected in through their registered transform and
 * blended; nothing large ever crosses into Java.
 */
class MosaicCanvas private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
) : AutoCloseable {

    private var closed = false

    /** Set once the accumulator has been collapsed into pixels and written. */
    private var consumed = false

    val megapixels: Double get() = width.toDouble() * height / 1_000_000.0

    /** What a save actually put on disk. */
    data class Written(val width: Int, val height: Int) {
        val megapixels: Double get() = width.toDouble() * height / 1_000_000.0
    }

    /**
     * Projects one developed tile onto the canvas.
     *
     * [transform] maps tile coordinates to canvas coordinates and comes from
     * [FeatureMatcher.register]. [feather] is how many pixels the tile's
     * contribution ramps over at its edge; without it every tile boundary is a
     * visible line, because two frames never agree exactly.
     *
     * Returns how many canvas pixels the tile reached, which is zero when it
     * falls outside the canvas entirely.
     */
    fun addTile(tile: Bitmap, transform: Homography, feather: Float = 64f): Long =
        addTile(tile, transform, floatArrayOf(feather, feather, feather))

    /**
     * The same, blending in bands.
     *
     * [feathers] is one ramp width per frequency band, narrowest first, in
     * tile pixels: fine detail crosses the seam over the first, and the broad
     * differences between tiles -- exposure, colour -- over the last. See the
     * note on the blend in Mosaic.cpp, and [BLEND_FEATHERS] for the widths
     * that ship. On a canvas created with one band only the first is used.
     */
    fun addTile(tile: Bitmap, transform: Homography, feathers: FloatArray): Long {
        if (closed) return 0
        return nAddTile(handle, tile, transform.m, feathers)
    }

    /**
     * One block of a tile matched against the canvas: where [transform] put
     * the block's centre and where the canvas found it, canvas coordinates,
     * with the correlation that found it. See [refine].
     */
    data class BlockMatch(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val ncc: Float)

    /**
     * Measures how far a tile placed by [transform] sits from what the
     * canvas already holds where the two overlap: the canvas's own pixels
     * as the measurement of where the tile belongs. Empty where the tile
     * overlaps nothing drawn, or nothing the two agree on. The correction
     * is fitted by [MosaicPlacement.correctionFor]; see the note on
     * refinement in Mosaic.cpp.
     */
    fun refine(tile: Bitmap, transform: Homography): List<BlockMatch> {
        if (closed || consumed) return emptyList()
        val out = FloatArray(MAX_REFINE_BLOCKS * 5)
        val n = nRefineTile(handle, tile, transform.m, out)
        return (0 until n).map { i ->
            BlockMatch(out[i * 5], out[i * 5 + 1], out[i * 5 + 2], out[i * 5 + 3], out[i * 5 + 4])
        }
    }

    /** Fraction of the canvas any tile has covered, for reporting sweep progress. */
    fun coverage(): Float = if (closed) 0f else nCoverage(handle)

    /**
     * Renders into a bitmap of exactly the canvas size.
     *
     * Uncovered pixels come out transparent rather than black, so an incomplete
     * sweep can be cropped to what was actually shot.
     */
    fun renderInto(bitmap: Bitmap): Boolean {
        if (closed || consumed) return false
        return nRenderInto(handle, bitmap)
    }

    /**
     * Writes the covered region straight down [fd] as a JPEG, consuming the
     * canvas.
     *
     * The alternative was a Bitmap of the whole canvas: 320 MB at 80 megapixels
     * on top of the 610 MB the canvas already holds, on a phone that had 1.4 GB
     * free -- which is why [MosaicSession] carried an `OutOfMemoryError` branch
     * that returned no photograph at all. The pixels are collapsed in place
     * into the canvas's own memory, four bytes a pixel over the accumulator's
     * eight, and the compressor writes to the descriptor in pieces. Nothing of
     * image size is allocated.
     *
     * Returns what was actually written, which is the covered bounding box
     * rather than the canvas: a hand-held mosaic does not cover a rectangle, and
     * JPEG has no alpha to keep the rest honest.
     *
     * The canvas is finished afterwards. Saving is the last thing a mosaic does.
     */
    fun compressTo(fd: Int, quality: Int = QUALITY): Written? {
        if (closed || consumed) return null
        val packed = nCompressTo(handle, fd, quality)
        // Zero means nothing had been swept, and the canvas was never touched;
        // a mosaic that has not covered anything yet is not a finished one.
        consumed = packed != 0L
        if (packed <= 0L) return null
        return Written((packed ushr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())
    }

    override fun close() {
        if (closed) return
        closed = true
        nDestroy(handle)
        handle = 0
    }

    private external fun nCreate(width: Int, height: Int, bands: Int): Long
    private external fun nDestroy(h: Long)
    private external fun nAddTile(h: Long, tile: Bitmap, transform: DoubleArray, feathers: FloatArray): Long
    private external fun nRefineTile(h: Long, tile: Bitmap, transform: DoubleArray, out: FloatArray): Int
    private external fun nCoverage(h: Long): Float
    private external fun nRenderInto(h: Long, bitmap: Bitmap): Boolean
    private external fun nCompressTo(h: Long, fd: Int, quality: Int): Long

    companion object {
        /**
         * Quality for a mosaic write.
         *
         * The same number [ImageSaver] uses. This is the same photograph as any
         * other the app produces, only written by a different route.
         */
        const val QUALITY = 95

        /** Frequency bands the blend keeps apart; see Mosaic.cpp. One is the old single feather. */
        const val BANDS = 3

        /** The most blocks a refinement returns: a four-by-four grid over the overlap. */
        const val MAX_REFINE_BLOCKS = 16

        /**
         * The feathers that ship, narrowest first, in tile pixels.
         *
         * Twenty-four pixels for the detail band's ramp, which takes the
         * tile's outermost pixels to nothing so a registration error at its
         * border is hidden; the detail band's *mask* is that ramp times a
         * steep hand-off on depth into the tile, so that across an overlap
         * the deeper tile owns the texture rather than the two being
         * averaged into a double image -- see `Mask` in Mosaic.h. A hundred
         * and sixty for the middle band and six hundred for the broadest,
         * which is most of a 35% overlap on a 4080-wide tile: an exposure
         * step between two tiles is spread across the width of the overlap.
         */
        val BLEND_FEATHERS = floatArrayOf(24f, 160f, 600f)

        /**
         * Allocates a canvas, or null when the device cannot afford one.
         *
         * Null is a real answer rather than a failure: the plan can be redrawn
         * against a smaller cap and offered again.
         */
        fun create(width: Int, height: Int, bands: Int = BANDS): MosaicCanvas? {
            if (!NativeMerge.isAvailable()) return null
            val h = MosaicCanvas(0L, width, height).nCreate(width, height, bands)
            if (h == 0L) {
                Log.w(TAG, "mosaic canvas ${width}x$height could not be allocated")
                return null
            }
            return MosaicCanvas(h, width, height)
        }

        fun create(plan: MosaicPlan): MosaicCanvas? =
            create(plan.canvasWidth, plan.canvasHeight)
    }
}
