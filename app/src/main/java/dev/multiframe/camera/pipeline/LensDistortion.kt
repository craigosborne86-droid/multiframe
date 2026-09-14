package dev.multiframe.camera.pipeline

import android.util.Log
import kotlin.math.max
import kotlin.math.min

private const val TAG = "LensDistortion"

/**
 * Undoing what the glass did to straight lines.
 *
 * ### The model
 *
 * Camera2 publishes `LENS_DISTORTION` as five Brown-Conrady terms -- three
 * radial, two tangential -- alongside `LENS_INTRINSIC_CALIBRATION`, the pinhole
 * model the lens approximates. Together they say, for any point in an ideal
 * rectilinear image, where the real lens actually put it.
 *
 * ### Why that direction is the convenient one
 *
 * The model runs *undistorted to distorted*, which sounds backwards for a
 * correction and is in fact exactly what a correction needs. Resampling works
 * by asking, for each pixel of the output, where in the input it came from --
 * and since the output is the undistorted image, that question is the model's
 * forward direction. No iteration, no numerical inversion, one evaluation per
 * output pixel.
 *
 * Correcting the other way round -- pushing each input pixel to where it
 * belongs -- would scatter rather than gather, leaving holes wherever the
 * expansion is more than one pixel and requiring a fill pass to hide them.
 *
 * ### What this does not correct
 *
 * Lateral chromatic aberration, which is a *per-channel* difference in radial
 * scale and needs its own estimate off the image data -- see [ChromaticAberration].
 * These coefficients describe the geometry all three channels share.
 */
object LensDistortion {

    /**
     * A lens's geometry: where the optical axis is, how long the lens is in
     * pixels, and how it bends the field.
     *
     * All in the coordinate system of the image being corrected, not of the
     * active array -- see [scaledTo], which is what moves between the two.
     */
    data class Model(
        /** Focal length in pixels, horizontal and vertical. */
        val fx: Float,
        val fy: Float,
        /** Principal point: where the optical axis meets the sensor. */
        val cx: Float,
        val cy: Float,
        /** Skew. Zero on every sensor seen so far, carried because the API has it. */
        val skew: Float,
        /** Radial terms. */
        val k1: Float,
        val k2: Float,
        val k3: Float,
        /** Tangential terms, from a lens element not quite parallel to the sensor. */
        val p1: Float,
        val p2: Float,
    ) {
        /**
         * Whether this model would move anything.
         *
         * A lens that reports all-zero coefficients is telling the truth about
         * being rectilinear, and resampling it is a full-image copy that
         * softens the picture through the interpolation and changes nothing
         * else. Worth detecting and skipping.
         */
        val isIdentity: Boolean
            get() = k1 == 0f && k2 == 0f && k3 == 0f && p1 == 0f && p2 == 0f

        /**
         * The same lens described in the coordinates of an image [width] x
         * [height] instead of the active array it was calibrated against.
         *
         * Intrinsics are published in active-array pixels. A capture is
         * routinely a different size -- a different aspect crop, or a binned
         * readout -- and applying unscaled intrinsics to it puts the optical
         * axis in the wrong place, which turns a correction into a distortion.
         *
         * The distortion coefficients themselves are dimensionless, defined on
         * normalised coordinates, so they carry across unchanged.
         */
        fun scaledTo(
            activeWidth: Int,
            activeHeight: Int,
            width: Int,
            height: Int,
        ): Model {
            if (activeWidth <= 0 || activeHeight <= 0) return this
            val sx = width.toFloat() / activeWidth
            val sy = height.toFloat() / activeHeight
            return copy(
                fx = fx * sx,
                fy = fy * sy,
                cx = cx * sx,
                cy = cy * sy,
                skew = skew * sx,
            )
        }
    }

