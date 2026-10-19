package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.*
import kotlin.math.roundToInt

/** Fixed root allocation, with replacement panels above a permanently reachable shutter. */
@Composable
internal fun Revision05Console(
    geometry: Revision05Geometry, landscape: Boolean, showPro: Boolean, showWhiteBalance: Boolean,
    showInfo: Boolean, manual: Boolean, manualAvailable: Boolean, mode: CaptureMode, controlsEnabled: Boolean,
    wbActive: Boolean, wbLabel: String, focusAssistAvailable: Boolean, focusAssistOpen: Boolean, onFocusAssist: () -> Unit,
    timerSeconds: Int, torchAvailable: Boolean, torchOn: Boolean, frontAvailable: Boolean, front: Boolean,
    summary: String, status: String, previewReading: String, mergeProgress: MergeUiProgress?,
    onPro: () -> Unit, onWhiteBalance: () -> Unit, onInfo: () -> Unit,
    onTimer: () -> Unit, onTorch: () -> Unit, onFront: () -> Unit, onMode: (CaptureMode) -> Unit,
    onAuto: () -> Unit, onManual: () -> Unit, onCancel: () -> Unit, cancellable: Boolean,
    sweep: String?, onExitSweep: () -> Unit,
    turret: @Composable () -> Unit, shutter: @Composable () -> Unit, gallery: @Composable () -> Unit,
    panel: @Composable () -> Unit, selectedTab: RangefinderTab, onTab: (RangefinderTab) -> Unit,
) {
    val footerHeight = if (landscape) TitaniumTokens.LandscapeFooter else TitaniumTokens.PortraitFooter
    val panelVisible = showPro || showWhiteBalance || showInfo || sweep != null
    Column(Modifier.revision05Region(geometry.controls).fillMaxSize()
        .background(TitaniumTokens.ConsoleFinish).instrumentEtching()
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
        .testTag("camera-control-band")) {
        // Both children use this same allocation. No panel enters image space.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (panelVisible) Column(Modifier.fillMaxSize().testTag("revision05-replacement-panel")) {
                if(showWhiteBalance || showInfo) Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch), horizontalArrangement=Arrangement.End) {
                    InstrumentButton("Done",if(showWhiteBalance) "Close white balance" else "Close photograph details",true,true,
                        if(showWhiteBalance) "revision05-auto-wb" else "revision05-info",if(showWhiteBalance) onWhiteBalance else onInfo)
                }
                if (showPro) Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch)) {
                    listOf("EXP" to RangefinderTab.ISO, "FOCUS" to RangefinderTab.FOCUS,
                        "BURST" to RangefinderTab.FRAMES, "WB" to RangefinderTab.COLOR).forEach { (name, tab) ->
                        val active = if (tab == RangefinderTab.ISO)
                            selectedTab == RangefinderTab.ISO || selectedTab == RangefinderTab.SHUTTER else selectedTab == tab
                        InstrumentButton(name, when(tab) { RangefinderTab.ISO -> "Exposure controls"
                            RangefinderTab.FOCUS -> "Focus controls"; RangefinderTab.FRAMES -> "Burst controls"
                            else -> "White balance controls" }, active, true,
                            "mechanical-tab-${when(tab) { RangefinderTab.ISO -> "exp"; RangefinderTab.FOCUS -> "focus"; RangefinderTab.FRAMES -> "frames"; else -> "color" }}",
                            { onTab(tab) }, Modifier.weight(1f))
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal=8.dp).testTag("revision05-panel-scroll")) {
                    when {
                        sweep != null -> {
                            Text(sweep, style=TitaniumTokens.Readable)
                            Text("Pan along the guide. Experimental; best on a tripod or scenes beyond 5 m. Framing is an estimate. Exit saves completed tiles as a partial mosaic and keeps existing photographs.", style=TitaniumTokens.ReadableSmall)
                        }
                        showInfo -> {
                            Text("Next photograph",style=TitaniumTokens.Readable)
                            Text(summary,style=TitaniumTokens.ReadableSmall,modifier=Modifier.testTag("revision05-capture-summary"))
                            Text(previewReading,style=TitaniumTokens.GaugeBone,modifier=Modifier.testTag("revision05-preview-reading"))
                        }
                        else -> panel()
                    }
                }
                if (sweep != null) InstrumentButton("Exit sweep","Stop acquisition and exit sweep",false,true,
                    "revision05-exit-sweep",onExitSweep,Modifier.fillMaxWidth())
                if (landscape && showPro) Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch)) {
                    InstrumentButton("Return to Auto","Return exposure focus and burst to Auto; white balance unchanged",!manual,
                        controlsEnabled,"revision05-return-auto",onAuto,Modifier.weight(1f))
                    InstrumentButton("Manual","Restore stored manual exposure",manual,controlsEnabled && manualAvailable,
                        "revision05-manual",onManual,Modifier.weight(1f))
                }
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch)) { turret() }
                Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch).horizontalScroll(rememberScrollState()),
                    verticalAlignment=Alignment.CenterVertically) {
                    InstrumentButton(if(wbActive) wbLabel else "WB","White balance",wbActive,controlsEnabled,
                        "revision05-auto-wb",onWhiteBalance)
                    InstrumentButton(if(timerSeconds==0) "Timer: Off" else "Timer: $timerSeconds seconds","Timer",timerSeconds>0,
                        controlsEnabled,"rangefinder-timer",onTimer)
                    if(torchAvailable) InstrumentButton("Light","Torch",torchOn,controlsEnabled,"rangefinder-torch",onTorch)
                    if(focusAssistAvailable) InstrumentButton("Loupe","Magnified focus assist",focusAssistOpen,controlsEnabled,
                        "rangefinder-focus-assist-toggle",onFocusAssist)
                    InstrumentButton("Info","Next photograph configuration",false,true,"revision05-info",onInfo)
                }
                Spacer(Modifier.weight(1f))
            }
            if (mergeProgress != null || status.isNotBlank() || cancellable) Row(Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth().height(if(cancellable) TitaniumTokens.MinimumTouch else TitaniumTokens.CaptureStatusHeight).background(TitaniumTokens.PlateSurface)) {
                Box(Modifier.weight(1f)) { MergeProcessingIndicator(mergeProgress,idleStatus=status) }
                if(cancellable) InstrumentButton("Cancel","Cancel timer or supported acquisition",false,true,"revision05-cancel",onCancel)
            }
        }
        Box(Modifier.fillMaxWidth().height(footerHeight).testTag("revision05-fixed-footer")) {
        Column(Modifier.fillMaxSize()) {
            @Composable fun modeButton(m: CaptureMode, modifier: Modifier) {
                InstrumentButton(m.name,"Shooting mode ${m.name}",mode==m,controlsEnabled && sweep==null,
                    "rangefinder-mode-${m.name.lowercase()}",{onMode(m)},modifier)
            }
            @Composable fun proButton(modifier: Modifier) {
                InstrumentButton(if(showPro) "Hide Pro" else "Pro","Pro controls",showPro || manual,
                    controlsEnabled && sweep==null,"rangefinder-pro",onPro,modifier)
            }
            @Composable fun flip() {
                if(frontAvailable) InstrumentButton(if(front) "Rear" else "Selfie","Switch camera",front,controlsEnabled,
                    "rangefinder-camera-flip",onFront) else Spacer(Modifier.size(TitaniumTokens.MinimumTouch))
            }
            if(landscape) {
                Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch)) {
                    modeButton(CaptureMode.PHOTO,Modifier.weight(1f));modeButton(CaptureMode.NIGHT,Modifier.weight(1f))
                    proButton(Modifier.weight(1f))
                }
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceBetween) {
                    gallery();Box(Modifier.size(92.dp),contentAlignment=Alignment.Center) { shutter() };flip()
                }
            } else Row(Modifier.fillMaxSize().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    gallery()
                    Column(Modifier.weight(1f)) {
                        modeButton(CaptureMode.PHOTO,Modifier.fillMaxWidth());modeButton(CaptureMode.NIGHT,Modifier.fillMaxWidth())
                    }
                }
                Box(Modifier.size(92.dp),contentAlignment=Alignment.Center) { shutter() }
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        proButton(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch))
                        if(showPro || manual) InstrumentButton(if(manual) "Return to Auto" else "Manual",
                            if(manual) "Return exposure focus and burst to Auto; white balance unchanged" else "Restore stored manual exposure",
                            manual,controlsEnabled && (manual || manualAvailable),
                            if(manual) "revision05-return-auto" else "revision05-manual",
                            if(manual) onAuto else onManual,Modifier.fillMaxWidth().height(footerHeight - TitaniumTokens.MinimumTouch))
                        else Spacer(Modifier.height(footerHeight - TitaniumTokens.MinimumTouch))
                    }
                    flip()
                }
            }
        }
        }
    }
}

