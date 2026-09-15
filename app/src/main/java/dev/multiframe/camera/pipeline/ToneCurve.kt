package dev.multiframe.camera.pipeline

import kotlin.math.ln
import kotlin.math.max

/**
 * The rendering curve: scene-linear light to a picture.
 *
 * ### Why there has to be one
 *
 * Sensor data is linear in photons. A screen is not, an eye is not, and film
 * never was. Sending linear data through nothing but a gamma encode produces a
 * technically faithful image that looks washed out and lifeless -- no shadow
 * density, no midtone separation, highlights that go grey rather than glowing.
 * Every camera ever made applies a rendering curve; "no curve" is not neutrality,
 * it is just a different and worse choice.
 *
 * The thing to avoid is not processing, it is the *phone* look: shadows lifted
 * until they are flat and grey, local contrast pushed until everything has a
 * halo, saturation raised until skin goes orange. That look comes from
 * aggressive local tone mapping. This is a single global curve, which is what
 * a camera profile or a film stock is.
 *
 * ### Shape
 *
 * Three stages, in the order a photographic process applies them.
 *
 * 1. **Highlight roll-off, in linear light and hue-preserving.** Compressing
 *    each channel separately is the classic digital failure: as a saturated red
 *    brightens, red clips first and the pixel slides through orange to yellow
 *    before reaching white. Compressing the *brightest channel* and scaling all
 *    three by the same factor keeps the hue and only changes the level.
 *
 * 2. **Highlight desaturation, keyed on luminance.** Ratio-preserving
 *    compression alone never lets anything reach white, so bright saturated
 *    areas stay resolutely coloured in a way film and print never do. Real
 *    media desaturate as they approach maximum density. Blending toward
 *    neutral only in the top of the range restores that, and is what makes a
 *    bright sky read as bright rather than as cyan. *Bright* means the
 *    pixel's luminance, `Y = 0.2126 R + 0.7152 G + 0.0722 B`, and not its
 *    brightest channel: keyed on the channel, a saturated red flower with
 *    R at three times nominal white and a luminance under it was read as
 *    three times over and pulled most of the way to white -- the field
 *    test's pale pink. Keyed on luminance it is what it is: a red at the top
 *    of the range, compressed by stage 1 with its hue and its saturation
 *    intact, and not overexposed at all.
 *
 * 3. **An S-curve, applied after the gamma encode.** Contrast belongs in the
 *    perceptual domain: this is where a Photoshop curve, a film characteristic
 *    curve and a camera picture style all operate. The same adjustment applied
 *    in linear light lands almost entirely on the highlights and does nothing
 *    for the midtones, which is exactly the separation the picture needs.
 *
 * Every stage is global and monotonic, so no pixel's rendering depends on its
 * neighbours. That is the structural difference from local tone mapping, and
 * the reason this cannot produce halos.
 */
object ToneCurve {

    /** Rec. 709 luminance weights: the same ones the sharpen and the peaking read by. */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /**
     * The filmic shoulder: linear below [knee], a logarithm above it that
     * reaches 1 exactly at [white], the scene-linear level rendered as
     * display white.
     *
     * ### Why a logarithm
     *
     * The curve this replaced was exponential -- `knee + h(1 − e^{−u})` --
     * and an exponential saturates: a stop above the knee it had given up
     * nine tenths of its headroom, two stops above it was white to the last
     * place. Everything in a sky brighter than that landed on the same
     * value, which is the sixth field test's chalk. A logarithm spends the
     * headroom evenly *in stops*: each doubling of the scene above the knee
     * earns about the same increment of output, from the knee out to the
     * white point, which is what a film curve's shoulder does and what a
     * cloud needs to keep its shape. With the knee at 0.7 and white at 5,
     * the stops above the knee land at 0.82, 0.86, 0.90, 0.95 and 1.0.
     *
     * ### The arithmetic
     *
     *     u    = (x − knee) / h,   u_w = (white − knee) / h,   h = 1 − knee
     *     y    = knee + h · ln(1 + a·u) / ln(1 + a·u_w)
     *
     * with `a` chosen so the slope at the knee is one (`a = ln(1 + a·u_w)`,
     * see [shoulderSteepness]): C¹ at the knee, so a gradient crossing it
     * has no visible crease. Above [white] the curve is 1, and the caller's
     * desaturation has already taken such a pixel to neutral. Both native
     * develops tabulate this same curve (`Tone.h`, `shoulderCurveParts`).
     */
    fun shoulder(x: Float, knee: Float, white: Float = DevelopParams.SHOULDER_WHITE): Float {
        if (x <= 0f) return 0f
        if (x <= knee) return x
        val headroom = 1f - knee
        if (headroom <= 0f) return knee
        if (x >= white) return 1f
        val uMax = (white - knee) / headroom
        if (uMax <= 0f) return 1f
        val a = shoulderSteepness(uMax)
        val u = (x - knee) / headroom
        return knee + headroom * (ln(1f + a * u) / ln(1f + a * uMax))
    }

