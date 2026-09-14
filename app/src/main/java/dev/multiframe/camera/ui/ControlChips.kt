package dev.multiframe.camera.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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

/**
 * The settings screen's chips, wrapped to the width they have. Settings only
 * -- see [ControlBar.settings]. Wrapped rather than scrolled: on a screen of
 * their own there is room for all of them, and a row that has to be scrolled
 * to be read is the thing the settings screen exists to end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsChips(
    specs: List<ControlSpec>,
    onControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        specs.forEach { spec -> ControlChip(spec) { onControl(spec.id) } }
    }
}

/**
 * The openers over the picture -- see [ControlBar.chrome]. Drawn as an icon
 * rather than a word: the top of the viewfinder is the one place a label is
 * clutter, and a pair of sliders is understood without one. A pill, like
 * every setting, and not a circle: circles are lenses.
 */
@Composable
fun ChromeOpeners(
    specs: List<ControlSpec>,
    onControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        specs.forEach { spec ->
            Box(
                modifier = Modifier
                    .heightIn(min = MinTouch)
                    .alpha(if (spec.enabled) 1f else 0.38f)
                    .clip(PillShape)
                    .background(if (spec.active) Ink.Bone else Ink.Pane)
                    .border(1.dp, if (spec.active) Ink.Bone else Ink.Hairline, PillShape)
                    .clickable(enabled = spec.enabled) { onControl(spec.id) }
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = spec.label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (spec.id) {
                        ControlBar.SETTINGS -> Icons.Outlined.Tune
                        else -> Icons.Outlined.Info
                    },
                    contentDescription = null,
                    tint = if (spec.active) Ink.OnBone else Ink.Bone,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * The mode carousel under the shutter -- see [ControlBar.carousel].
 *
 * Words, not pills: the selected mode is set in bone and the others in the
 * muted ink, the way a camera's mode dial shows one engraving lit and the
 * rest in the metal. AUTO's reading -- what it settled on -- sits beside it in
 * the accent, small. Every entry is a full touch target whatever its word's
 * width.
 */
@Composable
fun ModeCarousel(
    specs: List<ControlSpec>,
    onControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (specs.size < 2) return
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fadedEdges(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        specs.forEach { spec ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .alpha(if (spec.enabled) 1f else 0.38f)
                    .heightIn(min = MinTouch)
                    .clip(PillShape)
                    .clickable(enabled = spec.enabled) { onControl(spec.id) }
                    .padding(horizontal = 14.dp)
                    .semantics {
                        contentDescription = spec.value?.let { "${spec.label} $it" } ?: spec.label
                        selected = spec.active
                    },
            ) {
                Text(
                    text = spec.label,
                    style = if (spec.active) Type.ModeSelected else Type.Mode,
                    color = if (spec.active) Ink.Bone else Ink.Muted,
                )
                spec.value?.let { value ->
                    Text(
                        text = value,
                        style = Type.Gauge,
                        color = Ink.Amber,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
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
internal fun ControlChip(spec: ControlSpec, onClick: () -> Unit) {
    val shape: Shape = if (spec.kind == ControlKind.Action) ActionShape else PillShape
    val filled = spec.active && spec.kind != ControlKind.Cycle
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
            style = if (spec.kind == ControlKind.Action) Type.Action else Type.Control,
            color = if (filled) Ink.OnBone else Ink.Bone,
        )
        spec.value?.let { value ->
            Text(
                text = value,
                style = Type.Gauge,
                color = when {
                    filled -> Ink.OnBone
                    spec.valueIsReading -> Ink.Amber
                    else -> Ink.Muted
                },
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}
