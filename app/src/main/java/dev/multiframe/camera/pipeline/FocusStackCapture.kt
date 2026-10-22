package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.util.Log

private const val TAG = "Multiframe"

/**
 * Labs: a focus stack. One still burst with a manual focus distance per frame,
 * from the lens's nearest focus to infinity, merged by [FocusStack] and
 * written through the ordinary develop and DNG writer.
 */
object FocusStackCapture {

    /** Whether this camera can offer a stack: manual focus, and a ring deep enough for the frames. */
    fun supported(caps: CameraCapabilities?, stream: ZslRawStream?): Boolean =
        caps != null && caps.hasManualFocus && stream != null && stream.maxBurst >= FocusStack.FRAMES

    suspend fun capture(
        context: Context,
        stream: ZslRawStream,
        caps: CameraCapabilities,
        settings: ManualSettings,
        rotationDegrees: Int,
        lensProfile: LensProfile? = null,
        outputFormat: OutputFormat = OutputFormat.JPEG,
        develop: DevelopParams = DevelopParams(),
        writeDng: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): RawBurstResult {
        val statsBefore = stream.stats().toString()
        val distances = FocusStack.distances(caps.minFocusDiopters)
        val tCapture = System.currentTimeMillis()
        val shot = stream.captureStillBurst(settings, caps, distances.size, distances)
        val captureMillis = System.currentTimeMillis() - tCapture
        val burst = shot.burst
        var merger: NativeMerge? = null
        try {
            check(burst.awaitResults()) { "Missing exact CaptureResult; refusing metadata substitution" }
            val results = burst.results
            val profile = SensorProfile.from(stream.characteristics)
            val stack = FocusStack(stream.config.width, stream.config.height, profile)
            val tMerge = System.currentTimeMillis()
            // The ring hands frames newest first: infinity to near, one focus
            // step apart, which is the order the stack chains its alignment in.
            val referenceLight = FrameLight.of(results[0])
            for (i in 0 until burst.count) {
                onProgress("stacking ${i + 1}/${burst.count}")
                val buffer = burst.frames.buffer(i) ?: continue
                val gainScale = FrameLight.gainScale(referenceLight, FrameLight.of(results[i]))
                stack.add(FocusStack.source(buffer, burst.frames.rowStride), gainScale)
                burst.frames.release(i)
            }
            val focused = results.mapNotNull { it?.get(CaptureResult.LENS_FOCUS_DISTANCE) }
            val spanMillis = burst.frames.spanNs / 1_000_000
            val reference = results[0]
            burst.close()
            val merged = stack.finish()
            val mergeMillis = System.currentTimeMillis() - tMerge
            Log.i(TAG, "focus stack: %d frames, focus %s dpt, merge %dms".format(
                stack.frames, focused.joinToString { "%.2f".format(it) }, mergeMillis))

            val m = NativeMerge.create(stream.config.width, stream.config.height, profile)
                ?: return RawBurstResult(distances.size, stack.frames, null, captureMillis, mergeMillis, 0, 0,
                    null, null, "native develop allocation failed", 0, spanMillis, statsBefore)
            merger = m
            return RawBurstCapture.finishOutputs(
                context = context,
                merger = m,
                mergedBuffer = merged,
                width = stream.config.width,
                height = stream.config.height,
                profile = profile,
                characteristics = stream.characteristics,
                captureResult = reference,
                rotationDegrees = rotationDegrees,
                frameCount = distances.size,
                captured = stack.frames,
                stats = BayerMergeStats(stack.frames, 1f, 0f),
                captureMillis = captureMillis,
                mergeMillis = mergeMillis,
                tag = "stack",
                lens = stream.lens,
                lensProfile = lensProfile,
                outputFormat = outputFormat,
                burstSpanMillis = spanMillis,
                streamStats = stream.stats().toString(),
                develop = develop,
                writeDng = writeDng,
                plan = "${distances.size} focus steps, %.2f m to infinity".format(1f / caps.minFocusDiopters),
            )
        } finally {
            burst.close()
            merger?.close()
        }
    }
}
