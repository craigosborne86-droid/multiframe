package dev.multiframe.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.multiframe.camera.pipeline.MosaicGuideView
import dev.multiframe.camera.pipeline.TouchFocus

/** One target, derived from the real guide's projected active node. */
@Composable
internal fun Revision05SweepMarker(view: MosaicGuideView, orientation: Int, zoom: Float, modifier: Modifier) {
    Canvas(modifier) {
        val node = view.nodes.firstOrNull { it.node.index == view.activeNode } ?: return@Canvas
        val point = TouchFocus.toDisplayPoint(node.frameX ?: return@Canvas, node.frameY ?: return@Canvas, orientation)
        val target = Offset((.5f + (point.first - .5f) * zoom) * size.width,
            (.5f + (point.second - .5f) * zoom) * size.height)
        val centre = Offset(size.width / 2, size.height / 2)
        val bounded = Offset(target.x.coerceIn(24.dp.toPx(), size.width - 24.dp.toPx()),
            target.y.coerceIn(24.dp.toPx(), size.height - 24.dp.toPx()))
        val colour = if (view.steady) TitaniumTokens.PhosphorGreen else TitaniumTokens.OpticalAmber
        drawLine(colour.copy(alpha = .45f), centre, bounded, 1.dp.toPx())
        drawCircle(colour, 18.dp.toPx(), bounded, style = Stroke(1.5.dp.toPx()))
        drawArc(colour, -90f, 360 * view.holdProgress, false,
            topLeft = bounded - Offset(22.dp.toPx(),22.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(44.dp.toPx(),44.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
}
