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

    /**
     * The roll-off computed per pixel rather than read from the table, which is
     * what shipped until the table replaced it. Not an ablation: it renders the
     * same picture the slow way, so timing it against [FULL] is what the table
     * bought, and comparing the two outputs is what it cost.
     */
    const val EXACT_SHOULDER = 8

    /**
     * The demosaic asking each pixel whether it is on the border and reading
     * its own site out of the CFA pattern, which is what shipped until the loop
     * was split by parity. Not an ablation: it reconstructs the same picture
     * the slow way, through the same two arithmetic bodies, so timing it
     * against [FULL] is what the restructuring bought.
     */
    const val UNSPLIT_DEMOSAIC = 9

    /**
     * The split loop taken a pixel at a time rather than eight at a time, which
     * is what shipped between the parity split and the NEON kernels. Not an
     * ablation: same reconstruction, one lane wide.
     */
    const val SCALAR_DEMOSAIC = 10

    /**
     * The whole display chain gone: no clamp, no scale to the table's index, no
     * float-to-int convert and no lookup -- just the low byte of the float's own
     * bits, which still consumes the value so nothing above it is deleted.
     *
     * [NO_DISPLAY] only ever swapped the lookup for a multiply-add, so it could
     * measure the table load and nothing else, and it came back 241 of 480
     * rounds over thirty runs. This is the probe that can see the rest, and what
     * it sees is a fifth of the pass. Renders noise on purpose.
     */
    const val NO_DISPLAY_CHAIN = 11
}
