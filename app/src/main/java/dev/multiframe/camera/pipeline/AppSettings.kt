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
    val manualBurst: Boolean = false,
    val rememberedManualBurst: Boolean = false,
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
    /** PHOTO or NIGHT; retired mode names migrate to PHOTO. */
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    /** Self-timer delay in seconds; zero is off. */
    val timerSeconds: Int = 0,
    /** NIGHT's long exposure span in seconds, one of [LongExposureCapture.DURATIONS_SECONDS]; zero is off. */
    val longExposureSeconds: Int = 0,
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
     * Whether the live histogram is drawn in the reserved bottom control band. Off by
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
    /**
     * The sensor's un-binned maximum-resolution mode, where it has one
     * ([CameraCapabilities.supportsFullResolution]): a 50 MP raw at a few
     * frames a second on a ring of five, against the 12 MP binned readout at
     * thirty on a ring of thirty-two. Off by default; the `12 MP | 50 MP`
     * chip is the switch, and is absent on a sensor without the mode.
     */
    val fullResolution: Boolean = false,
    /**
     * Whether a JPEG carries its HDR rendition as an Ultra HDR gain map
     * ([HdrGainMap]): highlights up to two stops over SDR white on a screen
     * that has the headroom, today's picture on one that has not. Off by
     * default; the row is absent below API 34
     * ([CameraCapabilities.supportsHdrOutput]). HEIF is written without one.
     */
    val hdrOutput: Boolean = false,
    /**
     * Whether each JPEG carries signed C2PA Content Credentials stating it is
     * a computational capture, not generated ([ContentCredentials]). On by
     * default: it is the app's promise, made checkable.
     */
    val contentCredentials: Boolean = true,
) {

    /** The develop's parameters as these settings ask for them. */
    val developParams: DevelopParams
        get() = DevelopParams(
            localTone = if (localTone) DevelopParams.LOCAL_TONE_ON else 0f,
            shadowLift = if (localTone) DevelopParams.SHADOW_LIFT_ON else 0f,
            hdrHeadroom = if (hdrOutput) DevelopParams.HDR_HEADROOM else 0f,
        )

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
        "rememberedFocus" to manual.rememberedManualFocus.toString(),
        KEY_AWB to manual.awbMode.toString(),
        KEY_MANUAL_COLOR to manual.manualColor.toString(),
        "wbMode" to manual.whiteBalanceMode.name, "kelvin" to manual.kelvin.toString(), "tint" to manual.tint.toString(),
        KEY_EV to manual.evIndex.toString(),
        KEY_SUPPRESS_ISP to manual.suppressIspProcessing.toString(),
        KEY_MANUAL_BURST to manualBurst.toString(),
        "rememberedBurst" to rememberedManualBurst.toString(),
        KEY_BURST to FrameCountPolicy.normalize(burstFrames).toString(),
        KEY_GUARD to highlightGuard.toString(),
        KEY_DNG to dngEnabled.toString(),
        KEY_MOSAIC_PRESET to mosaicPreset.name,
        KEY_LOCAL_TONE to localTone.toString(),
        KEY_MODE to captureMode.name,
        KEY_TIMER to timerSeconds.toString(),
        KEY_LONG_EXPOSURE to longExposureSeconds.toString(),
        KEY_GUIDES to guides,
        KEY_FORMAT to outputFormat.name,
        KEY_HISTOGRAM to histogram.toString(),
        KEY_FULL_RESOLUTION to fullResolution.toString(),
        KEY_HDR_OUTPUT to hdrOutput.toString(),
        KEY_CONTENT_CREDENTIALS to contentCredentials.toString(),
    ) + (lensId?.let { mapOf(KEY_LENS to it) } ?: emptyMap())

    companion object {
        const val KEY_MANUAL_EXPOSURE = "manualExposure"
        const val KEY_ISO = "iso"
        const val KEY_EXPOSURE_NS = "exposureNs"
        const val KEY_MANUAL_FOCUS = "manualFocus"
        const val KEY_FOCUS_DIOPTRES = "focusDioptres"
        const val KEY_AWB = "awb"
        const val KEY_MANUAL_COLOR = "manualColor"
        const val KEY_EV = "ev"
        const val KEY_SUPPRESS_ISP = "suppressIsp"
        const val KEY_MANUAL_BURST = "manualBurst"
        const val KEY_BURST = "burst"
        const val KEY_LENS = "lens"
        const val KEY_GUARD = "guard"
        const val KEY_DNG = "dng"
        const val KEY_MODE = "mode"
        const val KEY_TIMER = "timer"
        const val KEY_LONG_EXPOSURE = "long_exposure"
        const val KEY_GUIDES = "guides"
        const val KEY_MOSAIC_PRESET = "mosaicPreset"
        const val KEY_LOCAL_TONE = "localTone"
        const val KEY_FORMAT = "outputFormat"
        const val KEY_HISTOGRAM = "histogram"
        const val KEY_FULL_RESOLUTION = "fullResolution"
        const val KEY_HDR_OUTPUT = "hdrOutput"
        const val KEY_CONTENT_CREDENTIALS = "contentCredentials"

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
                    rememberedManualFocus = bool("rememberedFocus", bool(KEY_MANUAL_FOCUS, false)),
                    focusDiopters = stored[KEY_FOCUS_DIOPTRES]?.toFloatOrNull()
                        ?: defaults.manual.focusDiopters,
                    awbMode = int(KEY_AWB, defaults.manual.awbMode),
                    manualColor = bool(KEY_MANUAL_COLOR, defaults.manual.manualColor),
                    whiteBalanceMode = WhiteBalanceMode.entries.firstOrNull { it.name == stored["wbMode"] } ?: WhiteBalanceMode.AUTO,
                    kelvin = int("kelvin", defaults.manual.kelvin),
                    tint = int("tint", defaults.manual.tint).coerceIn(-100, 100),
                    evIndex = int(KEY_EV, defaults.manual.evIndex),
                    suppressIspProcessing = bool(
                        KEY_SUPPRESS_ISP, defaults.manual.suppressIspProcessing,
                    ),
                ),
                burstFrames = FrameCountPolicy.normalize(int(KEY_BURST, defaults.burstFrames)),
                manualBurst = bool(KEY_MANUAL_BURST, defaults.manualBurst),
                rememberedManualBurst = bool("rememberedBurst", bool(KEY_MANUAL_BURST, false)),
                lensId = stored[KEY_LENS]?.takeIf { it.isNotBlank() },
                highlightGuard = bool(KEY_GUARD, defaults.highlightGuard),
                dngEnabled = bool(KEY_DNG, defaults.dngEnabled),
                // An unrecognised mode name -- from an older or newer version --
                // costs the mode rather than the whole configuration.
                captureMode = stored[KEY_MODE]?.let { name ->
                    CaptureMode.entries.firstOrNull { it.name == (if (name == "AUTO") "PHOTO" else name) }
                }?.takeIf { it == CaptureMode.PHOTO || it == CaptureMode.NIGHT } ?: defaults.captureMode,
                // A self-timer that forgets is worse than none, because you
                // find out by missing the shot. Clamped to the delays the
                // control can actually cycle to, so a stored value from
                // anywhere else cannot strand it.
                timerSeconds = int(KEY_TIMER, defaults.timerSeconds)
                    .takeIf { it in TIMER_DELAYS } ?: defaults.timerSeconds,
                longExposureSeconds = int(KEY_LONG_EXPOSURE, defaults.longExposureSeconds)
                    .takeIf { it in LongExposureCapture.DURATIONS_SECONDS } ?: defaults.longExposureSeconds,
                guides = stored[KEY_GUIDES]?.takeIf { it.isNotBlank() } ?: defaults.guides,
                // The key was "sweepPreset" for one release; a phone that
                // stored it under that name keeps its choice.
                mosaicPreset = MosaicPreset.named(stored[KEY_MOSAIC_PRESET] ?: stored["sweepPreset"]),
                localTone = bool(KEY_LOCAL_TONE, defaults.localTone),
                outputFormat = OutputFormat.named(stored[KEY_FORMAT]),
                histogram = bool(KEY_HISTOGRAM, defaults.histogram),
                fullResolution = bool(KEY_FULL_RESOLUTION, defaults.fullResolution),
                hdrOutput = bool(KEY_HDR_OUTPUT, defaults.hdrOutput),
                contentCredentials = bool(KEY_CONTENT_CREDENTIALS, defaults.contentCredentials),
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
                    awbMode = manual.awbMode.takeIf { it in caps.awbModes &&
                        (it != CameraMetadata.CONTROL_AWB_MODE_AUTO || !manual.manualColor || caps.supportsAwbLock) }
                        ?: (if (manual.manualColor) caps.awbModes.firstOrNull {
                            it != CameraMetadata.CONTROL_AWB_MODE_AUTO
                        } else null)
                        ?: CameraMetadata.CONTROL_AWB_MODE_AUTO,
                    manualColor = manual.manualColor && caps.hasManualColor,
                    evIndex = manual.effectiveEvIndex(caps),
                ),
                // The single-framing canvas needs a device that can hold one;
                // where it cannot, the mode's grid is what is remembered.
                mosaicPreset = if (mosaicPreset == MosaicPreset.ONE_SHOT && !caps.supportsSuperRes) {
                    MosaicPreset.DETAIL_BOOST
                } else mosaicPreset,
                // A sensor without the un-binned mode has no 50 MP to ask for.
                fullResolution = fullResolution && caps.supportsFullResolution,
                // A platform without Gainmap has no HDR rendition to write.
                hdrOutput = hdrOutput && caps.supportsHdrOutput,
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
