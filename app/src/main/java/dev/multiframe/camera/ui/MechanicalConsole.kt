package dev.multiframe.camera.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import dev.multiframe.camera.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ManualSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val plateShape = RoundedCornerShape(TitaniumTokens.RadiusPlate)

/** Fixed mechanical bay: PRO swaps faces without measuring the viewfinder again. */
@Composable
fun MechanicalConsole(
    open: Boolean,
    landscapeCompact: Boolean = false,
    availableHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier,
    onMeasured: (IntSize) -> Unit = {},
    header: @Composable BoxScope.() -> Unit,
    shutter: @Composable BoxScope.() -> Unit,
    idleAroundShutter: @Composable BoxScope.() -> Unit,
    idle: @Composable BoxScope.() -> Unit,
    drawer: @Composable BoxScope.() -> Unit,
) {
    val transition by animateFloatAsState(if (open) 1f else 0f, tween(360), label = "titanium-darkroom")
    val consoleHeight = availableHeight ?: if (landscapeCompact) 260.dp else TitaniumTokens.ConsoleHeight
    val shutterBayHeight = if (landscapeCompact || consoleHeight < TitaniumTokens.ConsoleHeight) 84.dp else 124.dp
    val drawerHeight = (consoleHeight - 48.dp - shutterBayHeight).coerceAtLeast(0.dp)
    Box(modifier.fillMaxWidth().height(consoleHeight)
        .background(TitaniumTokens.PlateSurface)
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
        .clipToBounds().onSizeChanged(onMeasured).testTag("rangefinder-console")) {
        Box(Modifier.fillMaxWidth().height(48.dp).align(Alignment.TopCenter)
            .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
            .testTag("rangefinder-console-header")) { header() }
        Box(Modifier.fillMaxWidth().height(shutterBayHeight).offset(y = 48.dp)) {
            if (!open) Box(Modifier.fillMaxSize()) { idleAroundShutter() }
            shutter()
        }
        Box(Modifier.fillMaxWidth().height(drawerHeight)
            .align(Alignment.BottomCenter).clipToBounds()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - transition
                translationY = -size.height * .16f * transition
            }.semantics { if (open) hideFromAccessibility() }) { if (!open) idle() }
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = transition
                translationY = size.height * (1f - transition)
            }.semantics { if (!open) hideFromAccessibility() }) { if (open) drawer() }
        }
    }
}

/** Five 35 mm equivalent stops on a bead-blasted plate; tap or scrub. */
@Composable
fun MechanicalTurret(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subPixelAvailable: Boolean = true,
    superResBadge: String? = null,
    landscapeCompact: Boolean = false,
    frontLabel: String? = null,
    available: List<Boolean> = List(5) { true },
) {
    if (frontLabel != null) {
        Box(modifier.fillMaxWidth().height(48.dp)
            .background(TitaniumTokens.PlateSurface)
            .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
            .testTag("rangefinder-turret"), contentAlignment = Alignment.Center) {
            Text("$frontLabel / FRONT", style = TitaniumTokens.LensFocal,
                color = TitaniumTokens.TelemetryWhite)
        }
        return
    }
    val labels = listOf("13mm", "24mm", "48mm", "120mm", "240mm")
    val latestSelect by rememberUpdatedState(onSelect)
    val latestIndex by rememberUpdatedState(selected)
    val view = LocalView.current
    var width by remember { mutableIntStateOf(1) }
    val position by animateFloatAsState(selected.toFloat(), spring(dampingRatio = .48f, stiffness = 430f),
        label = "milled-focal-detent")
    Box(modifier.fillMaxWidth().height(48.dp)
        .background(TitaniumTokens.PlateSurface)
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
        .onSizeChanged { width = it.width.coerceAtLeast(1) }
        .pointerInput(enabled, width) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var previous = latestIndex
                fun choose(x: Float) {
                    val next = ((x / width) * 5f).toInt().coerceIn(0, 4)
                    if (available.getOrElse(next) { false } && next != previous) { Haptics.tick(view); previous = next; latestSelect(next) }
                }
                choose(down.position.x)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    choose(change.position.x)
                    change.consume()
                }
            }
        }
        .semantics { contentDescription = "Milled focal turret, ${labels[selected.coerceIn(0,4)]} selected" }
        .testTag("rangefinder-turret")) {
        Canvas(Modifier.fillMaxSize()) {
            var x = 0f
            while (x < size.width) {
                drawLine(TitaniumTokens.HairlineBorder, Offset(x, 0f), Offset(x, size.height), 1f)
                x += 3.dp.toPx()
            }
        }
        Box(Modifier.fillMaxWidth(.2f).fillMaxHeight().offset {
            IntOffset((position * width / 5f).toInt(), 0)
        }.border(TitaniumTokens.Hairline, TitaniumTokens.OpticalAmber)
            .background(TitaniumTokens.OpticalAmber.copy(alpha = .10f))
            .semantics { turretHighlightIndex = position }
            .testTag("rangefinder-turret-highlight"))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).fillMaxHeight().semantics {
                    role = Role.RadioButton
                    this.selected = index == selected
                    contentDescription = "$label lens"
                    if (!enabled || !available.getOrElse(index) { false }) disabled()
                    onClick {
                        if (enabled && available.getOrElse(index) { false }) { Haptics.tick(view); latestSelect(index) }
                        enabled && available.getOrElse(index) { false }
                    }
                }.testTag("mechanical-lens-$index"), contentAlignment = Alignment.TopCenter) {
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        append(label.removeSuffix("mm"))
                        withStyle(androidx.compose.ui.text.SpanStyle(
                            fontSize = TitaniumTokens.LensZoom.fontSize)) { append("mm") }
                    }, style = TitaniumTokens.LensFocal.copy(lineHeight = TitaniumTokens.LensFocal.fontSize),
                        maxLines = 1, softWrap = false,
                        color = if (index == selected) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryWhite,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (subPixelAvailable && (selected == 2 || selected == 4)) {
            Text("SUB-PIXEL OPTICAL MERGE", style = TitaniumTokens.Section,
                color = TitaniumTokens.OpticalAmber,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                    .testTag("rangefinder-subpixel-label"))
            superResBadge?.let { badge ->
                Text(badge, style = TitaniumTokens.Badge,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 2.dp)
                        .testTag("rangefinder-superres-badge"))
            }
        }
    }
}

