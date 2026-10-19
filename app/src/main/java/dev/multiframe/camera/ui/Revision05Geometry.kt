package dev.multiframe.camera.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout

/** Window pixels. Insets exclude transient bar visibility and panel contents. */
internal data class CaptureSafeInsets(val left: Int = 0, val top: Int = 0,
    val right: Int = 0, val bottom: Int = 0)

internal data class Revision05Geometry(val image: Rect, val controls: Rect, val safe: Rect = Rect.Zero)

internal fun revision05Geometry(window: IntSize, aspect: Float, insets: CaptureSafeInsets,
    topControls: Int, railWidth: Int, reverse: Boolean, minimumConsoleHeight: Int = 0): Revision05Geometry {
    val width = window.width.coerceAtLeast(1)
    val height = window.height.coerceAtLeast(1)
    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: .75f
    val landscape = width > height
    val left = insets.left.coerceIn(0, width - 1)
    val right = (width - insets.right).coerceAtLeast(left + 1)
    val top = insets.top.coerceIn(0, height - 1)
    val bottom = (height - insets.bottom).coerceAtLeast(top + 1)
    if (!landscape) {
        val photoTop = (top + topControls).coerceAtMost(bottom - 1)
        val photoSpace = (bottom - photoTop - minimumConsoleHeight).coerceAtLeast(1)
        val photoWidth = minOf(right - left, (photoSpace * ratio).roundToInt()).coerceAtLeast(1)
        val photoHeight = (photoWidth / ratio).roundToInt()
        val x = left + (right - left - photoWidth) / 2
        val image = Rect(x.toFloat(), photoTop.toFloat(), (x + photoWidth).toFloat(), (photoTop + photoHeight).toFloat())
        return Revision05Geometry(image, Rect(left.toFloat(), image.bottom, right.toFloat(), bottom.toFloat()), Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
    }
    val rail = railWidth.coerceIn(1, (right - left - 1).coerceAtLeast(1))
    val imageLeft = if (reverse) left + rail else left
    val imageRight = if (reverse) right else right - rail
    val photoWidth = minOf(imageRight - imageLeft, ((bottom - top) * ratio).roundToInt()).coerceAtLeast(1)
    val photoHeight = (photoWidth / ratio).roundToInt()
    val x = imageLeft + (imageRight - imageLeft - photoWidth) / 2
    val y = top + (bottom - top - photoHeight) / 2
    return Revision05Geometry(Rect(x.toFloat(), y.toFloat(), (x + photoWidth).toFloat(), (y + photoHeight).toFloat()),
        Rect((if (reverse) left else right - rail).toFloat(), top.toFloat(),
            (if (reverse) left + rail else right).toFloat(), bottom.toFloat()), Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
}

/** Root coordinates and exact allocation; panels never enter this measurement. */
internal fun androidx.compose.ui.Modifier.revision05Region(rect: Rect): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.layout { measurable, constraints ->
        val child = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(
            rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
        layout(constraints.maxWidth, constraints.maxHeight) { child.place(rect.left.roundToInt(), rect.top.roundToInt()) }
    })
