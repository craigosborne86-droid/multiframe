package dev.multiframe.camera.pipeline

import android.util.Log

private const val TAG = "Optics"

/**
 * The two geometric corrections, applied together.
 *
 * [LensDistortion] straightens lines the glass bent; [ChromaticAberration]
 * puts the three colour planes back on top of each other. They are here
 * together because they are one resampling problem seen twice, and because the
 * order between them matters.
 *
 * ### Order
 *
 * Chromatic aberration first, distortion second.
 *
 * The CA estimate is a *radial scale* measured against the frame centre, and
 * that model only holds on an image whose geometry is still the one the lens
 * produced. Undistorting first moves every pixel radially by a
 * position-dependent amount, which is precisely the thing the CA search is
 * trying to measure — the two would be confounded and the estimate would come
 * back describing some mixture of the lens's dispersion and its barrel.
 *
 * ### Where this runs, and the compromise in it
 *
 * On the developed sRGB buffer, alongside [Defringe] and [Sharpen] — not on
 * linear RGB immediately after the demosaic, which is where the geometry
 * argument says it belongs and where `docs/REFERENCE.md` §3.1 places it.
 *
 * The reason is memory, and it is worth stating rather than hiding. The develop
 * is a single fused pass: demosaic, colour matrix, tone curve, gamma encode and
 * pack all happen per pixel without an intermediate ever existing. Splitting it
 * to insert a linear-space resample means materialising the whole frame in
 * linear RGB first — twelve megapixels at three channels is 144 MB as float, or
 * 72 MB at sixteen bits per channel, against a heap that [MemoryBudget] sizes at
 * 256 MB on this device. That is not affordable for a correction whose
 * displacement is a few pixels.
 *
 * What is lost by resampling in gamma-encoded space is real but small: the
 * interpolation is between values that are not proportional to light, so a
 * blend across a very high contrast edge lands slightly dark. The error scales
 * with the contrast across a single pixel and with the fractional part of the
 * displacement, and at the couple of pixels these corrections move it is well
 * under a code value everywhere except the hardest edges — which is where the
 * chromatic aberration correction is removing a far larger error anyway.
 *
 * If a linear intermediate ever becomes affordable — the Phase 3 sub-pixel
 * accumulation produces one, and the Phase 4 GPU develop would have one on the
 * device — this should move there and the compromise disappears.
 */
object Optics {

    /**
     * What to correct for one capture.
     *
     * [distortion] is null on a lens that published no calibration; [aberration]
     * is null when the estimate has not been made yet, and is filled in from
     * the image itself during [apply].
     */
    data class Params(
        val distortion: LensDistortion.Model? = null,
        val correctAberration: Boolean = true,
        /**
         * A measured scale, where one has already been established for this
         * lens. Null asks for it to be estimated from this frame.
         */
        val aberration: ChromaticAberration.Scale? = null,
        /**
         * Which lens this describes, for the log alone.
         *
         * Carried because a field report of "peak 75.5px" is not actionable
         * without it -- the ultrawide and the main lens differ by most of an
         * order of magnitude in how much they bend a straight line, and a
         * figure that could belong to either says nothing about whether the
         * correction is right.
         */
        val lensLabel: String = "lens",
        /** Camera id, for the per-lens aberration cache. */
        val lensId: String? = null,
    ) {
        val hasWork: Boolean
            get() = correctAberration || (distortion != null && !distortion.isIdentity)
    }

    /**
     * Builds the corrections a lens calls for, or null when there is nothing to
     * do for it.
     */
    fun paramsFor(profile: LensProfile?, width: Int, height: Int): Params? {
        if (profile == null) return null
        // Held to a corner stretch a picture can carry; see
        // LensDistortion.MAX_CORNER_STRETCH for what happens past it.
        val model = profile.let { LensDistortion.modelFor(it, width, height) }?.let { full ->
            val limited = LensDistortion.limitStretch(full, width, height)
            if (limited !== full) {
                Log.w(
                    TAG,
                    "%dmm: correction scaled back, corner stretch %.2fx to %.2fx".format(
                        profile.equivalent35mm,
                        LensDistortion.peakStretch(full, width, height),
                        LensDistortion.peakStretch(limited, width, height),
                    ),
                )
            }
            limited
        }

        val worthCorrecting = model != null &&
            !model.isIdentity &&
            LensDistortion.peakDisplacement(model, width, height) >=
            LensDistortion.NEGLIGIBLE_PX

        // Aberration is always worth looking for: it is estimated from the
        // image and costs nothing when the answer comes back as no correction.
        return Params(
            distortion = if (worthCorrecting) model else null,
            correctAberration = true,
            aberration = measured[profile.cameraId],
            lensLabel = "${profile.equivalent35mm}mm",
            lensId = profile.cameraId,
        )
    }

