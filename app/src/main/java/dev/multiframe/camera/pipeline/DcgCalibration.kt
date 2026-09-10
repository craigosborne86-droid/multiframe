package dev.multiframe.camera.pipeline

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp

/**
 * Finding the sensor's analog gain switch by watching its noise.
 *
 * ### What is being looked for
 *
 * A sensor with dual conversion gain has two analog amplifiers and switches
 * between them at some sensitivity. Below the switch it runs in high
 * conversion gain: less full-well capacity, but the read noise referred to the
 * input is small. Above it, low conversion gain: more headroom, more read
 * noise. The switch is invisible in every key Camera2 publishes -- there is no
 * `SENSOR_INFO_CONVERSION_GAIN_MODE` and no vendor tag an app may read -- but
 * it is not invisible in the data.
 *
 * Total noise variance at a given gain `g` is
 *
 *     var(g) = read^2 * g^2 + shot * g + fixed
 *
 * which is a quadratic in `g`, and -- this is the useful part -- *linear in its
 * three coefficients*. So it is an ordinary least-squares fit against the design
 * matrix `[g^2, g, 1]`, with no iteration and no initial guess. A conversion
 * gain switch changes `read` discontinuously, so it shows up as a step in the
 * leading coefficient: fit the quadratic once across everything, then fit two
 * of them either side of a break, and ask whether the break earned its three
 * extra parameters.
 *
 * ### Why not straight lines in log-log
 *
 * Because every sensor would come back positive. In log-log the model is not a
 * line at all: it is shot-noise-dominated at low gain, where the slope tends to
 * one, and read-noise-dominated at high gain, where it tends to two. The curve
 * bends smoothly between them on *every* sensor, switch or no switch, and a
 * two-line fit will always beat a one-line fit on that bend by a mile. The
 * first version of this fitted lines in log-log and reported a gain switch on
 * synthetic curves built with a constant read noise -- a threshold invented out
 * of the physics of an ordinary sensor. Fitting the model the noise actually
 * follows is what makes the F test mean anything.
 *
 * ### Why the answer is a threshold and not a ratio
 *
 * Knowing where the switch is means the bracket can straddle it: the low frame
 * just below, the high frame just above. That is the largest real difference in
 * conversion gain the sensor can give, and it is a different number on every
 * sensor. Bracketing by a fixed multiple lands wherever it lands.
 *
 * Everything here is arithmetic on an array of measurements. It knows nothing
 * about cameras, which is what lets it be tested against curves with a known
 * answer rather than against a phone.
 */
object DcgCalibration {

    /**
     * One measurement: the sensitivity requested, and the temporal variance
     * observed at it.
     *
     * Variance rather than standard deviation because the noise model is
     * additive in variance -- read, shot and fixed-pattern contributions sum
     * there and nowhere else.
     */
    data class Sample(val iso: Int, val variance: Double)

    /**
     * What the fit concluded.
     *
     * [thresholdIso] is null when no break was found, which is the common and
     * entirely unremarkable case: most sensors have a single conversion gain
     * and their noise curve is one straight line.
     */
    data class Result(
        val thresholdIso: Int?,
        /** Residual sum of squares of the single-line fit. */
        val singleLineRss: Double,
        /** Residual sum of squares of the best two-line fit. */
        val brokenLineRss: Double,
        /** The F statistic comparing the two. Zero when no break was tried. */
        val fStatistic: Double,
        val sampleCount: Int,
    ) {
        val foundThreshold: Boolean get() = thresholdIso != null

        override fun toString(): String =
            if (thresholdIso != null) {
                "gain switch at ISO $thresholdIso (F=%.1f, %d samples)"
                    .format(fStatistic, sampleCount)
            } else {
                "no gain switch found (F=%.1f, %d samples)"
                    .format(fStatistic, sampleCount)
            }
    }

    /** Fewer than this and a two-segment fit is describing noise, not a sensor. */
    const val MIN_SAMPLES = 8

    /**
     * Each segment must own at least this many points to be a segment.
     *
     * Four, not three: the model has three coefficients, so three points fit it
     * exactly and leave no residual at all. A segment that cannot fail to fit
     * makes every split look perfect.
     */
    const val MIN_SEGMENT = 4

    /**
     * How much better the broken fit has to be.
     *
     * The F statistic for adding two parameters, against roughly ten degrees of
     * freedom, sits near 4 at the 5% level and near 8 at the 1% level. This is
     * above both. The cost of a false positive here is a bracket built around a
     * gain switch that does not exist, which is worse than the cost of a false
     * negative -- that one just falls back to the software default and loses
     * nothing that was previously there.
     */
    const val F_THRESHOLD = 12.0

