package dev.multiframe.camera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable

/** PRO is a camera mode: its lit face and selected semantics track the request state. */
@Composable
fun RangefinderProToggle(engaged: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text("PRO", style = Type.Section, color = if (engaged) Ink.Ground else Ink.Bone,
        modifier = modifier
            .background(if (engaged) Ink.Amber else Ink.Panel)
            .border(1.dp, if (engaged) Ink.Amber else Ink.Hairline)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                selected = engaged
                contentDescription = if (engaged) "PRO mode on" else "PRO mode off"
            }
            .testTag("rangefinder-pro-toggle"))
}

/** Always reachable in the fixed console header, in either PRO state. */
@Composable
fun RangefinderCameraFlip(facingBack: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp, 48.dp)
            .background(Ink.Panel, RoundedCornerShape(24.dp))
            .border(1.dp, Ink.Hairline, RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = if (facingBack) "Switch to front camera" else "Switch to rear camera"
                if (!enabled) disabled()
            }
            .testTag("rangefinder-camera-flip"),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (facingBack) "SELFIE" else "REAR", style = Type.Section, color = Ink.Bone)
    }
}

/** Session torch, offered only when the active camera reports a flash LED. */
@Composable
fun RangefinderTorchToggle(active: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp, 48.dp)
            .background(if (active) Ink.Bone else Ink.Panel, RoundedCornerShape(24.dp))
            .border(1.dp, if (active) Ink.Bone else Ink.Hairline, RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                selected = active
                contentDescription = if (active) "Torch on. Tap to turn off" else "Torch off. Tap to turn on"
                if (!enabled) disabled()
            }
            .testTag("rangefinder-torch"),
        contentAlignment = Alignment.Center,
    ) {
        Text("TORCH", style = Type.Section, color = if (active) Ink.OnBone else Ink.Bone)
    }
}

/** A persistent self-timer detent beside the shutter, including while PRO is open. */
@Composable
fun RangefinderTimerToggle(seconds: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.width(80.dp).heightIn(min = 48.dp)
        .background(Ink.Panel, RoundedCornerShape(24.dp))
        .border(1.dp, if (seconds > 0) Ink.Bone else Ink.Hairline, RoundedCornerShape(24.dp))
        .clearAndSetSemantics {
            contentDescription = if (seconds == 0) "Timer off. Tap to change" else "Timer $seconds seconds. Tap to change"
            if (enabled) onClick { onClick(); true } else disabled()
        }
        .clickable(enabled = enabled, onClick = onClick)
        .testTag("rangefinder-timer"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TIMER", style = Type.Section, color = Ink.Muted)
            Text(if (seconds == 0) "OFF" else "${seconds}s", style = Type.Section,
                color = if (seconds == 0) Ink.Bone else Ink.Amber)
        }
    }
}

/** The fixed 322dp mechanical console. Both faces are measured in the same box. */
@Composable
fun RangefinderConsole(
    open: Boolean,
    modifier: Modifier = Modifier,
    onMeasured: (IntSize) -> Unit = {},
    header: @Composable BoxScope.() -> Unit,
    shutter: @Composable BoxScope.() -> Unit,
    idleAroundShutter: @Composable BoxScope.() -> Unit,
    idle: @Composable BoxScope.() -> Unit,
    drawer: @Composable BoxScope.() -> Unit,
) {
    val navigationInsetPx = WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat()
    val shift = animateFloatAsState(if (open) 1f else 0f,
        tween(540, easing = CubicBezierEasing(.2f, .84f, .24f, 1f)), label = "darkroom-shift").value
    val alpha = animateFloatAsState(if (open) 1f else 0f, tween(300), label = "darkroom-opacity").value
    Box(
        modifier = modifier.fillMaxWidth().height(RangefinderConsoleHeight)
            .drawBehind {
                // Keep the navigation gesture area genuinely transparent. The
                // image is behind it; only the console face gets an opaque fill.
                drawRect(Ink.Ground, size = Size(size.width, (size.height - navigationInsetPx).coerceAtLeast(0f)))
            }.clipToBounds()
            .testTag("rangefinder-console")
            .onSizeChanged(onMeasured),
    ) {
        // The shutter has its own permanent 130dp band. Only the secondary
        // controls below it and the readouts beside it swap when PRO changes.
        Box(Modifier.fillMaxWidth().height(125.dp).offset(y = 48.dp)) {
            Box(Modifier.fillMaxSize().semantics { if (open) hideFromAccessibility() }
                .graphicsLayer { this.alpha = 1f - alpha }) { idleAroundShutter() }
            if (open) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            })
            shutter()
        }
        Box(Modifier.fillMaxWidth().height(RangefinderConsoleHeight - 173.dp)
            .align(Alignment.BottomCenter).clipToBounds()) {
            Box(Modifier.fillMaxSize().semantics { if (open) hideFromAccessibility() }.graphicsLayer {
                translationY = -size.height * .18f * shift
                this.alpha = 1f - alpha
            }) {
                idle()
                if (open) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                })
            }
            Box(Modifier.fillMaxSize().semantics { if (!open) hideFromAccessibility() }.graphicsLayer {
                translationY = size.height * 1.04f * (1f - shift)
                this.alpha = alpha
            }) { drawer() }
        }
        Box(Modifier.fillMaxWidth().height(48.dp).testTag("rangefinder-console-header")) { header() }
    }
}