    /**
     * Builds a model from what a [LensProfile] holds, or null when the device
     * published too little to correct with.
     *
     * Both keys are optional in the Camera2 specification and either may be
     * absent. Distortion terms without intrinsics are unusable -- the
     * coefficients act on normalised coordinates, and without a focal length
     * and a principal point there is no way to normalise. Rather than invent
     * plausible intrinsics, this returns null and the caller skips correction:
     * an uncorrected image is honest, and one corrected against a guessed
     * optical axis is warped.
     */
    fun modelFor(profile: LensProfile, width: Int, height: Int): Model? {
        val d = profile.distortion ?: return null
        val i = profile.intrinsics ?: return null
        if (d.size < 5 || i.size < 5) return null

        val model = Model(
            fx = i[0], fy = i[1], cx = i[2], cy = i[3], skew = i[4],
            k1 = d[0], k2 = d[1], k3 = d[2], p1 = d[3], p2 = d[4],
        )
        if (model.fx <= 0f || model.fy <= 0f) return null

        if (!matchesArrayOrientation(
                profile.activeArrayWidth, profile.activeArrayHeight, width, height,
            )
        ) {
            // A frame that has been turned is not in the coordinates the
            // intrinsics were written in, and rescaling to fit does not turn
            // them -- it squashes one axis and stretches the other, moving the
            // optical axis somewhere it has never been.
            //
            // This shipped once. The 24mm main lens, close to rectilinear,
            // reported a 75.5px peak displacement because the correction was
            // being built for a 3064x4080 frame from a 4080x3064 array. The
            // caller now corrects before rotating; this refuses rather than
            // letting the mistake come back quietly.
            Log.w(
                TAG,
                "declining ${width}x$height against a " +
                    "${profile.activeArrayWidth}x${profile.activeArrayHeight} array: " +
                    "correct before rotating",
            )
            return null
        }

        val scaled = model.scaledTo(
            profile.activeArrayWidth, profile.activeArrayHeight, width, height,
        )
        Log.i(
            TAG,
            "%dmm k=[%.5f %.5f %.5f] p=[%.5f %.5f] f=[%.1f %.1f] c=[%.1f %.1f]".format(
                profile.equivalent35mm,
                scaled.k1, scaled.k2, scaled.k3, scaled.p1, scaled.p2,
                scaled.fx, scaled.fy, scaled.cx, scaled.cy,
            ),
        )
        return scaled
    }

    /**
     * Whether an image is the same way round as the array the lens was
     * calibrated against.
     *
     * Compared as an aspect ratio rather than by exact size, because a capture
     * is legitimately a different resolution -- a crop, a binned readout -- and
     * only a *transposed* one is the error being caught. The tolerance is loose
     * enough to admit a modest crop and far tighter than the 1.33 against 0.75
     * that a quarter turn produces.
     */
    internal fun matchesArrayOrientation(
        activeWidth: Int,
        activeHeight: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (activeWidth <= 0 || activeHeight <= 0 || width <= 0 || height <= 0) return true
        val array = activeWidth.toFloat() / activeHeight
        val image = width.toFloat() / height
        // Landscape against portrait, or the reverse, is always wrong.
        if ((array >= 1f) != (image >= 1f)) return false
        val ratio = max(array, image) / min(array, image)
        return ratio <= ASPECT_TOLERANCE
    }

    /** A capture may be cropped; it may not be transposed. */
    private const val ASPECT_TOLERANCE = 1.35f

    /**
     * Where the pixel that belongs at [outX], [outY] is to be found in the
     * distorted source.
     *
     * Writes the source coordinate into [out] as (x, y). Fractional: the caller
     * interpolates.
     */
    fun sourceFor(model: Model, outX: Float, outY: Float, out: FloatArray) {
        // Pixel to normalised, inverting the intrinsic matrix. The vertical
        // axis first because skew couples x to y and not the reverse.
        val y = (outY - model.cy) / model.fy
        val x = (outX - model.cx - model.skew * y) / model.fx

        val r2 = x * x + y * y
        val r4 = r2 * r2
        val r6 = r4 * r2
        val radial = 1f + model.k1 * r2 + model.k2 * r4 + model.k3 * r6

        val xy = x * y
        val dx = radial * x + 2f * model.p1 * xy + model.p2 * (r2 + 2f * x * x)
        val dy = radial * y + model.p1 * (r2 + 2f * y * y) + 2f * model.p2 * xy

        // Normalised back to pixels, applying the intrinsic matrix forwards.
        out[0] = model.fx * dx + model.skew * dy + model.cx
        out[1] = model.fy * dy + model.cy
    }