    /**
     * The `a` of the shoulder for a white point [uMax] headrooms past the
     * knee: the root of `a = ln(1 + a·uMax)`, which is what makes the slope
     * at the knee exactly one. Found by fixed-point iteration, which
     * converges in a dozen steps for any `uMax` above one; the native table
     * builder solves it the same way. For `uMax` at or under one there is
     * no such root and the curve is left linear to the white point.
     */
    fun shoulderSteepness(uMax: Float): Float {
        if (uMax <= 1f) return 1f
        var a = 2f
        repeat(40) {
            val next = ln(1f + a * uMax)
            if (kotlin.math.abs(next - a) < 1e-6f) return next
            a = next
        }
        return a
    }

    /**
     * Smootherstep blended with identity.
     *
     * At [amount] 0 this is the identity; at 1 it is a full S. The slope at
     * mid-grey is 1 + 0.875 * amount, so the strength parameter has a direct
     * reading as midtone contrast. Monotonic for any amount in 0..1, which
     * matters: a curve that ever descends turns a smooth gradient into banding.
     */
    fun sCurve(x: Float, amount: Float): Float {
        if (amount <= 0f) return x
        val c = x.coerceIn(0f, 1f)
        val s = c * c * c * (c * (c * 6f - 15f) + 10f)
        return c + amount * (s - c)
    }

    /**
     * Applies the linear-light stages to one pixel, writing back into [rgb].
     *
     * Expects white-balanced, colour-corrected, exposure-scaled scene-linear
     * values. Leaves the result in linear light, ready for the gamma encode.
     */
    fun renderLinear(rgb: FloatArray, params: DevelopParams) {
        var r = max(rgb[0], 0f)
        var g = max(rgb[1], 0f)
        var b = max(rgb[2], 0f)

        // How far past nominal white the scene actually was. Measured before
        // compression on purpose: the roll-off drives everything bright
        // asymptotically toward 1, so afterwards a bright sky and the sun are
        // indistinguishable. Only the scene-linear value still knows which is
        // which. The peak channel decides the compression, which has to keep
        // every channel in range; the luminance decides how much colour is
        // left, because that is what "overexposed" means for a colour.
        val scenePeak = max(r, max(g, b))
        val sceneLuma = LUMA_R * r + LUMA_G * g + LUMA_B * b

        // Stage 1: compress on the brightest channel and scale all three, so
        // the roll-off changes level without touching hue.
        if (scenePeak > params.shoulderKnee) {
            val scale = shoulder(scenePeak, params.shoulderKnee, params.shoulderWhite) / scenePeak
            r *= scale
            g *= scale
            b *= scale
        }

        // Stage 2: let the brightest areas give up their colour, the way film
        // and print do as they approach maximum density. A blue sky sitting
        // just under white keeps its blue; a specular highlight several stops
        // over goes white, because that is what being that bright looks like.
        val start = params.desaturationStart
        if (params.highlightDesaturation > 0f && sceneLuma > start) {
            // Zero at the threshold, approaching one without limit as the scene
            // value climbs, so there is no point at which it stops responding.
            val t = 1f - start / sceneLuma
            val mix = (t * t * params.highlightDesaturation).coerceIn(0f, 1f)
            val level = max(r, max(g, b))
            r += (level - r) * mix
            g += (level - g) * mix
            b += (level - b) * mix
        }

        rgb[0] = r
        rgb[1] = g
        rgb[2] = b
    }

    /**
     * The display-domain stage: black point, then the S-curve.
     *
     * Takes and returns a gamma-encoded value in 0..1.
     */
    fun renderDisplay(encoded: Float, params: DevelopParams): Float {
        var v = encoded
        if (params.blackPoint > 0f) {
            // A small toe. Film has one, and without it deep shadows read as
            // grey haze rather than as black.
            v = ((v - params.blackPoint) / (1f - params.blackPoint)).coerceAtLeast(0f)
        }
        return sCurve(v, params.contrast).coerceIn(0f, 1f)
    }
}
