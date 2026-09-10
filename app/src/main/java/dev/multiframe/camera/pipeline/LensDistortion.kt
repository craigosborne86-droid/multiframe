package dev.multiframe.camera.pipeline

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

        return model.scaledTo(
            profile.activeArrayWidth, profile.activeArrayHeight, width, height,
        )
    }

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

        val out = FloatArray(2)
        val maxX = width - 1
        val maxY = height - 1

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                sourceFor(model, x.toFloat(), y.toFloat(), out)
                dst[row + x] = sampleBilinear(src, width, maxX, maxY, out[0], out[1])
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
