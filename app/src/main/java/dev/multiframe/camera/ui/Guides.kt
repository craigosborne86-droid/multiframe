package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import dev.multiframe.camera.pipeline.Attitude
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

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

    /**
     * What the control reads, as opposed to what it is called.
     *
     * These were the same string when the control was one pill carrying both,
     * which put "GUIDES GUIDES" on the screen the moment the two were pulled
     * apart. A control's name is not one of its values.
     */
    val valueLabel: String
        get() = when (this) {
            OFF -> "OFF"
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
                val line = Ink.Guide
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
                    color = Ink.GuideStrong,
                    radius = span * 0.16f,
                    center = Offset(centreX, centreY),
                    style = Stroke(width = 2f),
                )
                return@Canvas
            }

            // Green only when it is actually level, so the colour carries the
            // information rather than merely decorating.
            val colour = if (attitude.isLevel) Ink.Level else Ink.GuideBright

            // The fixed reference the moving line is judged against.
            drawLine(
                Ink.GuideMid,
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
