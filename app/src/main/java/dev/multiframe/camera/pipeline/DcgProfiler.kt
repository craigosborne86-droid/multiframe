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

    /**
     * How many times to wait again before giving a rung up.
     *
     * The pipeline depth the specification guarantees is three frames, but a
     * busy phone can take longer, and the ring has to have flushed *both* of
     * its newest slots before a pair reads as settled. Four attempts is about
     * four hundred milliseconds of patience per rung in the worst case, and in
     * practice the first attempt succeeds on every rung but the first.
     */
    private const val SETTLE_ATTEMPTS = 4

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

        // What the scene is currently worth, which is what the ladder is
        // exposed against. Without a reading there is no reference brightness
        // and the measurement cannot be set up.
        val (aeIso, aeExposureNs) = stream.latestExposure()
            ?: run {
                Log.i(TAG, "no exposure reading yet; not profiling")
                return null
            }
        val reference = aeIso.toLong() * aeExposureNs

        // The ladder starts where auto-exposure already is, not at the sensor's
        // floor.
        //
        // Holding brightness constant means trading time against sensitivity,
        // and below the metered point that trade asks for *longer* exposures --
        // half a second at the bottom of this sensor's range. At half a second
        // the repeating request delivers two frames a second, and the ring runs
        // dry: the first attempt at this reported "ring reports none" for five
        // consecutive rungs. Above the metered point every exposure is shorter
        // than the one the viewfinder is already running at, so the stream
        // stays at frame rate throughout.
        //
        // The cost is that a dark scene has little range above its metered
        // point and may not offer enough rungs. That is reported and retried on
        // a later launch rather than worked around -- a measurement taken in
        // the dark would be the kind that produces a confident wrong answer.
        val floor = maxOf(isoMin, aeIso)
        val ladder = DcgCalibration.isoLadder(floor, isoMax, STEPS)
        if (ladder.size < DcgCalibration.MIN_SAMPLES) {
            Log.i(
                TAG,
                "only ${ladder.size} rungs available above ISO $floor " +
                    "(range $isoMin-$isoMax); too dark to profile here",
            )
            return null
        }

        val samples = ArrayList<DcgCalibration.Sample>(ladder.size)
        try {
            for (iso in ladder) {
                // Exposure time is traded against sensitivity so that every
                // rung of the ladder produces the *same brightness*. See
                // [exposureFor]: holding the product constant is what makes
                // analog gain the only thing that changes.
                val exposureNs = exposureFor(reference, iso, caps) ?: continue
                val probe = settings.copy(
                    manualExposure = true,
                    iso = iso,
                    exposureTimeNs = exposureNs,
                )
                stream.applySettings(probe, caps)

                // Verified rather than assumed, and retried rather than
                // dropped. A request takes effect some frames after it is
                // submitted: the first rung was reading 140 where its
                // neighbours read fractions, because the ring still held
                // auto-exposure frames from before the ladder started. Asking
                // the metadata what the frame was actually shot at costs
                // nothing and cannot be fooled by a slow pipeline -- and
                // waiting again is cheaper than losing the rung.
                var variance: Double? = null
                for (attempt in 0 until SETTLE_ATTEMPTS) {
                    delay(FRAME_MS * SETTLE_FRAMES)
                    variance = measurePair(stream, iso)
                    if (variance != null) break
                }
                if (variance == null) {
                    Log.i(
                        TAG,
                        "ISO $iso (${exposureNs / 1000}us) never settled; " +
                            "ring reports ${reportedIsos(stream)}",
                    )
                    continue
                }
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

        // The curve itself, not just the conclusion. A fit that collapses and a
        // sensor with no gain switch report the same thing, and the only way to
        // tell them apart from a field log is to see what went in.
        Log.i(
            TAG,
            "$cameraId curve: " + samples.joinToString(" ") {
                "%d=%.0f".format(it.iso, it.variance)
            },
        )

        val result = DcgCalibration.fit(samples)
        DcgCalibrationStore.record(context, cameraId, result)
        Log.i(TAG, "$cameraId: $result")
        return result
    }

    /**
     * The exposure time that keeps this rung as bright as the scene already is.
     *
     * ### Why not simply the shortest exposure
     *
     * Because the first version did that, and it measured nothing. A one
     * millisecond exposure indoors is a near-black frame: there is no signal,
     * so there is no shot noise, and the read noise of a modern sensor at base
     * gain is a fraction of one code value. The frame-to-frame difference is
     * then almost always exactly zero, and what the fit received was
     * quantisation rather than a noise curve -- variances of 0, 0, 0, 1, 1, 1
     * across the bottom half of the ladder.
     *
     * Holding the *exposure product* constant instead gives every rung the same
     * brightness as the scene the user is pointing at. Sensitivity and time
     * trade against each other exactly, so the signal level is fixed and the
     * analog gain is the only thing varying -- which is the measurement wanted,
     * and it is well above the quantisation floor at every rung.
     *
     * Null where the required time falls outside what the sensor accepts, which
     * is what caps the usable end of the ladder on a bright scene.
     */
    internal fun exposureFor(
        referenceProduct: Long,
        iso: Int,
        caps: CameraCapabilities,
    ): Long? {
        if (iso <= 0 || referenceProduct <= 0L) return null
        val wanted = referenceProduct / iso
        val floor = caps.exposureMinNs ?: 1_000L
        val ceiling = caps.exposureMaxNs ?: Long.MAX_VALUE
        // Clamped rather than coerced silently: a rung whose exposure had to be
        // changed to fit is a rung at a different brightness from the others,
        // which is the one thing this is arranging not to happen.
        return if (wanted < floor || wanted > ceiling) null else wanted
    }

    /**
     * Whether a frame was shot at the sensitivity that was asked for.
     *
     * Not equality. A sensor implements its own sensitivity steps and rounds a
     * request to the nearest one: asked for ISO 316 this phone delivers 315 and
     * says so. Held to a couple of percent, which is far tighter than the gaps
     * between rungs -- the ladder is geometric over four stops, so neighbouring
     * rungs differ by tens of percent and cannot be confused for one another.
     */
    internal fun isoMatches(reported: Int, requested: Int): Boolean {
        if (requested <= 0) return false
        return kotlin.math.abs(reported - requested).toFloat() / requested <= 0.02f
    }

    /** What the newest two frames say they were shot at, for diagnosis. */
    private fun reportedIsos(stream: ZslRawStream): String =
        stream.snapshot(2).use { burst ->
            (0 until burst.count).joinToString(",") { i ->
                burst.results.getOrNull(i)
                    ?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    ?.toString() ?: "?"
            }.ifEmpty { "none" }
        }

    /**
     * Variance from two consecutive frames currently in the ring, but only if
     * they were both actually shot at [expectedIso].
     *
     * Null when the ring cannot give two frames, or when the frames it gives
     * were shot at some other sensitivity -- which happens for the first rung
     * of every ladder, where the ring is still full of auto-exposure frames
     * from before the profiling started.
     */
    private fun measurePair(stream: ZslRawStream, expectedIso: Int): Double? {
        stream.snapshot(2).use { burst ->
            if (burst.count < 2) return null

            // Both frames, because a pair that straddles the change measures the
            // difference between two sensitivities rather than the noise at one.
            for (i in 0 until 2) {
                val shotAt = burst.results.getOrNull(i)
                    ?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    ?: return null
                if (!isoMatches(shotAt, expectedIso)) return null
            }

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
