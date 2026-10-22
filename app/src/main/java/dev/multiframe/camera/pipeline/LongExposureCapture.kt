package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import kotlin.math.max
import kotlin.math.pow

private const val TAG = "Multiframe"

/**
 * A long exposure made from the stream: every frame the ring takes for
 * [durationMillis] after the press, averaged into one raw picture.
 *
 * ### Why an average and not one long frame
 *
 * A single multi-second frame clips every highlight in daylight and has no
 * neutral-density filter to stop it. Averaging N short frames gathers the
 * same time span -- water goes to silk, clouds smear, traffic becomes light
 * -- while each frame keeps the highlight guard's exposure. The sum is held
 * in floats, so nothing clips that a single frame did not.
 *
 * ### Why motion rejection is off
 *
 * The ordinary merge exists to *remove* what moved: the finite-support weight
 * drops a sample that disagrees with the reference by more than twice the
 * noise. Here what moved is the picture. The accumulator is created with a
 * tolerance so wide that every sample keeps full weight (REFERENCE §3.10),
 * which turns the merge into the plain average a long exposure is.
 *
 * ### Alignment
 *
 * On a tripod (the gyroscope's [MotionClass.TRIPOD]) frames are summed where
 * they land: alignment would only chase the moving water. Otherwise each
 * frame is aligned onto the first exactly as a burst is, so a braced hand
 * still gets a sharp shore under the silk; frames are sampled as fast as that
 * alignment allows, and the log records the rate. The CFA offsets stay even.
 *
 * ### Reference
 *
 * The first frame after the press, not the newest: a long exposure is the
 * span *after* the shutter, and the first frame is the one the photographer
 * framed. This is the one exception to "the reference is the newest", and it
 * only sets geometry -- with rejection off no frame's content outranks another.
 */
object LongExposureCapture {

    /** Durations offered, in seconds. Zero is off. */
    val DURATIONS_SECONDS: List<Int> = listOf(0, 2, 4, 8)

    fun next(current: Int): Int {
        val i = DURATIONS_SECONDS.indexOf(current)
        return DURATIONS_SECONDS[if (i < 0) 0 else (i + 1) % DURATIONS_SECONDS.size]
    }

    /** Wide enough that the finite-support weight is 1 for every sample. */
    internal const val NO_REJECTION_TOLERANCE = 1.0e6f

    private const val TILE_TARGET = 32
    private const val FRAME_TIMEOUT_MS = 1_000L