    /**
     * Lateral aberration measured per lens, kept for the life of the process.
     *
     * ### Why it is cached rather than measured each time
     *
     * Because it is a property of the glass, not of the photograph. The radial
     * scale between red and green is fixed by the lens's dispersion; pointing
     * the camera somewhere else does not change it.
     *
     * Re-measuring every capture was wrong on three counts, and the device
     * suite caught the third:
     *
     *  * It answers the same question repeatedly.
     *  * A single frame is a worse estimate than one taken from whichever frame
     *    happened to have the most edges in it.
     *  * **Its cost depends on the scene.** The search runs over pixels found
     *    on an edge, and a busy scene has many times more of them than a plain
     *    one. That made develop time scene-dependent, and
     *    `ZslStreamDeviceTest.repeatedCapturesTakeAConsistentTime` -- which
     *    holds four consecutive shots to within 1.4x of each other -- failed
     *    on the spread.
     *
     * In memory rather than on disk. One estimate per lens per launch is
     * already cheap, and a stored calibration would need a format, a version
     * and an invalidation rule to save a few hundred milliseconds once.
     */
    private val measured = java.util.concurrent.ConcurrentHashMap<
        String, ChromaticAberration.Scale,
        >()

    /** For the settings reset, and for tests that need a clean slate. */
    fun forgetMeasurements() = measured.clear()

    /**
     * Applies both corrections to a packed ARGB frame, in place.
     *
     * Returns what was actually done, for the log and for a caller that wants
     * to reuse the aberration estimate on the next frame of the same burst.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        params: Params,
    ): ChromaticAberration.Scale {
        val scale = resolveAberration(pixels, width, height, params)
        val model = params.distortion?.takeIf { !it.isIdentity }
        if (model == null && scale.isIdentity) return scale

        // Fused: one gather, reading the untouched original. See
        // `applyReference` for the arithmetic, which is what Optics.cpp does.
        val source = pixels.copyOf(width * height)
        applyReference(source, pixels, width, height, model, scale)
        logApplied(params, model, width, height)
        return scale
    }

    /**
     * Corrects a bitmap, returning a new one, on the native path where it is
     * available and through [applyReference] where it is not.
     *
     * The source is not recycled. It is the caller's, and on the fallback path
     * it is also the buffer the reference reads from.
     */
    fun apply(bitmap: android.graphics.Bitmap, params: Params): Pair<android.graphics.Bitmap, ChromaticAberration.Scale> {
        val width = bitmap.width
        val height = bitmap.height

        // The aberration estimate needs the pixels as an IntArray, but only
        // the first time a lens is seen. After that the cached scale is used
        // and the native path never copies anything out.
        val scale = params.aberration ?: run {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            resolveAberration(pixels, width, height, params)
        }

        val model = params.distortion?.takeIf { !it.isIdentity }
        if (model == null && scale.isIdentity) return bitmap to scale

        NativeOptics.correct(bitmap, model, scale)?.let { corrected ->
            logApplied(params, model, width, height)
            return corrected to scale
        }

        // The Kotlin reference, on a copy.
        val source = IntArray(width * height)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)
        applyReference(source, out, width, height, model, scale)
        val corrected = android.graphics.Bitmap.createBitmap(
            out, width, height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        logApplied(params, model, width, height)
        return corrected to scale
    }

