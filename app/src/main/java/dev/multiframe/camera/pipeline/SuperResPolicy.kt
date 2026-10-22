package dev.multiframe.camera.pipeline

/** Single-viewpoint accumulation; mosaic launch is a separate Labs action. */
object SuperResPolicy {
    /**
     * Whether the next press builds the 2x canvas: at a zoom past the lens,
     * on the 2x/10x centre crops, or -- with HIGH RES on, in PHOTO -- at 1x,
     * where the full frame is accumulated at twice the sensor's sampling
     * (about 48 MP from a 12 MP readout). NIGHT keeps its merge: there the
     * frames are spent on noise, not resolution.
     */
    fun wantsCanvas(mode: CaptureMode, zoom: Float, capable: Boolean, opticalZoom: Float = 1f, highRes: Boolean = false): Boolean =
        capable && ((highRes && mode == CaptureMode.PHOTO) || zoom > 1.001f ||
            kotlin.math.abs(opticalZoom - 2f) < 0.25f || kotlin.math.abs(opticalZoom - 10f) < 1f)
}
