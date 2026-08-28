package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CameraMetadata

/**
 * Everything the app should remember between launches.
 *
 * A camera that forgets its frame count, its lens and whether the user turned
 * the highlight guard off is a camera that has to be reconfigured every time it
 * opens, which no one does more than twice before giving up on the settings.
 */
data class AppSettings(
    val manual: ManualSettings = ManualSettings(),
    val burstFrames: Int = 8,
    /** Physical camera id of the chosen lens, or null to use the default. */
    val lensId: String? = null,
    val mergeEnabled: Boolean = true,
    val highlightGuard: Boolean = true,
    /**
     * On by default. Zero shutter lag is the headline of this camera -- the
     * frames already exist when the button is pressed -- and defaulting it off
     * made the app feel slower than it is, for no benefit but a smaller ring.
     */
    val zslEnabled: Boolean = true,
    val captureMode: CaptureMode = CaptureMode.AUTO,
    /** Self-timer delay in seconds; zero is off. */
    val timerSeconds: Int = 0,
    /** Which composition guides are drawn, by [dev.multiframe.camera.ui.GuideMode] name. */
    val guides: String = "OFF",
) {

    /**
     * Reduces to strings, which is the whole persistence format.
     *
     * Deliberately not serialisation of the object graph. A stored blob that
     * cannot be read by a later version is worse than a missing setting: it
     * either crashes on upgrade or has to be version-gated forever. Independent
     * keys degrade one at a time, and an unrecognised one is simply ignored.
     */
    fun encode(): Map<String, String> = mapOf(
        KEY_MANUAL_EXPOSURE to manual.manualExposure.toString(),
        KEY_ISO to manual.iso.toString(),
        KEY_EXPOSURE_NS to manual.exposureTimeNs.toString(),
        KEY_MANUAL_FOCUS to manual.manualFocus.toString(),
        KEY_FOCUS_DIOPTRES to manual.focusDiopters.toString(),
        KEY_AWB to manual.awbMode.toString(),
        KEY_EV to manual.evIndex.toString(),
        KEY_SUPPRESS_ISP to manual.suppressIspProcessing.toString(),
        KEY_BURST to burstFrames.toString(),
        KEY_MERGE to mergeEnabled.toString(),
        KEY_GUARD to highlightGuard.toString(),
        KEY_ZSL to zslEnabled.toString(),
        KEY_MODE to captureMode.name,
        KEY_TIMER to timerSeconds.toString(),
        KEY_GUIDES to guides,
    ) + (lensId?.let { mapOf(KEY_LENS to it) } ?: emptyMap())

    companion object {
        const val KEY_MANUAL_EXPOSURE = "manualExposure"
        const val KEY_ISO = "iso"
        const val KEY_EXPOSURE_NS = "exposureNs"
        const val KEY_MANUAL_FOCUS = "manualFocus"
        const val KEY_FOCUS_DIOPTRES = "focusDioptres"
        const val KEY_AWB = "awb"
        const val KEY_EV = "ev"
        const val KEY_SUPPRESS_ISP = "suppressIsp"
        const val KEY_BURST = "burst"
        const val KEY_LENS = "lens"
        const val KEY_MERGE = "merge"
        const val KEY_GUARD = "guard"
        const val KEY_ZSL = "zsl"
        const val KEY_MODE = "mode"
        const val KEY_TIMER = "timer"
        const val KEY_GUIDES = "guides"

        private const val PREFERENCES = "multiframe.settings"

        /** The delays the timer control cycles through, including off. */
        val TIMER_DELAYS = setOf(0, 3, 10)

        /**
         * Rebuilds from stored strings.
         *
         * Every field falls back to its default independently, so a value
         * written by another version, corrupted, or simply absent costs that
         * one setting rather than the whole configuration.
         */
        fun decode(stored: Map<String, String>): AppSettings {
            val defaults = AppSettings()

            fun bool(key: String, fallback: Boolean) =
                stored[key]?.toBooleanStrictOrNull() ?: fallback

            fun int(key: String, fallback: Int) = stored[key]?.toIntOrNull() ?: fallback

            return AppSettings(
                manual = ManualSettings(
                    manualExposure = bool(KEY_MANUAL_EXPOSURE, defaults.manual.manualExposure),
                    iso = int(KEY_ISO, defaults.manual.iso),
                    exposureTimeNs = stored[KEY_EXPOSURE_NS]?.toLongOrNull()
                        ?: defaults.manual.exposureTimeNs,
                    manualFocus = bool(KEY_MANUAL_FOCUS, defaults.manual.manualFocus),
                    focusDiopters = stored[KEY_FOCUS_DIOPTRES]?.toFloatOrNull()
                        ?: defaults.manual.focusDiopters,
                    awbMode = int(KEY_AWB, defaults.manual.awbMode),
                    evIndex = int(KEY_EV, defaults.manual.evIndex),
                    suppressIspProcessing = bool(
                        KEY_SUPPRESS_ISP, defaults.manual.suppressIspProcessing,
                    ),
                ),
                burstFrames = int(KEY_BURST, defaults.burstFrames).coerceIn(1, 32),
                lensId = stored[KEY_LENS]?.takeIf { it.isNotBlank() },
                mergeEnabled = bool(KEY_MERGE, defaults.mergeEnabled),
                highlightGuard = bool(KEY_GUARD, defaults.highlightGuard),
                zslEnabled = bool(KEY_ZSL, defaults.zslEnabled),
                // An unrecognised mode name -- from an older or newer version --
                // costs the mode rather than the whole configuration.
                captureMode = stored[KEY_MODE]?.let { name ->
                    CaptureMode.entries.firstOrNull { it.name == name }
                } ?: defaults.captureMode,
                // A self-timer that forgets is worse than none, because you
                // find out by missing the shot. Clamped to the delays the
                // control can actually cycle to, so a stored value from
                // anywhere else cannot strand it.
                timerSeconds = int(KEY_TIMER, defaults.timerSeconds)
                    .takeIf { it in TIMER_DELAYS } ?: defaults.timerSeconds,
                guides = stored[KEY_GUIDES]?.takeIf { it.isNotBlank() } ?: defaults.guides,
            )
        }

        /**
         * Validates against what this camera can actually do.
         *
         * A setting restored from a previous device, or from before the user
         * switched lenses, can name hardware that is not here. Sending it would
         * have the camera reject the whole request, so it is corrected on the
         * way in instead.
         */
        fun AppSettings.reconcile(caps: CameraCapabilities, lenses: List<Lens>): AppSettings {
            val lens = lensId?.takeIf { id -> lenses.any { it.cameraId == id } }
            return copy(
                lensId = lens,
                manual = manual.copy(
                    manualExposure = manual.manualExposure && caps.hasManualSensor,
                    manualFocus = manual.manualFocus && caps.hasManualFocus,
                    awbMode = if (caps.awbModes.contains(manual.awbMode)) manual.awbMode
                    else CameraMetadata.CONTROL_AWB_MODE_AUTO,
                    evIndex = manual.effectiveEvIndex(caps),
                ),
            )
        }

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            @Suppress("UNCHECKED_CAST")
            val stored = prefs.all.mapNotNull { (k, v) ->
                (v as? String)?.let { k to it }
            }.toMap()
            return decode(stored)
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    clear()
                    settings.encode().forEach { (k, v) -> putString(k, v) }
                }
                .apply()
        }
    }
}
