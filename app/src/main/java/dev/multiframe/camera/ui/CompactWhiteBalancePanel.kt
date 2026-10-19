package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.multiframe.camera.pipeline.*

/** Same calibrated WB state/callback; one selected scale inside the shooting dock. */
@Composable
internal fun CompactWhiteBalancePanel(settings: ManualSettings, caps: CameraCapabilities?,
    onSettings: (ManualSettings)->Unit, previewKelvin: Int) {
    val range=CalibratedWhiteBalance.range(caps?.whiteBalanceCalibration)
    val supported=caps?.supportsManualWhiteBalance==true && range!=null
    var tint by rememberSaveable { mutableStateOf(false) }
    var pickingMode by rememberSaveable { mutableStateOf(false) }
    var entry by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled=supported && settings.whiteBalanceMode==WhiteBalanceMode.MANUAL
    Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch),verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        InstrumentButton(when(settings.whiteBalanceMode) { WhiteBalanceMode.AUTO->"Auto WB";WhiteBalanceMode.LOCK->"Lock WB";else->"Manual WB" },
            "White balance ${settings.whiteBalanceLabel()}; choose Auto, Lock, Manual or preset",pickingMode,true,
            "revision05-wb-mode",{pickingMode=!pickingMode})
        if(supported) {
            InstrumentButton("Kelvin","Adjust calibrated Kelvin",!tint,true,"revision05-wb-kelvin-tab",{tint=false;pickingMode=false})
            InstrumentButton("Tint","Adjust tint",tint,true,"revision05-wb-tint-tab",{tint=true;pickingMode=false})
        }
        }
        Spacer(Modifier.width(4.dp))
        InstrumentButton(when {
            enabled && tint -> "%+d".format(settings.tint)
            enabled -> "${settings.kelvin} K"
            previewKelvin>0 -> "≈ $previewKelvin K"
            else -> settings.whiteBalanceLabel()
        },if(enabled) "Edit calibrated white balance numerically" else "Current white balance; preview estimate when available",
            enabled,enabled,"revision05-wb-value",{input=if(tint) settings.tint.toString() else settings.kelvin.toString();error=null;entry=true},
            labelStyle=if(enabled || previewKelvin>0) TitaniumTokens.GaugeCompact else TitaniumTokens.ReadableSmall)
    }
    if(pickingMode) Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch).horizontalScroll(rememberScrollState())) {
        WhiteBalanceMode.entries.forEach { mode ->
            InstrumentButton(when(mode) {WhiteBalanceMode.AUTO->"Auto WB";WhiteBalanceMode.LOCK->"Lock WB";else->"Manual WB"},
                "White balance",settings.whiteBalanceMode==mode,when(mode) {WhiteBalanceMode.AUTO->true;WhiteBalanceMode.LOCK->caps?.supportsAwbLock==true;else->supported},
                "revision05-wb-${mode.name.lowercase()}",{
                    onSettings(settings.copy(whiteBalanceMode=mode,manualColor=false,
                        awbMode=android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO,
                        kelvin=range?.let {settings.kelvin.coerceIn(it)} ?: settings.kelvin));pickingMode=false
                })
        }
        ManualSettings.AWB_LABELS.filterKeys {it!=android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO && it in (caps?.awbModes ?: emptyList())}
            .forEach { (mode,name) -> InstrumentButton(name,"Advertised camera WB preset",settings.awbMode==mode,true,
                "revision05-wb-preset-$mode",{onSettings(settings.copy(whiteBalanceMode=WhiteBalanceMode.AUTO,manualColor=false,awbMode=mode));pickingMode=false}) }
    } else if(supported) {
        WhiteBalanceScale(if(tint) "Tint" else "Kelvin",if(tint) settings.tint else settings.kelvin.coerceIn(range!!),
            if(tint) -100..100 else range!!,enabled,if(tint) "revision05-tint" else "revision05-kelvin",
            {onSettings(if(tint) settings.copy(tint=it) else settings.copy(kelvin=it))},showLabel=false)
    } else Text("Calibrated manual WB unavailable. Auto and advertised lock/presets remain available.",style=TitaniumTokens.ReadableSmall)
    if(entry && supported) Dialog(onDismissRequest={entry=false}) {
        Column(Modifier.fillMaxWidth().background(TitaniumTokens.PlateSurface).padding(16.dp)) {
            Text(if(tint) "Tint: −100 to +100" else "Kelvin: ${range!!.first}–${range.last} K",style=TitaniumTokens.Readable)
            BasicTextField(input,{input=it;error=null},singleLine=true,textStyle=TitaniumTokens.GaugeBone,
                cursorBrush=SolidColor(TitaniumTokens.OpticalAmber),keyboardOptions=KeyboardOptions(keyboardType=if(tint) KeyboardType.Text else KeyboardType.Number),
                modifier=Modifier.fillMaxWidth().heightIn(min=TitaniumTokens.MinimumTouch)
                    .semantics {contentDescription=if(tint) "Tint numeric entry" else "Kelvin numeric entry"}
                    .testTag(if(tint) "revision05-tint-input" else "revision05-kelvin-input"))
            error?.let {Text(it,style=TitaniumTokens.ReadableSmall,color=TitaniumTokens.Danger)}
            Row {
                InstrumentButton("Cancel","Cancel WB numeric entry",false,true,"revision05-wb-entry-cancel",{entry=false})
                InstrumentButton("Apply","Apply calibrated WB numeric entry",false,true,"revision05-kelvin-apply",{
                    val value=input.toIntOrNull()
                    val valid=value!=null && (if(tint) value in -100..100 else value in range!!) &&
                        CalibratedWhiteBalance.resolve(caps!!.whiteBalanceCalibration,if(tint) settings.kelvin else value!!,if(tint) value!! else settings.tint)!=null
                    if(valid) {onSettings(if(tint) settings.copy(tint=value!!) else settings.copy(kelvin=value!!));entry=false}
                    else error=if(tint) "Enter −100 to +100" else "Enter ${range!!.first}–${range.last} K"
                })
            }
        }
    }
}
