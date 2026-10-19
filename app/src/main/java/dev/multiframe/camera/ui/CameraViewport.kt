package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntSize

/** Closest framing first, then resolution. RAW dimensions include sensor
 * margins, so an exact aspect-ratio tolerance can reject every photo preview
 * and accidentally select a square stream (seen on the Pixel front camera).
 */
internal fun closestPhotoPreview(sizes: List<IntSize>, raw: IntSize): IntSize? =
    sizes.filter { it.width > 0 && it.height > 0 }.minWithOrNull(
        compareBy<IntSize> { kotlin.math.abs(it.width.toDouble() / it.height - raw.width.toDouble() / raw.height) }
            .thenByDescending { it.width.toLong() * it.height },
    )

internal fun capturePreviewSize(
    characteristics: android.hardware.camera2.CameraCharacteristics,
    raw: dev.multiframe.camera.pipeline.RawStreamConfig,
): android.util.Size {
    val map = (if (raw.fullResolution) runCatching {
        characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
    }.getOrNull() else null) ?: characteristics.get(
        android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val choices = map?.getOutputSizes(android.view.SurfaceHolder::class.java)
        ?.filter { it.width <= 1920 }?.map { IntSize(it.width, it.height) }.orEmpty()
    return closestPhotoPreview(choices, IntSize(raw.width, raw.height))?.let {
        android.util.Size(it.width, it.height)
    } ?: dev.multiframe.camera.pipeline.ZslRawStream.previewSizeFor(characteristics, raw)
}

// The reference bay remains a ceiling. Full-width sensor framing takes priority
// where the literal bay and telemetry cannot both fit beside the complete photo.
internal val RangefinderTurretHeight = TitaniumTokens.TurretHeight
internal val RangefinderConsoleHeight = TitaniumTokens.ConsoleHeight

/** Width owns portrait framing; the console gets the remaining letterbox.
 * This depends only on window/stream/telemetry geometry, never PRO content.
 */
internal fun portraitPhotoHeight(width: Int, aspect: Float): Int =
    kotlin.math.round(width / (aspect.takeIf { it.isFinite() && it > 0f } ?: .75f)).toInt()

internal fun portraitControlHeight(width: Int, height: Int, aspect: Float, telemetryHeight: Int): Int =
    (height - telemetryHeight - portraitPhotoHeight(width, aspect)).coerceAtLeast(0)

/** Outside and not-yet-measured pixels never belong to camera metering. */
internal fun imagePoint(bounds: androidx.compose.ui.geometry.Rect?, point: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset? {
    if (bounds == null || bounds.width <= 0f || bounds.height <= 0f || !bounds.contains(point)) return null
    return androidx.compose.ui.geometry.Offset((point.x - bounds.left) / bounds.width,
        (point.y - bounds.top) / bounds.height)
}

/**
 * Fit the complete photo between independently measured instrument decks.
 * The top plate includes the device cutout and scaled text; drawer content
 * never participates in the preview measurement.
 */
internal fun Modifier.previewRegion(
    layout: CameraLayout,
    frameAspect: Float = 3f / 4f,
    telemetryHeightPx: Int = 0,
): Modifier = when (layout.posture) {
    Posture.SIDEBAR -> this.fillMaxSize().layout { measurable, constraints ->
        val rail = layout.railWidth.roundToPx()
        val availableWidth = (constraints.maxWidth - rail).coerceAtLeast(1)
        val availableHeight = constraints.maxHeight
        val aspect = frameAspect.takeIf { it.isFinite() && it > 0f } ?: (4f / 3f)
        val frameWidth = minOf(availableWidth, (availableHeight * aspect).toInt()).coerceAtLeast(1)
        val frameHeight = minOf(availableHeight, (frameWidth / aspect).toInt()).coerceAtLeast(1)
        val child = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(frameWidth, frameHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.place((availableWidth - child.width) / 2, (availableHeight - child.height) / 2)
        }
    }
    Posture.TABLETOP -> this.fillMaxSize()
        .layout { measurable, constraints ->
            // The pane above the hinge, in the window's own pixels.
            val paneHeight = layout.paneSplitTopPx.coerceIn(0, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = paneHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((constraints.maxWidth - placeable.width) / 2, (paneHeight - placeable.height) / 2)
            }
        }
    // Three independent decks. The photo is measured only inside the middle
    // deck; changing PRO content cannot enter this measurement pass.
    else -> this.fillMaxSize().layout { measurable, constraints ->
        val windowWidth = constraints.maxWidth
        val windowHeight = constraints.maxHeight
        val frameHeight = portraitPhotoHeight(windowWidth, frameAspect)
        val child = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(windowWidth, frameHeight))
        layout(windowWidth, windowHeight) {
            child.place(0, telemetryHeightPx)
        }
    }
}

/** Visible source fraction for an aspect-preserving centre crop. */
internal fun centerCropFraction(sourceAspect: Float, viewportAspect: Float): Pair<Float, Float> =
    if (sourceAspect > viewportAspect) viewportAspect / sourceAspect to 1f
    else 1f to sourceAspect / viewportAspect

/** Map a viewport touch to the source frame that the user actually sees. */
internal fun centerCroppedPoint(x: Float, y: Float, sourceAspect: Float, viewportAspect: Float): Pair<Float, Float> {
    val (fractionX, fractionY) = centerCropFraction(sourceAspect, viewportAspect)
    return (.5f + (x - .5f) * fractionX) to (.5f + (y - .5f) * fractionY)
}

/**
 * Where the controls stand: the strip along the bottom, the rail down the
 * end of a wide window, or the pane under a foldable's hinge.
 */
internal fun Modifier.controlsRegion(layout: CameraLayout, frameAspect: Float = .75f,
    telemetryHeightPx: Int = 0): Modifier = when (layout.posture) {
    Posture.SIDEBAR -> this.fillMaxHeight().width(layout.railWidth)
    Posture.TABLETOP -> this.layout { measurable, constraints ->
        val top = layout.paneSplitBottomPx.coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight - top))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - placeable.width) / 2, constraints.maxHeight - placeable.height)
        }
    }
    else -> this.layout { measurable, constraints ->
        val child = measurable.measure(constraints.copy(minHeight = 0,
            maxHeight = portraitControlHeight(constraints.maxWidth, constraints.maxHeight,
                frameAspect, telemetryHeightPx)))
        layout(child.width, child.height) { child.place(0, 0) }
    }
}
