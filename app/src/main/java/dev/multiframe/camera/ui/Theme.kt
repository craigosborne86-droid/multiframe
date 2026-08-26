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