@Composable
internal fun InstrumentButton(label: String, description: String, active: Boolean, enabled: Boolean,
    tag: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    labelStyle: androidx.compose.ui.text.TextStyle = TitaniumTokens.ReadableSmall) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(modifier.heightIn(min = TitaniumTokens.MinimumTouch).widthIn(min = TitaniumTokens.MinimumTouch)
         .clip(RoundedCornerShape(TitaniumTokens.RadiusPlate))
        .background(TitaniumTokens.ControlFinish)
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder, RoundedCornerShape(TitaniumTokens.RadiusPlate))
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .drawWithContent {
            drawContent()
            if (pressed) drawRect(TitaniumTokens.TitaniumM6.copy(alpha = .12f))
            if (active) drawLine(TitaniumTokens.OpticalAmber,
                Offset(size.width * .225f, size.height - 3.dp.toPx()),
                Offset(size.width * .775f, size.height - 3.dp.toPx()), 2.dp.toPx())
        }
        .semantics { contentDescription = "$description: $label"; selected = active; if (!enabled) disabled() }
        .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, style = labelStyle.copy(fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal),
            color = if (!enabled) TitaniumTokens.TelemetryDim else if (active) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryWhite)
    }
}

@Composable
internal fun Revision05TopDeck(geometry: Revision05Geometry, histogramVisible: Boolean,
    onSettings: () -> Unit, onHistogram: () -> Unit, resolutionControl: @Composable () -> Unit) {
    val density = LocalDensity.current
    val top = geometry.safe.top
    Row(Modifier.revision05Region(Rect(maxOf(geometry.image.left, geometry.safe.left), top, minOf(geometry.image.right, geometry.safe.right), top + with(density) { TitaniumTokens.MinimumTouch.toPx() })).fillMaxSize()
        .padding(horizontal = 8.dp).testTag("revision05-top-deck"), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        InstrumentButton("Settings", "Open Settings", false, true, "titanium-labs-button", onSettings)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Back only the readout, preserving visible framing between the
            // three compact HUD surfaces instead of masking the whole strip.
            Box(Modifier.background(TitaniumTokens.PlateSurface)
                .padding(horizontal = 8.dp)) { resolutionControl() }
        }
        InstrumentButton("RGB", "Preview RGB histogram", histogramVisible, true, "revision05-rgb", onHistogram)
    }
}

