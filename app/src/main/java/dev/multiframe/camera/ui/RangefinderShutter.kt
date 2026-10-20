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
