package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "DcgProfiler"

/**
 * Runs the read-noise measurement against a live stream, without telling anyone.
 *
 * ### The constraint this is built around
 *
 * The textbook way to measure read noise is a dark frame: cap the lens, shoot a
 * series, measure what comes back. That is a calibration routine, and a
 * calibration routine is a thing the user has to be asked to perform -- a
 * dialog, a dark room, an instruction to cover the camera. Nobody does it, and
 * a feature that depends on it is a feature nobody has.
 *
 * So this measures the same quantity off whatever the camera happens to be
 * pointed at. The trick is in [DcgCalibration.temporalVariance]: the difference
 * of two frames of the same scene cancels the scene and leaves the noise. The
 * lens cap was never measuring darkness for its own sake -- it was removing the
 * scene, and subtraction removes it just as well.
 *
 * The whole run is a second or two of ISO steps on the repeating request. The
 * viewfinder stays live throughout. Nothing is shown, nothing is asked, and if
 * it fails it simply does not record an answer and tries again next launch.
 *
 * ### Why the frames come from the ring rather than from a capture
 *
 * A one-shot capture at each ISO would interrupt the preview and cost a session
 * reconfiguration per step. The repeating request is already delivering frames
 * into the ring at thirty a second; changing its sensitivity and waiting three
 * frames costs nothing that was not already being spent.
 */
object DcgProfiler {

    /** How many rungs of the ISO ladder to sample. */
    private const val STEPS = 16

    /**
     * Frames to discard after changing sensitivity.
     *
     * The request takes effect some frames after it is submitted, and the ring
     * is full of frames shot at the old value. Three is the pipeline depth the
     * Camera2 specification guarantees is enough for a sensitivity change to
     * have landed.
     */
    private const val SETTLE_FRAMES = 3

    /** At 30fps, one frame. Used to pace the settle. */
    private const val FRAME_MS = 34L

    /**
     * The crop measured, per side.
     *
     * Centre only, and small. Lens shading makes the corners noisier than the
     * middle by a factor that has nothing to do with read noise, and a full
     * frame is 12 megapixels to difference at every one of sixteen steps. A
     * 256-square centre crop is enough samples for a variance to be stable to
     * well under a percent, and it is the part of the frame least polluted by
     * anything but the sensor.
     */
    private const val CROP = 256

    /**
     * Measures [stream] across its sensitivity range and records the result.
     *
     * Returns the fit, or null when the run could not be completed -- no raw
     * configuration, no sensitivity range, or frames that never arrived. A null
     * is not recorded, so the next launch will try again.
     *
     * Restores the caller's settings before returning, including on failure.
     */
    suspend fun profile(
        context: Context,
        stream: ZslRawStream,
        caps: CameraCapabilities,
        settings: ManualSettings,
        cameraId: String,
    ): DcgCalibration.Result? {
        val isoMin = caps.isoMin
        val isoMax = caps.isoMax
        if (isoMin == null || isoMax == null || !caps.hasManualSensor) {
            Log.i(TAG, "no manual sensitivity on $cameraId; nothing to profile")
            return null
        }

        val ladder = DcgCalibration.isoLadder(isoMin, isoMax, STEPS)
        if (ladder.size < DcgCalibration.MIN_SAMPLES) {
            Log.i(TAG, "sensitivity range too narrow to profile: $isoMin-$isoMax")
            return null
        }

        val samples = ArrayList<DcgCalibration.Sample>(ladder.size)
        try {
            for (iso in ladder) {
                // Exposure is pinned short and constant across the ladder. Two
                // things depend on it: a short integration keeps the scene from
                // moving between the pair, and a *constant* one means the only
                // thing changing across the ladder is the analog gain, which is
                // the variable being fitted.
                val probe = settings.copy(
                    manualExposure = true,
                    iso = iso,
                    exposureTimeNs = probeExposureNs(caps),
                )
                stream.applySettings(probe, caps)
                delay(FRAME_MS * SETTLE_FRAMES)

                val variance = measurePair(stream) ?: continue
                samples.add(DcgCalibration.Sample(iso, variance))
            }
        } finally {
            // Whatever happened, the camera goes back to what the user had.
            stream.applySettings(settings, caps)
        }

        if (samples.size < DcgCalibration.MIN_SAMPLES) {
            Log.i(TAG, "only ${samples.size} usable samples; not fitting")
            return null
        }

        val result = DcgCalibration.fit(samples)
        DcgCalibrationStore.record(context, cameraId, result)
        Log.i(TAG, "$cameraId: $result")
        return result
    }

    /**
     * The shortest exposure the sensor will honour, within reason.
     *
     * Short because the two frames of a pair must see the same scene: a hand
     * holding a phone moves, and at 1/30s that movement is signal the
     * subtraction will not cancel. Not *the* shortest, because some sensors
     * report a minimum they do not really support.
     */
    private fun probeExposureNs(caps: CameraCapabilities): Long {
        val floor = caps.exposureMinNs ?: return 1_000_000L
        // A millisecond, or the sensor's floor if that is longer.
        return floor.coerceAtLeast(1_000_000L)
    }

    /**
     * Variance from two consecutive frames currently in the ring.
     *
     * Null when the ring cannot give two frames, which happens while the stream
     * is still filling and is not an error.
     */
    private fun measurePair(stream: ZslRawStream): Double? {
        stream.snapshot(2).use { burst ->
            if (burst.count < 2) return null
            val first = burst.frames.buffer(0) ?: return null
            val second = burst.frames.buffer(1) ?: return null

            val width = stream.config.width
            val height = stream.config.height
            val a = centreCrop(first, width, height) ?: return null
            val b = centreCrop(second, width, height) ?: return null

            return DcgCalibration.temporalVariance(a, b)
        }
    }

    /**
     * A [CROP]-square block from the middle of a raw frame.
     *
     * Aligned to an even offset so the crop starts on the same colour filter
     * site every time. Without that, two crops could sample opposite phases of
     * the Bayer pattern and their difference would carry the pattern rather
     * than the noise.
     */
    internal fun centreCrop(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
    ): ShortArray? {
        val side = minOf(CROP, width, height)
        if (side < 16) return null

        val startX = ((width - side) / 2) and 1.inv()
        val startY = ((height - side) / 2) and 1.inv()

        val shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (shorts.capacity() < width * height) return null

        val out = ShortArray(side * side)
        for (row in 0 until side) {
            val src = (startY + row) * width + startX
            shorts.position(src)
            shorts.get(out, row * side, side)
        }
        return out
    }
}