    /**
     * The ISO values to sample, logarithmically spaced across the range.
     *
     * Logarithmic because the noise model is linear in log ISO, so evenly
     * spaced points in log space are evenly spaced in the thing being fitted.
     * Linear spacing would put three quarters of the samples above ISO 1600 and
     * leave the bottom of the range -- where a gain switch usually is --
     * described by two points.
     */
    fun isoLadder(min: Int, max: Int, steps: Int): List<Int> {
        if (min <= 0 || max <= min || steps < 2) return emptyList()
        val lnMin = ln(min.toDouble())
        val lnMax = ln(max.toDouble())
        return (0 until steps)
            // Rounded, not truncated, and then held inside the range. Truncating
            // returned 49 for a sensor whose floor is ISO 50, because
            // exp(ln(50)) lands a hair under it -- and a request one below the
            // advertised minimum is one the camera is entitled to reject.
            .map {
                Math.round(exp(lnMin + (lnMax - lnMin) * it / (steps - 1)))
                    .toInt().coerceIn(min, max)
            }
            .distinct()
    }

    /**
     * Temporal variance between two frames of the same scene at the same
     * settings.
     *
     * The difference of two frames cancels the scene and leaves twice the
     * noise, which is the trick that makes this measurable without a dark room:
     * whatever the sensor is pointed at contributes to both frames equally and
     * subtracts away. Halving the variance of the difference recovers the
     * variance of one frame.
     *
     * Both frames must be the same size. Values are raw sensor counts.
     */
    fun temporalVariance(first: ShortArray, second: ShortArray): Double {
        require(first.size == second.size) {
            "frames differ in size: ${first.size} and ${second.size}"
        }
        if (first.isEmpty()) return 0.0

        // Mean of the difference is subtracted rather than assumed zero: a
        // slight exposure drift between the two frames shifts every pixel
        // together, and counting that as noise would swamp the measurement.
        var sum = 0.0
        for (i in first.indices) {
            sum += ((first[i].toInt() and 0xFFFF) - (second[i].toInt() and 0xFFFF)).toDouble()
        }
        val mean = sum / first.size

        var sumSq = 0.0
        for (i in first.indices) {
            val d = ((first[i].toInt() and 0xFFFF) -
                (second[i].toInt() and 0xFFFF)).toDouble() - mean
            sumSq += d * d
        }
        // Variance of a difference of two independent samples is twice the
        // variance of one.
        return (sumSq / first.size) / 2.0
    }

