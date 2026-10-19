package dev.multiframe.camera.ui

import android.view.HapticFeedbackConstants
import android.view.View
import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Titanium Rangefinder tokens transcribed from the supplied CSS reference.
 * Amber marks selected mechanical controls and readings in the console; on
 * the photograph only green (level), red (clipping) and cyan (peaking)
 * appear. These are interface cues, not image processing.
 */
/** Compatibility names for older non-capture call sites. Every value comes from TitaniumTokens. */
object Ink {
    val Ground = TitaniumTokens.Chassis
    val Bone = TitaniumTokens.TelemetryWhite
    val BoneDim = TitaniumTokens.TelemetryWhite.copy(alpha = .5f)
    val Pane = TitaniumTokens.PlateSurface
    val PaneSolid = TitaniumTokens.PlateSurface
    val Hairline = TitaniumTokens.HairlineBorder
    val Muted = TitaniumTokens.TelemetryDim
    val Amber = TitaniumTokens.OpticalAmber
    val AmberDim = TitaniumTokens.OpticalAmber.copy(alpha = .4f)
    val Panel = TitaniumTokens.PlateSurface
    val OnBone = TitaniumTokens.Chassis
    val OnBoneMuted = TitaniumTokens.TitaniumM6Dark
    val Edge = TitaniumTokens.FrameLine
    val Readout = TitaniumTokens.Chassis.copy(alpha = .8f)
    val Faint = TitaniumTokens.Chassis.copy(alpha = .4f)
    val ScrimTop = TitaniumTokens.Chassis.copy(alpha = .65f)
    val ScrimBottom = TitaniumTokens.Chassis.copy(alpha = .72f)
    val Guide = TitaniumTokens.TelemetryWhite.copy(alpha = .3f)
    val GuideStrong = TitaniumTokens.TelemetryWhite.copy(alpha = .4f)
    val GuideFaint = TitaniumTokens.TelemetryWhite.copy(alpha = .1f)
    val GuideMid = TitaniumTokens.TelemetryWhite.copy(alpha = .27f)
    val GuideText = TitaniumTokens.TelemetryWhite.copy(alpha = .73f)
    val GuideBright = TitaniumTokens.TelemetryWhite.copy(alpha = .8f)
    val Level = TitaniumTokens.PhosphorGreen
    val Clipping = TitaniumTokens.ClippingRed
    val Peaking = TitaniumTokens.SignalCyan
    val Confirm = TitaniumTokens.PhosphorGreen
    val Danger = TitaniumTokens.Danger
    val Sheet = TitaniumTokens.ProofVelvet
    val SheetBody = TitaniumTokens.TelemetryWhite
    val SheetMuted = TitaniumTokens.TelemetryDim
    val SheetCode = TitaniumTokens.PlateSurface
}

/** Compatibility type names; the type scale is declared only in TitaniumTokens. */
object Type {
    val Gauge = TitaniumTokens.Gauge
    val GaugeLarge = TitaniumTokens.GaugeLarge
    val GaugeBone = TitaniumTokens.GaugeBone
    val Label = TitaniumTokens.Label
    val Control = TitaniumTokens.Control
    val Section = TitaniumTokens.Section
    val Status = TitaniumTokens.Status
    val Countdown = TitaniumTokens.Countdown
    val Instruction = TitaniumTokens.Instruction
    val Action = TitaniumTokens.Action
    val SliderLabel = TitaniumTokens.SliderLabel
    val SliderValue = TitaniumTokens.SliderValue
    val Unit = TitaniumTokens.Unit
    val Indicator = TitaniumTokens.Indicator
    val Dot = TitaniumTokens.Dot
    val Spec = TitaniumTokens.Spec
    val LensFocal = TitaniumTokens.LensFocal
    val LensZoom = TitaniumTokens.LensZoom
    val Mode = TitaniumTokens.Mode
    val ModeSelected = TitaniumTokens.ModeSelected
    val Badge = TitaniumTokens.Badge
    val Reset = TitaniumTokens.Reset
}

/**
 * Tactile feedback for every state change worth feeling.
 *
 * Centralised here so every haptic in the app is catalogued in one place, and
 * so the vocabulary can be audited against the mapping table in
 * docs/REFERENCE.md section 1.3.
 *
 * **No haptics during capture.** Vibration motor activation during the burst
 * introduces motion blur. CaptureFeedback emits the mechanical release only
 * after the requested frames have been captured or locked.
 */
object Haptics {
    private fun record(event: String, emitted: Boolean) {
        if (dev.multiframe.camera.BuildConfig.DEBUG)
            android.util.Log.d("TitaniumHaptics", "$event emitted=$emitted")
    }
    /** Two short release impacts, after a ZSL burst is locked. */
    fun mechanicalRelease(context: Context) {
        runCatching {
            val motor = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            motor.defaultVibrator.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 12, 40, 9), intArrayOf(0, 180, 0, 120), -1,
            ))
            record("mechanicalRelease after capture", true)
        }
    }
    /** Shutter pressed — a photograph was taken. */
    fun shutter(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM).also { record("shutter", it) }

    /** Self-timer tick, slider detent (ISO round number, EV zero). */
    fun tick(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK).also { record("tick", it) }

    /** A manual dial's value change: a distinct mechanical click at each detent. */
    fun dialDetent(view: View) {
        val played = runCatching {
            val motor = view.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            motor.defaultVibrator.takeIf { it.hasVibrator() }?.let {
                it.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                true
            } ?: false
        }.getOrDefault(false)
        if (!played) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        record("dialDetent CLICK", played)
    }

    /** Mode toggle, cycle step, lens switch — discrete mechanical detent. */
    fun segment(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK).also { record("segment", it) }

    /** Mosaic tile rejected, invalid action, error. */
    fun reject(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)

    /** Mosaic tile locked, gallery delete confirmed. */
    fun confirm(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}

private val MultiframeColors = darkColorScheme(
    primary = Ink.Amber,
    onPrimary = Ink.OnBone,
    surface = Ink.Panel,
    onSurface = Ink.Bone,
    background = Ink.Ground,
    onBackground = Ink.Bone,
)

@Composable
fun MultiframeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MultiframeColors, content = content)
}
