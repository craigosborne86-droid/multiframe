package dev.multiframe.camera.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The palette, such as it is.
 *
 * Two rules decide all of it. Nothing coloured sits on top of the photograph,
 * because a saturated hue in the corner of the eye changes how the colours
 * under it are judged -- which is the one thing a camera whose argument is
 * neutrality cannot afford. And selection is shown by *weight* rather than by
 * hue: an active control is filled bone, an inactive one is a dark pane. That
 * leaves exactly one accent, spent only on numbers and live readings, where an
 * instrument has always spent it.
 */
object Ink {
    /** The ground, and what the app falls back to before a frame arrives. */
    val Ground = Color(0xFF000000)

    /** Active fill and primary text. Warm white rather than pure, which reads
     *  as paper next to a photograph instead of as a light leak. */
    val Bone = Color(0xFFF2F0EA)

    /** Bone at reduced presence: tertiary text, disabled labels. */
    val BoneDim = Color(0x80F2F0EA)

    /** An inactive control: a pane over the image, not a block of colour. */
    val Pane = Color(0xD9141A1F)

    /** Non-overlay screen backgrounds (gallery, settings). Opaque, not over the photograph. */
    val PaneSolid = Color(0xFF0F1215)

    /** The edge of a pane. Enough to separate it from a dark photograph. */
    val Hairline = Color(0x26FFFFFF)

    /** Secondary text: present, not competing. */
    val Muted = Color(0xFF9BA1A7)

    /** The one accent. Values, sliders, and anything reporting a live reading. */
    val Amber = Color(0xFFFFB020)

    /** Amber at reduced presence: pressed/focused state of accent elements. */
    val AmberDim = Color(0x66FFB020)

    /** Behind a panel, where the image has to give way to be readable. */
    val Panel = Color(0xF00A0C0E)

    /** Ink on a bone fill. */
    val OnBone = Color(0xFF0B0D0F)

    /** Secondary text on a bone fill: the lens strip's zoom factor. */
    val OnBoneMuted = Color(0xCC06121F)

    /** The edge of something showing a picture, rather than of a control. */
    val Edge = Color(0x66FFFFFF)

    /**
     * The grounds a reading can sit on, darkest first.
     *
     * There were five of these written as literals in five places -- 0xCC, 0xAA,
     * 0xA6, 0xB8 and 0x66 of black -- which is what a palette becomes when each
     * overlay picks its own as it is written. Named by what sits on them:
     *
     *  * [Readout] is behind text that must be legible over anything, which is
     *    most of them, and behind the countdown.
     *  * [Faint] is behind a graphic that carries its own contrast, where a
     *    darker ground would fight the photograph for no gain.
     *
     * There was a third, `Dialogue`, at 0xAA. It was the sweep's ground, and
     * 0xAA of black over a sunlit street is not a ground -- the numbers were
     * competing with the photograph they were reporting on. The sweep now uses
     * [Panel], the same surface as the manual controls, because it is the same
     * kind of thing: something the app puts in front of you deliberately.
     */
    val Readout = Color(0xCC000000)
    val Faint = Color(0x66000000)

    /** The two ends of the scrims the controls sit on. */
    val ScrimTop = Color(0xA6000000)
    val ScrimBottom = Color(0xB8000000)

    /**
     * The guides, which are drawn straight onto the picture and so are the
     * faintest things here. A guide that competes with the photograph for
     * attention has stopped being a guide.
     */
    val Guide = Color(0x4DFFFFFF)
    val GuideStrong = Color(0x66FFFFFF)
    val GuideFaint = Color(0x1AFFFFFF)
    val GuideMid = Color(0x44FFFFFF)
    val GuideText = Color(0xBBFFFFFF)
    val GuideBright = Color(0xCCFFFFFF)

    /**
     * The two exceptions to "nothing coloured sits on the photograph", written
     * down here so they are decisions rather than drift.
     *
     * Both are states the camera has to shout about, and both are conventions
     * older than any phone: a level that has arrived, and a highlight that has
     * gone. Neither is decoration and neither is persistent -- they appear when
     * the thing is true and vanish when it stops being true, which is what
     * keeps them from doing what a saturated hue in the corner of the eye
     * otherwise does to the colours judged under it.
     */
    val Level = Color(0xFF6FE39A)
    val Clipping = Color(0xFFFF6B6B)

    /**
     * Semantic colours for states outside the viewfinder. These never appear
     * on the photograph during normal operation. [Confirm] appears in the
     * sweep reticle guidance; [Danger] appears in gallery delete confirmation.
     */
    val Confirm = Color(0xFF5BC87A)
    val Danger = Color(0xFFE85B5B)

