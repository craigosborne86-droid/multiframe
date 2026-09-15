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
    val highlightGuard: Boolean = true,
    /**
     * Whether every capture writes the merged raw as a DNG beside the JPEG.
     * On by default: the DNG is the same merged data the JPEG was developed
     * from, and is what makes the capture a photograph rather than a
     * rendering. Off for the photographer who wants the space back.
     */
    val dngEnabled: Boolean = true,
    /**
     * The mode the viewfinder opens in. Never [CaptureMode.SUPER_RES]: that
     * mode holds the telephoto and the ring while it is selected, and an app
     * that reopened holding them would be reopening mid-gesture. The screen
     * stores AUTO in its place.
     */
    val captureMode: CaptureMode = CaptureMode.AUTO,
    /** Self-timer delay in seconds; zero is off. */
    val timerSeconds: Int = 0,
    /** Which composition guides are drawn, by [dev.multiframe.camera.ui.GuideMode] name. */
    val guides: String = "OFF",
    /**
     * Which Super Res engine the mode uses: the single-framing 2x canvas or
     * a telephoto grid, and which grid. See [MosaicPreset]. Remembered
     * because it is a decision about the kind of picture, not about the
     * shot. The 2X set-once toggle this mode absorbed is gone: a 2x canvas
     * is `ONE_SHOT`, chosen above the shutter in Super Res.
     */
    val mosaicPreset: MosaicPreset = MosaicPreset.DETAIL_BOOST,
    /**
     * Whether the live histogram is drawn under the exposure gauge. Off by
     * default: the viewfinder shows the picture and the three numbers that
     * describe its exposure, and the plot is for the photographer who wants
     * to see the distribution behind them.
     */
    val histogram: Boolean = false,
    /**
     * Whether the bilateral-grid local tone operator runs in the develop.
     *
     * On by default since the sixth field test. The operator at
     * [DevelopParams.LOCAL_TONE_ON] takes in half of each neighbourhood's
     * range about its mean and carries the detail through untouched: the
     * room and its window both land on the page, and the micro-contrast the
     * PRD's photographic rendering is protective of is exactly what the
     * detail layer keeps. Off is still a choice, in the settings.
     */
    val localTone: Boolean = true,
    /**
     * What the developed image is written as. The DNG is unaffected.
     *
     * JPEG by default, for the compatibility reason in [OutputFormat].
     */
    val outputFormat: OutputFormat = OutputFormat.JPEG,
) {

    /** The develop's parameters as these settings ask for them. */
    val developParams: DevelopParams
        get() = DevelopParams(localTone = if (localTone) DevelopParams.LOCAL_TONE_ON else 0f)

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
        KEY_GUARD to highlightGuard.toString(),
        KEY_DNG to dngEnabled.toString(),
        KEY_MOSAIC_PRESET to mosaicPreset.name,
        KEY_LOCAL_TONE to localTone.toString(),
        KEY_MODE to captureMode.name,
        KEY_TIMER to timerSeconds.toString(),
        KEY_GUIDES to guides,
        KEY_FORMAT to outputFormat.name,
        KEY_HISTOGRAM to histogram.toString(),
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
        const val KEY_GUARD = "guard"
        const val KEY_DNG = "dng"
        const val KEY_MODE = "mode"
        const val KEY_TIMER = "timer"
        const val KEY_GUIDES = "guides"
        const val KEY_MOSAIC_PRESET = "mosaicPreset"
        const val KEY_LOCAL_TONE = "localTone"
        const val KEY_FORMAT = "outputFormat"
        const val KEY_HISTOGRAM = "histogram"

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
                highlightGuard = bool(KEY_GUARD, defaults.highlightGuard),
                dngEnabled = bool(KEY_DNG, defaults.dngEnabled),
                // An unrecognised mode name -- from an older or newer version --
                // costs the mode rather than the whole configuration.
                captureMode = stored[KEY_MODE]?.let { name ->
                    CaptureMode.entries.firstOrNull { it.name == name }
                }?.takeIf { it != CaptureMode.SUPER_RES } ?: defaults.captureMode,
                // A self-timer that forgets is worse than none, because you
                // find out by missing the shot. Clamped to the delays the
                // control can actually cycle to, so a stored value from
                // anywhere else cannot strand it.
                timerSeconds = int(KEY_TIMER, defaults.timerSeconds)
                    .takeIf { it in TIMER_DELAYS } ?: defaults.timerSeconds,
                guides = stored[KEY_GUIDES]?.takeIf { it.isNotBlank() } ?: defaults.guides,
                // The key was "sweepPreset" for one release; a phone that
                // stored it under that name keeps its choice.
                mosaicPreset = MosaicPreset.named(stored[KEY_MOSAIC_PRESET] ?: stored["sweepPreset"]),
                localTone = bool(KEY_LOCAL_TONE, defaults.localTone),
                outputFormat = OutputFormat.named(stored[KEY_FORMAT]),
                histogram = bool(KEY_HISTOGRAM, defaults.histogram),
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
                // The single-framing canvas needs a device that can hold one;
                // where it cannot, the mode's grid is what is remembered.
                mosaicPreset = if (mosaicPreset == MosaicPreset.ONE_SHOT && !caps.supportsSuperRes) {
                    MosaicPreset.DETAIL_BOOST
                } else mosaicPreset,
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