/** An engraved cylinder with a sliding optical-amber index. */
@Composable
fun MechanicalModeTumbler(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit,
                           modifier: Modifier = Modifier, landscapeCompact: Boolean = false) {
    val labels = listOf("PHOTO", "NIGHT", "LONG EXP")
    val latestSelect by rememberUpdatedState(onSelect)
    val latestIndex by rememberUpdatedState(selected)
    val view = LocalView.current
    var width by remember { mutableIntStateOf(1) }
    val position by animateFloatAsState(selected.toFloat(), spring(dampingRatio = .48f, stiffness = 430f),
        label = "mode-cylinder-detent")
    Box(modifier.fillMaxWidth().height(if (landscapeCompact) TitaniumTokens.MinimumTouch else 61.dp)
        .background(Brush.verticalGradient(listOf(TitaniumTokens.TitaniumM6Dark,
            TitaniumTokens.PlateSurface, TitaniumTokens.Chassis, TitaniumTokens.PlateSurface)))
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
        .onSizeChanged { width = it.width.coerceAtLeast(1) }
        .pointerInput(enabled, width) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position.x
                var end = start
                val initialIndex = latestIndex
                var currentIndex = initialIndex
                val tapped = ((start / width) * 3f).toInt().coerceIn(0, 2)
                if (tapped != currentIndex) { Haptics.tick(view); latestSelect(tapped); currentIndex = tapped }
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    end = change.position.x
                    change.consume()
                }
                val next = if (kotlin.math.abs(end - start) >= 36.dp.toPx())
                    (initialIndex + if (end < start) 1 else -1).coerceIn(0, 2)
                else tapped
                if (next != currentIndex) { Haptics.tick(view); latestSelect(next) }
            }
        }.semantics { contentDescription = "Cylinder mode tumbler, ${labels[selected.coerceIn(0,2)]}" }
        .testTag("rangefinder-mode-tumbler")) {
        Box(Modifier.fillMaxWidth(1f / 3f).height(2.dp).offset {
            IntOffset((position * width / 3f).toInt(), 0)
        }.background(TitaniumTokens.OpticalAmber)
            .semantics { modeIndicatorIndex = position }
            .testTag("rangefinder-mode-indicator"))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).fillMaxHeight().semantics {
                    role = Role.RadioButton
                    this.selected = index == selected
                    contentDescription = "$label capture mode"
                    if (!enabled) disabled()
                    onClick {
                        if (enabled) { Haptics.tick(view); latestSelect(index) }
                        enabled
                    }
                }.testTag("mechanical-mode-$index"), contentAlignment = Alignment.Center) {
                    Text(label, style = TitaniumTokens.Mode,
                        color = if (index == selected) TitaniumTokens.TelemetryWhite else TitaniumTokens.TelemetryDim)
                    if (index == selected) Canvas(Modifier.align(Alignment.BottomCenter).size(7.dp)) {
                        drawCircle(TitaniumTokens.OpticalAmber, radius = 2.dp.toPx())
                    }
                }
            }
        }
    }
}

