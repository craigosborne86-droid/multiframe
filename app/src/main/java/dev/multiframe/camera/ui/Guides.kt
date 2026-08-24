package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import dev.multiframe.camera.pipeline.Attitude
import kotlin.math.abs
import kotlin.math.min

/** What composition aids are showing. Cycled by one control rather than several. */
enum class GuideMode {
    OFF,
    GRID,
    GRID_AND_LEVEL;

    val showsGrid: Boolean get() = this != OFF
    val showsLevel: Boolean get() = this == GRID_AND_LEVEL

    val label: String
        get() = when (this) {
            OFF -> "GUIDES"
            GRID -> "GRID"
            GRID_AND_LEVEL -> "GRID+LEVEL"
        }

    fun next(): GuideMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Composition aids drawn over the viewfinder.
 *
 * Deliberately faint. A guide that competes with the picture for attention
 * stops being a guide, so these sit at the edge of visibility and the level
 * only asserts itself when it has something to say.
 */
@Composable
fun Guides(
    mode: GuideMode,
    attitude: Attitude?,
    modifier: Modifier = Modifier,
) {
    if (mode == GuideMode.OFF) return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (mode.showsGrid) {
                val line = Color(0x33FFFFFF)
                for (i in 1..2) {
                    val x = size.width * i / 3f
                    val y = size.height * i / 3f
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
            }

            if (!mode.showsLevel || attitude == null) return@Canvas

            val centreX = size.width / 2f
            val centreY = size.height / 2f
            val span = min(size.width, size.height) * 0.28f

            if (attitude.facingUpOrDown) {
                // Roll means nothing when the phone points up or down: gravity
                // lies along the viewing axis and there is no horizon in frame.
                // A ring says "pointing straight at the ground" without
                // pretending to know which way up that is.
                drawCircle(
                    color = Color(0x66FFFFFF),
                    radius = span * 0.16f,
                    center = Offset(centreX, centreY),
                    style = Stroke(width = 2f),
                )
                return@Canvas
            }

            // Green only when it is actually level, so the colour carries the
            // information rather than merely decorating.
            val colour = if (attitude.isLevel) Color(0xFF6FE39A) else Color(0xCCFFFFFF)

            // The fixed reference the moving line is judged against.
            drawLine(
                Color(0x44FFFFFF),
                Offset(centreX - span * 0.2f, centreY),
                Offset(centreX + span * 0.2f, centreY),
                strokeWidth = 2f,
            )

            rotate(degrees = attitude.rollDegrees, pivot = Offset(centreX, centreY)) {
                drawLine(
                    colour,
                    Offset(centreX - span, centreY),
                    Offset(centreX - span * 0.28f, centreY),
                    strokeWidth = 3f,
                )
                drawLine(
                    colour,
                    Offset(centreX + span * 0.28f, centreY),
                    Offset(centreX + span, centreY),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

/**
 * Live histogram of the scene.
 *
 * The one display that answers the question exposure is actually about: is
 * anything clipped. A clipped highlight cannot be recovered later, and it is
 * invisible on a phone screen in daylight, which is exactly when it happens.
 */
@Composable
fun Histogram(
    bins: IntArray,
    modifier: Modifier = Modifier,
) {
    if (bins.isEmpty()) return
    val peak = bins.max().coerceAtLeast(1)

    Canvas(modifier = modifier) {
        val barWidth = size.width / bins.size
        bins.forEachIndexed { index, count ->
            // Square root, so a few hundred clipped pixels are visible against
            // the hundreds of thousands in the midtones. Linear would hide
            // exactly the thing worth seeing.
            val height = size.height * kotlin.math.sqrt(count.toFloat() / peak)
            val clipping = index == bins.size - 1 && count > 0
            drawRect(
                color = if (clipping) Color(0xFFFF6B6B) else Color(0xBBFFFFFF),
                topLeft = Offset(index * barWidth, size.height - height),
                size = androidx.compose.ui.geometry.Size(
                    width = barWidth.coerceAtLeast(1f),
                    height = height,
                ),
            )
        }
    }
}
