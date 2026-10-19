package dev.multiframe.camera.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.random.Random

/** Editable Compose rendering of the reference's 130px canvas shutter. */
@Composable
fun RangefinderShutter(
    enabled: Boolean,
    counting: Boolean,
    frames: Int,
    onCapture: () -> Unit,
    onFlash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .94f else 1f,tween(130),label="shutter-half-press")
    val progress = remember { Animatable(0f) }
    val progressDurationMs = 520 + frames * 14
    Canvas(modifier.size(130.dp).graphicsLayer { scaleX=scale;scaleY=scale }
        .clickable(enabled=enabled || counting,interactionSource=interaction,indication=null) {
            onCapture()
            if(!counting) {
                onFlash()
                scope.launch(Dispatchers.Default) { MechanicalClick.play() }
                // CameraScreen emits the mechanical haptic at a safe point in
                // the actual capture path, never during sensor integration.
                scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f,tween(progressDurationMs))
                    delay(180)
                    progress.snapTo(0f)
                }
            }
        }
        .semantics {
            contentDescription=if(counting)"Cancel timer" else "Shutter"
            shutterScale = scale
            shutterProgress = progress.value
            shutterDurationMs = progressDurationMs
        }
        .testTag("rangefinder-shutter")) {
        val cx=size.width/2f;val cy=size.height/2f;val r=size.minDimension*.477f
        drawCircle(brush=Brush.radialGradient(listOf(Color(0xFF414148),Color(0xFF242429),Color(0xFF77777C),Color(0xFF17171C)),
            center=Offset(cx-r*.18f,cy-r*.25f),radius=r*1.4f),radius=r)
        for(i in 0 until 104) {
            val a=(i*2*PI/104).toFloat();val r1=if(i%4==0)r*.84f else r*.90f
            drawLine(if(i%4==0)Color(0xFFA0A0A5) else Color(0xFF525258),
                Offset(cx+cos(a)*r1,cy+sin(a)*r1),Offset(cx+cos(a)*r*.985f,cy+sin(a)*r*.985f),
                if(i%4==0)1.1.dp.toPx() else .7.dp.toPx())
        }
        drawCircle(Color(0xFF0C0C10),radius=r*.82f,style=Stroke(3.dp.toPx()))
        drawCircle(brush=Brush.radialGradient(listOf(Color(0xFF333339),Color(0xFF1B1B20),Color(0xFF09090C)),
            center=Offset(cx-r*.28f,cy-r*.33f),radius=r*.80f),radius=r*.76f)
        drawCircle(Color(0xFF8F8F95),radius=r*.73f,style=Stroke(1.2.dp.toPx()))
        drawCircle(Color(0xFF37373E),radius=r*.57f,style=Stroke(1.dp.toPx()))
        drawCircle(Color(0xFF4A4A50),radius=2.4.dp.toPx(),center=Offset(cx-r*.16f,cy-r*.18f))
        if(progress.value>0f) drawArc(Ink.Amber,-90f,360f*progress.value,false,
            topLeft=Offset(cx-r*.79f,cy-r*.79f),size=androidx.compose.ui.geometry.Size(r*1.58f,r*1.58f),
            style=Stroke(3.5.dp.toPx(),cap=StrokeCap.Round))
    }
}

/** Two damped, band-pass-filtered noise impulses, matching the Web Audio reference. */
internal object MechanicalClick {
    internal const val SAMPLE_RATE = 44_100

    fun synthesize(seed: Long = System.nanoTime()): ShortArray {
        val rate=SAMPLE_RATE;val samples=ShortArray((rate*.11).toInt())
        for((n,start) in listOf(0,52).withIndex()) {
            val duration=if(n==0)14 else 25
            val centre=if(n==0)1800.0 else 720.0
            val omega=2.0*PI*centre/rate;val alpha=sin(omega)/(2*.8)
            val a0=1+alpha;val b0=alpha/a0;val b2=-alpha/a0
            val a1=-2*cos(omega)/a0;val a2=(1-alpha)/a0
            var x1=0.0;var x2=0.0;var y1=0.0;var y2=0.0
            val length=rate*duration/1000;val offset=rate*start/1000
            val random=Random(seed+n)
            for(i in 0 until length) {
                val x=random.nextDouble(-1.0,1.0)
                val y=b0*x+b2*x2-a1*y1-a2*y2
                x2=x1;x1=x;y2=y1;y1=y
                val envelope=(1.0-i.toDouble()/length).pow(2.0)
                val gain=if(n==0).16 else .11
                samples[offset+i]=(y*envelope*gain*32767).toInt().coerceIn(-32768,32767).toShort()
            }
        }
        return samples
    }

    suspend fun play() {
        val rate=SAMPLE_RATE
        val samples=synthesize()
        runCatching {
            val track=AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size*2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(samples,0,samples.size)
            track.play()
            delay(140)
            track.release()
        }
    }
}