    suspend fun capture(
        context: Context,
        stream: ZslRawStream,
        durationMillis: Long,
        steady: Boolean,
        rotationDegrees: Int,
        lensProfile: LensProfile? = null,
        outputFormat: OutputFormat = OutputFormat.JPEG,
        develop: DevelopParams = DevelopParams(),
        writeDng: Boolean = true,
        onProgress: (elapsedMillis: Long, frames: Int) -> Unit = { _, _ -> },
    ): RawBurstResult {
        val profile = SensorProfile.from(stream.characteristics)
        val pullStops = stream.pullStops
        @Suppress("NAME_SHADOWING")
        val develop = develop.copy(meteredGain = 2.0.pow(-pullStops.toDouble()).toFloat())
        val statsBefore = stream.stats().toString()

        val merger = NativeMerge.create(
            stream.config.width, stream.config.height, profile,
            BayerMergeParams(noiseTolerance = NO_REJECTION_TOLERANCE),
        ) ?: return RawBurstResult(
            0, 0, null, 0, 0, 0, 0, null, null,
            "native accumulator allocation failed", 0, 0, statsBefore,
        )

        var captured = 0
        var offered = 0
        val tStart = System.currentTimeMillis()
        try {
            val tilesX = max(1, merger.proxyWidth / TILE_TARGET)
            val tilesY = max(1, merger.proxyHeight / TILE_TARGET)
            val zero = AlignmentField(tilesX, tilesY, IntArray(tilesX * tilesY), IntArray(tilesX * tilesY))
            var lastNs = stream.newestFrameTimestampNs()
            var refProxy: Plane? = null
            var referenceLight: Double? = null
            var referenceResult: TotalCaptureResult? = null
            var firstNs = 0L
            var newestNs = 0L

            while (System.currentTimeMillis() - tStart < durationMillis) {
                if (!stream.awaitFrameAfter(lastNs, FRAME_TIMEOUT_MS)) break
                val burst = stream.snapshot(1)
                try {
                    if (burst.count == 0) continue
                    val ts = burst.frames.timestampsNs[0]
                    if (ts <= lastNs) continue
                    // Frames the alignment fell behind on are counted, so the
                    // log says how much of the span was sampled.
                    offered += stream.readyFramesSince(lastNs).coerceAtLeast(1)
                    lastNs = ts
                    if (!burst.awaitResults()) continue
                    val result = burst.results[0] ?: continue
                    if (result.get(CaptureResult.SENSOR_TIMESTAMP) != ts) continue
                    val buffer = burst.frames.buffer(0) ?: continue
                    val stride = burst.frames.rowStride
                    if (referenceLight == null) {
                        merger.setReference(buffer, stride)
                        if (!steady) refProxy = merger.lumaProxy(buffer, stride)
                        referenceLight = FrameLight.of(result)
                        referenceResult = result
                        firstNs = ts
                    } else {
                        val gainScale = FrameLight.gainScale(referenceLight, FrameLight.of(result))
                        val field = refProxy?.let { ref ->
                            val proxy = merger.lumaProxy(buffer, stride, gainScale)
                            Aligner.alignNative(ref, proxy, tilesX, tilesY)
                                ?: Aligner.align(Aligner.buildPyramid(ref), Aligner.buildPyramid(proxy), tilesX, tilesY)
                        } ?: zero
                        merger.addFrame(buffer, stride, field, gainScale)
                    }
                    newestNs = ts
                    captured++
                    onProgress(System.currentTimeMillis() - tStart, captured)
                } finally {
                    burst.close()
                }
            }

            val spanMillis = (newestNs - firstNs) / 1_000_000
            Log.i(TAG, "long exposure: %d of ~%d frames over %d ms (%s)".format(
                captured, offered, spanMillis, if (steady) "tripod, unaligned" else "aligned"))
            if (captured < 2) {
                return RawBurstResult(
                    0, captured, null, 0, 0, 0, 0, null, null,
                    "long exposure took $captured frame(s): the stream stopped", 0, spanMillis, statsBefore,
                )
            }
            val captureMillis = System.currentTimeMillis() - tStart
            val tMerge = System.currentTimeMillis()
            val (mergedBuffer, stats) = merger.finish()
            val mergeMillis = System.currentTimeMillis() - tMerge
            return RawBurstCapture.finishOutputs(
                context = context,
                merger = merger,
                mergedBuffer = mergedBuffer,
                width = merger.width,
                height = merger.height,
                profile = profile,
                characteristics = stream.characteristics,
                captureResult = referenceResult,
                rotationDegrees = rotationDegrees,
                frameCount = captured,
                captured = captured,
                stats = stats,
                captureMillis = captureMillis,
                mergeMillis = mergeMillis,
                tag = "long${durationMillis / 1000}s",
                lens = stream.lens,
                lensProfile = lensProfile,
                outputFormat = outputFormat,
                burstSpanMillis = spanMillis,
                streamStats = stream.stats().toString(),
                develop = develop,
                baselineExposureStops = -pullStops,
                writeDng = writeDng,
            )
        } catch (e: Exception) {
            Log.e(TAG, "long exposure failed", e)
            return RawBurstResult(
                0, captured, null, 0, 0, 0, 0, null, null,
                "long exposure failed: ${e.message}", 0, 0, statsBefore,
            )
        } finally {
            merger.close()
        }
    }
}
