package dev.multiframe.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ManualSettings
import kotlin.math.ln
import kotlin.math.exp

/**
 * The manual controls, and everything else you set once.
 *
 * The division against the row over the viewfinder is deliberate: that row is
 * what a photographer changes between shots, and this is what they set and
 * leave. `A/B` and `GUARD` live here for that reason -- neither is touched
 * while composing, and in the row they were pushing things off the edge of the
 * screen.
 *
 * ### The panel does not draw its own ground
 *
 * It used to, with rounded top corners, while the action strip and the shutter
 * stayed outside it -- so the surface stopped in mid-air and the photograph
 * reappeared beneath it before the shutter. The screen now puts one ground
 * under the panel, the strip and the shutter together. See `CameraScreen`.
 */
@Composable
fun ControlsPanel(
    settings: ManualSettings,
    caps: CameraCapabilities?,
    onChange: (ManualSettings) -> Unit,
    modifier: Modifier = Modifier,
    abMode: Boolean = false,
    onAbMode: (Boolean) -> Unit = {},
    /** Null where there is no zero-shutter-lag stream to guard highlights for. */
    highlightGuard: Boolean? = null,
    onHighlightGuard: (Boolean) -> Unit = {},
    onReset: (() -> Unit)? = null,
) {
    if (caps == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 6.dp),
    ) {
        SectionLabel("Exposure and focus")

        Row(modifier = Modifier.padding(bottom = 2.dp)) {
            if (caps.hasManualSensor && (caps.hasIsoRange || caps.hasExposureRange)) {
                Toggle("AE ${if (settings.manualExposure) "MAN" else "AUTO"}", settings.manualExposure) {
                    onChange(settings.copy(manualExposure = !settings.manualExposure))
                }
            }
            if (caps.hasManualFocus) {
                Toggle("AF ${if (settings.manualFocus) "MAN" else "AUTO"}", settings.manualFocus) {
                    onChange(settings.copy(manualFocus = !settings.manualFocus))
                }
            }
            // Only offered where the ISP's processing can actually be switched off.
            if (caps.canDisableNoiseReduction() || caps.canDisableEdgeEnhancement()) {
                Toggle("ISP ${if (settings.suppressIspProcessing) "OFF" else "ON"}", settings.suppressIspProcessing) {
                    onChange(settings.copy(suppressIspProcessing = !settings.suppressIspProcessing))
                }
            }
        }

        if (settings.manualExposure && caps.hasManualSensor) {
            if (caps.hasIsoRange) {
                LabelledSlider(
                    label = "ISO",
                    value = settings.iso.toFloat(),
                    valueText = settings.iso.toString(),
                    range = caps.isoMin!!.toFloat()..caps.isoMax!!.toFloat(),
                ) { onChange(settings.copy(iso = it.toInt())) }
            }
            if (caps.hasExposureRange) {
                // Shutter speed spans several orders of magnitude, so the slider
                // is logarithmic or the short end would be unreachable.
                val lo = ln(caps.exposureMinNs!!.toDouble()).toFloat()
                val hi = ln(caps.exposureMaxNs!!.toDouble()).toFloat()
                LabelledSlider(
                    label = "SHUTTER",
                    value = ln(settings.exposureTimeNs.toDouble()).toFloat().coerceIn(lo, hi),
                    valueText = settings.shutterLabel(),
                    range = lo..hi,
                ) { onChange(settings.copy(exposureTimeNs = exp(it.toDouble()).toLong())) }
            }
        } else if (caps.supportsExposureCompensation) {
            LabelledSlider(
                label = "EV",
                value = settings.evIndex.toFloat(),
                valueText = "%+.1f".format(settings.evIndex * caps.evStep),
                range = caps.evMin.toFloat()..caps.evMax.toFloat(),
                centered = true,
            ) { onChange(settings.copy(evIndex = it.toInt())) }
        }

        if (settings.manualFocus && caps.hasManualFocus) {
            LabelledSlider(
                label = "FOCUS",
                value = settings.focusDiopters,
                valueText = settings.focusLabel(),
                range = 0f..caps.minFocusDiopters,
            ) { onChange(settings.copy(focusDiopters = it)) }
        }

        SectionLabel("White balance")

        // Scrolls, and says so at whichever end it can still travel towards.
        // Without the fade the last preset was sliced mid-word against the
        // screen edge -- "Shad" -- which reads as a layout that has given up
        // rather than as a row with more in it.
        val wb = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fadedEdges(wb)
                .horizontalScroll(wb),
        ) {
            ManualSettings.AWB_LABELS.forEach { (mode, label) ->
                if (caps.awbModes.contains(mode)) {
                    Toggle(label, settings.awbMode == mode) {
                        onChange(settings.copy(awbMode = mode))
                    }
                }
            }
        }

        SectionLabel("Set once")

        Row {
            // Writes both the merged and the unmerged version of the same
            // burst, which is the demonstration of what this camera is for --
            // and not something anyone changes shot to shot.
            Toggle("A/B ${if (abMode) "ON" else "OFF"}", abMode) { onAbMode(!abMode) }
            highlightGuard?.let { on ->
                Toggle("GUARD ${if (on) "ON" else "OFF"}", on) { onHighlightGuard(!on) }
            }
        }

        onReset?.let { reset ->
            // Somewhere to get back to from wherever the controls have ended
            // up. A camera you hand to someone else needs one of these, and
            // without it the only way back is to reinstall.
            //
            // Set apart from the toggles above rather than sitting flush with
            // them: it is the one control here that discards work, and it read
            // as just another chip in a column of chips.
            Box(
                modifier = Modifier
                    .padding(top = Gap)
                    .heightIn(min = MinTouch)
                    .border(BorderStroke(1.dp, Ink.Hairline), RoundedCornerShape(24.dp))
                    .clickable(onClick = reset)
                    .padding(horizontal = 18.dp)
                    .semantics { contentDescription = "Reset all settings" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "RESET ALL SETTINGS",
                    color = Ink.Muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // What this camera can actually do.
        //
        // It was the first thing in the panel, which gave a line of reference
        // text top billing over every control -- and being the widest thing
        // here it wrapped mid-phrase ("... focus to 11 / cm"), so the most
        // prominent line was also the most awkward one. It is a specification,
        // so it reads as a footnote: below a rule, at the end, where a wrap
        // costs nothing.
        Box(
            modifier = Modifier
                .padding(top = Gap)
                .fillMaxWidth()
                .height(1.dp)
                .background(Ink.Hairline),
        )
        Text(
            text = caps.summary(),
            color = Ink.Muted,
            // Not monospace, which is the one place in this app that rule is
            // worth breaking. Monospace at a size anyone can read is wider
            // than the panel, so the line wrapped mid-phrase -- "focus to 11
            // / cm" -- and the fix that kept the font was 9sp, which trades a
            // wrap for something nobody can read. This is a sentence about
            // what the camera can do, not a live reading, so it is set like
            // one and fits on a line.
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** The rhythm the panel is set on. One number, not five arbitrary ones. */
private val Gap = 14.dp
private val MinTouch = 48.dp

/**
 * The heading over a group of controls.
 *
 * The panel was one undifferentiated stack of chips and sliders with five
 * different gaps between them. Three quiet headings do more for it than any
 * amount of adjusting those gaps: they say *why* these controls are together.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Ink.Muted,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = Gap, bottom = 8.dp),
    )
}

/**
 * A labelled value with a rail to drag along.
 *
 * ### Why the track is drawn here rather than taken from Material
 *
 * Material 3's expressive slider draws a 16dp-tall filled track. Coloured with
 * this app's accent it put a solid saturated amber bar across a third of the
 * panel -- the largest and most saturated shape anywhere in the interface, for
 * a control that is not the most important thing on the screen. That breaks the
 * one rule the palette actually has: the accent is spent on *readings*, and a
 * bar is not a reading.
 *
 * So the rail is neutral and thin, weight carries the filled portion the way it
 * carries selection everywhere else, and the accent is spent on the handle and
 * the number -- which between them are perhaps 2% of the panel's area.
 *
 * The slot-based `Slider` that makes this possible is still experimental in
 * material3 1.4.0 -- the track and thumb slots were only promoted to stable
 * during the 1.5.0 alphas -- hence the opt-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    /**
     * Fill from the centre rather than from the left end.
     *
     * Exposure compensation is a signed quantity whose interesting value is
     * zero, and drawn as a left-filled bar it read as a level control -- "1.0
     * of a possible 3.0" rather than "one stop under". Material's own
     * `CenteredTrack` is internal in 1.4.0, but the track is drawn here
     * anyway, so it costs one branch.
     */
    centered: Boolean = false,
    onValue: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            color = Ink.Muted,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(66.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Ink.Amber,
                activeTrackColor = Ink.Bone,
                inactiveTrackColor = Ink.Guide,
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(Ink.Amber, CircleShape),
                )
            },
            track = { state ->
                val span = state.valueRange.endInclusive - state.valueRange.start
                val fraction =
                    if (span <= 0f) 0f
                    else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                ) {
                    val y = size.height / 2f
                    val stroke = 3.dp.toPx()
                    drawLine(
                        color = Ink.Guide,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    val origin = if (centered) 0.5f else 0f
                    if (fraction != origin) {
                        drawLine(
                            color = Ink.Bone,
                            start = Offset(size.width * origin, y),
                            end = Offset(size.width * fraction, y),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label },
        )
        Text(
            valueText,
            color = Ink.Amber,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(70.dp)
                .padding(start = 10.dp),
        )
    }
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            // Material's floor for anything you touch. These were 28dp tall.
            .heightIn(min = MinTouch)
            .background(if (active) Ink.Bone else Ink.Pane, shape)
            .then(
                if (active) Modifier
                else Modifier.border(BorderStroke(1.dp, Ink.Hairline), shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Ink.OnBone else Ink.Bone,
            fontSize = 12.sp,
            letterSpacing = 0.3.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
