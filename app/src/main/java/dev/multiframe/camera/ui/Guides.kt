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
 * The outline, the reticle and the dots: the picture being composed, where to
 * point, and what has been captured.
 *
 * Drawn over the viewfinder, which during a mosaic shows the telephoto. The
 * outline is the canvas -- the whole photograph -- projected into the current
 * frame by the frame's registration, so the user sees how much of the scene
 * the mosaic will cover and where the frame they are looking at sits in it;
 * before the anchor it is drawn as the anchor would place it, centred. Each
 * dot is a node of the plan, projected the same way, so it sits on the part
 * of the scene the tile will be and moves with the scene as the phone pans,
 * which is what makes it a target rather than a diagram. A node beyond the
 * edge of the frame is drawn at the edge, pointing the way. The reticle is
 * fixed at the centre.
 *
 * ### The locking ring
 *
 * The stability gate wants a hold: still, inside the radius, for the settle
 * time. The ring around the locking dot is that hold made visible -- an arc
 * that fills as the time runs and empties the moment the gate reads a drift
 * or a turn, so the user sees what steady means before the burst is taken
 * rather than after. Fires green when it closes.
 *
 * ### Colour, and the rule it bends
 *
 * The palette rule is that nothing coloured sits on the photograph, with two
 * documented exceptions for transient states of the photograph itself. This
 * is the third, and `Ink.Confirm`'s own comment names it: mosaic tile
 * guidance. A dot reads muted while it is only a place, amber as the reticle
 * closes on it -- a live reading of distance, which is what amber is for --
 * solid green once its tile is on the canvas, and red for a beat when a
 * capture there failed. Nothing here persists past the mosaic.
 */
