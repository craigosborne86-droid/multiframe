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
     * The standing highlight bias: a stop under what the meter would choose,
     * on every scene the burst can pay for, before any clipping is seen.
     *
     * The guard used to act only once the histogram showed clipping, which
     * is to say once the sky was already gone in the frames the shutter
     * would take. A stop of headroom kept in hand is what a cloud needs to
     * stay a cloud: the raw's clip moves a stop above the bright end of the
     * scene, the develop's own exposure puts the midtones back where the
     * meter had them, and the filmic shoulder spends the stop on the
     * highlights' tones. The shadows pay a stop of noise and the merge takes
     * it back -- eight frames are a stop and a half. A scene with clipping
     * in it is pulled further, up to what the burst affords; a dark scene,
     * one whose bright end is under [BIAS_FLOOR_LEVEL], has no highlights to
     * hold and is not biased at all.
     */
    const val HIGHLIGHT_BIAS_STOPS = 1.0f

    /** Below this bright end there is nothing to protect and the bias stays off. */
    const val BIAS_FLOOR_LEVEL = 0.10f

    /**
     * The bias, once on, stays on until the scene's bright end falls to this
     * share of [BIAS_FLOOR_LEVEL]: half a stop of hysteresis, so a scene
     * sitting at the floor does not switch the bias on and off.
     */
    const val BIAS_RELEASE_FRACTION = 0.7f

    /**
     * The risk pull -- what the guard adds beyond the bias for clipping it
     * has seen -- relaxes only once the frame's bright end sits this far
     * under [HIGHLIGHT_SAFETY]: half a stop of headroom, so a highlight at
     * the safety line is left where it is rather than chased up and down.
     */
    const val RISK_RELAX_BELOW = 0.70f

    /** How much of the risk pull is given back per decision once the headroom is there. */
    const val RISK_RELAX_STEP = 0.5f

    /**
     * What the highlight guard has decided, carried from one decision to the
     * next.
     *
     * ### Why the guard has to remember
     *
     * The first guard decided from each frame's histogram alone, and the
     * frame it looked at was the one its own last decision had darkened.
     * A dim room with a window sat at a bright end of 0.15: over the floor,
     * so the bias went on; the meter took the stop off, the bright end read
     * 0.075, under the floor, so the bias came off; and the meter put the
     * stop back. A period of two decisions, for as long as the camera was
     * pointed there, with the ISO swinging a stop each way -- and a burst
     * taken across the swing held frames at different gains, which is what
     * the merge turned into a green picture ([BayerMerger]'s note on the
     * pedestal). The judge was reading its own verdict.
     *
     * So the decision is made on the scene, not on the frame: the bright end
     * is read back up by the pull in force before it is judged against the
     * floor, and the bias has hysteresis. The risk pull -- for clipping the
     * bias did not cover -- grows while clipping is still seen and relaxes
     * only once the frame has half a stop of headroom, a step at a time.
     * From rest on a still scene this reaches what [recommendedPullStops]
     * would have said; it differs in never reversing itself on the strength
     * of a frame it darkened.
     */
    data class Guard(
        /** Whether the standing bias is in force. */
        val standing: Boolean = false,
        /** Stops pulled beyond the bias for clipping the guard has seen. Never negative. */
        val riskStops: Float = 0f,
        /** The bias in force when [standing]; the constructor's default is the shipped one. */
        val bias: Float = HIGHLIGHT_BIAS_STOPS,
    ) {
        /** Stops of pull this guard asks for: zero or negative. */
        val pullStops: Float
            get() {
                val total = (if (standing) bias else 0f) + riskStops
                // Zero rather than negated zero, which compares unequal to it.
                return if (total <= 0f) 0f else -total
            }

        companion object {
            val REST = Guard()
        }
    }

    /**
     * The guard's next decision, from the frame it is looking at and the
     * decision in force when that frame was exposed. See [Guard].
     */
    fun nextGuard(
        analysis: SceneAnalysis,
        frameCount: Int,
        current: Guard = Guard.REST,
        maxPull: Float = MAX_UNDEREXPOSURE_STOPS,
        dcgRatio: Int = 1,
        bias: Float = HIGHLIGHT_BIAS_STOPS,
    ): Guard {
        val affordable = min(shadowRecoveryStopsDcg(frameCount, dcgRatio), maxPull)
        if (affordable <= 0f) return Guard(false, 0f, bias)

        // The scene's bright end: the frame's, read back up by the pull that
        // darkened it. Clipped stays clipped -- nothing reads above one.
        val unpulledHighlight = min(1f, analysis.highlightLevel * 2f.pow(-current.pullStops))
        val standing = bias > 0f && bias <= affordable &&
            if (current.standing) unpulledHighlight > BIAS_FLOOR_LEVEL * BIAS_RELEASE_FRACTION
            else unpulledHighlight > BIAS_FLOOR_LEVEL

        // The risk. While the frame still clips, or its bright end is over
        // the safety line, the risk is raised to what this frame implies --
        // the stops the scene's bright end is over the line, plus the
        // allowance for clipping the percentile does not see -- and never
        // lowered by it: a frame that shows less clipping than the last
        // because the pull is working is not a reason to undo the pull.
        // Nor does the risk accumulate tick on tick: a lamp in the frame
        // clips whatever the pull, and a guard that added to the pull for
        // as long as it saw a clipped pixel walked every lamp-lit room to
        // the burst's whole allowance. It relaxes only once the frame's
        // bright end has half a stop of headroom, a step at a time.
        val atRisk = analysis.clippedFraction > ALLOWED_CLIPPING ||
            analysis.highlightLevel > HIGHLIGHT_SAFETY
        var risk = current.riskStops.coerceAtLeast(0f)
        if (atRisk) {
            val needed = if (unpulledHighlight <= 1e-4f) 0f
            else max(0f, (ln((unpulledHighlight / HIGHLIGHT_SAFETY).toDouble()) / ln(2.0)).toFloat())
            risk = max(risk, needed + extraForClipping(analysis.clippedFraction))
        } else if (analysis.highlightLevel < HIGHLIGHT_SAFETY * RISK_RELAX_BELOW) {
            risk = max(0f, risk - RISK_RELAX_STEP)
        }

        // Only what the burst can pay back, the bias first.
        val standingStops = if (standing) bias else 0f
        risk = min(risk, max(0f, affordable - standingStops))
        return Guard(standing, risk, bias)
    }

    /**
     * Clipping the percentile has not caught: heavily blown scenes pin the
     * top percentile at 1.0 and understate how far down to pull.
     */
    private fun extraForClipping(clippedFraction: Float): Float = when {
        clippedFraction > 0.10f -> 1.5f
        clippedFraction > 0.03f -> 0.8f
        clippedFraction > ALLOWED_CLIPPING -> 0.3f
        else -> 0f
    }

    /** The exposure compensation index for a pull, in the camera's steps and range. */
    fun evIndexFor(pullStops: Float, caps: CameraCapabilities): Int {
        if (!caps.supportsExposureCompensation || caps.evStep <= 0f) return 0
        return (pullStops / caps.evStep).toInt().coerceIn(caps.evMin, caps.evMax)
    }

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
     * Shadow recovery with dual conversion gain.
     *
     * The high-ISO frames read further into shadows — [dcgRatio] times more
     * analog gain is `log2(dcgRatio)` stops of extra sensitivity — but only
     * half that improvement survives averaging because the high-ISO frames
     * also contribute more noise. The net extra budget is
     * `0.5 * log2(dcgRatio)` stops on top of the sqrt(N) base.
     */
    fun shadowRecoveryStopsDcg(frameCount: Int, dcgRatio: Int): Float {
        val base = shadowRecoveryStops(frameCount)
        if (dcgRatio <= 1) return base
        return base + (0.5 * ln(dcgRatio.toDouble()) / ln(2.0)).toFloat()
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
        dcgRatio: Int = 1,
        /** The standing bias, see [HIGHLIGHT_BIAS_STOPS]; zero asks for the clipping guard alone. */
        bias: Float = HIGHLIGHT_BIAS_STOPS,
    ): Float {
        // The bias, on any scene with a bright end worth holding.
        val standing = if (bias > 0f && analysis.highlightLevel > BIAS_FLOOR_LEVEL) bias else 0f

        // Nothing bright enough to be at risk beyond that.
        val atRisk = analysis.clippedFraction > ALLOWED_CLIPPING ||
            analysis.highlightLevel > HIGHLIGHT_SAFETY

        // Stops needed to bring the highlight percentile back under the safety
        // level. A ratio of levels is a ratio of light, so its log base two is
        // the number of stops directly.
        val needed = if (!atRisk || analysis.highlightLevel <= 1e-4f) 0f
        else (ln((analysis.highlightLevel / HIGHLIGHT_SAFETY).toDouble()) / ln(2.0)).toFloat()

        // Clipping that the percentile has not caught: heavily blown scenes
        // pin the top percentile at 1.0 and understate how far down to pull.
        val extraForClipping = if (atRisk) extraForClipping(analysis.clippedFraction) else 0f

        val wanted = standing + max(needed, 0f) + extraForClipping
        // Only spend what the burst can pay back. Pulling further than the
        // merge can recover trades a blown highlight for a noisy shadow, which
        // is not obviously a better picture.
        val affordable = min(shadowRecoveryStopsDcg(frameCount, dcgRatio), maxPull)
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
        dcgRatio: Int = 1,
    ): Int {
        if (!caps.supportsExposureCompensation || caps.evStep <= 0f) return 0
        return evIndexFor(recommendedPullStops(analysis, frameCount, dcgRatio = dcgRatio), caps)
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
        // Every row of a Bayer pattern has a green site at one of its two
        // phases; each sampled row starts there and an even stride keeps
        // the phase. Testing each sample's phase from column zero saw only
        // phase (0, 0), and on a sensor whose (0, 0) is not green the
        // histogram was empty. The native ring samples the same way.
        val stepX = if (stride and 1 == 1) stride + 1 else stride
        var y = 0
        while (y < frame.height) {
            val rowPhase = (y and 1) * 2
            val x0 = when {
                cfa[rowPhase] == 1 -> 0
                cfa[rowPhase + 1] == 1 -> 1
                else -> -1
            }
            if (x0 >= 0) {
                var x = x0
                while (x < frame.width) {
                    val raw = frame.data[y * frame.width + x].toInt() and 0xFFFF
                    val lin = ((raw - sensor.blackAt(x, y)) / range).coerceIn(0f, 1f)
                    out[(lin * (bins - 1)).toInt()]++
                    x += stepX
                }
            }
            y += stride
        }
        return out
    }
}
