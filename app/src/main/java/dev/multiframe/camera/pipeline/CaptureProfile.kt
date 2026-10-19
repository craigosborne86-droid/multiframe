package dev.multiframe.camera.pipeline

/** Panel visibility never participates in these capture decisions. WB is independent. */
fun ManualSettings.returnToAuto(): ManualSettings = copy(manualExposure = false, rememberedManualFocus = manualFocus || rememberedManualFocus, manualFocus = false, evIndex = 0)
fun ManualSettings.restoreManual(caps: CameraCapabilities?): ManualSettings = copy(
    manualExposure = caps?.hasManualSensor == true,
    manualFocus = (manualFocus || rememberedManualFocus) && caps?.hasManualFocus == true,
)

object NextCaptureSummary {
    fun of(settings: ManualSettings, frames: Int, dng: Boolean, zoom: Float, superRes: Boolean, locale: java.util.Locale = java.util.Locale.getDefault()): String = buildString {
        append(if (settings.manualExposure) "ISO ${settings.iso} · ${settings.shutterLabel(locale)}" else "Exposure: Auto")
        append(" · Burst: $frames frames · DNG: ${if (dng) "On" else "Off"}")
        if (zoom >= 2f && superRes) append(" · Subpixel merge requested")
    }
}

/** Presentation outcome derived from persistence, independently of merge statistics. */
data class CaptureSaveFeedback(val message: String?, val hasSavedOutput: Boolean) {
    companion object {
        fun forOutputs(rendered: Boolean, dng: Boolean, requestedDng: Boolean): CaptureSaveFeedback =
            CaptureSaveFeedback(when {
                !rendered && !dng -> "Photograph could not be saved. Check storage and try again."
                !rendered -> "DNG saved. Rendered image could not be saved."
                requestedDng && !dng -> "Image saved. DNG could not be saved."
                else -> null
            }, rendered || dng)
    }
}

/** Immutable UI/capture configuration, also used to restore a temporary Labs session. */
data class CaptureConfigurationSnapshot(val settings: ManualSettings, val mode: CaptureMode,
    val frames: Int, val manualBurst: Boolean, val timerSeconds: Int, val lensId: String?,
    val zoom: Float, val proVisible: Boolean, val fullResolution: Boolean) {
    /** Persist the temporary session's return point, not an acquisition job. */
    fun encode(): Map<String, String> = AppSettings(manual = settings, captureMode = mode,
        burstFrames = frames, manualBurst = manualBurst, timerSeconds = timerSeconds,
        lensId = lensId, fullResolution = fullResolution).encode() +
        mapOf("sweepZoom" to zoom.toString(), "sweepPro" to proVisible.toString())

    companion object {
        fun decode(values: Map<String, String>): CaptureConfigurationSnapshot? {
            if (!values.containsKey("sweepZoom")) return null
            val s = AppSettings.decode(values)
            return CaptureConfigurationSnapshot(s.manual, s.captureMode, s.burstFrames, s.manualBurst,
                s.timerSeconds, s.lensId, values["sweepZoom"]?.toFloatOrNull() ?: 1f,
                values["sweepPro"]?.toBooleanStrictOrNull() ?: false, s.fullResolution)
        }
    }
}