    /**
     * How far the correction moves the worst-affected pixel, in pixels.
     *
     * Sampled at the four corners and the four edge midpoints, which between
     * them bracket the extremes: radial distortion is largest at the corners,
     * and a strongly tangential lens can peak on an edge instead.
     *
     * Used to decide whether correcting is worth a resample at all. Under half
     * a pixel the change is invisible and the interpolation costs more
     * sharpness than the geometry gains.
     */
    fun peakDisplacement(model: Model, width: Int, height: Int): Float {
        val w = width - 1f
        val h = height - 1f
        val probes = floatArrayOf(
            0f, 0f, w, 0f, 0f, h, w, h,
            w / 2f, 0f, w / 2f, h, 0f, h / 2f, w, h / 2f,
        )
        val out = FloatArray(2)
        var worst = 0f
        var i = 0
        while (i < probes.size) {
            val px = probes[i]
            val py = probes[i + 1]
            sourceFor(model, px, py, out)
            val d = kotlin.math.hypot(out[0] - px, out[1] - py)
            if (d > worst) worst = d
            i += 2
        }
        return worst
    }

    /** Below this the resample costs more sharpness than the geometry gains. */
    const val NEGLIGIBLE_PX = 0.5f

    /**
     * The most a corner may be magnified by the correction.
     *
     * Undistorting barrel pulls the corners' source inward, so the output's
     * corners are the source's corner pixels spread over more ground:
     * magnified, and by the bilinear resample, softened. A few percent is the
     * price of straight lines and nobody sees it; fifteen percent is a corner
     * of interpolated mush, which is what the field test saw on the main lens
     * -- and on a lens whose calibration asks for more than that, either the
     * calibration describes a frame this is not, or the lens is one whose
     * corners were mush before the correction. Either way the correction is
     * scaled back to this rather than declined: partly straight is still
     * straighter than the glass left it.
     */
    const val MAX_CORNER_STRETCH = 1.15f

