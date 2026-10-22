package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.MosaicPreset
import dev.multiframe.camera.pipeline.OutputFormat

private val serviceShape = RoundedCornerShape(TitaniumTokens.RadiusPlate)

/** Technical service manual: rows, rules and engraved readings, never Material cards. */
@Composable
fun SettingsScreen(
    chips: List<ControlSpec>,
    onControl: (String) -> Unit,
    onDismiss: () -> Unit,
    labs: (@Composable () -> Unit)? = null,
    openLabsInitially: Boolean = false,
    viewfinder: (@Composable () -> Unit)? = null,
    panel: @Composable () -> Unit,
) {
    val view = LocalView.current
    var labsOpen by remember(openLabsInitially) { mutableStateOf(openLabsInitially) }
    Column(Modifier.fillMaxSize().background(TitaniumTokens.Chassis)
        .statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp)
            .background(TitaniumTokens.PlateSurface)
            .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MULTIFRAME", style = TitaniumTokens.Section,
                    color = TitaniumTokens.TitaniumM6)
                Text("Settings", style = TitaniumTokens.Control)
            }
            ServiceAction("CLOSE", "Close settings", onDismiss)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)) {
            ServiceHeading("01", "CAPTURE")
            chips.filter { it.id != ControlBar.HISTOGRAM && it.id != ControlBar.GUIDES }.forEach { spec ->
                ServiceRow(if (spec.id == ControlBar.DNG) "DNG" else spec.label,
                    spec.value ?: if (spec.active) "ON" else "OFF", active = spec.active,
                    enabled = spec.enabled, onClick = { onControl(spec.id) })
            }
            Spacer(Modifier.height(16.dp))
            ServiceHeading("02", "VIEWFINDER")
            chips.filter { it.id == ControlBar.HISTOGRAM || it.id == ControlBar.GUIDES }.forEach { spec ->
                ServiceRow(if (spec.id == ControlBar.HISTOGRAM) "Preview RGB histogram" else "Composition grid",
                    spec.value ?: if (spec.active) "ON" else "OFF", active = spec.active,
                    enabled = spec.enabled, onClick = { onControl(spec.id) })
            }
            Spacer(Modifier.height(16.dp))
            viewfinder?.invoke()
            ServiceHeading("03", "IMAGE PROCESSING")
            panel()
            if (labs != null) {
                Spacer(Modifier.height(16.dp))
                ServiceHeading("04", "LABS / TECHNOLOGY PREVIEWS")
                ServiceRow("TECHNOLOGY PREVIEWS", if (labsOpen) "OPEN" else "CLOSED",
                    active = labsOpen, onClick = { Haptics.segment(view); labsOpen = !labsOpen })
                if (labsOpen) labs()
            }
        }
    }
}

@Composable
private fun ServiceHeading(number: String, label: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = TitaniumTokens.Section, color = TitaniumTokens.OpticalAmber)
        Spacer(Modifier.width(12.dp))
        Text(label, style = TitaniumTokens.Section, color = TitaniumTokens.TitaniumM6)
    }
}

/** One full-width switch in a hairline frame, with no nested capsule. */
@Composable
fun ServiceRow(label: String, value: String, modifier: Modifier = Modifier,
               detail: String? = null, active: Boolean = false, enabled: Boolean = true,
               onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 4.dp)
        .background(TitaniumTokens.PlateSurface, serviceShape)
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder, serviceShape)
        .clickable(enabled = enabled, role = if (value == "ON" || value == "OFF") Role.Switch else Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .semantics {
            contentDescription = "$label $value"
            selected = active
            if (value == "ON" || value == "OFF") toggleableState = ToggleableState(value == "ON")
        }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TitaniumTokens.Readable,
                color = if (enabled) TitaniumTokens.TelemetryWhite else TitaniumTokens.TelemetryDim,
                modifier = Modifier.weight(1f))
            Text(value, style = TitaniumTokens.Control,
                color = if (active) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim)
        }
        if (detail != null) Text(detail, style = TitaniumTokens.ReadableSmall,
            color = TitaniumTokens.TelemetryDim, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ServiceAction(label: String, description: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = TitaniumTokens.MinimumTouch)
        .background(TitaniumTokens.Chassis, serviceShape)
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder, serviceShape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(label, style = TitaniumTokens.Section)
    }
}