    /**
     * Fits the samples and reports whether the sensor has a gain switch.
     *
     * The gain axis is ISO normalised to the lowest sample, so `g` is a
     * multiple of base gain rather than a raw sensitivity. It only rescales the
     * coefficients -- the residuals, and therefore the F test, are unchanged --
     * but it keeps `g^2` from reaching 10^8 on a sensor that runs to ISO 12800,
     * which is where a normal-equations solve starts losing precision.
     */
    fun fit(samples: List<Sample>): Result {
        val usable = samples
            .filter { it.iso > 0 && it.variance > 0.0 }
            .sortedBy { it.iso }
            .distinctBy { it.iso }

        if (usable.size < MIN_SAMPLES) {
            return Result(null, 0.0, 0.0, 0.0, usable.size)
        }

        val base = usable.first().iso.toDouble()
        val g = DoubleArray(usable.size) { usable[it].iso / base }

        // The variance axis is normalised as well as the gain axis, and for a
        // stronger reason than tidiness.
        //
        // The weights are 1/v^2, so a variance in raw counts squared -- which
        // on a 10-bit sensor reaches millions at the top of the range -- gives
        // weights around 1e-13, and the weighted moments underflow. The 3x3
        // determinant then falls under any fixed guard and every segment
        // returns a residual of zero, which is not "no gain switch found" but
        // the fit collapsing and reporting F=0 as though it were an answer.
        // That is exactly what the phone did: every camera, every time.
        //
        // Dividing through by the first variance makes the system scale-free.
        // The residuals are relative, so the fit and the F statistic are
        // unchanged by it -- only the conditioning is.
        val scale = usable.first().variance
        val v = DoubleArray(usable.size) { usable[it].variance / scale }

        val singleRss = quadraticRss(g, v, 0, usable.size)

        // Every interior split that leaves both segments long enough to be
        // fitted. Exhaustive because there are at most a couple of dozen
        // candidates, and because the alternative -- a gradient method on a
        // piecewise objective -- has local minima this does not.
        var bestRss = Double.MAX_VALUE
        var bestSplit = -1
        for (split in MIN_SEGMENT..(usable.size - MIN_SEGMENT)) {
            val rss = quadraticRss(g, v, 0, split) +
                quadraticRss(g, v, split, usable.size)
            if (rss < bestRss) {
                bestRss = rss
                bestSplit = split
            }
        }

        if (bestSplit < 0) {
            return Result(null, singleRss, singleRss, 0.0, usable.size)
        }

        // F test for the three extra coefficients the broken fit costs: the
        // second segment gets its own read, shot and fixed terms.
        val df = usable.size - 6
        val f = if (df > 0 && bestRss > 0.0) {
            (((singleRss - bestRss) / 3.0) / (bestRss / df)).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val threshold = if (f > F_THRESHOLD) usable[bestSplit].iso else null
        return Result(threshold, singleRss, bestRss, f, usable.size)
    }

    /**
     * Weighted residual sum of squares of the least-squares quadratic
     * `a*g^2 + b*g + c` through `[from, until)`.
     *
     * ### Why it is weighted
     *
     * Because the measurement error here is *relative*, not absolute. A sample
     * variance estimated from N pixels has a standard error of about
     * `var * sqrt(2/N)` -- proportional to the variance itself. Across an ISO
     * range of 50 to 12800 the variance grows by four orders of magnitude, so
     * an unweighted fit is decided entirely by the top two or three samples and
     * the bottom half of the range contributes nothing to it.
     *
     * That is not a subtle inefficiency; it manufactures thresholds. Left
     * unweighted, this fitted an ordinary constant-read-noise sensor and
     * reported a gain switch on sixteen of forty noise seeds, because a
     * two-segment fit could always chase the few enormous residuals at the top
     * better than one segment could. Weighting each residual by `1/v^2` puts
     * every sample on the same footing in relative terms, which is the scale
     * the error actually lives on, and the false positives go to zero.
     *
     * Solved through the normal equations: for three parameters that is a
     * symmetric 3x3 system with a closed-form determinant. Conditioning is the
     * usual objection and QR the usual answer; here the gain axis is normalised
     * to start at one and spans two or three decades, which keeps the Gram
     * matrix well inside what double precision handles.
     *
     * Returns zero for a segment too short to have a residual -- three points
     * fit a three-parameter model exactly. [MIN_SEGMENT] keeps that out of the
     * search.
     */
    private fun quadraticRss(
        g: DoubleArray,
        v: DoubleArray,
        from: Int,
        until: Int,
    ): Double {
        val n = until - from
        if (n < 4) return 0.0

        // Weighted moments of the design matrix. s_k is the weighted sum of
        // g^k, with weight 1/v^2 so that the fit minimises relative error.
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (i in from until until) {
            val x = g[i]
            val x2 = x * x
            val w = 1.0 / (v[i] * v[i])
            s0 += w
            s1 += w * x
            s2 += w * x2
            s3 += w * x2 * x
            s4 += w * x2 * x2
            t0 += w * v[i]
            t1 += w * x * v[i]
            t2 += w * x2 * v[i]
        }

        // [s4 s3 s2][a]   [t2]
        // [s3 s2 s1][b] = [t1]
        // [s2 s1 s0][c]   [t0]
        val det = s4 * (s2 * s0 - s1 * s1) -
            s3 * (s3 * s0 - s1 * s2) +
            s2 * (s3 * s1 - s2 * s2)

        // Singular relative to the size of the matrix, not against a fixed
        // number. An absolute threshold is a scale assumption in disguise: it
        // calls a perfectly well-conditioned system singular as soon as the
        // moments are small, which is how this silently answered "no gain
        // switch" for every sensor before the axes were normalised.
        val magnitude = maxOf(abs(s4), abs(s3), abs(s2), abs(s1), abs(s0))
        if (magnitude <= 0.0 || abs(det) < 1e-12 * magnitude * magnitude * magnitude) {
            return 0.0
        }

        val a = (
            t2 * (s2 * s0 - s1 * s1) -
                s3 * (t1 * s0 - s1 * t0) +
                s2 * (t1 * s1 - s2 * t0)
            ) / det
        val b = (
            s4 * (t1 * s0 - s1 * t0) -
                t2 * (s3 * s0 - s1 * s2) +
                s2 * (s3 * t0 - t1 * s2)
            ) / det
        val c = (
            s4 * (s2 * t0 - t1 * s1) -
                s3 * (s3 * t0 - t1 * s2) +
                t2 * (s3 * s1 - s2 * s2)
            ) / det

        // Reported on the same weighted scale the fit minimised, so that the two
        // sides of the F test are comparable quantities.
        var rss = 0.0
        for (i in from until until) {
            val x = g[i]
            val r = (v[i] - (a * x * x + b * x + c)) / v[i]
            rss += r * r
        }
        return rss
    }
}
