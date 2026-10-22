package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

private const val TAG = "Multiframe"

/**
 * The astro capture: [AstroPlanner.Plan.frames] still exposures, taken a few
 * at a time and merged as they arrive, so the span is bounded by patience
 * rather than by the ring.
 *
 * ### Two stacks and a mask
 *
 * Each frame is merged twice: into a *sky* stack, aligned so the stars land
 * on the reference's stars, and into a *ground* stack, unshifted. Both merges
 * keep their motion rejection. The [SkyMask] -- measured from which of the two
 * explanations fits each pixel better across the stack, or, where the user
 * chose it in Labs, a segmentation model's mask ([SkySegmenter]) -- blends
 * them per CFA cell. Stars stay points over the ground; the ground stays sharp
 * under the turning sky.
 *
 * ### The sky's offset where a tile does not see it
 *
 * A tile that is mostly ground aligns at zero, and its slice of sky would be
 * merged unshifted. The sky stack therefore gives such tiles the frame's sky
 * motion: the median offset of the tiles that did move. Early in the span,
 * before the sky has moved a pixel, there is nothing to tell apart and both
 * stacks agree.
 *
 * ### Reference
 *
 * The first frame, as in the long exposure (§3.10): the span is after the
 * press, and the first frame is the one the photographer framed.
 *
 * Memory: two accumulators (about twice a burst merge's) and the frames in
 * flight -- never the whole span.
 */
object AstroCapture {

    private const val TILE_TARGET = 32
    private const val CHUNK = 6
    private const val HOT_SAMPLE_FRAMES = 4

    /** Where astro can be offered: a tripod reading needs the gyro, long frames need manual exposure. */
    fun supported(caps: CameraCapabilities?, stream: ZslRawStream?): Boolean =
        caps != null && caps.hasManualSensor && stream != null && stream.maxBurst >= CHUNK

