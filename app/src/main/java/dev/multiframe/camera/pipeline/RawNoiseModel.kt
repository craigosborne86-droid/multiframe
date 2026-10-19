package dev.multiframe.camera.pipeline

/** Camera2 SENSOR_NOISE_PROFILE in unshaded, black-pedestalled RAW DN.
 *
 * Camera2 defines variance S*x + O for black-subtracted signal normalised to
 * [0, 1], in CFA SITE order (not RggbChannelVector order). With R = white-black,
 * varDN(raw) = S*R*max(raw-black, 0) + O*R^2. A later signal gain g multiplies
 * variance by g^2. No WB, shading or post-RAW sensitivity boost belongs here.
 * See CaptureResult.SENSOR_NOISE_PROFILE and DNG 1.7.1, NoiseProfile (pp.59–60).
 *
 * Optional in the accumulator; capture does not enable this experiment yet.
 */
class RawNoiseModel private constructor(private val coefficients: FloatArray) {
    fun variance(site: Int, raw: Float): Float =
        coefficients[site] * (raw - coefficients[8 + site]).coerceAtLeast(0f) + coefficients[4 + site]

    internal fun packed(): FloatArray = coefficients.copyOf()

    companion object {
        /** Four interleaved (S,O) pairs and four black levels in sensor 2x2 site order. */
        fun fromNormalized(profile: DoubleArray, black: FloatArray, white: Int): RawNoiseModel? {
            if (profile.size != 8 || black.size != 4 || white <= 0 ||
                profile.any { !it.isFinite() || it < 0.0 } ||
                black.any { !it.isFinite() || it < 0f || it >= white }) return null
            val out = FloatArray(12)
            for (c in 0..3) {
                if (profile[c * 2] == 0.0 && profile[c * 2 + 1] == 0.0) return null
                val range = white.toDouble() - black[c]
                out[c] = (profile[c * 2] * range).toFloat()
                out[4 + c] = (profile[c * 2 + 1] * range * range).toFloat()
                out[8 + c] = black[c]
            }
            return if (out.all { it.isFinite() }) RawNoiseModel(out) else null
        }

        /** Variance of the reference-minus-normalised-alternate residual. */
        fun residualVariance(reference: RawNoiseModel, alternate: RawNoiseModel,
                             site: Int, refRaw: Float, altRaw: Float, gainScale: Float): Float =
            reference.variance(site, refRaw) + alternate.variance(site, altRaw) / (gainScale * gainScale)
    }
}
