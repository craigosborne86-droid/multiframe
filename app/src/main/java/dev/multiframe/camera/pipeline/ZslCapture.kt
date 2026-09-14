package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.util.Log
import kotlin.math.max

private const val TAG = "Multiframe"

/**
 * The zero-shutter-lag shutter.
 *
 * Everything expensive has already happened by the time this is called: the
 * frames are in the ring, captured before the button was pressed. All that
 * remains is to lock them, merge, and write.
 *
 * The contrast with [RawBurstCapture] is the point of the whole phase. There,
 * a shutter press *starts* an 8-frame sequential capture spanning several
 * seconds. Here the same 8 frames already exist, 33 ms apart, and the shutter
 * costs a mutex.
 */
object ZslCapture {

    private const val TILE_TARGET = 32

    suspend fun captureAndMerge(
        context: Context,
        stream: ZslRawStream,
        frameCount: Int,
        rotationDegrees: Int,
        /** Optical calibration for the streaming lens, where the device has any. */
        lensProfile: LensProfile? = null,
        outputFormat: OutputFormat = OutputFormat.JPEG,
        /**
         * Whether to attempt the 2x canvas. Attempted is the word: the burst
         * has to have earned it, see [SubPixelCoverage], and the ordinary
         * merge runs regardless -- for the DNG, and as what is written when
         * the canvas declines.
         */
        superRes: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): RawBurstResult {
        val profile = SensorProfile.from(stream.characteristics)
        val statsBefore = stream.stats()

        // The handover: shutter press to frames in hand. Measured around the
        // lock alone, because that is the only part a user waits for.
        val handoverStart = System.nanoTime()
        val burst = stream.snapshot(frameCount)
        val handoverMicros = (System.nanoTime() - handoverStart) / 1000

        if (burst.count == 0) {
            burst.close()
            return RawBurstResult(
                frameCount, 0, null, 0, 0, 0, 0, null, null,
                "ring empty: no frames buffered yet",
                handoverMicros, 0, statsBefore.toString(),
            )
        }

        Log.i(
            TAG,
            "ZSL handover: ${burst.count} frames in ${handoverMicros}us, " +
                "span %.0fms".format(burst.frames.spanNs / 1e6),
        )

        var merger: NativeMerge? = null
        // The 2x canvas, when one is built. Closed in the finally below as
        // well as on its own path, because close is idempotent and a canvas
        // left open is 300 MB.
        var canvas: NativeSubPixel? = null
        var mergeMillis = 0L
        var captured = 0

        try {
            val t0 = System.currentTimeMillis()
            val m = NativeMerge.create(stream.config.width, stream.config.height, profile)
                ?: run {
                    burst.close()
                    return RawBurstResult(
                        frameCount, 0, null, 0, 0, 0, 0, null, null,
                        "native accumulator allocation failed",
                        handoverMicros, 0, statsBefore.toString(),
                    )
                }
            merger = m

            val tilesX = max(1, m.proxyWidth / TILE_TARGET)
            val tilesY = max(1, m.proxyHeight / TILE_TARGET)
            val stride = burst.frames.rowStride

            // Pick the lowest-ISO frame as reference. With DCG the ring
            // alternates between two sensitivities, and the low-ISO frame
            // preserves highlights -- exactly the content the robustness
            // weight would otherwise have to reject from a high-ISO
            // reference. When all frames share the same ISO (DCG off) the
            // newest frame wins, which is the one closest to the shutter.
            var refIndex = 0
            var refIso = Int.MAX_VALUE
            for (i in 0 until burst.count) {
                val iso = burst.results[i]
                    ?.get(CaptureResult.SENSOR_SENSITIVITY) ?: continue
                if (iso < refIso) {
                    refIso = iso
                    refIndex = i
                }
            }
            val referenceIso = burst.results[refIndex]
                ?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0

            val refBuffer = burst.frames.buffer(refIndex)
                ?: error("reference slot unreadable")
            m.setReference(refBuffer, stride)
            val refProxy = m.lumaProxy(refBuffer, stride)
            // Only built when the native search is unavailable: it needs the
            // pyramid, and native builds its own.
            val refPyramid by lazy { Aligner.buildPyramid(refProxy) }
            captured = 1

            // Split out because the single "merge" figure bundles native
            // accumulation together with alignment, and alignment runs on the
            // JVM. Optimising the total without knowing the ratio would be
            // guessing at which half to spend the effort on.
            var proxyNanos = 0L
            var pyramidNanos = 0L
            var alignNanos = 0L
            var accumulateNanos = 0L

            // Kept for the 2x path, which decides from them whether to run
            // and then places every frame by them. Aligned once, used twice.
            val aligned = ArrayList<AlignedFrame>(burst.count)

            for (i in 0 until burst.count) {
                if (i == refIndex) continue
                onProgress("merging ${captured + 1}/${burst.count}")
                val buffer = burst.frames.buffer(i) ?: continue

                val frameIso = burst.results[i]
                    ?.get(CaptureResult.SENSOR_SENSITIVITY) ?: referenceIso
                val gainScale = if (referenceIso > 0 && frameIso > 0)
                    frameIso.toFloat() / referenceIso.toFloat() else 1f

                var mark = System.nanoTime()
                val proxy = m.lumaProxy(buffer, stride, gainScale)
                proxyNanos += System.nanoTime() - mark

                mark = System.nanoTime()
                val field = Aligner.alignNative(refProxy, proxy, tilesX, tilesY)
                    ?: run {
                        val pyramid = Aligner.buildPyramid(proxy)
                        pyramidNanos += System.nanoTime() - mark
                        mark = System.nanoTime()
                        Aligner.align(refPyramid, pyramid, tilesX, tilesY)
                    }
                alignNanos += System.nanoTime() - mark

                mark = System.nanoTime()
                m.addFrame(buffer, stride, field, gainScale)
                accumulateNanos += System.nanoTime() - mark

                aligned.add(AlignedFrame(buffer, field, gainScale))
                captured++
            }
            mergeMillis = System.currentTimeMillis() - t0

            // The 2x canvas, if asked for and if the burst earned it. Before
            // the burst is closed, because the accumulator reads the frames in
            // place and sweeps them once per strip.
            var outcome: SuperResOutcome? = null
            if (superRes) {
                val decision = accumulateCanvas(
                    stream, profile, refBuffer, stride, aligned, onProgress,
                )
                canvas = decision.first
                outcome = decision.second
                Log.i(TAG, "2x: ${outcome.reason ?: "canvas accepted"}")
            }

            Log.i(
                TAG,
                ("merge breakdown over ${captured - 1} frames: proxy %dms, " +
                    "pyramid %dms, align %dms, accumulate %dms").format(
                    proxyNanos / 1_000_000, pyramidNanos / 1_000_000,
                    alignNanos / 1_000_000, accumulateNanos / 1_000_000,
                ),
            )

            val spanMillis = burst.frames.spanNs / 1_000_000
            // Slots go back to the pool the moment the pixels have been read,
            // so the ring returns to full depth while the outputs are written.
            burst.close()

            val t1 = System.currentTimeMillis()
            val (mergedBuffer, stats) = m.finish()
            mergeMillis += System.currentTimeMillis() - t1

            val statsAfter = stream.stats()

            val built = canvas
            if (built != null && outcome != null) {
                val tOut = System.currentTimeMillis()
                val written = try {
                    SuperResCapture.finishOutputs(
                        context = context,
                        canvas = built,
                        mergedBuffer = mergedBuffer,
                        mergedWidth = m.width,
                        mergedHeight = m.height,
                        profile = profile,
                        characteristics = stream.characteristics,
                        captureResult = burst.results[refIndex] ?: burst.referenceResult,
                        rotationDegrees = rotationDegrees,
                        captured = captured,
                        lens = stream.lens,
                    )
                } finally {
                    built.close()
                }
                if (written != null) {
                    val developMillis = System.currentTimeMillis() - tOut
                    Log.i(
                        TAG,
                        "2x outputs: %dx%d JPEG %dms, DNG %dms".format(
                            written.width, written.height, written.jpegMillis, written.dngMillis,
                        ),
                    )
                    return RawBurstResult(
                        frameCount, captured, stats, 0, mergeMillis,
                        developMillis, written.dngMillis,
                        written.dngName, written.jpegName,
                        "2x accumulation -> JPEG${if (written.dngName != null) " + DNG" else ""}, $captured frames",
                        handoverMicros, spanMillis, statsAfter.toString(),
                        dcgActive = stream.dcg,
                        superRes = outcome.copy(produced = true, developMillis = developMillis),
                    )
                }
                // The develop declined after the canvas was built. The merged
                // buffer is still here, so the ordinary outputs are written
                // and the result says why they are ordinary.
                outcome = outcome.copy(produced = false, reason = "2x develop declined")
            }

            return RawBurstCapture.finishOutputs(
                context = context,
                merger = m,
                mergedBuffer = mergedBuffer,
                width = m.width,
                height = m.height,
                profile = profile,
                characteristics = stream.characteristics,
                captureResult = burst.results[refIndex] ?: burst.referenceResult,
                rotationDegrees = rotationDegrees,
                frameCount = frameCount,
                captured = captured,
                stats = stats,
                captureMillis = 0,
                mergeMillis = mergeMillis,
                tag = "zsl",
                lens = stream.lens,
                lensProfile = lensProfile,
                outputFormat = outputFormat,
                handoverMicros = handoverMicros,
                burstSpanMillis = spanMillis,
                streamStats = statsAfter.toString(),
                dcgActive = stream.dcg,
            ).copy(superRes = outcome)
        } catch (e: Exception) {
            Log.e(TAG, "ZSL merge failed", e)
            burst.close()
            return RawBurstResult(
                frameCount, captured, null, 0, mergeMillis, 0, 0, null, null,
                "ZSL merge failed: ${e.message}",
                handoverMicros, 0, statsBefore.toString(),
            )
        } finally {
            canvas?.close()
            merger?.close()
        }
    }

