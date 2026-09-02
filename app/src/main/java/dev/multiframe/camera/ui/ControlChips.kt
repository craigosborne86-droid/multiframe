package dev.multiframe.camera.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How a control looks, given what kind of thing it is.
 *
 * The palette already had two rules: nothing coloured sits on the photograph,
 * and selection is shown by weight rather than hue. That left no colour to
 * spend on telling a switch apart from a trigger, which is the distinction this
 * interface most needed to make -- so the third rule is **shape**.
 *
 *  * A **setting** is a full pill. Rounded, soft, and reversible-looking,
 *    because it is: it changes what the next capture will do and nothing else.
 *  * An **action** is squared off. It takes a photograph the moment it is
 *    touched, and it does not look like the things that do not.
 *
 * Shape survives being seen in peripheral vision at arm's length in sunlight,
 * which is the actual viewing condition, and it costs no colour at all.
 *
 * Within the settings, a value is set apart from its label and set in the
 * accent -- the same rule the sliders in [ControlsPanel] follow, where a number
 * is the only thing allowed to be coloured. `FRAMES 8` reads as an instrument;
 * `8 FRAMES` read as a mode whose name happened to start with a digit.
 *
 * ### Why the chips are 48dp tall
 *
 * They were 44. Material's minimum touch target is 48x48dp, and a screenshot
 * measured against the accessibility tree had every chip in the top row and
 * every action beside the shutter failing it -- seven controls, on a camera
 * that is meant to be operated one-handed while holding a phone still. The
 * height is [MinTouch] rather than more vertical padding so the number is
 * stated once and is the thing the guideline actually names.
 */
private val PillShape = RoundedCornerShape(24.dp)
private val ActionShape = RoundedCornerShape(8.dp)

/** Material's minimum touch target. Not a look; a floor. */
private val MinTouch = 48.dp

/** How far a scrolling row fades out at an edge it can still scroll towards. */
private const val FadeWidthPx = 96f

/**
 * Fade a scrolling row at whichever edges it can still travel towards.
 *
 * The trailing edge alone was not enough. Scrolled to the far end, the *first*
 * chip was sliced down its middle by the viewport with nothing to say why --
 * `ZSL OFF` rendered as `SL OFF`, which reads as a typo rather than as a row
 * that continues. A cut is only legible as "there is more this way" when the
 * edge it is cut against is soft.
 */
internal fun Modifier.fadedEdges(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    startX = 0f,
                    endX = FadeWidthPx,
                    colors = listOf(Color.Transparent, Color.Black),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    startX = size.width - FadeWidthPx,
                    endX = size.width,
                    colors = listOf(Color.Black, Color.Transparent),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** The row over the picture. Settings only -- see [ControlBar.modes]. */
@Composable
fun ControlRow(
    specs: List<ControlSpec>,
    onControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (openers, settings) = specs.partition { it.kind == ControlKind.Opener }
    val scroll = rememberScrollState()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The settings scroll, and on a narrow screen most of them start off
        // the edge. That is deliberate -- the row is longer than the phone.
        //
        // What is not deliberate is a pill sliced down the middle at the
        // boundary, which reads as a broken layout rather than as a hint that
        // there is more. So both edges fade, and each only when there is in
        // fact more that way: faded with nothing beyond it, the outermost
        // control would look disabled.
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .fadedEdges(scroll)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            settings.forEach { spec -> ControlChip(spec) { onControl(spec.id) } }
        }
        // The openers do not scroll. They are the way into the manual controls
        // and the way to find out what any of this does, and putting them at
        // the end of a row nine long meant they were never once on screen.
        if (openers.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            openers.forEach { spec -> ControlChip(spec) { onControl(spec.id) } }
        }
    }
}

/**
 * The strip beside the shutter. Actions only -- see [ControlBar.actions].
 *
 * Here rather than in the row above because everything in it writes a
 * photograph, and the shutter is where a camera puts the things that do.
 */
@Composable
fun ActionStrip(
    specs: List<ControlSpec>,
    onControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (specs.isEmpty()) return
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fadedEdges(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        specs.forEach { spec -> ControlChip(spec) { onControl(spec.id) } }
    }
}

@Composable
private fun ControlChip(spec: ControlSpec, onClick: () -> Unit) {
    val shape: Shape = if (spec.kind == ControlKind.Action) ActionShape else PillShape
    val filled = spec.active && spec.kind != ControlKind.Cycle
    // A cycler is never "on" -- it always holds some value -- so an active one
    // is marked by a brighter edge rather than by a fill. Filling it would say
    // the same thing a mode's fill says, which is the confusion being undone.
    val edge = when {
        filled -> Ink.Bone
        spec.active -> Ink.Bone
        else -> Ink.Hairline
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .alpha(if (spec.enabled) 1f else 0.38f)
            .heightIn(min = MinTouch)
            .background(if (filled) Ink.Bone else Ink.Pane, shape)
            .border(1.dp, edge, shape)
            .clickable(enabled = spec.enabled, onClick = onClick)
            .padding(horizontal = 15.dp)
            .semantics {
                contentDescription = spec.value?.let { "${spec.label} $it" } ?: spec.label
            },
    ) {
        Text(
            text = spec.label,
            color = if (filled) Ink.OnBone else Ink.Bone,
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
            fontWeight = if (spec.kind == ControlKind.Action) FontWeight.SemiBold
                         else FontWeight.Medium,
        )
        spec.value?.let { value ->
            Text(
                text = value,
                // The one accent, spent where an instrument has always spent
                // it: on the reading, and not on the word for not having one.
                color = when {
                    filled -> Ink.OnBone
                    spec.valueIsReading -> Ink.Amber
                    else -> Ink.Muted
                },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}
