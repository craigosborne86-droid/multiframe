package dev.multiframe.camera.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.R
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.FrameCountPolicy
import dev.multiframe.camera.pipeline.ManualSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.pow

enum class RangefinderTab { ISO, SHUTTER, FOCUS, FRAMES, COLOR }

private data class DialValue(val label: String, val numeric: Long = 0L, val major: Boolean = false)

private fun valuesFor(tab: RangefinderTab, caps: CameraCapabilities, frameCeiling: Int): List<DialValue> = when (tab) {
    RangefinderTab.ISO -> listOf(64,100,200,400,800,1600,3200,6400,12800,25600)
        .filter { it >= (caps.isoMin ?: 1) && it <= (caps.isoMax ?: Int.MAX_VALUE) }
        .map { DialValue(it.toString(),it.toLong(),it==100 || it==400 || it==1600 || it==6400) }
    RangefinderTab.SHUTTER -> (
        listOf(1000,800,640,500,400,320,250,200,160,125,100,80,60,50,40,30,15,8,4,2)
        .map { DialValue("1/$it",1_000_000_000L/it,it in listOf(1000,500,250,125,60,30)) }
        + listOf(1L,2L,4L,6L).map { DialValue("${it}s",it*1_000_000_000L,true) }
    )
        .filter { it.numeric >= (caps.exposureMinNs ?: 0L) && it.numeric <= (caps.exposureMaxNs ?: Long.MAX_VALUE) }
    RangefinderTab.FOCUS -> listOf(.1f,.2f,.3f,.5f,.7f,1f,1.5f,2f,3f,5f,10f)
        .filter { 1f/it <= caps.minFocusDiopters }
        .mapIndexed { i,m -> DialValue("${m}m",(1_000_000f/m).toLong(),i%2==0) } + DialValue("∞",0L,true)
    RangefinderTab.FRAMES -> FrameCountPolicy.stepsUpTo(frameCeiling)
        .map { count -> DialValue("${count}f", count.toLong(), true) }
    // Camera2 AWB modes are categorical. There is no calibrated Kelvin-to-gains
    // transform in this app's current ManualSettings; show the real presets.
    RangefinderTab.COLOR -> ManualSettings.AWB_LABELS.filterKeys {
        it in caps.awbModes && (it != android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO || caps.supportsAwbLock)
    }.map { (mode,label) ->
        DialValue(if (mode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO) "LOCKED AWB"
            else label.uppercase(), mode.toLong(), true)
    }
}