@Composable
fun MosaicGuidance(
    view: dev.multiframe.camera.pipeline.MosaicGuideView,
    sensorOrientation: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val dot = 9.dp.toPx()
        val ring = 14.dp.toPx()
        val lockRing = 22.dp.toPx()
        val stroke = 2.dp.toPx()
        val hair = 1.dp.toPx()
        val margin = 18.dp.toPx()

        fun display(fx: Float, fy: Float): Offset {
            val (dx, dy) = dev.multiframe.camera.pipeline.TouchFocus.toDisplayPoint(fx, fy, sensorOrientation)
            return Offset(dx * w, dy * h)
        }

        // The outline of the photograph. A quadrilateral, since the frame's
        // registration is a homography; drawn in the guide's faint ink, and
        // the canvas clips whatever lies beyond the view -- which for a 2x2
        // is most of it, and that is the point: the picture is bigger than
        // the frame.
        view.canvasCorners?.let { corners ->
            val pts = corners.map { (fx, fy) -> display(fx, fy) }
            for (i in pts.indices) {
                drawLine(Ink.GuideBright, pts[i], pts[(i + 1) % pts.size], hair * 1.5f)
            }
            // The grid between the nodes, fainter: which tile is which.
            if (view.nodes.size > 1) {
                val columns = view.nodes.maxOf { it.node.column } + 1
                val rows = view.nodes.maxOf { it.node.row } + 1
                fun at(c: Int, r: Int): Offset? {
                    val n = view.nodes.firstOrNull { it.node.column == c && it.node.row == r } ?: return null
                    val fx = n.frameX ?: return null
                    val fy = n.frameY ?: return null
                    return display(fx, fy)
                }
                for (r in 0 until rows) for (c in 0 until columns) {
                    val here = at(c, r) ?: continue
                    at(c + 1, r)?.let { drawLine(Ink.GuideMid, here, it, hair) }
                    at(c, r + 1)?.let { drawLine(Ink.GuideMid, here, it, hair) }
                }
            }
        }

        // The reticle: a fine cross with a gap at the centre, so the thing
        // being aimed at is not hidden by the aim.
        val cx = w / 2f
        val cy = h / 2f
        val arm = 22.dp.toPx()
        val gap = 6.dp.toPx()
        drawLine(Ink.GuideBright, Offset(cx - arm, cy), Offset(cx - gap, cy), stroke)
        drawLine(Ink.GuideBright, Offset(cx + gap, cy), Offset(cx + arm, cy), stroke)
        drawLine(Ink.GuideBright, Offset(cx, cy - arm), Offset(cx, cy - gap), stroke)
        drawLine(Ink.GuideBright, Offset(cx, cy + gap), Offset(cx, cy + arm), stroke)
        drawCircle(Ink.GuideBright, radius = ring, center = Offset(cx, cy), style = Stroke(stroke))

        for (node in view.nodes) {
            val fx = node.frameX
            val fy = node.frameY
            if (fx == null || fy == null) continue
            val raw = display(fx, fy)
            // Clamped to the frame, with a margin so a dot at the edge is whole.
            val x = raw.x.coerceIn(margin, w - margin)
            val y = raw.y.coerceIn(margin, h - margin)
            val offFrame = raw.x != x || raw.y != y
            val centre = Offset(x, y)

            when (node.state) {
                dev.multiframe.camera.pipeline.MosaicGuide.NodeState.DONE -> {
                    drawCircle(Ink.Confirm, radius = dot, center = centre)
                }
                dev.multiframe.camera.pipeline.MosaicGuide.NodeState.LOCKING -> {
                    // The hold: an arc that fills, in amber while the gate is
                    // being satisfied and empty the moment it is not.
                    drawCircle(Ink.Amber, radius = dot * 0.5f, center = centre)
                    drawCircle(Ink.AmberDim, radius = lockRing, center = centre, style = Stroke(stroke))
                    if (view.holdProgress > 0f) {
                        drawArc(
                            color = Ink.Amber,
                            startAngle = -90f,
                            sweepAngle = 360f * view.holdProgress,
                            useCenter = false,
                            topLeft = Offset(centre.x - lockRing, centre.y - lockRing),
                            size = Size(lockRing * 2, lockRing * 2),
                            style = Stroke(stroke * 1.5f),
                        )
                    }
                }
                dev.multiframe.camera.pipeline.MosaicGuide.NodeState.CAPTURING -> {
                    drawCircle(Ink.Confirm, radius = lockRing, center = centre, style = Stroke(stroke * 1.5f))
                    drawCircle(Ink.Confirm, radius = dot * 0.5f, center = centre)
                }
                dev.multiframe.camera.pipeline.MosaicGuide.NodeState.NEAR -> {
                    drawCircle(Ink.Amber, radius = ring, center = centre, style = Stroke(stroke))
                    drawCircle(Ink.Amber, radius = dot * 0.5f, center = centre)
                }
                dev.multiframe.camera.pipeline.MosaicGuide.NodeState.REJECTED -> {
                    drawCircle(Ink.Danger, radius = ring, center = centre, style = Stroke(stroke))
                }
                dev.multiframe.camera.pipeline.MosaicGuide.NodeState.PENDING -> {
                    drawCircle(
                        if (offFrame) Ink.GuideMid else Ink.GuideBright,
                        radius = dot * 0.6f, center = centre,
                    )
                    drawCircle(Ink.GuideMid, radius = ring, center = centre, style = Stroke(stroke * 0.5f))
                }
            }
        }
    }
}

/**
 * The grid, chosen on the HUD: 2x2, 3x3, and a drawn region where a wider
 * lens is there to draw it on. A pill, since it is a setting; selection by
 * weight, as every other setting here.
 */
@Composable
fun MosaicPresetPill(
    preset: dev.multiframe.camera.pipeline.MosaicPreset,
    customOffered: Boolean,
    onPreset: (dev.multiframe.camera.pipeline.MosaicPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = buildList {
        add(dev.multiframe.camera.pipeline.MosaicPreset.DETAIL_BOOST)
        add(dev.multiframe.camera.pipeline.MosaicPreset.LARGE_FORMAT)
        if (customOffered) add(dev.multiframe.camera.pipeline.MosaicPreset.CUSTOM)
    }
    Row(
        modifier = modifier
            .background(Ink.Pane, RoundedCornerShape(24.dp))
            .border(1.dp, Ink.Hairline, RoundedCornerShape(24.dp))
            .padding(3.dp),
    ) {
        for (option in options) {
            val selected = option == preset
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
                    .background(if (selected) Ink.Bone else Color.Transparent, RoundedCornerShape(21.dp))
                    .clickable { onPreset(option) }
                    .semantics { contentDescription = "mosaic ${option.label}" }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = Type.Control,
                    color = if (selected) Ink.Ground else Ink.Bone,
                )
            }
        }
    }
}