/** The M6-inspired release: knurled collar, lathe rings and threaded cable socket. */
@Composable
fun MechanicalShutter(enabled: Boolean, counting: Boolean, frames: Int,
                      onCapture: () -> Unit, onFlash: () -> Unit,
                      modifier: Modifier = Modifier,
    captureProgress: Float? = null, onPrime: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, tween(120), label = "shutter-half-press")
    LaunchedEffect(pressed) {
        if (pressed) { Haptics.halfPress(view); onPrime() }
    }
    Canvas(modifier.size(TitaniumTokens.ShutterDiameter)
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled || counting) 1f else .38f }
        .clickable(enabled = enabled || counting, role = Role.Button, interactionSource = interaction, indication = null) {
            onCapture()
            if (!counting) {
                onFlash()
                scope.launch(Dispatchers.Default) { MechanicalClick.play() }

            }
        }.semantics {
            contentDescription = if (counting) "Cancel timer" else "Mechanical shutter"
            shutterScale = scale
            shutterProgress = captureProgress ?: 0f
            shutterDurationMs = 0
        }.testTag("rangefinder-shutter")) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        drawCircle(brush = Brush.radialGradient(listOf(TitaniumTokens.TitaniumM6,
            TitaniumTokens.TitaniumM6Dark, TitaniumTokens.TitaniumM6),
            center = centre - Offset(radius * .22f, radius * .22f), radius = radius * 1.6f), radius = radius)
        drawCircle(TitaniumTokens.Chassis, radius = radius * .81f)
        drawCircle(brush = Brush.radialGradient(listOf(TitaniumTokens.TitaniumM6,
            TitaniumTokens.TitaniumM6Dark, TitaniumTokens.PlateSurface),
            center = centre - Offset(radius * .20f, radius * .20f), radius = radius),
            radius = radius * .75f)
        for (i in 0 until 18) {
            drawCircle(TitaniumTokens.TitaniumM6Dark.copy(alpha = .16f + i * .012f),
                radius = radius * (.20f + i * .028f), style = Stroke(.45.dp.toPx()))
        }
        drawCircle(TitaniumTokens.Chassis, radius = radius * .17f)
        for (i in 0 until 3) {
            drawCircle(TitaniumTokens.TitaniumM6Dark.copy(alpha = .65f),
                radius = radius * (.10f + i * .02f), style = Stroke(.65.dp.toPx()))
        }
        drawCircle(if (pressed) TitaniumTokens.OpticalAmber else TitaniumTokens.TitaniumM6Dark,
            radius = radius * .80f, style = Stroke(if (pressed) 2.dp.toPx() else 1.dp.toPx()))
        if ((captureProgress ?: 0f) > 0f) drawArc(TitaniumTokens.OpticalAmber, -90f, 360f * (captureProgress ?: 0f),
            false, topLeft = Offset(centre.x - radius * .82f, centre.y - radius * .82f),
            size = Size(radius * 1.64f, radius * 1.64f),
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun MechanicalProToggle(engaged: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = TitaniumTokens.MinimumTouch)
        .background(if (engaged) TitaniumTokens.OpticalAmber.copy(alpha = .16f) else TitaniumTokens.PlateSurface, plateShape)
        .border(TitaniumTokens.Hairline,
            if (engaged) TitaniumTokens.OpticalAmber else TitaniumTokens.HairlineBorder, plateShape)
        .clickable(role = Role.Switch, onClick = onClick).padding(horizontal = 14.dp)
        .semantics {
            selected = engaged
            toggleableState = ToggleableState(engaged)
            contentDescription = if (engaged) "PRO mode on" else "PRO mode off"
        }
        .testTag("rangefinder-pro-toggle"), contentAlignment = Alignment.Center) {
        Text("PRO", style = TitaniumTokens.Control,
            color = if (engaged) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryWhite)
    }
}

