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
    // Only the stops this camera can reach are milled; an unreachable focal
    // length is absent rather than greyed, and the rest share the width.
    val stops = labels.indices.filter { available.getOrElse(it) { false } }.ifEmpty { listOf(selected.coerceIn(0, 4)) }
    val latestStops by rememberUpdatedState(stops)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestIndex by rememberUpdatedState(selected)
    val view = LocalView.current
    var width by remember { mutableIntStateOf(1) }
    val position by animateFloatAsState(stops.indexOf(selected).coerceAtLeast(0).toFloat(), spring(dampingRatio = .48f, stiffness = 430f),
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
                    val visible = latestStops
                    val next = visible[((x / width) * visible.size).toInt().coerceIn(0, visible.size - 1)]
                    if (next != previous) { Haptics.tick(view); previous = next; latestSelect(next) }
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
        Box(Modifier.fillMaxWidth(1f / stops.size).fillMaxHeight().offset {
            IntOffset((position * width / stops.size).toInt(), 0)
        }.border(TitaniumTokens.Hairline, TitaniumTokens.OpticalAmber)
            .background(TitaniumTokens.OpticalAmber.copy(alpha = .10f))
            .semantics { turretHighlightIndex = position }
            .testTag("rangefinder-turret-highlight"))
        Row(Modifier.fillMaxSize()) {
            stops.forEach { index ->
                val label = labels[index]
                Box(Modifier.weight(1f).fillMaxHeight().semantics {
                    role = Role.RadioButton
                    this.selected = index == selected
                    contentDescription = "$label lens"
                    if (!enabled) disabled()
                    onClick {
                        if (enabled) { Haptics.tick(view); latestSelect(index) }
                        enabled
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

/** The M6-inspired release: knurled collar, lathe rings and threaded cable socket. */
@Composable
fun MechanicalShutter(enabled: Boolean, counting: Boolean, frames: Int,
                      onCapture: () -> Unit, onFlash: () -> Unit,
                      modifier: Modifier = Modifier,
    captureProgress: Float? = null, onPrime: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, tween(120), label = "shutter-half-press")
    // The press primes focus and metering in silence: the ring is still
    // recording, and the frames this release locks would carry the motor.
    LaunchedEffect(pressed) {
        if (pressed) onPrime()
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