/** Five equally measured focal positions, with an overshooting metal cursor. */
@Composable
fun RangefinderTurret(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit,
                      modifier: Modifier = Modifier, subPixelAvailable: Boolean = true,
                      superResBadge: String? = null) {
    val labels = listOf("13mm", "24mm", "48mm", "120mm", "240mm")
    val latestSelect by rememberUpdatedState(onSelect)
    var widthPx by remember { mutableIntStateOf(1) }
    val animatedIndex by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = spring(dampingRatio = .48f, stiffness = 430f),
        label = "focal-turret-position",
    )
    Box(
        modifier.fillMaxWidth().height(RangefinderTurretHeight)
            .background(Ink.Panel)
            .border(1.dp, Ink.Hairline)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(enabled, widthPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun choose(x: Float) = latestSelect(((x / widthPx) * 5f).toInt().coerceIn(0, 4))
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
            .testTag("rangefinder-turret"),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 3.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(Ink.Hairline, Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
        }
        Box(Modifier.fillMaxWidth(.2f).fillMaxHeight().offset { IntOffset((animatedIndex * widthPx / 5f).toInt(), 0) }
            .border(1.dp, Ink.Amber).background(Ink.AmberDim)
            .semantics { turretHighlightIndex = animatedIndex }
            .testTag("rangefinder-turret-highlight"))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(label, style = Type.LensFocal, color = if (index == selected) Ink.Amber else Ink.Bone,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (subPixelAvailable && (selected == 2 || selected == 4)) {
            Text("Sub-Pixel Optical Merge", style = Type.Section, color = Ink.Muted,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                    .testTag("rangefinder-subpixel-label"))
            superResBadge?.let { badge ->
                Text(badge, style = Type.Section, color = Ink.Amber,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 2.dp)
                        .testTag("rangefinder-superres-badge"))
            }
        }
    }
}

/** Three-column cylinder tumbler: taps select, a swipe crosses one detent at a time. */
@Composable
fun RangefinderModeTumbler(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf("PHOTO", "NIGHT", "LONG EXP")
    val latestSelect by rememberUpdatedState(onSelect)
    val latestIndex by rememberUpdatedState(selected)
    var widthPx by remember { mutableIntStateOf(1) }
    val animatedIndex by animateFloatAsState(selected.toFloat(), spring(dampingRatio=.48f,stiffness=430f),
        label="mode-tumbler-position")
    val threshold = 36.dp
    Box(modifier.fillMaxWidth().height(61.dp)
        .background(Brush.verticalGradient(listOf(Color(0xFF25252A),Color(0xFF151519),Color(0xFF111115),Color(0xFF222228))))
        .border(1.dp,Ink.Hairline)
        .onSizeChanged { widthPx=it.width.coerceAtLeast(1) }
        .pointerInput(enabled,widthPx) {
            if(!enabled) return@pointerInput
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                latestSelect(((down.position.x/widthPx)*3f).toInt().coerceIn(0,2))
                var lastX=down.position.x
                while(true) {
                    val change=awaitPointerEvent().changes.firstOrNull()?:break
                    if(!change.pressed) break
                    if(kotlin.math.abs(change.position.x-lastX)>=threshold.toPx()) {
                        latestSelect((latestIndex+if(change.position.x<lastX)1 else -1).coerceIn(0,2))
                        lastX=change.position.x
                    }
                    change.consume()
                }
            }
        }.semantics { contentDescription="Cylinder mode tumbler, ${labels[selected.coerceIn(0,2)]}" }
        .testTag("rangefinder-mode-tumbler")) {
        Canvas(Modifier.fillMaxSize()) {
            val pitch=5.dp.toPx()
            var x=0f
            while(x<size.width) {
                drawLine(Ink.Muted.copy(alpha=.28f),Offset(x,0f),Offset(x,5.dp.toPx()),1f)
                drawLine(Ink.Muted.copy(alpha=.28f),Offset(x,size.height-5.dp.toPx()),Offset(x,size.height),1f)
                x+=pitch
            }
        }
        Box(Modifier.fillMaxWidth(1f/3f).height(3.dp).offset {
            IntOffset((animatedIndex*widthPx/3f).toInt(),0)
        }.background(Ink.Amber).semantics { modeIndicatorIndex = animatedIndex }
            .testTag("rangefinder-mode-indicator"))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i,label ->
                Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center) {
                    Text(label,style=Type.Mode,color=if(i==selected)Ink.Bone else Ink.Muted)
                    if(i==selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom=7.dp)
                        .size(5.dp).background(Ink.Amber,CircleShape))
                }
            }
        }
    }
}
