package dev.multiframe.camera.pipeline

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What a scene's brightness distribution says about how to expose it. */
data class SceneAnalysis(
    /** Fraction of the frame at or above the clipping point. */
    val clippedFraction: Float,
    /** Level, 0..1, below which the chosen highlight percentile sits. */
    val highlightLevel: Float,
    /** Level below which the shadow percentile sits. */
    val shadowLevel: Float,
    /** Highlight over shadow, in stops: how much range the scene spans. */
    val dynamicRangeStops: Float,
)

/**
 * Deciding how much light to let in.
 *
 * ### The trade every camera makes, and why a merging camera makes it differently
 *
 * A clipped highlight is gone. No processing recovers it, because the sensor
 * recorded the same maximum value for every brightness above its limit. A noisy
 * shadow, by contrast, still contains its detail — it is merely buried, and
 * averaging frames digs it out.
 *
 * That asymmetry means the right exposure is not the one that centres the
 * histogram. It is the one that just avoids clipping, with the shadows left to
 * be recovered afterwards. Single-frame cameras cannot lean on this very far,
 * because lifting shadows lifts their noise with them and the picture falls
 * apart. A camera that merges a burst can, and by a precisely knowable amount.
 *
 * ### How far is precisely knowable
 *
 * Averaging N independent frames improves signal-to-noise by sqrt(N). Expressed
 * in stops, that is `0.5 * log2(N)` — the exposure that can be given up while
 * arriving at the same shadow noise as a single frame.
 *
 * At 8 frames that is 1.5 stops of highlight headroom bought for nothing. At
 * 32 it is 2.5. This is the entire argument for burst capture stated as one
 * number, and it is why the frame count feeds directly into the exposure
 * decision rather than being an unrelated setting.
 *
 * The result is a picture with the highlights of a carefully exposed frame and
 * the shadows of an over-exposed one. That is what separates it from a phone
 * that simply brightens everything and loses the sky.
 */
object ExposureStrategy {

    /** Never pull further than this, however many frames are merged. */
    const val MAX_UNDEREXPOSURE_STOPS = 3.0f

    /**
     * Leave a little room below clipping. Sensor response is not perfectly
     * linear right at the top, and auto-exposure will not land exactly where
     * it was asked to.
     */
    const val HIGHLIGHT_SAFETY = 0.92f

    /** Speculars and light sources are allowed to clip; they carry no detail. */
    const val ALLOWED_CLIPPING = 0.005f

    /**
     * Stops of exposure a burst of [frameCount] can give up while ending at the
     * same shadow noise as a single frame.
     *
     * The sqrt(N) law. Kept as its own function because it is the number the
     * whole strategy turns on.
     */
    fun shadowRecoveryStops(frameCount: Int): Float {
        if (frameCount <= 1) return 0f
        return (0.5 * ln(frameCount.toDouble()) / ln(2.0)).toFloat()
    }

    /**
     * Reads a luma histogram.
     *
     * [histogram] is any number of equal-width bins over the 0..1 range.
     */
    fun analyse(
        histogram: IntArray,
        highlightPercentile: Float = 0.995f,
        shadowPercentile: Float = 0.10f,
    ): SceneAnalysis {
        val total = histogram.sumOf { it.toLong() }
        if (total == 0L || histogram.isEmpty()) {
            return SceneAnalysis(0f, 0f, 0f, 0f)
        }
        val bins = histogram.size

        fun levelAt(percentile: Float): Float {
            val target = (total * percentile).toLong().coerceIn(0L, total)
            var seen = 0L
            for (i in histogram.indices) {
                seen += histogram[i]
                if (seen >= target) return (i + 0.5f) / bins
            }
            return 1f
        }

        val clipped = histogram.last().toFloat() / total
        val highlight = levelAt(highlightPercentile)
        val shadow = levelAt(shadowPercentile)

        // Ratio of levels in stops, with a floor so a black frame does not
        // report infinite range.
        val range = if (shadow <= 1e-4f || highlight <= 1e-4f) 0f
        else (ln((highlight / max(shadow, 1e-4f)).toDouble()) / ln(2.0)).toFloat()

        return SceneAnalysis(clipped, highlight, shadow, max(range, 0f))
    }

