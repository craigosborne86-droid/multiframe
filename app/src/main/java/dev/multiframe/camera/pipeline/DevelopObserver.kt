package dev.multiframe.camera.pipeline

/**
 * Optional, read-only diagnostics for the Kotlin reference develop. Native production
 * rendering never installs this observer. Pixel callbacks may run on parallel row
 * workers; implementations must own disjoint output locations and must not mutate rgb.
 * Frame callbacks are synchronous and must not mutate or retain the supplied image.
 */
interface DevelopObserver {
    enum class LinearStage { BALANCED_DEMOSAIC, MATRIX, PRE_TONE, POST_TONE }
    enum class DisplayStage { PRE_CHROMA, POST_CHROMA, POST_DEFRINGE, POST_SHARPEN }

    fun linear(stage: LinearStage, x: Int, y: Int, rgb: FloatArray)
    fun display(stage: DisplayStage, pixels: IntArray, width: Int, height: Int)
}
