package dev.multiframe.camera.pipeline

/** Submission order shared with the capture caller; callbacks retain native ownership. */
internal fun submitSubPixelFrames(
    alternateCount: Int,
    setReference: () -> Boolean,
    addFrame: (Int) -> Boolean,
) {
    check(setReference()) { "2x reference submission rejected" }
    for (i in 0 until alternateCount) {
        check(addFrame(i)) { "2x frame ${i + 2}/${alternateCount + 1} submission rejected" }
    }
}
