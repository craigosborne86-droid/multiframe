package dev.multiframe.camera.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Inset and focus target in coordinates relative to the displayed preview. */
internal data class FocusAssistInset(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val focusX: Float,
    val focusY: Float,
) {
    companion object {
        fun fromBounds(preview: Rect?, inset: Rect?, focus: Offset): FocusAssistInset? {
            if (preview == null || inset == null || preview.width <= 0f || preview.height <= 0f) return null
            val clipped = preview.intersect(inset)
            if (clipped.width <= 0f || clipped.height <= 0f) return null
            return FocusAssistInset(
                left = (clipped.left - preview.left) / preview.width,
                top = (clipped.top - preview.top) / preview.height,
                width = clipped.width / preview.width,
                height = clipped.height / preview.height,
                focusX = focus.x.coerceIn(0f, 1f),
                focusY = focus.y.coerceIn(0f, 1f),
            )
        }
    }
}
