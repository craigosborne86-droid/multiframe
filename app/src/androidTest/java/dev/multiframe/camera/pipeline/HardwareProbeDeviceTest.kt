package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TAG = "HardwareProbe"

/**
 * What this phone's cameras say about themselves, written down.
 *
 * Every hardware assumption in the app is gated on a runtime check (CLAUDE.md,
 * "Verify, never assume"), and this is where the answers to those checks are
 * read for a new device and kept: one report per run, in the log and in a
 * file under the app's external files, listing for each rear lens the sensor
 * geometry, the optical calibration, the sensitivity and exposure ranges, the
 * raw stream sizes -- and, for the ultra-high-resolution sensors, the
 * un-binned raw size behind `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`.
 *
 * The assertions are the ones every device has to pass: at least one rear
 * lens, described sensibly. The rest is a record, because the numbers differ
 * between the Pixel 11 Pro and the Pixel 9 Pro XL and the record is what the
 * code is then written against.
 */
@RunWith(AndroidJUnit4::class)
class HardwareProbeDeviceTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Test
    fun everyRearLensIsProbedAndTheReportIsKept() {
        val lenses = LensCatalog.enumerate(manager)
        val rear = LensCatalog.rear(lenses)
        assertThat(rear).isNotEmpty()

        val report = StringBuilder()
        report.appendLine("Multiframe hardware probe: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}, build ${Build.ID}")
        report.appendLine("camera ids: ${manager.cameraIdList.joinToString()}")

        for (lens in rear) {
            val ch = manager.getCameraCharacteristics(lens.cameraId)
            val profile = LensProfiles.from(ch, lens)
            report.appendLine()
            report.appendLine("== ${lens.label} (camera ${lens.cameraId}${lens.logicalId?.let { ", via logical $it" } ?: ""}) ==")
            report.appendLine("  focal ${profile.focalLengthMm} mm, f/${profile.apertureF}, 35mm-eq ${profile.equivalent35mm}, zoom ${lens.zoomFactor}x")
            report.appendLine("  sensor %.2f x %.2f mm, active array %d x %d, pixel pitch %.2f um".format(
                profile.sensorWidthMm, profile.sensorHeightMm, profile.activeArrayWidth, profile.activeArrayHeight, profile.pixelPitchMicrons))
            val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            report.appendLine("  pixel array ${pixelArray?.width} x ${pixelArray?.height}, orientation ${ch.get(CameraCharacteristics.SENSOR_ORIENTATION)}, CFA ${ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)}")
            report.appendLine("  white level ${ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)}, black pattern ${ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> "%d/%d/%d/%d".format(p.getOffsetForIndex(0,0), p.getOffsetForIndex(1,0), p.getOffsetForIndex(0,1), p.getOffsetForIndex(1,1)) }}")
            report.appendLine("  raw ${profile.supportsRaw}, manual sensor ${profile.supportsManualSensor}, manual focus ${profile.supportsManualFocus}, OIS ${profile.hasOis}")
            report.appendLine("  ISO ${profile.isoMin}..${profile.isoMax}, exposure ${profile.exposureMinNs}..${profile.exposureMaxNs} ns, max analog ISO ${ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)}")
            report.appendLine("  distortion ${profile.distortion?.joinToString { "%.5f".format(it) } ?: "none published"}")
            report.appendLine("  intrinsics ${profile.intrinsics?.joinToString { "%.2f".format(it) } ?: "none published"}")
            report.appendLine("  DCG threshold on record: ${DcgCalibrationStore.thresholdFor(context, lens.cameraId) ?: "not profiled"}")

            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val ultra = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
            report.appendLine("  capabilities: ${caps.joinToString()} (ultra-high-resolution ${ultra})")

            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
            report.appendLine("  RAW_SENSOR sizes (default pixel mode): ${rawSizes.joinToString { size -> "${size.width}x${size.height} @${minFrameMs(map!!, size)}ms" }}")
            if (ultra) {
                val maxMap = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                val maxRaw = maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
                val maxActive = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                val maxPixel = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                report.appendLine("  maximum-resolution active array ${maxActive?.width()} x ${maxActive?.height()}, pixel array ${maxPixel?.width} x ${maxPixel?.height}")
                report.appendLine("  RAW_SENSOR sizes (maximum-resolution mode): ${maxRaw.joinToString { size -> "${size.width}x${size.height} @${minFrameMs(maxMap!!, size)}ms" }}")
                assertThat(profile.supportsNativeHighRes).isEqualTo(maxRaw.isNotEmpty() && profile.supportsRaw)
                assertThat(profile.maxResolutionDimensions).isEqualTo(
                    maxRaw.maxByOrNull { it.width.toLong() * it.height }
                        ?.let { FrameSize(it.width, it.height) }
                )
                val maxYuv = maxMap?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width.toLong() * it.height }
                report.appendLine("  largest YUV in maximum-resolution mode: ${maxYuv?.width}x${maxYuv?.height}")
            }
            if (!ultra) {
                assertThat(profile.supportsNativeHighRes).isFalse()
                assertThat(profile.maxResolutionDimensions).isNull()
            }
            val physical = ch.physicalCameraIds
            if (physical.isNotEmpty()) report.appendLine("  physical members: ${physical.joinToString()}")
        }

        val text = report.toString()
        for (line in text.lines()) Log.i(TAG, line)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, "hardware-probe-${Build.DEVICE}.txt")
        out.writeText(text)
        Log.i(TAG, "report written to ${out.absolutePath}")
        assertThat(out.length()).isGreaterThan(0L)
    }

    private fun minFrameMs(map: android.hardware.camera2.params.StreamConfigurationMap, size: android.util.Size): Long =
        try { map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size) / 1_000_000L } catch (e: Exception) { -1L }
}