@Composable
fun ServiceCaptureSettings(
    caps: CameraCapabilities?,
    highlightGuard: Boolean?,
    onHighlightGuard: (Boolean) -> Unit,
    localTone: Boolean,
    onLocalTone: (Boolean) -> Unit,
    outputFormat: OutputFormat?,
    onOutputFormat: (OutputFormat) -> Unit,
    /** Null where the row is absent: below API 34, or while the pictures are HEIF. */
    hdrOutput: Boolean? = null,
    onHdrOutput: (Boolean) -> Unit = {},
    /** Null where the row is absent: while the pictures are HEIF, which v1 does not sign. */
    contentCredentials: Boolean? = null,
    onContentCredentials: (Boolean) -> Unit = {},
    /** Null where the row is absent: no memory or native path for the 2x canvas. */
    highRes: Boolean? = null,
    highResMegapixels: Int = 48,
    onHighRes: (Boolean) -> Unit = {},
    onReset: () -> Unit,
) {
    if (caps == null) return
    val view = LocalView.current
    highlightGuard?.let { enabled ->
        ServiceRow("GUARD", if (enabled) "ON" else "OFF",
            detail = "Protects bright highlights during capture.", active = enabled,
            onClick = { Haptics.segment(view); onHighlightGuard(!enabled) })
    }
    ServiceRow("LOCAL TONE", if (localTone) "ON" else "OFF",
        detail = "Lifts dark areas while preserving contrast and texture.", active = localTone,
        onClick = { Haptics.segment(view); onLocalTone(!localTone) })
    outputFormat?.let { format ->
        ServiceRow("FILE FORMAT", format.label, active = format == OutputFormat.HEIF,
            onClick = { Haptics.segment(view); onOutputFormat(format.next()) })
    }
    hdrOutput?.let { enabled ->
        ServiceRow("HDR OUTPUT", if (enabled) "ON" else "OFF",
            detail = "Adds a gain map: highlights up to two stops brighter on an HDR screen.",
            active = enabled,
            onClick = { Haptics.segment(view); onHdrOutput(!enabled) })
    }
    highRes?.let { enabled ->
        ServiceRow("HIGH RES", if (enabled) "$highResMegapixels MP" else "12 MP",
            detail = "PHOTO merges 16 frames at twice the sensor's sampling. Real sub-pixel samples from your hand's movement; " +
                "on a tripod or where they are missing, it saves at 12 MP and says why.",
            active = enabled, onClick = { Haptics.segment(view); onHighRes(!enabled) })
    }
    contentCredentials?.let { enabled ->
        ServiceRow("CONTENT CREDENTIALS", if (enabled) "ON" else "OFF",
            detail = "Signs each photo on this phone: a computational capture, no generative AI.",
            active = enabled,
            onClick = { Haptics.segment(view); onContentCredentials(!enabled) })
    }
    Spacer(Modifier.height(12.dp))
    var resetRequested by remember { mutableStateOf(false) }
    ServiceRow("RESET ALL SETTINGS", "RESET", onClick = { resetRequested = true })
    if (resetRequested) androidx.compose.ui.window.Dialog(onDismissRequest = { resetRequested = false }) {
        Column(Modifier.background(TitaniumTokens.PlateSurface).border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder).padding(16.dp)) {
            Text("Reset capture, viewfinder and image-processing settings? Saved photographs are kept.", style = TitaniumTokens.Readable)
            InstrumentButton("Keep settings", "Cancel reset", false, true, "revision05-reset-cancel", { resetRequested = false })
            InstrumentButton("Reset settings", "Confirm reset", false, true, "revision05-reset-confirm", { resetRequested = false; onReset() })
        }
    }
    Text(caps.summary(), style = TitaniumTokens.ReadableSmall, modifier = Modifier.padding(8.dp))
}

/** The panning grid stays behind Labs and has no path into PHOTO or NIGHT. */
@Composable
fun BrenizerLabs(
    preset: MosaicPreset,
    megapixels: Map<MosaicPreset, Double>,
    enabled: Boolean,
    onPreset: (MosaicPreset) -> Unit,
    onLaunch: () -> Unit,
) {
    val view = LocalView.current
    Text("BRENIZER SWEEP / BETA", style = TitaniumTokens.Control,
        color = TitaniumTokens.OpticalAmber, modifier = Modifier.padding(vertical = 8.dp))
    Text("Experimental. Best on tripods or scenes > 5m.",
        style = TitaniumTokens.ReadableSmall, modifier = Modifier.padding(bottom = 8.dp))
    for (option in listOf(MosaicPreset.DETAIL_BOOST, MosaicPreset.LARGE_FORMAT)) {
        ServiceRow("${option.label} GRID", megapixels[option]?.let { "%.0f MP".format(it) } ?: "— MP",
            active = option == preset, enabled = enabled,
            onClick = { Haptics.tick(view); onPreset(option) })
    }
    if (enabled) ServiceRow("ARM BRENIZER SWEEP", "START", active = true,
        onClick = { Haptics.segment(view); onLaunch() })
}

/** Labs: a focus stack, armed for the next press. Absent where the camera cannot focus by hand. */
@Composable
fun FocusStackLabs(armed: Boolean, enabled: Boolean, onArm: (Boolean) -> Unit) {
    val view = LocalView.current
    Text("FOCUS STACK / BETA", style = TitaniumTokens.Control,
        color = TitaniumTokens.OpticalAmber, modifier = Modifier.padding(vertical = 8.dp))
    Text("Eight frames from the nearest focus to infinity, each part taken from the frame where it is sharp. Best on a tripod.",
        style = TitaniumTokens.ReadableSmall, modifier = Modifier.padding(bottom = 8.dp))
    ServiceRow("ARM FOCUS STACK", if (armed) "ARMED" else "START", active = armed, enabled = enabled,
        onClick = { Haptics.segment(view); onArm(!armed) })
}

/**
 * Labs: astro's sky mask. The model row is absent until a model is bundled;
 * collection keeps training pairs on the phone, nothing leaves it.
 */
@Composable
fun AstroLabs(modelAvailable: Boolean, useModel: Boolean, onUseModel: (Boolean) -> Unit,
              collect: Boolean, onCollect: (Boolean) -> Unit) {
    val view = LocalView.current
    Text("ASTRO SKY MASK / BETA", style = TitaniumTokens.Control,
        color = TitaniumTokens.OpticalAmber, modifier = Modifier.padding(vertical = 8.dp))
    if (modelAvailable) ServiceRow("SKY MASK", if (useModel) "ML MODEL" else "MEASURED",
        detail = "The model only chooses which alignment each pixel's measured samples get; it makes no pixels. Photos say when it was used.",
        active = useModel, onClick = { Haptics.segment(view); onUseModel(!useModel) })
    ServiceRow("COLLECT TRAINING PAIRS", if (collect) "ON" else "OFF",
        detail = "Saves each astro shot's reference frame and measured sky mask on this phone, to train Multiframe's own model.",
        active = collect, onClick = { Haptics.segment(view); onCollect(!collect) })
}