@Composable
fun RangefinderDial(
    settings: ManualSettings,
    caps: CameraCapabilities?,
    frames: Int,
    frameCeiling: Int,
    tab: RangefinderTab,
    onTab: (RangefinderTab) -> Unit,
    onSettings: (ManualSettings) -> Unit,
    onFrames: (Int) -> Unit,
    compact: Boolean = false,
    landscapeCompact: Boolean = false,
    showTabs: Boolean = true,
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    docked: Boolean = false,
    header: (@Composable (String) -> Unit)? = null,
) {
    if (caps == null) return
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestSettings by rememberUpdatedState(settings)
    val latestOnSettings by rememberUpdatedState(onSettings)
    val latestOnFrames by rememberUpdatedState(onFrames)
    val latestHapticsEnabled by rememberUpdatedState(hapticsEnabled)
    val available = remember(caps,frameCeiling) {
        RangefinderTab.entries.filter { valuesFor(it,caps,frameCeiling).isNotEmpty() &&
            (it != RangefinderTab.FOCUS || caps.hasManualFocus) &&
            (it != RangefinderTab.ISO && it != RangefinderTab.SHUTTER || caps.hasManualSensor) }
    }
    val values = remember(tab,caps,frameCeiling) { valuesFor(tab,caps,frameCeiling) }
    if (values.isEmpty()) return
    val colorTabLabel = stringResource(R.string.rangefinder_color_tab)
    val colorFooter = stringResource(R.string.rangefinder_color_footer)
    val raw = when(tab) {
        RangefinderTab.ISO -> settings.iso.toLong()
        RangefinderTab.SHUTTER -> settings.exposureTimeNs
        RangefinderTab.FOCUS -> if(settings.focusDiopters == 0f) 0L else (settings.focusDiopters*1_000_000).toLong()
        RangefinderTab.FRAMES -> frames.toLong()
        RangefinderTab.COLOR -> settings.awbMode.toLong()
    }
    var index by remember(tab,caps,frameCeiling) {
        mutableIntStateOf(values.indices.minByOrNull { abs(values[it].numeric-raw) } ?: 0)
    }
    var offsetPx by remember(tab) { mutableFloatStateOf(0f) }
    LaunchedEffect(raw, values) {
        val selected = values.indices.minByOrNull { abs(values[it].numeric - raw) } ?: 0
        if (index != selected) {
            index = selected
            offsetPx = 0f
        }
    }
    var travelPx by remember(tab) { mutableFloatStateOf(0f) }
    var coast by remember { mutableStateOf<Job?>(null) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var lastTime by remember { mutableLongStateOf(0L) }
    val tickPaint = remember(density) {
        Paint().apply {
            color = TitaniumTokens.TitaniumM6.copy(alpha = .86f).toArgb()
            textSize = with(density) { TitaniumTokens.Section.fontSize.toPx() }
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
    }
    val widestTickLabelPx = remember(values, tickPaint) {
        values.maxOf { tickPaint.measureText(it.label) }
    }
    val stepPx = with(density) {
        if (tab == RangefinderTab.COLOR) maxOf(34.dp.toPx(), widestTickLabelPx + 12.dp.toPx())
        else 34.dp.toPx()
    }
    fun apply(next: Int) {
        val clamped = if (tab == RangefinderTab.COLOR) ((next % values.size) + values.size) % values.size
            else next.coerceIn(0, values.lastIndex)
        if (clamped==index) return
        index=clamped
        val v=values[clamped]
        if (latestHapticsEnabled) Haptics.dialDetent(view)
        when(tab) {
            RangefinderTab.ISO -> latestOnSettings(latestSettings.copy(manualExposure=true,iso=v.numeric.toInt()))
            RangefinderTab.SHUTTER -> latestOnSettings(latestSettings.copy(manualExposure=true,exposureTimeNs=v.numeric))
            RangefinderTab.FOCUS -> latestOnSettings(latestSettings.copy(manualFocus=true,rememberedManualFocus=true,focusDiopters=v.numeric/1_000_000f))
            RangefinderTab.FRAMES -> latestOnFrames(v.numeric.toInt())
            RangefinderTab.COLOR -> latestOnSettings(latestSettings.copy(awbMode=v.numeric.toInt()))
        }
    }
    fun move(dx: Float) {
        travelPx += dx
        offsetPx+=dx
        while (offsetPx<=-stepPx) { offsetPx+=stepPx; apply(index+1) }
        while (offsetPx>=stepPx) { offsetPx-=stepPx; apply(index-1) }
        if (tab != RangefinderTab.COLOR &&
            ((index==0&&offsetPx>0)||(index==values.lastIndex&&offsetPx<0))) offsetPx=0f
    }
    fun settle() {
        when {
            offsetPx <= -stepPx / 2f -> apply(index + 1)
            offsetPx >= stepPx / 2f -> apply(index - 1)
        }
        offsetPx = 0f
    }
    Column(modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("rangefinder-dial")) {
        if (showTabs) Row(Modifier.fillMaxWidth().height(if (compact) 35.dp else 48.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            available.forEach { item ->
                val label = if (item == RangefinderTab.COLOR) colorTabLabel else item.name
                Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                    .border(TitaniumTokens.Hairline, if(tab==item) TitaniumTokens.OpticalAmber else TitaniumTokens.HairlineBorder)
                    .clickable { coast?.cancel(); onTab(item); Haptics.segment(view) }
                    .semantics { contentDescription = "$label dial tab" },contentAlignment=Alignment.Center) {
                    Text(label, style=TitaniumTokens.Section, color=if(tab==item) TitaniumTokens.OpticalAmber else TitaniumTokens.TelemetryDim)
                }
            }
        }
        if (header != null) header(values[index].label) else Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 36.dp else 44.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceBetween) {
            Text(when(tab){RangefinderTab.ISO->"SENSOR GAIN / ISO";RangefinderTab.SHUTTER->"SHUTTER / ⅓ STOP";
                RangefinderTab.FOCUS->"MANUAL FOCUS / M";RangefinderTab.FRAMES->"FUSION DEPTH / FRAMES";
                RangefinderTab.COLOR->"WHITE BALANCE / PRESET"}, style=TitaniumTokens.Section)
            Text(values[index].label, style=TitaniumTokens.SelectedValue)
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("‹", style=TitaniumTokens.GaugeBone, modifier=Modifier.size(48.dp).clickable {
                coast?.cancel(); offsetPx = 0f; apply(index-1)
            }
                .semantics { contentDescription="Previous dial detent" })
            Canvas(Modifier.weight(1f).height(if (docked) TitaniumTokens.MinimumTouch else if (compact) 64.dp else 91.dp).background(Brush.verticalGradient(listOf(TitaniumTokens.PlateSurface, TitaniumTokens.Chassis)))
                .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder)
                .pointerInput(tab,values) {
                    detectHorizontalDragGestures(
                        onDragStart={ coast?.cancel(); velocity=0f; lastTime=0L },
                        onHorizontalDrag={ change,dx ->
                            val now=change.uptimeMillis
                            val dt=(now-lastTime).coerceAtLeast(1L).toFloat()
                            if(lastTime!=0L) velocity=.72f*velocity+.28f*dx/dt
                            lastTime=now
                            move(dx);change.consume()
                        },
                        onDragEnd={
                            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { settle(); return@detectHorizontalDragGestures }
                            // Keep a little inertia, but never coast across a
                            // second value after the finger has left the dial.
                            var v=(velocity*16f).coerceIn(-stepPx*.25f,stepPx*.25f)
                            coast=scope.launch {
                                var previous=0L
                                var remaining=stepPx*.5f
                                while(abs(v)>.28f && remaining>0f) {
                                    withFrameNanos { stamp ->
                                        if(previous!=0L) {
                                             val dt=((stamp-previous)/1_000_000f).coerceIn(1f,32f)
                                             v*=.78f.pow(dt/16f)
                                             val dx=(v*dt/16f).coerceIn(-remaining,remaining)
                                             move(dx)
                                             remaining-=abs(dx)
                                         }
                                         previous=stamp
                                     }
                                 }
                                 settle()
                             }
                         },
                         onDragCancel={ coast?.cancel(); settle() },
                     )
                 }
                 .semantics {
                     contentDescription = "${tab.name} inertial control dial"
                     dialTravelPx = travelPx
                     dialOffsetPx = offsetPx
                     dialTickStepPx = stepPx
                     dialWidestTickLabelPx = widestTickLabelPx
                 }
                 .testTag("rangefinder-dial-ruler")) {
                 val mid=size.width/2f
                 val start=index-20; val end=index+20
                 for(i in start..end) {
                     if(tab != RangefinderTab.COLOR && i !in values.indices) continue
                     val x=mid+(i-index)*stepPx+offsetPx
                     if(x<0f||x>size.width) continue
                     val value=values[((i % values.size) + values.size) % values.size]
                     val major=value.major
                     drawLine(if(i==index) TitaniumTokens.OpticalAmber else if(major) TitaniumTokens.TelemetryWhite else TitaniumTokens.TelemetryDim,
                         Offset(x,0f),Offset(x,if(major)size.height*.70f else size.height*.45f),
                         if(i==index)2.dp.toPx() else 1.dp.toPx())
                     if(major) {
                         drawContext.canvas.nativeCanvas.drawText(value.label,x,size.height-6.dp.toPx(),tickPaint)
                     }
                 }
                 drawLine(TitaniumTokens.OpticalAmber,Offset(mid,0f),Offset(mid,size.height),2.dp.toPx())
             }
             Text("›", style=TitaniumTokens.GaugeBone, modifier=Modifier.size(48.dp).clickable {
                 coast?.cancel(); offsetPx = 0f; apply(index+1)
             }
                 .semantics { contentDescription="Next dial detent" })
         }
         if (!compact) Text(when(tab){RangefinderTab.FOCUS->"CYAN PEAKING";RangefinderTab.FRAMES->"BURST DEPTH";
             RangefinderTab.COLOR->colorFooter;RangefinderTab.ISO->"SENSOR GAIN";
             RangefinderTab.SHUTTER->"⅓ EV STEPS"}, style=TitaniumTokens.Section,
             modifier=Modifier.align(Alignment.End).padding(top=6.dp))
     }
 }