    /** One alternate frame, aligned onto the reference, ready to be placed. */
    private class AlignedFrame(
        val buffer: java.nio.ByteBuffer,
        val field: AlignmentField,
        val gainScale: Float,
    )

    /**
     * Builds the 2x canvas from the aligned burst, or explains why not.
     *
     * Three gates, in order of cost. The alignment predicts the coverage
     * before anything is allocated; the allocation itself can decline on a
     * device short of memory; and the finished canvas's measured holes have
     * to honour the same bounds the prediction was made against. A canvas
     * that fails the last gate is closed here and the ordinary merge is what
     * the user gets, with the reason.
     */
    private fun accumulateCanvas(
        stream: ZslRawStream,
        profile: SensorProfile,
        refBuffer: java.nio.ByteBuffer,
        stride: Int,
        aligned: List<AlignedFrame>,
        onProgress: (String) -> Unit,
    ): Pair<NativeSubPixel?, SuperResOutcome> {
        val prediction = SubPixelCoverage.predict(aligned.map { it.field }, profile.cfaPattern)
        Log.i(
            TAG,
            "2x coverage predicted from %d frames: holes R %.1f%% G %.1f%% B %.1f%%".format(
                prediction.frames, prediction.perChannel[0] * 100,
                prediction.perChannel[1] * 100, prediction.perChannel[2] * 100,
            ),
        )
        if (!prediction.worthAttempting) {
            return null to SuperResOutcome(
                produced = false,
                predictedHoles = prediction.perChannel,
                measuredHoles = null,
                reason = if (prediction.frames < 2) "2x needs more than one frame"
                else "burst lacks sub-pixel diversity (predicted green holes %.1f%%, chroma %.1f%%)".format(
                    prediction.perChannel[1] * 100,
                    maxOf(prediction.perChannel[0], prediction.perChannel[2]) * 100,
                ),
            )
        }

        val canvas = NativeSubPixel.create(stream.config.width, stream.config.height, profile)
            ?: return null to SuperResOutcome(
                produced = false,
                predictedHoles = prediction.perChannel,
                measuredHoles = null,
                reason = "2x canvas could not be allocated",
            )

        val t0 = System.currentTimeMillis()
        try {
            canvas.setReference(refBuffer, stride)
            for ((i, f) in aligned.withIndex()) {
                onProgress("placing ${i + 2}/${aligned.size + 1} at 2x")
                canvas.addFrame(f.buffer, stride, f.field, f.gainScale)
            }
            onProgress("resolving 2x canvas")
            val result = canvas.finish()
            val accumulateMillis = System.currentTimeMillis() - t0
            if (result == null) {
                canvas.close()
                return null to SuperResOutcome(
                    produced = false, predictedHoles = prediction.perChannel,
                    measuredHoles = null, reason = "2x accumulation failed",
                    accumulateMillis = accumulateMillis,
                )
            }
            Log.i(
                TAG,
                ("2x accumulated %d frames in %dms: holes R %.1f%% G %.1f%% B %.1f%% " +
                    "(interior %.2f%%, all %.2f%%), mean contribution %.2f").format(
                    result.framesMerged, accumulateMillis,
                    result.interiorHolesPerChannel[0] * 100, result.interiorHolesPerChannel[1] * 100,
                    result.interiorHolesPerChannel[2] * 100,
                    result.interiorHoleFraction * 100, result.holeFraction * 100,
                    result.meanContribution,
                ),
            )
            if (!result.acceptable) {
                canvas.close()
                return null to SuperResOutcome(
                    produced = false, predictedHoles = prediction.perChannel,
                    measuredHoles = result.interiorHolesPerChannel,
                    reason = "measured holes exceeded the bound (green %.1f%%, chroma %.1f%%)".format(
                        result.interiorHolesPerChannel[1] * 100,
                        maxOf(result.interiorHolesPerChannel[0], result.interiorHolesPerChannel[2]) * 100,
                    ),
                    accumulateMillis = accumulateMillis,
                )
            }
            return canvas to SuperResOutcome(
                produced = false, predictedHoles = prediction.perChannel,
                measuredHoles = result.interiorHolesPerChannel, reason = null,
                accumulateMillis = accumulateMillis,
            )
        } catch (e: Exception) {
            Log.w(TAG, "2x accumulation failed", e)
            canvas.close()
            return null to SuperResOutcome(
                produced = false, predictedHoles = prediction.perChannel,
                measuredHoles = null, reason = "2x accumulation failed: ${e.message}",
                accumulateMillis = System.currentTimeMillis() - t0,
            )
        }
    }
}
