package dev.multiframe.camera.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
 */
private val PillShape = RoundedCornerShape(20.dp)
/** How far the scrolling settings fade out at their trailing edge. */
private const val FadeWidthPx = 44f
private val ActionShape = RoundedCornerShape(6.dp)

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
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The settings scroll, and on a narrow screen most of them start off
        // the edge. That is deliberate -- the row is longer than the phone.
        //
        // What is not deliberate is a pill sliced down the middle at the
        // boundary, which reads as a broken layout rather than as a hint that
        // there is more. So the trailing edge fades, and only when there is
        // in fact more: faded with nothing beyond it, the last control would
        // look disabled.
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
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
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            settings.forEach { spec -> ControlChip(spec) { onControl(spec.id) } }
        }
        // The openers do not scroll. They are the way into the manual controls
        // and the way to find out what any of this does, and putting them at
        // the end of a row nine long meant they were never once on screen.
        if (openers.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
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
            .padding(horizontal = 4.dp)
            .alpha(if (spec.enabled) 1f else 0.38f)
            .background(if (filled) Ink.Bone else Ink.Pane, shape)
            .border(1.dp, edge, shape)
            .clickable(enabled = spec.enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
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
