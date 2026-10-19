package dev.multiframe.camera.pipeline

object CaptureOrientation {
    fun degrees(sensor: Int, device: Int, facingBack: Boolean): Int =
        (sensor + (if (facingBack) device else -device) + 360) % 360
    fun exif(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }
}
