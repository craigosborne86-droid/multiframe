package dev.multiframe.camera.pipeline

/**
 * Which piece of the develop's last pass to leave out when timing it.
 *
 * `demosaic+tone` is the largest item in a capture and reports as a single
 * figure, and this project's recurring lesson is that a single figure is
 * usually two. The pieces cannot be timed separately because they are fused --
 * the demosaic's three floats go straight into the colour matrix without ever
 * reaching memory -- so each variant is the whole pass with one piece removed,
 * and what the piece costs is what removing it saves.
 *
 * The values must match `ToneAblation` in bayer_merge.cpp.
 */
object ToneAblation {
    /** The pass as it ships. */
    const val FULL = 0

    /** Without the 3x3 colour matrix: nine multiplies and six adds a pixel. */
    const val NO_MATRIX = 1

    /** Without `renderLinear`: three maxima and two data-dependent branches. */
    const val NO_RENDER = 2

    /** With the display table replaced by a bare clamp, scale and round. */
    const val NO_DISPLAY = 3

    /** The demosaic on its own, writing its three values straight out. */
    const val NONE = 4

    /** `renderLinear` without the highlight roll-off: its exponential, its
     *  divide, and the branch that decides whether to take them. */
    const val NO_SHOULDER = 5

    /** `renderLinear` without the highlight desaturation. */
    const val NO_DESAT = 6

    /**
     * The roll-off with its exponential swapped for a reciprocal -- the same
     * saturating shape, and still a divide, so not free. What this variant
     * saves is therefore not what the call costs but what a fast approximation
     * of it could hope to recover. Renders a wrong picture on purpose.
     */
    const val NO_EXP = 7
}
