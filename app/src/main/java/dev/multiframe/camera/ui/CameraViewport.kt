package dev.multiframe.camera.ui

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

/** Outside and not-yet-measured pixels never belong to camera metering. */
internal fun imagePoint(bounds: androidx.compose.ui.geometry.Rect?, point: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset? {
    if (bounds == null || bounds.width <= 0f || bounds.height <= 0f || !bounds.contains(point)) return null
    return androidx.compose.ui.geometry.Offset((point.x - bounds.left) / bounds.width,
        (point.y - bounds.top) / bounds.height)
}

/** Visible source fraction for an aspect-preserving centre crop. */
internal fun centerCropFraction(sourceAspect: Float, viewportAspect: Float): Pair<Float, Float> =
    if (sourceAspect > viewportAspect) viewportAspect / sourceAspect to 1f
    else 1f to sourceAspect / viewportAspect
