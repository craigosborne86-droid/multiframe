package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sensor level and focus aids in image coordinates, clear of the stable HUD. */
@Composable
fun TitaniumReticle(
    measuredTilt: Float?,
    manualFocus: Boolean,
    focusAssistAvailable: Boolean,
    focusAssistOpen: Boolean,
    focusAnchor: Offset,
    onFocusAnchorChange: (Offset) -> Unit,
    onFocusInsetBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    topClearance: Dp = 0.dp,
    focusInsetOnLeft: Boolean = false,
) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val tilt = measuredTilt ?: 0f
    val latestAnchor by rememberUpdatedState(focusAnchor)
    val latestChange by rememberUpdatedState(onFocusAnchorChange)
    Box(modifier.fillMaxSize().onSizeChanged { measured = it }
        .semantics { reticleTilt = measuredTilt ?: Float.NaN }.testTag("rangefinder-view-reticle")) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 13.dp.toPx()
            val arm = 16.dp.toPx()
            for (x in listOf(inset, size.width - inset)) for (y in listOf(inset, size.height - inset)) {
                val dx = if (x < size.width / 2f) arm else -arm
                val dy = if (y < size.height / 2f) arm else -arm
                drawLine(TitaniumTokens.FrameLine, Offset(x, y), Offset(x + dx, y), 1.dp.toPx())
                drawLine(TitaniumTokens.FrameLine, Offset(x, y), Offset(x, y + dy), 1.dp.toPx())
            }
            val centre = Offset(size.width / 2f, (topClearance + 36.dp).toPx())
            val level = measuredTilt != null && abs(tilt) <= TitaniumTokens.LevelToleranceDegrees
            if (level) {
                drawLine(TitaniumTokens.PhosphorGreen.copy(alpha = .17f),
                    Offset(centre.x - 28.dp.toPx(), centre.y), Offset(centre.x + 28.dp.toPx(), centre.y), 8.dp.toPx())
                drawLine(TitaniumTokens.PhosphorGreen,
                    Offset(centre.x - 28.dp.toPx(), centre.y), Offset(centre.x + 28.dp.toPx(), centre.y), 1.dp.toPx())
            } else {
                rotate(tilt, pivot = centre) {
                    val reach = 28.dp.toPx()
                    val gap = 5.dp.toPx()
                    val line = TitaniumTokens.TelemetryWhite.copy(alpha = .75f)
                    drawLine(line, Offset(centre.x - reach, centre.y), Offset(centre.x - gap, centre.y), 1.dp.toPx())
                    drawLine(line, Offset(centre.x + gap, centre.y), Offset(centre.x + reach, centre.y), 1.dp.toPx())
                    drawLine(line, Offset(centre.x, centre.y - reach), Offset(centre.x, centre.y - gap), 1.dp.toPx())
                    drawLine(line, Offset(centre.x, centre.y + gap), Offset(centre.x, centre.y + reach), 1.dp.toPx())
                }
            }
        }
        androidx.compose.material3.Text(if (measuredTilt == null) "Level —" else "Level %+.1f°".format(measuredTilt),
            style = TitaniumTokens.ReadableSmall, color = if (measuredTilt != null && abs(measuredTilt) <= TitaniumTokens.LevelToleranceDegrees)
                TitaniumTokens.PhosphorGreen else TitaniumTokens.TelemetryWhite,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = topClearance + 60.dp).testTag("rangefinder-tilt-readout"))
        // Sensor information is read-only: tapping cannot make a tilted device level.
        // This marker is separate from the draggable focus target below.
        Box(Modifier.align(Alignment.TopCenter).offset(y = topClearance + 12.dp).size(48.dp)
            .testTag("rangefinder-reticle"))
        if (manualFocus && focusAssistAvailable && measured.width > 0 && measured.height > 0) {
            val widthPx = measured.width
            val heightPx = measured.height
            Canvas(Modifier.offset {
                    IntOffset((focusAnchor.x * widthPx).roundToInt() - 24.dp.roundToPx(),
                        (focusAnchor.y * heightPx).roundToInt() - 24.dp.roundToPx())
                }.size(48.dp)
                .pointerInput(widthPx, heightPx) {
                    var dragged = latestAnchor
                    detectDragGestures(onDragStart = { dragged = latestAnchor }) { change, delta ->
                        dragged = Offset((dragged.x + delta.x / widthPx).coerceIn(0f, 1f),
                            (dragged.y + delta.y / heightPx).coerceIn(0f, 1f))
                        latestChange(dragged)
                        change.consume()
                    }
                }.semantics { contentDescription = "Drag focus target" }
                .testTag("rangefinder-focus-target")) {
                val mid = Offset(size.width / 2f, size.height / 2f)
                val cyan = TitaniumTokens.SignalCyan.copy(alpha = .58f)
                drawCircle(cyan, 13.dp.toPx(), mid, style = Stroke(1.dp.toPx()))
                val span = 21.dp.toPx()
                drawLine(cyan, Offset(mid.x - span, mid.y), Offset(mid.x - 16.dp.toPx(), mid.y), 1.dp.toPx())
                drawLine(cyan, Offset(mid.x + 16.dp.toPx(), mid.y), Offset(mid.x + span, mid.y), 1.dp.toPx())
            }
            if (focusAssistOpen) {
                // Keep the loupe opposite the bounded landscape manual panel.
                Box(Modifier.align(if (focusInsetOnLeft) Alignment.TopStart else Alignment.TopEnd)
                    .offset(x = if (focusInsetOnLeft) 8.dp else (-8).dp, y = topClearance + 8.dp)
                    .size(TitaniumTokens.FocusInsetWidth, TitaniumTokens.FocusInsetHeight)
                    .border(TitaniumTokens.Hairline, TitaniumTokens.SignalCyan,
                        RoundedCornerShape(TitaniumTokens.RadiusSmall))
                    .onGloballyPositioned { onFocusInsetBounds(it.boundsInRoot()) }
                    .testTag("rangefinder-focus-inset"))
            }
        }
    }
}

