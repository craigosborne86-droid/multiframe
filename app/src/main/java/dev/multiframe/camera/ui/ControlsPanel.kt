package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ManualSettings
import kotlin.math.ln
import kotlin.math.exp

private val Accent = Ink.Amber
private val PanelBg = Ink.Panel

@Composable
fun ControlsPanel(
    settings: ManualSettings,
    caps: CameraCapabilities?,
    onChange: (ManualSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (caps == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = caps.summary(),
            color = Ink.Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(modifier = Modifier.padding(bottom = 6.dp)) {
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
                valueText = "%.1f".format(settings.evIndex * caps.evStep),
                range = caps.evMin.toFloat()..caps.evMax.toFloat(),
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
        ) {
            ManualSettings.AWB_LABELS.forEach { (mode, label) ->
                if (caps.awbModes.contains(mode)) {
                    Toggle(label, settings.awbMode == mode) {
                        onChange(settings.copy(awbMode = mode))
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
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
            letterSpacing = 0.3.sp,
            modifier = Modifier.width(62.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Ink.Guide,
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label },
        )
        Text(
            valueText,
            color = Ink.Amber,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(64.dp)
                .padding(start = 8.dp),
        )
    }
}

@Composable
private fun Toggle(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Ink.OnBone else Ink.Bone,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(if (active) Ink.Bone else Ink.Pane, RoundedCornerShape(14.dp))
            .then(
                if (active) Modifier
                else Modifier.border(BorderStroke(1.dp, Ink.Hairline), RoundedCornerShape(14.dp))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .semantics { contentDescription = label },
    )
}
