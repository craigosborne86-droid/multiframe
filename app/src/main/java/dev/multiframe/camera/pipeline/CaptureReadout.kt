package dev.multiframe.camera.pipeline

import kotlin.math.roundToInt

/**
 * What a capture actually did, in a form a person can read.
 *
 * ### Why this exists
 *
 * This app's whole argument is that many raw frames beat one, and it measures
 * that per capture — how many frames it merged, and how much of them survived
 * rejection. Both went to logcat and nowhere else, so the one thing that makes
 * this camera different from the one already on the phone was invisible to the
 * person holding it.
 *
 * ### The number that is not here, and why
 *
 * The obvious readout is "noise ÷3.5", and it cannot honestly be derived from
 * what is recorded. Merging weights each frame by how far it disagrees with the
 * reference, and the improvement in signal-to-noise of a weighted mean is
 * `Σw / √(Σw²)` — which is **scale-invariant**. Halving every weight changes
 * nothing at all. Only the *variation* between weights matters, and only their
 * mean is kept.
 *
 * So [MergeReadout] reports what was measured: the frames that went in, and the
 * share of them that survived. A number that sounds better and is not supported
 * would be worse than no number, particularly in an app whose entire claim is
 * that it measures things properly.
 *
 * "Kept" is worth reading correctly: it is not a quality score. A still scene
 * on a braced phone keeps almost everything; a moving one keeps less *because
 * the merge is doing its job* and refusing to smear what moved.
 */
object CaptureReadout {

    /**
     * The headline for a finished capture.
     *
     * [framesMerged] counts every frame that went into the picture, including
     * the reference. [meanContribution] is the mean weight given to the others,
     * in 0..1; the reference always carries a full share and is not in it.
     */
    fun of(framesMerged: Int, meanContribution: Float): String {
        if (framesMerged <= 1) return "1 frame"
        val kept = (meanContribution.coerceIn(0f, 1f) * 100).roundToInt()
        return "$framesMerged frames · $kept% kept"
    }

    /**
     * The same, with the effective frame count spelled out.
     *
     * A burst of twelve that kept half of each is not a burst of twelve, and
     * this is the honest way to say so: the reference plus what the rest
     * actually contributed. Shown where there is room for it.
     */
    fun detailed(framesMerged: Int, meanContribution: Float): String {
        if (framesMerged <= 1) return "1 frame"
        val effective = effectiveFrames(framesMerged, meanContribution)
        return "%s · %.1f effective".format(of(framesMerged, meanContribution), effective)
    }

    /**
     * The reference frame, plus the share each other frame contributed.
     *
     * Not a noise figure — see the note above about why one cannot be had from
     * this — but it is the honest answer to "how much of that burst is in the
     * picture".
     */
    fun effectiveFrames(framesMerged: Int, meanContribution: Float): Float {
        if (framesMerged <= 1) return 1f
        return 1f + (framesMerged - 1) * meanContribution.coerceIn(0f, 1f)
    }
}
