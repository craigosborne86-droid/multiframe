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
        val model = profile.let { LensDistortion.modelFor(it, width, height) }

        val worthCorrecting = model != null &&
            !model.isIdentity &&
            LensDistortion.peakDisplacement(model, width, height) >=
            LensDistortion.NEGLIGIBLE_PX

        // Aberration is always worth looking for: it is estimated from the
        // image and costs nothing when the answer comes back as no correction.
        return Params(
            distortion = if (worthCorrecting) model else null,
            correctAberration = true,
        )
    }

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
        var scale = ChromaticAberration.NONE

        if (params.correctAberration) {
            scale = params.aberration
                ?: ChromaticAberration.estimate(pixels, width, height)
            if (!scale.isIdentity) {
                ChromaticAberration.apply(pixels, width, height, scale)
                Log.i(TAG, "corrected $scale")
            }
        }

        val model = params.distortion
        if (model != null && !model.isIdentity) {
            // The resample cannot be done in place: a pixel already rewritten
            // would become the source for its neighbour.
            val source = pixels.copyOf(width * height)
            LensDistortion.apply(source, pixels, width, height, model)
            Log.i(
                TAG,
                "undistorted, peak %.1fpx".format(
                    LensDistortion.peakDisplacement(model, width, height),
                ),
            )
        }

        return scale
    }
}
