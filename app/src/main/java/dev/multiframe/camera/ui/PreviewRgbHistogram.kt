package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal data class PreviewRgbHistogram(val channels: List<IntArray>, val timestamp: Long, val samplingMicros: Long)

@Composable
internal fun PreviewRgbPlot(histogram: PreviewRgbHistogram?, modifier: Modifier = Modifier) {
    Column(modifier.width(140.dp).background(TitaniumTokens.Chassis.copy(alpha = .85f))
        .border(TitaniumTokens.Hairline, TitaniumTokens.HairlineBorder).padding(6.dp)
        .semantics { contentDescription = "Preview RGB histogram. Rendered preview, not RAW clipping." }) {
        Text("Preview RGB", style = TitaniumTokens.ReadableSmall)
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val channels = histogram?.channels ?: return@Canvas
            val peak = channels.maxOf { it.maxOrNull() ?: 1 }.coerceAtLeast(1)
            channels.forEachIndexed { index, bins ->
                val path = Path()
                bins.forEachIndexed { i, count ->
                    val x = i * size.width / (bins.size - 1)
                    val y = size.height * (1 - count.toFloat() / peak)
                    if(i == 0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                drawPath(path, listOf(TitaniumTokens.HistogramRed, TitaniumTokens.HistogramGreen, TitaniumTokens.HistogramBlue)[index], style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
