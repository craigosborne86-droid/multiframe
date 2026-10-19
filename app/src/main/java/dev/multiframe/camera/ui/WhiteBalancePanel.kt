package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.*
import kotlin.math.roundToInt

@Composable
internal fun WhiteBalancePanel(settings: ManualSettings, caps: CameraCapabilities?, onSettings: (ManualSettings) -> Unit, previewKelvin: Int = 0, compact: Boolean = false) {
    if (compact) { CompactWhiteBalancePanel(settings, caps, onSettings, previewKelvin); return }
    val range = CalibratedWhiteBalance.range(caps?.whiteBalanceCalibration)
    val manualSupported = caps?.supportsManualWhiteBalance == true && range != null
    val actualMode = settings.whiteBalanceMode
    Row(Modifier.fillMaxWidth()) {
        WhiteBalanceMode.entries.forEach { mode ->
            InstrumentButton(when(mode) { WhiteBalanceMode.AUTO -> "Auto WB"; WhiteBalanceMode.LOCK -> "Lock WB"; WhiteBalanceMode.MANUAL -> "Manual WB" },
                "White balance", actualMode == mode, when(mode) {
                    WhiteBalanceMode.AUTO -> true; WhiteBalanceMode.LOCK -> caps?.supportsAwbLock == true; WhiteBalanceMode.MANUAL -> manualSupported
                }, "revision05-wb-${mode.name.lowercase()}", {
                    onSettings(settings.copy(whiteBalanceMode = mode, manualColor = false,
                        awbMode = android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO,
                        kelvin = range?.let { settings.kelvin.coerceIn(it) } ?: settings.kelvin))
                }, Modifier.weight(1f))
        }
    }
    Text(if (actualMode == WhiteBalanceMode.MANUAL) "${settings.kelvin} K" else settings.whiteBalanceLabel(), style = TitaniumTokens.SelectedValue, modifier = Modifier.testTag("revision05-wb-value"))
    if (actualMode != WhiteBalanceMode.MANUAL && previewKelvin > 0)
        Text("Preview WB estimate: $previewKelvin K", style = TitaniumTokens.ReadableSmall)
    var presetsVisible by rememberSaveable { mutableStateOf(false) }
    val presets = ManualSettings.AWB_LABELS.filterKeys { it != android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO && it in (caps?.awbModes ?: emptyList()) }
    if (presets.isNotEmpty()) {
        InstrumentButton("Presets", "Advertised camera WB presets", presetsVisible, true, "revision05-wb-presets", { presetsVisible = !presetsVisible })
        if (presetsVisible) Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            presets.forEach { (mode, name) -> InstrumentButton(name, "White balance preset", settings.awbMode == mode,
                true, "revision05-wb-preset-$mode", { onSettings(settings.copy(whiteBalanceMode = WhiteBalanceMode.AUTO,
                    manualColor = false, awbMode = mode)) }) }
        }
    }
    if (!manualSupported) {
        Text("This camera does not expose calibrated manual WB. Auto and supported AWB lock remain available.", style = TitaniumTokens.ReadableSmall)
        return
    }
    val enabled = actualMode == WhiteBalanceMode.MANUAL
    val supported = range!!
    Text("Sensor calibration: ${supported.first}–${supported.last} K · AE/AF unchanged.", style = TitaniumTokens.ReadableSmall)
    WhiteBalanceScale(if (enabled) "Kelvin" else "Stored Kelvin", settings.kelvin.coerceIn(supported), supported, enabled,
        "revision05-kelvin", { value -> onSettings(settings.copy(kelvin = value)) })
    WhiteBalanceScale(if (enabled) "Tint" else "Stored tint", settings.tint, -100..100, enabled, "revision05-tint",
        { value -> onSettings(settings.copy(tint = value)) })
    var input by rememberSaveable { mutableStateOf(settings.kelvin.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.kelvin) { input = settings.kelvin.toString(); error = null }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun applyInput() {
        val number = input.toIntOrNull()
        if (number == null || number !in supported || CalibratedWhiteBalance.resolve(caps.whiteBalanceCalibration, number, settings.tint) == null)
            error = "Enter ${supported.first}–${supported.last} K"
        else { onSettings(settings.copy(kelvin = number)); error = null; keyboard?.hide() }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        BasicTextField(input, { input = it; error = null }, enabled = enabled, singleLine = true,
            textStyle = TitaniumTokens.GaugeBone, cursorBrush = SolidColor(TitaniumTokens.OpticalAmber),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { applyInput() }),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
                .padding(12.dp).semantics { contentDescription = "Kelvin numeric entry, ${supported.first} to ${supported.last}" }
                .testTag("revision05-kelvin-input"))
        InstrumentButton("Apply", "Apply Kelvin numeric entry", false, enabled, "revision05-kelvin-apply", {
            applyInput()
        })
    }
    error?.let { Text(it, style = TitaniumTokens.ReadableSmall, color = TitaniumTokens.Danger) }
}

/** Relative displacement, fixed needle; no absolute jump when the marker is tapped. */
@Composable
internal fun WhiteBalanceScale(label: String, value: Int, range: IntRange, enabled: Boolean,
    tag: String, onValue: (Int) -> Unit, showLabel: Boolean = true) {
    val latestValue by rememberUpdatedState(value)
    val callback by rememberUpdatedState(onValue)
    Column {
        if (showLabel) Text("$label: $value", style = TitaniumTokens.GaugeBone)
        Canvas(Modifier.fillMaxWidth().height(48.dp).testTag(tag)
            .semantics { contentDescription = "$label moving scale"; progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat());
                if (enabled) setProgress { callback(it.roundToInt().coerceIn(range)); true } else disabled() }
            .pointerInput(range, enabled) {
                if (enabled) {
                    var position = latestValue.toFloat()
                    detectHorizontalDragGestures(onDragStart = { position = latestValue.toFloat() }) { change, amount ->
                        change.consume(); position -= amount / size.width.coerceAtLeast(1) * (range.last - range.first) / 2f
                        callback(position.roundToInt().coerceIn(range))
                    }
                }
            }) {
            val spacing = 12.dp.toPx()
            val phase = ((value - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1) * spacing * 10) % spacing
            for (index in -30..30) {
                val x = size.width / 2 + index * spacing - phase
                drawLine(TitaniumTokens.TelemetryDim, Offset(x, 8.dp.toPx()), Offset(x, if(index % 5 == 0) 30.dp.toPx() else 21.dp.toPx()), 1.dp.toPx())
            }
            drawLine(if(enabled) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim,
                Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
        }
    }
}
