package dev.multiframe.camera.pipeline

/** Single-viewpoint accumulation; mosaic launch is a separate Labs action. */
object SuperResPolicy {
    fun wantsCanvas(mode: CaptureMode, zoom: Float, capable: Boolean): Boolean =
        capable && (mode == CaptureMode.SUPER_RES || zoom >= 2f)
}