@Composable
internal fun Revision05ManualPanel(settings: ManualSettings, caps: CameraCapabilities?, frames: Int,
    ceiling: Int, tab: RangefinderTab, onTab: (RangefinderTab) -> Unit, onSettings: (ManualSettings) -> Unit,
    onFrames: (Int) -> Unit, hapticsEnabled: Boolean, focusAssistAvailable: Boolean = false,
    focusAssistOpen: Boolean = false, onFocusAssist: () -> Unit = {}) {
    if(caps==null) { Text("Camera controls unavailable",style=TitaniumTokens.ReadableSmall);return }
    var ev by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) { if(tab!=RangefinderTab.ISO) ev=false }
    @Composable fun header(value: String) {
        Row(Modifier.fillMaxWidth().height(TitaniumTokens.MinimumTouch),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            when(tab) {
                RangefinderTab.ISO,RangefinderTab.SHUTTER -> {
                    InstrumentButton("ISO","Select sensitivity",tab==RangefinderTab.ISO && !ev,caps.hasManualSensor,
                        "mechanical-subtab-iso",{ev=false;onTab(RangefinderTab.ISO)})
                    InstrumentButton("Shutter","Select exposure duration",tab==RangefinderTab.SHUTTER,caps.hasManualSensor,
                        "mechanical-subtab-shutter",{ev=false;onTab(RangefinderTab.SHUTTER)})
                    if(caps.supportsExposureCompensation) InstrumentButton("EV","Exposure compensation while AE is automatic",ev,
                        !settings.manualExposure,"revision05-ev-select",{onTab(RangefinderTab.ISO);ev=true})
                }
                RangefinderTab.FOCUS -> {
                    InstrumentButton("Auto","Use continuous autofocus",!settings.manualFocus,true,"revision05-focus-auto",
                        {onSettings(settings.copy(manualFocus=false,rememberedManualFocus=false))})
                    InstrumentButton("Manual","Use stored focus distance",settings.manualFocus,caps.hasManualFocus,"revision05-focus-manual",
                        {onSettings(settings.copy(manualFocus=true,rememberedManualFocus=true))})
                    if(focusAssistAvailable) InstrumentButton("Loupe","Magnified focus assist",focusAssistOpen,true,
                        "rangefinder-focus-assist-toggle",onFocusAssist)
                }
                else -> Text("Requested",style=TitaniumTokens.ReadableSmall)
            }
            }
            Spacer(Modifier.width(8.dp))
            Text(when {
                ev -> "%+.1f EV".format(settings.evIndex*caps.evStep)
                (tab==RangefinderTab.ISO || tab==RangefinderTab.SHUTTER) && !settings.manualExposure -> "Auto"
                tab==RangefinderTab.FOCUS && !settings.manualFocus -> "Auto"
                else -> value
            },style=TitaniumTokens.GaugeCompact,modifier=Modifier.testTag("revision05-selected-control-value"))
        }
    }
    if(ev && !settings.manualExposure) {
        header("")
        WhiteBalanceScale("Exposure compensation",settings.evIndex,caps.evMin..caps.evMax,true,"revision05-ev",
            {onSettings(settings.copy(evIndex=it))},showLabel=false)
    } else if((tab==RangefinderTab.ISO || tab==RangefinderTab.SHUTTER) && !caps.hasManualSensor) {
        header("")
        Text("Manual exposure unavailable on this camera",style=TitaniumTokens.ReadableSmall)
    } else RangefinderDial(settings,caps,frames,ceiling,tab,onTab,onSettings,onFrames,
        compact=true,showTabs=false,hapticsEnabled=hapticsEnabled,docked=true,header={header(it)})
}
