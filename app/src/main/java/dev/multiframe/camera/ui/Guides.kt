package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.remember
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
                color = if (clipping) Ink.Clipping else Ink.GuideText,
                topLeft = Offset(index * barWidth, size.height - height),
                size = androidx.compose.ui.geometry.Size(
                    width = barWidth.coerceAtLeast(1f),
                    height = height,
                ),
            )
        }
    }
}

/**
 * Focus peaking overlay.
 *
 * Marks where the image is resolving detail, drawn straight from a small edge
 * map rather than as thousands of individual shapes: at one rectangle per
 * marked pixel a 500-wide map would be tens of thousands of draw calls a
 * second. An ImageBitmap is one.
 *
 * Shown only while manual focus is engaged, because that is the only time it
 * answers a question the user is asking.
 */
@Composable
fun Peaking(
    mask: ByteArray?,
    maskWidth: Int,
    maskHeight: Int,
    modifier: Modifier = Modifier,
    colour: Color = Ink.Amber,
) {
    if (mask == null || maskWidth <= 0 || maskHeight <= 0) return
    if (mask.size < maskWidth * maskHeight) return

    val image = remember(mask, maskWidth, maskHeight) {
        val pixels = IntArray(maskWidth * maskHeight)
        val base = colour.value.toLong().let {
            // Colour components, so only the alpha varies per pixel.
            val argb = colour.toArgb()
            argb and 0x00FFFFFF
        }
        for (i in pixels.indices) {
            val strength = mask[i].toInt() and 0xFF
            // Transparent where nothing is resolving, so the picture shows
            // through everywhere the overlay has nothing to say.
            pixels[i] = (strength shl 24) or base
        }
        android.graphics.Bitmap.createBitmap(
            pixels, maskWidth, maskHeight, android.graphics.Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawImage(
            image = image,
            dstSize = androidx.compose.ui.unit.IntSize(
                size.width.toInt(), size.height.toInt(),
            ),
        )
    }
}

/**
 * Where the sweep has been, and where it has not.
 *
 * A sweep tells the photographer how much is covered and, until now, nothing
 * about *where* -- which is the one thing a guided capture has to answer. The
 * assembler has tracked this on a 32x32 grid all along, with a comment saying
 * it was for drawing the sweep guide; nothing ever drew it.
 *
 * It is a map rather than an overlay on the viewfinder, and that distinction is
 * the whole design. During a sweep the preview shows the *telephoto*, which
 * sees a small fraction of the canvas being built -- so cells drawn across the
 * frame would appear to say "this part of what you are looking at is covered",
 * which is not what they mean. Drawn small, at the canvas's own shape, they say
 * what they actually are: the finished picture, filling in.
 */
@Composable
fun SweepMap(
    grid: Array<BooleanArray>,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    val rows = grid.size
    if (rows == 0) return
    val columns = grid[0].size
    if (columns == 0) return
    // A canvas with no shape to speak of would divide by zero below.
    val shape = if (aspect.isFinite() && aspect > 0.01f) aspect else 1f

    Canvas(modifier = modifier.aspectRatio(shape)) {
        val cellW = size.width / columns
        val cellH = size.height / rows

        for (r in 0 until rows) {
            val row = grid[r]
            for (c in 0 until minOf(columns, row.size)) {
                val topLeft = Offset(c * cellW, r * cellH)
                val cell = Size(cellW, cellH)
                if (row[c]) {
                    drawRect(color = Ink.Bone, topLeft = topLeft, size = cell)
                } else {
                    drawRect(
                        color = Ink.GuideFaint,
                        topLeft = topLeft,
                        size = cell,
                    )
                }
            }
        }

        // An outline, so an empty map still reads as a frame waiting to be
        // filled rather than as nothing at all.
        drawRect(
            color = Ink.Hairline,
            topLeft = Offset.Zero,
            size = size,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}
