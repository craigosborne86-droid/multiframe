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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ManualSettings
import kotlin.math.ln
import kotlin.math.exp

private val Accent = Color(0xFF4A9EFF)
private val PanelBg = Color(0xDD0A0E12)

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
            color = Color(0xFF8A97A5),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        Row(modifier = Modifier.padding(bottom = 6.dp)) {
            if (caps.hasManualSensor) {
                Toggle("AE ${if (settings.manualExposure) "MAN" else "AUTO"}", settings.manualExposure) {
                    onChange(settings.copy(manualExposure = !settings.manualExposure))
                }
            }
            if (caps.hasManualFocus) {
                Toggle("AF ${if (settings.manualFocus) "MAN" else "AUTO"}", settings.manualFocus) {
                    onChange(settings.copy(manualFocus = !settings.manualFocus))
                }
            }
            Toggle("ISP ${if (settings.suppressIspProcessing) "OFF" else "ON"}", settings.suppressIspProcessing) {
                onChange(settings.copy(suppressIspProcessing = !settings.suppressIspProcessing))
            }
        }

        if (settings.manualExposure && caps.hasManualSensor) {
            caps.isoRange?.let { range ->
                LabelledSlider(
                    label = "ISO",
                    value = settings.iso.toFloat(),
                    valueText = settings.iso.toString(),
                    range = range.lower.toFloat()..range.upper.toFloat(),
                ) { onChange(settings.copy(iso = it.toInt())) }
            }
            caps.exposureTimeRange?.let { range ->
                // Shutter speed spans several orders of magnitude, so the slider
                // is logarithmic or the short end would be unreachable.
                val lo = ln(range.lower.toDouble()).toFloat()
                val hi = ln(range.upper.toDouble()).toFloat()
                LabelledSlider(
                    label = "SHUTTER",
                    value = ln(settings.exposureTimeNs.toDouble()).toFloat().coerceIn(lo, hi),
                    valueText = settings.shutterLabel(),
                    range = lo..hi,
                ) { onChange(settings.copy(exposureTimeNs = exp(it.toDouble()).toLong())) }
            }
        } else if (caps.evStep > 0f) {
            LabelledSlider(
                label = "EV",
                value = settings.evIndex.toFloat(),
                valueText = "%.1f".format(settings.evIndex * caps.evStep),
                range = caps.evRange.lower.toFloat()..caps.evRange.upper.toFloat(),
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
            color = Color(0xFF8A97A5),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(62.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color(0xFF2A3138),
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label },
        )
        Text(
            valueText,
            color = Color.White,
            fontSize = 11.sp,
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
        color = if (active) Color(0xFF06121F) else Color.White,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(if (active) Accent else Color(0xFF1C2229), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
    )
}
