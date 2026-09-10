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

                captured++
            }
            mergeMillis = System.currentTimeMillis() - t0

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
                handoverMicros = handoverMicros,
                burstSpanMillis = spanMillis,
                streamStats = statsAfter.toString(),
                dcgActive = stream.dcg,
            )
        } catch (e: Exception) {
            Log.e(TAG, "ZSL merge failed", e)
            burst.close()
            return RawBurstResult(
                frameCount, captured, null, 0, mergeMillis, 0, 0, null, null,
                "ZSL merge failed: ${e.message}",
                handoverMicros, 0, statsBefore.toString(),
            )
        } finally {
            merger?.close()
        }
    }
}
