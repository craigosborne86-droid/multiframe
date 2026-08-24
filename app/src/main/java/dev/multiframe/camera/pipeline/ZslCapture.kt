package dev.multiframe.camera.pipeline

import android.content.Context
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

            // Newest frame is the reference: it is the one closest to the
            // instant the user actually pressed the shutter.
            val refBuffer = burst.frames.buffer(0) ?: error("reference slot unreadable")
            m.setReference(refBuffer, stride)
            val refPyramid = Aligner.buildPyramid(m.lumaProxy(refBuffer, stride))
            captured = 1

            for (i in 1 until burst.count) {
                onProgress("merging ${i + 1}/${burst.count}")
                val buffer = burst.frames.buffer(i) ?: continue
                val field = Aligner.align(
                    refPyramid,
                    Aligner.buildPyramid(m.lumaProxy(buffer, stride)),
                    tilesX, tilesY,
                )
                m.addFrame(buffer, stride, field)
                captured++
            }
            mergeMillis = System.currentTimeMillis() - t0

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
                captureResult = burst.referenceResult,
                rotationDegrees = rotationDegrees,
                frameCount = frameCount,
                captured = captured,
                stats = stats,
                captureMillis = 0,
                mergeMillis = mergeMillis,
                tag = "zsl",
                handoverMicros = handoverMicros,
                burstSpanMillis = spanMillis,
                streamStats = statsAfter.toString(),
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