    /** The About sheet, which covers the picture rather than floating on it. */
    val Sheet = Color(0xFF06090C)
    val SheetBody = Color(0xFFD4DDE6)
    val SheetMuted = Color(0xFF8A97A5)
    val SheetCode = Color(0xFF161C22)
}

/**
 * The type scale.
 *
 * Monospace is reserved for values the user reads as numbers: ISO, shutter
 * speed, EV, zoom, tile count, coverage, focus distance, lens focal length,
 * countdown digits. The system default is used for labels, buttons, section
 * headers, and prose.
 *
 * Every composable that displays text uses one of these styles rather than
 * writing a raw [fontSize] or [fontFamily]. This is what makes the interface
 * look like one instrument instead of five prototypes.
 */
object Type {
    /** Live reading: ISO 400, 1/125s, 2.1x, 89%. The accent, monospaced. */
    val Gauge = TextStyle(
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = Ink.Amber,
    )

    /** Prominent reading: tile count during sweep, large zoom indicator. */
    val GaugeLarge = TextStyle(
        fontSize = 26.sp,
        fontFamily = FontFamily.Monospace,
        color = Ink.Amber,
    )

    /** Reading that is informational, not accented: coverage %, shutter label. */
    val GaugeBone = TextStyle(
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = Ink.Bone,
    )

    /** Static label next to a reading: "ISO", "SHUTTER", "EV". */
    val Label = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = Ink.Muted,
    )

    /** Chip/button text: "MERGE ON", "FRAMES", "GUIDES". */
    val Control = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        color = Ink.Bone,
    )

    /** Section header in the PRO panel: "EXPOSURE AND FOCUS", "WHITE BALANCE". */
    val Section = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = Ink.Muted,
    )

    /** Transient status: "raw burst...", "12 frames · 89% kept". */
    val Status = TextStyle(
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = Color.White,
    )

    /** Self-timer countdown numeral. */
    val Countdown = TextStyle(
        fontSize = 76.sp,
        fontFamily = FontFamily.Monospace,
        color = Ink.Bone,
    )

    /** Guidance text during sweep: "PAN SLOWLY". Uppercase by convention. */
    val Instruction = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = Ink.Muted,
    )

    /** Action chip labels beside the shutter. Slightly heavier than [Control]. */
    val Action = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = Ink.Bone,
    )

    /** Slider label in the PRO panel: "ISO", "SHUTTER", "FOCUS". */
    val SliderLabel = TextStyle(
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = Ink.Muted,
    )

    /** Slider value readout in the PRO panel. Amber, monospaced. */
    val SliderValue = TextStyle(
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = Ink.Amber,
    )

    /** Readout unit suffix: "EV", "mm", "s". Smaller, muted. */
    val Unit = TextStyle(
        fontSize = 11.sp,
        color = Ink.Muted,
    )

    /** "MAN" indicator in the readout bar. Bone, semi-bold. */
    val Indicator = TextStyle(
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = Ink.Bone,
    )

    /** Readout separator dot. */
    val Dot = TextStyle(
        fontSize = 13.sp,
        color = Ink.Muted,
    )

    /** The specification line at the bottom of the PRO panel. */
    val Spec = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = Ink.Muted,
    )

    /** Lens chip focal length. */
    val LensFocal = TextStyle(
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = Ink.Bone,
    )

    /** Lens chip zoom factor. */
    val LensZoom = TextStyle(
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = Ink.Muted,
    )

    /** Reset button text. */
    val Reset = TextStyle(
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Medium,
        color = Ink.Muted,
    )
}

/**
 * Tactile feedback for every state change worth feeling.
 *
 * Centralised here so every haptic in the app is catalogued in one place, and
 * so the vocabulary can be audited against the mapping table in
 * docs/REFERENCE.md section 1.3.
 *
 * **No haptics during capture.** Vibration motor activation during the burst
 * introduces motion blur. The shutter haptic fires at press time, before the
 * burst begins.
 */
object Haptics {
    /** Shutter pressed — a photograph was taken. */
    fun shutter(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

    /** Self-timer tick, slider detent (ISO round number, EV zero). */
    fun tick(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    /** Mode toggle, cycle step, lens switch — discrete mechanical detent. */
    fun segment(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)

    /** Sweep tile rejected, invalid action, error. */
    fun reject(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)

    /** Sweep tile locked, gallery delete confirmed. */
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