    suspend fun capture(
        context: Context,
        stream: ZslRawStream,
        caps: CameraCapabilities,
        settings: ManualSettings,
        plan: AstroPlanner.Plan,
        rotationDegrees: Int,
        segmenter: SkySegmenter? = null,
        lensProfile: LensProfile? = null,
        outputFormat: OutputFormat = OutputFormat.JPEG,
        develop: DevelopParams = DevelopParams(),
        writeDng: Boolean = true,
        /** Checked between frames: true ends the span early and keeps the stack (Cancel, or heat). */
        shouldStop: () -> Boolean = { false },
        /** Labs: save this capture's (reference, measured mask) pair for training the sky model. */
        collectTraining: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RawBurstResult {
        val statsBefore = stream.stats().toString()
        val profile = SensorProfile.from(stream.characteristics)
        val w = stream.config.width; val h = stream.config.height
        val sky = NativeMerge.create(w, h, profile) ?: return failed("native accumulator allocation failed", statsBefore)
        val ground = NativeMerge.create(w, h, profile) ?: run { sky.close(); return failed("native accumulator allocation failed", statsBefore) }
        val stillSettings = settings.copy(manualExposure = true, iso = plan.iso, exposureTimeNs = plan.exposureNs)
        val tStart = System.currentTimeMillis()
        var captured = 0
        try {
            val tilesX = max(1, sky.proxyWidth / TILE_TARGET)
            val tilesY = max(1, sky.proxyHeight / TILE_TARGET)
            val zero = AlignmentField(tilesX, tilesY, IntArray(tilesX * tilesY), IntArray(tilesX * tilesY))
            val hotMap = HotPixelMap(w, h)
            val mask = SkyMask(sky.proxyWidth, sky.proxyHeight)
            var refProxy: Plane? = null
            var refLight = 0.0
            var reference: TotalCaptureResult? = null
            var firstNs = 0L
            var lastNs = 0L
            var hotSettled = false

            // The sky's motion so far: each frame's search starts from the last
            // frame's, so a span longer than the search's own reach still tracks.
            var predicted = Pair(0, 0)
            while (captured < plan.frames && !(captured >= 2 && shouldStop())) {
                val take = minOf(CHUNK, plan.frames - captured)
                val shot = stream.captureStillBurst(stillSettings, caps, take)
                try {
                    check(shot.burst.awaitResults()) { "Missing exact CaptureResult; refusing metadata substitution" }
                    // Oldest first within the chunk, so the stack runs in time order.
                    for (i in shot.burst.count - 1 downTo 0) {
                        val buffer = shot.burst.frames.buffer(i) ?: continue
                        val stride = shot.burst.frames.rowStride
                        val result = shot.burst.results[i] ?: continue
                        val src = FocusStack.source(buffer, stride)
                        if (!hotSettled) {
                            hotMap.sample(src)
                            if (captured + 1 >= HOT_SAMPLE_FRAMES || captured + 1 >= plan.frames) {
                                Log.i(TAG, "astro: ${hotMap.settle().size} hot pixels")
                                hotSettled = true
                            }
                        }
                        if (hotSettled) {
                            val writable = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            hotMap.repair(src) { x, y, v -> writable.putShort(y * stride + x * 2, v.toShort()) }
                        }
                        val ts = shot.burst.frames.timestampsNs[i]
                        if (refProxy == null) {
                            sky.setReference(buffer, stride)
                            ground.setReference(buffer, stride)
                            refProxy = sky.lumaProxy(buffer, stride)
                            refLight = FrameLight.of(result)
                            reference = result
                            firstNs = ts
                        } else {
                            val gain = FrameLight.gainScale(refLight, FrameLight.of(result))
                            val proxy = sky.lumaProxy(buffer, stride, gain)
                            val shifted = shiftedProxy(proxy, predicted.first, predicted.second)
                            val found = Aligner.alignNative(refProxy, shifted, tilesX, tilesY)
                                ?: Aligner.align(Aligner.buildPyramid(refProxy), Aligner.buildPyramid(shifted), tilesX, tilesY)
                            val field = AlignmentField(tilesX, tilesY,
                                IntArray(found.dx.size) { found.dx[it] + predicted.first },
                                IntArray(found.dy.size) { found.dy[it] + predicted.second })
                            val skyField = withSkyMotion(field)
                            predicted = skyMotion(field) ?: predicted
                            sky.addFrame(buffer, stride, skyField, gain)
                            ground.addFrame(buffer, stride, zero, gain)
                            mask.add(refProxy, proxy, skyField)
                        }
                        lastNs = ts
                        captured++
                        onProgress(captured, plan.frames)
                    }
                } finally {
                    shot.close()
                }
            }

            val spanMillis = (lastNs - firstNs) / 1_000_000
            val captureMillis = System.currentTimeMillis() - tStart
            val tMerge = System.currentTimeMillis()
            val (skyBuf, skyStats) = sky.finish()
            val (groundBuf, _) = ground.finish()
            val ref = refProxy ?: return failed("astro took no frames", statsBefore)
            // The mask: the stack's own evidence, or the user's chosen model where one ran.
            val modelMask = segmenter?.let { s -> runCatching { s.skyMask(ref) }.getOrNull() }
            val measuredMask = mask.finish()
            // Training pairs are always the measured mask: the model learns what the stack proved.
            if (collectTraining && mask.frames >= 8) SkySegmenters.collect(context, ref, measuredMask)
            val skyMask = modelMask ?: measuredMask
            val out = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
            SkyMask.blend(skyBuf, groundBuf, skyMask, w, h, out)
            val mergeMillis = System.currentTimeMillis() - tMerge
            val skyShare = skyMask.average()
            Log.i(TAG, "astro: %d frames over %ds, sky %.0f%% (%s), merge %dms".format(
                captured, spanMillis / 1000, skyShare * 100, if (modelMask != null) segmenter?.name else "measured", mergeMillis))

            return RawBurstCapture.finishOutputs(
                context = context,
                merger = sky,
                mergedBuffer = out,
                width = w,
                height = h,
                profile = profile,
                characteristics = stream.characteristics,
                captureResult = reference,
                rotationDegrees = rotationDegrees,
                frameCount = plan.frames,
                captured = captured,
                stats = skyStats,
                captureMillis = captureMillis,
                mergeMillis = mergeMillis,
                tag = "astro",
                lens = stream.lens,
                lensProfile = lensProfile,
                outputFormat = outputFormat,
                burstSpanMillis = spanMillis,
                streamStats = stream.stats().toString(),
                // The plan set the gain a stop under the meter's light; the develop gives it back.
                develop = develop.copy(meteredGain = 2f),
                writeDng = writeDng,
                plan = plan.reading() + if (modelMask != null) " · sky mask: ${segmenter?.name} (ML, Labs)" else " · sky mask: measured",
                alignment = CaptureReport.ALIGN_TILES,
                mlAnalysis = if (modelMask != null) segmenter?.name else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "astro failed", e)
            return failed("astro failed: ${e.message}", statsBefore, captured)
        } finally {
            sky.close()
            ground.close()
        }
    }

    /** Whether the phone is hot enough that a long span should end now and keep its stack. */
    fun tooHot(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
        return power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
    }

    /** The proxy as seen from (dx, dy) further on: out(x, y) = in(x + dx, y + dy), edges held. */
    internal fun shiftedProxy(p: Plane, dx: Int, dy: Int): Plane {
        if (dx == 0 && dy == 0) return p
        val out = ByteArray(p.data.size)
        for (y in 0 until p.height) {
            val sy = (y + dy).coerceIn(0, p.height - 1)
            for (x in 0 until p.width) out[y * p.width + x] = p.data[sy * p.width + (x + dx).coerceIn(0, p.width - 1)]
        }
        return Plane(p.width, p.height, out)
    }

    /** The median offset of the tiles that moved, where at least a tenth did; null otherwise. */
    internal fun skyMotion(field: AlignmentField): Pair<Int, Int>? {
        val moving = field.dx.indices.filter { abs(field.dx[it]) + abs(field.dy[it]) > 0 }
        if (moving.size * 10 < field.dx.size) return null
        return Pair(moving.map { field.dx[it] }.sorted()[moving.size / 2], moving.map { field.dy[it] }.sorted()[moving.size / 2])
    }

    /**
     * The tile field with the frame's sky motion given to tiles that did not
     * move: the median offset of the tiles that did, where at least a tenth of
     * them moved. A field that did not move anywhere is returned as it is.
     */
    internal fun withSkyMotion(field: AlignmentField): AlignmentField {
        val moving = field.dx.indices.filter { abs(field.dx[it]) + abs(field.dy[it]) > 0 }
        if (moving.size * 10 < field.dx.size) return field
        val mx = moving.map { field.dx[it] }.sorted()[moving.size / 2]
        val my = moving.map { field.dy[it] }.sorted()[moving.size / 2]
        return AlignmentField(field.tilesX, field.tilesY,
            IntArray(field.dx.size) { if (field.dx[it] == 0 && field.dy[it] == 0) mx else field.dx[it] },
            IntArray(field.dy.size) { if (field.dx[it] == 0 && field.dy[it] == 0) my else field.dy[it] })
    }

    private fun failed(message: String, stats: String, captured: Int = 0) =
        RawBurstResult(0, captured, null, 0, 0, 0, 0, null, null, message, 0, 0, stats)
}

/**
 * A model that marks sky (Labs, opt-in). It only *classifies* measured pixels;
 * the mask it returns chooses between two stacks of measured samples, and
 * nothing it computes becomes a pixel value. Implementations must be on-device
 * and offline.
 */
interface SkySegmenter {
    /** Shown in the photograph's report and Content Credentials. */
    val name: String
    /** Sky probability per proxy pixel (one per CFA cell), 0..1, same size as [proxy]. */
    fun skyMask(proxy: Plane): FloatArray
}
