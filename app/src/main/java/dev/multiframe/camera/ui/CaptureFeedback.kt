package dev.multiframe.camera.ui

/** Release feedback is ordered after the sensor-side capture operation. */
internal object CaptureFeedback {
    fun <T> afterSync(capture: () -> T, successful: (T) -> Boolean, vibrate: () -> Unit): T {
        val result = capture()
        if (successful(result)) vibrate()
        return result
    }

    suspend fun <T> afterSuspending(
        capture: suspend () -> T,
        successful: (T) -> Boolean,
        vibrate: () -> Unit,
    ): T {
        val result = capture()
        if (successful(result)) vibrate()
        return result
    }
}