@Composable
fun MechanicalUtility(label: String, description: String, active: Boolean = false,
                      enabled: Boolean = true, tag: String, modifier: Modifier = Modifier,
                      onClick: () -> Unit) {
    Box(modifier.heightIn(min = TitaniumTokens.MinimumTouch)
        .background(TitaniumTokens.PlateSurface, plateShape)
        .border(TitaniumTokens.Hairline,
            if (active) TitaniumTokens.OpticalAmber else TitaniumTokens.HairlineBorder, plateShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 9.dp)
        .semantics { contentDescription = description; selected = active; if (!enabled) disabled() }
        .testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(label, style = TitaniumTokens.Section,
            color = if (active) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryWhite)
    }
}

/** Four engraved PRO tabs; EXP selects ISO and a second tap reaches shutter. */
@Composable
fun MechanicalProDial(
    settings: ManualSettings,
    caps: CameraCapabilities?,
    frames: Int,
    frameCeiling: Int,
    tab: RangefinderTab,
    onTab: (RangefinderTab) -> Unit,
    onSettings: (ManualSettings) -> Unit,
    onFrames: (Int) -> Unit,
    hapticsEnabled: Boolean,
    landscapeCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val sections = listOf("EXP", "FOCUS", "FRAMES", stringResource(R.string.rangefinder_color_tab))
    val sectionIds = listOf("exp", "focus", "frames", "color")
    val active = when (tab) {
        RangefinderTab.ISO, RangefinderTab.SHUTTER -> 0
        RangefinderTab.FOCUS -> 1
        RangefinderTab.FRAMES -> 2
        RangefinderTab.COLOR -> 3
    }
    Column(modifier.fillMaxWidth().background(TitaniumTokens.PlateSurface)
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
        .testTag("mechanical-pro-dial")) {
        Row(Modifier.fillMaxWidth().height(if (landscapeCompact) 26.dp else 30.dp)) {
            sections.forEachIndexed { index, label ->
                val offered = when (index) {
                    0 -> caps?.hasManualSensor == true
                    1 -> caps?.hasManualFocus == true
                    else -> true
                }
                Box(Modifier.weight(1f).fillMaxHeight()
                    .border(TitaniumTokens.Hairline,
                        if (index == active) TitaniumTokens.OpticalAmber else TitaniumTokens.HairlineBorder)
                    .clickable(enabled = offered, role = Role.Tab) {
                        val next = when (index) {
                            0 -> if (active == 0 && tab == RangefinderTab.ISO) RangefinderTab.SHUTTER else RangefinderTab.ISO
                            1 -> RangefinderTab.FOCUS
                            2 -> RangefinderTab.FRAMES
                            else -> RangefinderTab.COLOR
                        }
                        onTab(next)
                        Haptics.tick(view)
                    }.semantics {
                        contentDescription = "$label manual dial tab"
                        selected = index == active
                        if (!offered) disabled()
                    }
                    .testTag("mechanical-tab-${sectionIds[index]}"),
                    contentAlignment = Alignment.Center) {
                    Text(label, style = TitaniumTokens.Section,
                        color = if (index == active) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim)
                }
            }
        }
        if (active == 0) {
            Row(
                Modifier.fillMaxWidth().height(if (landscapeCompact) 16.dp else 20.dp)
                    .background(TitaniumTokens.Chassis)
                    .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isIso = tab == RangefinderTab.ISO
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable { onTab(RangefinderTab.ISO); Haptics.tick(view) }
                        .semantics { contentDescription = "Select ISO parameter" }
                        .testTag("mechanical-subtab-iso"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "ISO: ${settings.iso}",
                        style = TitaniumTokens.Badge,
                        color = if (isIso) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim,
                    )
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(TitaniumTokens.HairlineBorder))
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable { onTab(RangefinderTab.SHUTTER); Haptics.tick(view) }
                        .semantics { contentDescription = "Select Shutter parameter" }
                        .testTag("mechanical-subtab-shutter"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "SHUTTER: ${settings.shutterLabel()}",
                        style = TitaniumTokens.Badge,
                        color = if (!isIso) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim,
                    )
                }
            }
        }
        RangefinderDial(settings = settings, caps = caps, frames = frames, frameCeiling = frameCeiling,
            tab = tab, onTab = onTab, onSettings = onSettings, onFrames = onFrames,
            compact = true, landscapeCompact = landscapeCompact,
            showTabs = false, hapticsEnabled = hapticsEnabled)
    }
}
