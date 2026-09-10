package dev.multiframe.camera.pipeline

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val TAG = "HevcProbe"

/**
 * Whether a HEIF could be written on this device by the long route.
 *
 * `Bitmap.CompressFormat` has no HEIC entry on this platform -- see
 * [HeifDeviceTest], which records the whole enum as
 * `[JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS]` on API 37 -- so the short path
 * to HEIF does not exist here. The long path is a HEVC `MediaCodec` feeding a
 * `MediaMuxer` opened as `MUXER_OUTPUT_HEIF`, and that is a few hundred lines
 * of EGL and codec plumbing: a `MediaCodec` input surface cannot be drawn to
 * with a `Canvas`, so the bitmap has to be uploaded as a texture and rendered
 * through an EGL context created against the codec's surface.
 *
 * Before writing any of that, this establishes whether the two pieces it needs
 * are present at all. It asserts nothing about HEIF being possible -- it
 * records what is here, so the decision to build the long path is made on
 * evidence rather than on the documentation.
 */
@RunWith(AndroidJUnit4::class)
class HevcProbeDeviceTest {

    @Test
    fun theHardwareHevcEncodersAreRecorded() {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoders = codecs.codecInfos
            .filter { it.isEncoder }
            .filter { it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } }

        Log.i(TAG, "HEVC encoders: ${encoders.map { it.name }}")

        for (info in encoders) {
            val caps = runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }.getOrNull() ?: continue
            val video = caps.videoCapabilities ?: continue

            Log.i(
                TAG,
                "%s: %s, up to %dx%d, surface input %b".format(
                    info.name,
                    if (info.isHardwareAccelerated) "hardware" else "software",
                    video.supportedWidths.upper,
                    video.supportedHeights.upper,
                    caps.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    ),
                ),
            )
        }

        // Not an assertion that HEIF is possible -- only that the probe ran and
        // the device answered. A phone with no HEVC encoder is a real answer.
        assertThat(encoders).isNotNull()
    }

    /**
     * Whether the muxer will actually open a HEIF container here.
     *
     * The constant exists in the SDK; whether a given build accepts it is
     * another question, and one worth answering before building a pipeline that
     * ends in it.
     */
    @Test
    fun theMuxerEitherOpensHeifOrSaysItCannot() {
        val file = File.createTempFile("heifprobe", ".heic")
        var opened = false
        try {
            // MediaMuxer is not AutoCloseable on this platform, so it is
            // released by hand.
            val muxer = MediaMuxer(
                file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF,
            )
            opened = true
            muxer.release()
        } catch (e: Exception) {
            Log.i(TAG, "MUXER_OUTPUT_HEIF refused: ${e.message}")
        } finally {
            file.delete()
        }

        Log.i(TAG, "MediaMuxer accepts MUXER_OUTPUT_HEIF: $opened")
        // Recorded, not required. Either answer decides the roadmap, and a
        // refusal is as useful a finding as an acceptance.
        assertThat(file.exists()).isFalse()
    }
}
