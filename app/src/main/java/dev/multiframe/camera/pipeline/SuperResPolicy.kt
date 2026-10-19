package dev.multiframe.camera.pipeline

/** Single-viewpoint accumulation; mosaic launch is a separate Labs action. */
object SuperResPolicy {
    fun wantsCanvas(mode: CaptureMode, zoom: Float, capable: Boolean, opticalZoom: Float = 1f): Boolean =
        capable && (zoom > 1.001f || kotlin.math.abs(opticalZoom - 2f) < 0.25f || kotlin.math.abs(opticalZoom - 10f) < 1f)
}