    /** The aberration scale for this capture: cached for the lens, or measured now. */
    private fun resolveAberration(
        pixels: IntArray,
        width: Int,
        height: Int,
        params: Params,
    ): ChromaticAberration.Scale {
        if (!params.correctAberration) return ChromaticAberration.NONE
        params.aberration?.let { return it }

        val scale = ChromaticAberration.estimate(pixels, width, height)
        // Kept for the rest of the session, so the search runs once per lens
        // rather than once per photograph -- but only an estimate the search
        // believed. A dark frame's estimate is noise, and cached noise would
        // fringe every frame after it; the next frame gets to try. See
        // `measured` and `ChromaticAberration.MIN_IMPROVEMENT`.
        if (scale.confident) params.lensId?.let { measured[it] = scale }
        Log.i(TAG, "${params.lensLabel}: measured $scale")
        return if (scale.confident) scale else ChromaticAberration.NONE
    }

    private fun logApplied(
        params: Params,
        model: LensDistortion.Model?,
        width: Int,
        height: Int,
    ) {
        if (model != null) {
            Log.i(
                TAG,
                "%s: corrected %dx%d, peak %.1fpx".format(
                    params.lensLabel, width, height,
                    LensDistortion.peakDisplacement(model, width, height),
                ),
            )
        } else {
            Log.i(TAG, "${params.lensLabel}: corrected aberration only")
        }
    }

    /**
     * The fused correction, in Kotlin, on packed ARGB.
     *
     * This is the reference Optics.cpp is held to. It composes the two
     * gathers: for an output pixel, undistortion says where it came from in
     * the distorted image, and the aberration correction says where red and
     * blue at *that* position really are. Each channel is interpolated once,
     * at its own source.
     *
     * [src] and [dst] must not alias -- this reads the whole of one and writes
     * the whole of the other. A null [model] means no geometric correction and
     * the source position is the output position.
     */
    fun applyReference(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        model: LensDistortion.Model?,
        scale: ChromaticAberration.Scale,
    ) {
        require(src.size >= width * height) { "source is smaller than $width x $height" }
        require(dst.size >= width * height) { "destination is smaller than $width x $height" }

        val maxX = width - 1
        val maxY = height - 1
        // The aberration centre is the frame centre, which is what the
        // estimator measured against. Not the distortion model's principal
        // point: the two describe different things.
        val acx = (width - 1) / 2f
        val acy = (height - 1) / 2f

        Bands.rows(height) { yStart, yEnd ->
            val s = FloatArray(2)
            for (y in yStart until yEnd) {
                val row = y * width
                val fy = y.toFloat()
                for (x in 0 until width) {
                    val sx: Float
                    val sy: Float
                    if (model != null) {
                        LensDistortion.sourceFor(model, x.toFloat(), fy, s)
                        sx = s[0]
                        sy = s[1]
                    } else {
                        sx = x.toFloat()
                        sy = fy
                    }

                    val g = sample(src, width, maxX, maxY, sx, sy, 8)

                    val rx = acx + (sx - acx) * scale.red
                    val ry = acy + (sy - acy) * scale.red
                    val r = sample(src, width, maxX, maxY, rx, ry, 16)

                    val bx = acx + (sx - acx) * scale.blue
                    val by = acy + (sy - acy) * scale.blue
                    val b = sample(src, width, maxX, maxY, bx, by, 0)

                    dst[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    /** One channel, bilinear, clamped at the border, rounded to nearest even. */
    private fun sample(
        src: IntArray,
        width: Int,
        maxX: Int,
        maxY: Int,
        fx: Float,
        fy: Float,
        shift: Int,
    ): Int {
        val cx = fx.coerceIn(0f, maxX.toFloat())
        val cy = fy.coerceIn(0f, maxY.toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = if (x0 < maxX) x0 + 1 else x0
        val y1 = if (y0 < maxY) y0 + 1 else y0
        val tx = cx - x0
        val ty = cy - y0

        val v00 = ((src[y0 * width + x0] ushr shift) and 0xFF).toFloat()
        val v10 = ((src[y0 * width + x1] ushr shift) and 0xFF).toFloat()
        val v01 = ((src[y1 * width + x0] ushr shift) and 0xFF).toFloat()
        val v11 = ((src[y1 * width + x1] ushr shift) and 0xFF).toFloat()

        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        val v = top + (bottom - top) * ty
        return kotlin.math.round(v).toInt().coerceIn(0, 255)
    }
}
