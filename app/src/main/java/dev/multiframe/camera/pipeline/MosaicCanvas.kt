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
     * Sets the noise floor: how much of the finest band is given up where
     * tiles overlap in the shadows, 0 to 1. See [detailAttenuationFor] for
     * the number a mosaic's ISO earns, and Mosaic.cpp's note on the noise
     * floor for what it does. Zero, the default, changes nothing.
     */
    fun setNoiseFloor(detailAttenuation: Float) {
        if (closed) return
        nSetNoiseFloor(handle, detailAttenuation.coerceIn(0f, 1f))
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
    private external fun nSetNoiseFloor(h: Long, attenuation: Float)
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
         * The noise floor's reach with the ISO: nothing at [NOISE_ISO_FROM]
         * and under, the whole [MAX_DETAIL_ATTENUATION] from [NOISE_ISO_FULL]
         * up, a straight ramp between. Sixteen hundred is where the fifth
         * field test's cross-hatch showed; eight hundred is where the
         * develop's own sharpening starts to fade for the same noise
         * (`DevelopParams.LOW_ISO`), and the two agree about where a
         * telephoto tile stops being clean.
         */
        const val NOISE_ISO_FROM = 800
        const val NOISE_ISO_FULL = 1600

        /**
         * Half the finest band, at most: the shadow of an overlap keeps its
         * 4x4 mean and half its detail, which at ISO 3200 is half its noise
         * and, at the tile scale a mosaic lands at, none of its picture.
         * More than half and the overlaps read as soft bands; the field
         * test's complaint was weave, not grain.
         */
        const val MAX_DETAIL_ATTENUATION = 0.5f

        /** The noise floor a mosaic shot at [iso] earns; zero with no ISO known. Pure. */
        fun detailAttenuationFor(iso: Int?): Float {
            if (iso == null || iso <= NOISE_ISO_FROM) return 0f
            val t = ((iso - NOISE_ISO_FROM).toFloat() / (NOISE_ISO_FULL - NOISE_ISO_FROM)).coerceIn(0f, 1f)
            return MAX_DETAIL_ATTENUATION * t
        }

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