    /**
     * How many stops to pull the exposure down, given what the scene looks like
     * and how many frames will be merged.
     *
     * Returns a non-positive number: zero means the scene needs no protection.
     */
    fun recommendedPullStops(
        analysis: SceneAnalysis,
        frameCount: Int,
        maxPull: Float = MAX_UNDEREXPOSURE_STOPS,
    ): Float {
        // Nothing bright enough to be at risk.
        if (analysis.clippedFraction <= ALLOWED_CLIPPING &&
            analysis.highlightLevel <= HIGHLIGHT_SAFETY
        ) {
            return 0f
        }

        // Stops needed to bring the highlight percentile back under the safety
        // level. A ratio of levels is a ratio of light, so its log base two is
        // the number of stops directly.
        val needed = if (analysis.highlightLevel <= 1e-4f) 0f
        else (ln((analysis.highlightLevel / HIGHLIGHT_SAFETY).toDouble()) / ln(2.0)).toFloat()

        // Clipping that the percentile has not caught: heavily blown scenes
        // pin the top percentile at 1.0 and understate how far down to pull.
        val extraForClipping = when {
            analysis.clippedFraction > 0.10f -> 1.5f
            analysis.clippedFraction > 0.03f -> 0.8f
            analysis.clippedFraction > ALLOWED_CLIPPING -> 0.3f
            else -> 0f
        }

        val wanted = max(needed, 0f) + extraForClipping
        // Only spend what the burst can pay back. Pulling further than the
        // merge can recover trades a blown highlight for a noisy shadow, which
        // is not obviously a better picture.
        val affordable = min(shadowRecoveryStops(frameCount), maxPull)
        // Returned explicitly rather than as negated zero, which is a distinct
        // float value and compares unequal to zero.
        if (affordable <= 0f || wanted <= 0f) return 0f
        return -min(wanted, affordable)
    }

    /**
     * The same decision as an exposure compensation index the camera will take.
     *
     * [evStep] is the device's compensation step, commonly a third or a half of
     * a stop, and the range is clamped to what it actually offers.
     */
    fun recommendedEvIndex(
        analysis: SceneAnalysis,
        frameCount: Int,
        caps: CameraCapabilities,
    ): Int {
        if (!caps.supportsExposureCompensation || caps.evStep <= 0f) return 0
        val stops = recommendedPullStops(analysis, frameCount)
        val index = (stops / caps.evStep).toInt()
        return index.coerceIn(caps.evMin, caps.evMax)
    }

    /**
     * The gain the developer should apply to put the shadows back.
     *
     * Exactly undoes the pull, so a protected exposure renders at the
     * brightness it would have had, with the highlights it would not.
     */
    fun shadowRecoveryGain(pullStops: Float): Float = 2f.pow(-pullStops)

    /**
     * A luma histogram from a CFA frame, sampled at green sites.
     *
     * Green is half the sensor and carries most of the luminance, so it is both
     * the representative channel and the cheap one.
     */
    fun histogramOf(
        frame: BayerFrame,
        sensor: SensorProfile,
        bins: Int = 64,
        stride: Int = 4,
    ): IntArray {
        val out = IntArray(bins)
        val range = sensor.range.toFloat()
        val cfa = sensor.cfaPattern
        var y = 0
        while (y < frame.height) {
            var x = 0
            while (x < frame.width) {
                if (cfa[(y and 1) * 2 + (x and 1)] == 1) {
                    val raw = frame.data[y * frame.width + x].toInt() and 0xFFFF
                    val lin = ((raw - sensor.blackAt(x, y)) / range).coerceIn(0f, 1f)
                    out[(lin * (bins - 1)).toInt()]++
                }
                x += stride
            }
            y += stride
        }
        return out
    }
}