    /**
     * The correction's local magnification at the worst corner: how many
     * source pixels of ground one output pixel there is spread over,
     * measured along the radius by a finite difference of [sourceFor].
     * Greater than one is a corner stretched; less than one, compressed
     * (pincushion). One for the identity.
     */
    fun peakStretch(model: Model, width: Int, height: Int): Float {
        val w = width - 1f
        val h = height - 1f
        val corners = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)
        val a = FloatArray(2)
        val b = FloatArray(2)
        var worst = 1f
        var i = 0
        while (i < corners.size) {
            val px = corners[i]
            val py = corners[i + 1]
            // A step of a few pixels toward the centre, so the difference is
            // taken inside the frame and along the radius.
            val toCentreX = model.cx - px
            val toCentreY = model.cy - py
            val len = kotlin.math.hypot(toCentreX, toCentreY).coerceAtLeast(1f)
            val step = 4f
            val ux = toCentreX / len * step
            val uy = toCentreY / len * step
            sourceFor(model, px, py, a)
            sourceFor(model, px + ux, py + uy, b)
            // Source distance covered per output distance: the inverse of the
            // magnification. The output moved `step`; the source moved this.
            val sourceStep = kotlin.math.hypot(b[0] - a[0], b[1] - a[1])
            val stretch = if (sourceStep > 1e-3f) step / sourceStep else 1f
            if (stretch > worst) worst = stretch
            i += 2
        }
        return worst
    }

    /**
     * The model with its correction scaled back until no corner is stretched
     * past [maxStretch]. The displacement is close to linear in the
     * coefficients, so the excess is removed in proportion and the result
     * checked; two rounds is enough for the terms' small non-linearity.
     * Returns the model unchanged where it is already within the limit.
     */
    fun limitStretch(model: Model, width: Int, height: Int, maxStretch: Float = MAX_CORNER_STRETCH): Model {
        var limited = model
        repeat(3) {
            val stretch = peakStretch(limited, width, height)
            if (stretch <= maxStretch) return limited
            val t = ((maxStretch - 1f) / (stretch - 1f)).coerceIn(0.05f, 0.98f)
            limited = limited.copy(
                k1 = limited.k1 * t, k2 = limited.k2 * t, k3 = limited.k3 * t,
                p1 = limited.p1 * t, p2 = limited.p2 * t,
            )
        }
        return limited
    }

    /**
     * Resamples [src] into [dst], both packed ARGB at [width] x [height].
     *
     * Bilinear. Edge pixels that map outside the source are clamped to the
     * border rather than left black: a correction that expands the field pulls
     * from beyond the frame at the edges, and a black rim is more obviously
     * wrong than a stretched one.
     *
     * [dst] must not alias [src].
     */
    fun apply(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        model: Model,
    ) {
        require(src.size >= width * height) { "source is smaller than $width x $height" }
        require(dst.size >= width * height) { "destination is smaller than $width x $height" }

        val maxX = width - 1
        val maxY = height - 1

        // Banded across the cores. Rows are independent -- this gathers from
        // the source and writes only to its own row of the destination -- and
        // single-threaded it was measured at 1.85 seconds on a twelve
        // megapixel frame, which is more than twice the budget for the whole
        // capture-to-review path.
        Bands.rows(height) { yStart, yEnd ->
            // One scratch pair per band, not per pixel.
            val out = FloatArray(2)
            for (y in yStart until yEnd) {
                val row = y * width
                for (x in 0 until width) {
                    sourceFor(model, x.toFloat(), y.toFloat(), out)
                    dst[row + x] = sampleBilinear(src, width, maxX, maxY, out[0], out[1])
                }
            }
        }
    }

    /** Bilinear sample of a packed ARGB image, clamped at the border. */
    private fun sampleBilinear(
        src: IntArray,
        width: Int,
        maxX: Int,
        maxY: Int,
        fx: Float,
        fy: Float,
    ): Int {
        val cx = fx.coerceIn(0f, maxX.toFloat())
        val cy = fy.coerceIn(0f, maxY.toFloat())

        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = if (x0 < maxX) x0 + 1 else x0
        val y1 = if (y0 < maxY) y0 + 1 else y0

        val tx = cx - x0
        val ty = cy - y0

        val p00 = src[y0 * width + x0]
        val p10 = src[y0 * width + x1]
        val p01 = src[y1 * width + x0]
        val p11 = src[y1 * width + x1]

        // Per channel, weights folded so each output channel costs four
        // multiplies rather than four blends of a packed value.
        val w00 = (1f - tx) * (1f - ty)
        val w10 = tx * (1f - ty)
        val w01 = (1f - tx) * ty
        val w11 = tx * ty

        fun channel(shift: Int): Int {
            val v = ((p00 ushr shift) and 0xFF) * w00 +
                ((p10 ushr shift) and 0xFF) * w10 +
                ((p01 ushr shift) and 0xFF) * w01 +
                ((p11 ushr shift) and 0xFF) * w11
            // Rounded, not truncated. The four weights sum to one in exact
            // arithmetic and to a hair under it in float, so interpolating a
            // flat patch of 128 lands on 127.99998 -- and truncation turns that
            // into 127. Every resampled pixel would come back up to one code
            // value dark, across the whole frame, for a correction that is
            // supposed to move geometry and change nothing else.
            return kotlin.math.round(v).toInt().coerceIn(0, 255)
        }

        return (0xFF shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }
}