/** Minimalist tap-to-focus reticle: optical amber precision corner brackets with center crosshairs. */
@Composable
fun TitaniumFocusPointReticle(
    point: Offset,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizeDp = 48.dp
    val sizePx = with(density) { sizeDp.toPx() }
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (point.x - sizePx / 2f).roundToInt(),
                    (point.y - sizePx / 2f).roundToInt(),
                )
            }
            .size(sizeDp)
            .semantics { contentDescription = "Focus point" }
            .testTag("rangefinder-tap-focus-reticle"),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val amber = TitaniumTokens.OpticalAmber
            val arm = 8.dp.toPx()
            val stroke = 1.dp.toPx()
            // Corner brackets
            drawLine(amber, Offset(0f, 0f), Offset(arm, 0f), stroke)
            drawLine(amber, Offset(0f, 0f), Offset(0f, arm), stroke)
            drawLine(amber, Offset(size.width, 0f), Offset(size.width - arm, 0f), stroke)
            drawLine(amber, Offset(size.width, 0f), Offset(size.width, arm), stroke)
            drawLine(amber, Offset(0f, size.height), Offset(arm, size.height), stroke)
            drawLine(amber, Offset(0f, size.height), Offset(0f, size.height - arm), stroke)
            drawLine(amber, Offset(size.width, size.height), Offset(size.width - arm, size.height), stroke)
            drawLine(amber, Offset(size.width, size.height), Offset(size.width, size.height - arm), stroke)
            // Center precision cross with gap
            val cx = size.width / 2f
            val cy = size.height / 2f
            val gap = 4.dp.toPx()
            val crossArm = 4.dp.toPx()
            drawLine(amber, Offset(cx - gap - crossArm, cy), Offset(cx - gap, cy), stroke)
            drawLine(amber, Offset(cx + gap, cy), Offset(cx + gap + crossArm, cy), stroke)
            drawLine(amber, Offset(cx, cy - gap - crossArm), Offset(cx, cy - gap), stroke)
            drawLine(amber, Offset(cx, cy + gap), Offset(cx, cy + gap + crossArm), stroke)
        }
    }
}
