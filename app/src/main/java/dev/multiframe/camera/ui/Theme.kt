package dev.multiframe.camera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

    /** An inactive control: a pane over the image, not a block of colour. */
    val Pane = Color(0xB3161A1E)

    /** The edge of a pane. Enough to separate it from a dark photograph. */
    val Hairline = Color(0x26FFFFFF)

    /** Secondary text: present, not competing. */
    val Muted = Color(0xFF9BA1A7)

    /** The one accent. Values, sliders, and anything reporting a live reading. */
    val Amber = Color(0xFFFFB020)

    /** Behind a panel, where the image has to give way to be readable. */
    val Panel = Color(0xF00A0C0E)

    /** Ink on a bone fill. */
    val OnBone = Color(0xFF0B0D0F)

    /**
     * Secondary text directly over the photograph, where a pane would be more
     * furniture than the reading is worth.
     */
    val Subtle = Color(0x99FFFFFF)

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
     *    most of them.
     *  * [Dialogue] is behind something the user is meant to look at rather than
     *    read past: the sweep's progress.
     *  * [Faint] is behind a graphic that carries its own contrast, where a
     *    darker ground would fight the photograph for no gain.
     */
    val Readout = Color(0xCC000000)
    val Dialogue = Color(0xAA000000)
    val Faint = Color(0x66000000)

    /** The two ends of the scrims the controls sit on. */
    val ScrimTop = Color(0xA6000000)
    val ScrimBottom = Color(0xB8000000)

    /**
     * The guides, which are drawn straight onto the picture and so are the
     * faintest things here. A guide that competes with the photograph for
     * attention has stopped being a guide.
     */
    val Guide = Color(0x33FFFFFF)
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

    /** The About sheet, which covers the picture rather than floating on it. */
    val Sheet = Color(0xF2070A0D)
    val SheetBody = Color(0xFFD4DDE6)
    val SheetMuted = Color(0xFF8A97A5)
    val SheetCode = Color(0xFF161C22)
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
