package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.Build

/** Crop only after full-frame development, so exposure and shading retain their coordinates. */
data class RenderCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    fun pixels(width: Int, height: Int): SensorRect {
        val l = (left * width).toInt().coerceIn(0, width - 1)
        val t = (top * height).toInt().coerceIn(0, height - 1)
        return SensorRect(l, t, (right * width).toInt().coerceIn(l + 1, width),
            (bottom * height).toInt().coerceIn(t + 1, height))
    }

    companion object {
        fun from(ch: CameraCharacteristics, result: CaptureResult?): RenderCrop {
            if (result == null) return RenderCrop()
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return RenderCrop()
            val zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f
            val requested = if (zoom > 1.001f) TouchFocus.cropForZoom(SensorRect.from(active), zoom, zoom)
                else result.get(CaptureResult.SCALER_CROP_REGION)?.let { SensorRect.from(it) } ?: SensorRect.from(active)
            val raw = if (Build.VERSION.SDK_INT >= 34) result.get(CaptureResult.SCALER_RAW_CROP_REGION) else null
            return between(requested, raw?.let { SensorRect.from(it) } ?: SensorRect.from(active))
        }

        internal fun between(requested: SensorRect, raw: SensorRect): RenderCrop = RenderCrop(
            ((requested.left - raw.left).toFloat() / raw.width).coerceIn(0f, 1f),
            ((requested.top - raw.top).toFloat() / raw.height).coerceIn(0f, 1f),
            ((requested.right - raw.left).toFloat() / raw.width).coerceIn(0f, 1f),
            ((requested.bottom - raw.top).toFloat() / raw.height).coerceIn(0f, 1f),
        )
    }
}
