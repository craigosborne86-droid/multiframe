package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.multiframe.camera.pipeline.Attitude
import kotlin.math.abs
import kotlin.math.roundToInt

/** One-finger movement on the live frame; the centre reticle handles taps separately. */
@Composable
fun Modifier.rangefinderTiltGesture(onTilt: (Float) -> Unit): Modifier {
    val latestTilt by rememberUpdatedState(onTilt)
    return this.pointerInput(Unit) {
        detectDragGestures { change, _ ->
            val angle = ((change.position.x / size.width - .5f) * 14f).coerceIn(-8f, 8f)
            latestTilt(if (abs(angle) < .4f) 0f else angle)
            change.consume()
        }
    }
}

/** Dragging the image adjusts the instrument horizon; tapping its cross snaps level. */
@Composable
fun RangefinderReticle(tilt: Float, mode: String, zebra: Boolean, manualFocus: Boolean,
                       onLevel: () -> Unit,
                       measuredTilt: Float? = tilt,
                       onZebraToggle: () -> Unit = {},
                       bottomReadoutInset: Dp = 0.dp,
                       focusAssistAvailable: Boolean = false,
                       focusAssistOpen: Boolean = false,
                       focusAnchor: Offset = Offset(.5f, .5f),
                       onFocusAssistToggle: () -> Unit = {},
                       onFocusAnchorChange: (Offset) -> Unit = {},
                       onFocusInsetBounds: (Rect) -> Unit = {},
                       modifier: Modifier = Modifier) {
    var reticleSize by remember { mutableStateOf(IntSize.Zero) }
    val latestFocusAnchor by rememberUpdatedState(focusAnchor)
    val latestFocusChange by rememberUpdatedState(onFocusAnchorChange)
    Box(modifier.fillMaxSize().onSizeChanged { reticleSize = it }
        .semantics { reticleTilt = tilt }.testTag("rangefinder-view-reticle")) {
        Canvas(Modifier.fillMaxSize()) {
            val mid=Offset(size.width/2,size.height/2)
            val inset=12.dp.toPx(); val arm=12.dp.toPx()
            val corner=Ink.Bone.copy(alpha=.67f)
            for(x in listOf(inset,size.width-inset)) for(y in listOf(inset,size.height-inset)) {
                val dx=if(x<size.width/2)arm else -arm
                val dy=if(y<size.height/2)arm else -arm
                drawLine(corner,Offset(x,y),Offset(x+dx,y),1.dp.toPx())
                drawLine(corner,Offset(x,y),Offset(x,y+dy),1.dp.toPx())
            }
            val level=measuredTilt != null && abs(tilt)<=Attitude.LEVEL_TOLERANCE
            rotate(tilt,pivot=mid) {
                val spread=if(level)48.dp.toPx() else 68.dp.toPx()
                val glow=if(level)Ink.Level else Ink.Bone
                if(level) drawLine(Ink.Level.copy(alpha=.18f),Offset(mid.x-spread,mid.y),
                    Offset(mid.x+spread,mid.y),10.dp.toPx())
                drawLine(glow.copy(alpha=if(level)1f else .74f),Offset(mid.x-spread,mid.y),
                    Offset(mid.x+spread,mid.y),1.5.dp.toPx())
                drawLine(glow.copy(alpha=if(level).9f else .72f),Offset(mid.x,mid.y-13.dp.toPx()),
                    Offset(mid.x,mid.y+13.dp.toPx()),1.dp.toPx())
            }
        }
        Box(Modifier.align(Alignment.Center).size(width=188.dp,height=64.dp)
            .clickable(onClick=onLevel)
            .semantics { contentDescription="Snap horizon level" }
            .testTag("rangefinder-reticle"))
        if (focusAssistAvailable && reticleSize.width > 0 && reticleSize.height > 0) {
            val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
            Canvas(Modifier.offset {
                    IntOffset((focusAnchor.x * reticleSize.width).roundToInt() - targetPx / 2,
                        (focusAnchor.y * reticleSize.height).roundToInt() - targetPx / 2)
                }.size(48.dp)
                .clickable(onClick = onLevel)
                .pointerInput(reticleSize) {
                    var dragged = latestFocusAnchor
                    detectDragGestures(onDragStart = { dragged = latestFocusAnchor }) { change, movement ->
                        dragged = Offset(
                            (dragged.x + movement.x / reticleSize.width).coerceIn(0f, 1f),
                            (dragged.y + movement.y / reticleSize.height).coerceIn(0f, 1f),
                        )
                        latestFocusChange(dragged)
                        change.consume()
                    }
                }
                .semantics { contentDescription = "Drag focus target; tap to level" }
                .testTag("rangefinder-focus-target")) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val ink = Ink.Peaking.copy(alpha = .72f)
                drawCircle(ink, radius = 13.dp.toPx(), center = centre,
                    style = Stroke(width = 1.dp.toPx()))
                val reach = 22.dp.toPx()
                val gap = 16.dp.toPx()
                drawLine(ink, Offset(centre.x - reach, centre.y), Offset(centre.x - gap, centre.y), 1.dp.toPx())
                drawLine(ink, Offset(centre.x + gap, centre.y), Offset(centre.x + reach, centre.y), 1.dp.toPx())
                drawLine(ink, Offset(centre.x, centre.y - reach), Offset(centre.x, centre.y - gap), 1.dp.toPx())
                drawLine(ink, Offset(centre.x, centre.y + gap), Offset(centre.x, centre.y + reach), 1.dp.toPx())
            }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 122.dp, end = 10.dp)
                    .heightIn(min = 48.dp)
                    .background(Ink.Pane, RoundedCornerShape(24.dp))
                    .border(1.dp, if (focusAssistOpen) Ink.Edge else Ink.Hairline, RoundedCornerShape(24.dp))
                    .clickable(onClick = onFocusAssistToggle)
                    .padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Magnified focus assist ${if (focusAssistOpen) "on" else "off"}. Tap to toggle" }
                    .testTag("rangefinder-focus-assist-toggle"), contentAlignment = Alignment.Center) {
                Text("FOCUS 3×", style = Type.Section, color = if (focusAssistOpen) Ink.Bone else Ink.Muted)
            }
            if (focusAssistOpen) {
                Box(Modifier.align(Alignment.TopEnd).statusBarsPadding()
                        .padding(top = 178.dp, end = 10.dp)
                        .size(width = 128.dp, height = 96.dp)
                        .border(1.dp, Ink.Edge)
                        .onGloballyPositioned { onFocusInsetBounds(it.boundsInRoot()) }
                        .clickable(onClick = onFocusAssistToggle)
                        .semantics { contentDescription = "Magnified focus view. Tap to close" }
                        .testTag("rangefinder-focus-inset"))
            }
        }
        Text("$mode / 4:3", style=Type.Section, color=Ink.Bone,
            modifier=Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp))
        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top=65.dp,start=10.dp)
                .heightIn(min=48.dp)
                .background(Ink.Pane, RoundedCornerShape(24.dp))
                .border(1.dp, Ink.Hairline, RoundedCornerShape(24.dp))
                .clickable(onClick=onZebraToggle)
                .padding(horizontal=9.dp)
                .semantics { contentDescription="Labs zebra clipping ${if(zebra)"on" else "off"}. Tap to toggle" }
                .testTag("rangefinder-labs"), contentAlignment=Alignment.Center) {
            Text(if(zebra)"LABS · ZEBRA ON" else "LABS · ZEBRA OFF", style=Type.Section,
                color=if(zebra) Ink.Amber else Ink.Muted)
        }
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top=65.dp,end=10.dp)
                .heightIn(min=48.dp)
                .background(Ink.Pane, RoundedCornerShape(24.dp))
                .border(1.dp, Ink.Hairline, RoundedCornerShape(24.dp))
                .clickable(onClick=onZebraToggle)
                .padding(horizontal=9.dp)
                .semantics { contentDescription="Zebra clipping ${if(zebra)"on" else "off"}. Tap to toggle; same as Labs zebra" }
                .testTag("rangefinder-zebra-badge"), contentAlignment=Alignment.Center) {
            Text(if(zebra)"ZBR ON · TAP" else "ZBR OFF · TAP", style=Type.Section, color=Ink.Bone)
        }
        Text(if(manualFocus)"MF / PHASE LOCK" else "AF / PHASE LOCK",
            style=Type.Section, color=Ink.Bone,
            modifier=Modifier.align(Alignment.BottomStart).padding(start=10.dp,bottom=bottomReadoutInset+10.dp))
        val measuredLevel = measuredTilt != null && abs(measuredTilt) <= Attitude.LEVEL_TOLERANCE
        Text(when {
            measuredTilt == null -> "TILT / —"
            measuredLevel -> "LEVEL / %+.1f°".format(measuredTilt)
            else -> "%+.1f°".format(measuredTilt)
        }, style=Type.Gauge,
            color=if(measuredTilt == null) Ink.Muted else if(measuredLevel) Ink.Level else Ink.Amber,
            modifier=Modifier.align(Alignment.BottomEnd).padding(end=10.dp,bottom=bottomReadoutInset+10.dp)
                .testTag("rangefinder-tilt-badge"))
    }
}
