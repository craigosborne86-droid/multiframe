package dev.multiframe.camera.pipeline

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.util.Log
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

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
 *
 * ### The shutter and the develop are two calls
 *
 * [take] is the shutter: it locks the frames and returns, in microseconds,
 * on the thread that pressed the button. [develop] is everything after --
 * the merge, the render, the write -- and it runs on a worker while the
 * viewfinder goes on and the next shutter press takes the next burst. The
 * ring gives back each frame's slot the moment the merge has read it
 * ([RingSnapshot.release]), so under a develop the camera refills the ring
 * and a press half a second later finds a full burst waiting.
 * [captureAndMerge] is the two in one call, for a caller that wants to wait.
 */
object ZslCapture {

    private const val TILE_TARGET = 32

    /** Actual merge work, including the final native resolve. */
    data class MergeProgress(val processed: Int, val total: Int, val finished: Boolean = false)

    /** A burst in hand, with what the shutter press knew about it. */
    class Shot internal constructor(
        val burst: ZslBurst,
        val frameCount: Int,
        /** Shutter press to frames in hand, in microseconds. */
        val handoverMicros: Long,
        internal val statsBefore: String,
        /** The highlight guard's pull in force at the press: zero or negative stops. The DNG carries it undone. */
        val pullStops: Float = 0f,
    ) : AutoCloseable {
        val count: Int get() = burst.count
        override fun close() = burst.close()
    }

    /**
     * The shutter: locks the newest [frameCount] frames. Costs a mutex and a
     * scan of the ring, whatever the frame size.
     */
    fun take(stream: ZslRawStream, frameCount: Int): Shot {
        val statsBefore = stream.stats().toString()
        val handoverStart = System.nanoTime()
        val burst = stream.snapshot(frameCount)
        val handoverMicros = (System.nanoTime() - handoverStart) / 1000
        if (burst.count > 0) {
            Log.i(
                TAG,
                "ZSL handover: ${burst.count} frames in ${handoverMicros}us, " +
                    "span %.0fms".format(burst.frames.spanNs / 1e6),
            )
        }
        return Shot(burst, frameCount, handoverMicros, statsBefore, stream.pullStops)
    }

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
        /** How the JPEG is rendered; the default is the PRD's global rendering. */
        develop: DevelopParams = DevelopParams(),
        /** Whether the merged raw is written as a DNG beside the JPEG. */
        writeDng: Boolean = true,
        onProgress: (String) -> Unit = {},
        onMergeProgress: (MergeProgress) -> Unit = {},
        outputTag: String = "zsl",
    ): RawBurstResult = develop(
        context, stream, take(stream, frameCount), rotationDegrees,
        lensProfile, outputFormat, superRes, develop, writeDng, onProgress, onMergeProgress, outputTag,
    )

    /**
     * Everything after the shutter: merges [shot]'s burst, renders and
     * writes it. Closes the shot. Runs wherever it is called from -- the
     * screen calls it on a worker, one shot at a time, in the order taken.
     */
    suspend fun develop(
        context: Context,
        stream: ZslRawStream,
        shot: Shot,
        rotationDegrees: Int,
        lensProfile: LensProfile? = null,
        outputFormat: OutputFormat = OutputFormat.JPEG,
        superRes: Boolean = false,
        develop: DevelopParams = DevelopParams(),
        writeDng: Boolean = true,
        onProgress: (String) -> Unit = {},
        onMergeProgress: (MergeProgress) -> Unit = {},
        outputTag: String = "zsl",
        /** The plan that chose the exposure, in words, for the photograph's report. */
        plan: String? = null,
        /** What the user chose in Labs: reference choice, person-aware merge, and the develop's analyses. */
        analysis: AnalysisChoices = AnalysisChoices.NONE,
    ): RawBurstResult {
        val profile = SensorProfile.from(stream.characteristics)
        // The meter's intent, for the develop to stay near: the guard's pull
        // undone. A shot taken with no pull (NIGHT, a dark scene) is developed
        // at about the brightness the meter chose, not lifted to a daylight.
        @Suppress("NAME_SHADOWING")
        val develop = develop.copy(meteredGain = 2.0.pow(-shot.pullStops.toDouble()).toFloat())
        val statsBefore = shot.statsBefore
        val frameCount = shot.frameCount
        val handoverMicros = shot.handoverMicros
        val burst = shot.burst

        if (burst.count == 0) {
            burst.close()
            return RawBurstResult(
                frameCount, 0, null, 0, 0, 0, 0, null, null,
                "ring empty: no frames buffered yet",
                handoverMicros, 0, statsBefore,
            )
        }

        onMergeProgress(MergeProgress(0, burst.count))

        var merger: NativeMerge? = null
        // The 2x canvas, when one is built. Closed in the finally below as
        // well as on its own path, because close is idempotent and a canvas
        // left open is 300 MB.
        var canvas: NativeSubPixel? = null
        var mergeMillis = 0L
        var captured = 0

        try {
            check(burst.awaitResults()) { "Missing exact CaptureResult; refusing metadata substitution" }
            val results = burst.results
            check(results.indices.all { i ->
                results[i]?.get(CaptureResult.SENSOR_TIMESTAMP) == burst.frames.timestampsNs[i]
            }) { "RAW/CaptureResult timestamp mismatch" }
            val t0 = System.currentTimeMillis()
            val m = NativeMerge.create(stream.config.width, stream.config.height, profile)
                ?: run {
                    burst.close()
                    return RawBurstResult(
                        frameCount, 0, null, 0, 0, 0, 0, null, null,
                        "native accumulator allocation failed",
                        handoverMicros, 0, statsBefore,
                    )
                }
            merger = m

            val tilesX = max(1, m.proxyWidth / TILE_TARGET)
            val tilesY = max(1, m.proxyHeight / TILE_TARGET)
            val stride = burst.frames.rowStride

            // The reference is the newest frame: the one closest to the
            // shutter, the exposure the photographer saw. Under dual
            // conversion gain it is the lowest-ISO frame instead -- the ring
            // alternates between two sensitivities there, and the low frame
            // keeps the highlights the high one clipped.
            //
            // It used to be the lowest-ISO frame always, on the reasoning
            // that with one ISO they were all the same. They are not when the
            // meter moves inside the burst: the lowest was then the darkest
            // frame of a re-settling meter, sometimes stops under the rest,
            // and the whole shot -- the DNG in particular, which viewers
            // render at the metadata's exposure -- came out at that frame's
            // brightness. The other frames are brought to the reference's
            // scale by their gain ratio either way.
            var refIndex = 0
            if (stream.dcg) {
                var refIso = Int.MAX_VALUE
                for (i in 0 until burst.count) {
                    val iso = results[i]
                        ?.get(CaptureResult.SENSOR_SENSITIVITY) ?: continue
                    if (iso < refIso) {
                        refIso = iso
                        refIndex = i
                    }
                }
            }
            // Labs: the sharpest of the newest few instead of the newest
            // (ReferenceChooser). Not under DCG, whose reference is chosen
            // for its highlights.
            val uses = ArrayList<AnalysisUse>(analysis.upstream)
            var chosenProxy: Plane? = null
            if (!stream.dcg && analysis.reference != ReferenceChoice.NEWEST && burst.count > 1) {
                val picked = chooseReference(context, m, burst, results, stride, analysis, uses)
                if (picked != null) {
                    refIndex = picked.first
                    chosenProxy = picked.second
                }
            }
            // ISO times exposure: the frame's gathered light, which is what a
            // frame at other settings is brought to the reference's scale by.
            // The shutter cap can move the exposure as well as the gain.
            val referenceLight = FrameLight.of(results[refIndex])

            val refBuffer = burst.frames.buffer(refIndex)
                ?: error("reference slot unreadable")
            m.setReference(refBuffer, stride)
            val refProxy = chosenProxy ?: m.lumaProxy(refBuffer, stride)
            // Labs: the reference's own samples, kept for blending moving
            // people back from it after the merge (PersonMask).
            val personModel = analysis.person?.takeIf { analysis.personMerge && !superRes }
            val refCopy = personModel?.let { PersonMask.copyPacked(refBuffer, stride, m.width, m.height) }
            // Only built when the native search is unavailable: it needs the
            // pyramid, and native builds its own.
            val refPyramid by lazy { Aligner.buildPyramid(refProxy) }
            captured = 1
            onMergeProgress(MergeProgress(captured, burst.count))
            // The merge has its own copy of the reference; the slot goes
            // back to the ring now. Not under the 2x canvas, which reads
            // every frame again once the burst is aligned.
            if (!superRes) burst.frames.release(refIndex)

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

                val gainScale = FrameLight.gainScale(referenceLight, FrameLight.of(results[i]))

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

                // For the 2x canvas, with the tiles the fit declined given
                // their neighbours' fractions: the merge above reads the
                // integer offsets only and is unchanged by this.
                aligned.add(AlignedFrame(buffer, field.withFlatTilesBorrowed(), gainScale))
                captured++
                onMergeProgress(MergeProgress(captured, burst.count))
                // Read once, given back at once: the camera refills this
                // slot while the next frame is merged, and the next shutter
                // press finds a burst rather than the two frames a held
                // ring would have left it.
                if (!superRes) burst.frames.release(i)
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
            if (personModel != null && refCopy != null) {
                runCatching {
                    val refCells = CfaThumbnail.cells(refCopy, m.width * 2, m.width, m.height, profile, PersonMask.WIDTH, PersonMask.HEIGHT)
                    val mergedCells = CfaThumbnail.cells(mergedBuffer, m.width * 2, m.width, m.height, profile, PersonMask.WIDTH, PersonMask.HEIGHT)
                    val color = ColorProfile.calibrated(stream.characteristics, results[refIndex])
                    val weights = PersonMask.mergeWeights(personModel.run(PersonMask.input(refCells, color)), refCells, mergedCells)
                    PersonMask.blendTowardReference(mergedBuffer, refCopy, m.width * 2, m.width, m.height, weights)
                    uses += personModel.use()
                    Log.i(TAG, "person-aware merge: %.1f%% of cells from the reference".format(weights.count { it > 0.5f } * 100f / weights.size))
                }.onFailure { Log.w(TAG, "person-aware merge declined", it) }
            }
            mergeMillis += System.currentTimeMillis() - t1
            onMergeProgress(MergeProgress(captured, burst.count, finished = true))

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
                        captureResult = results[refIndex],
                        rotationDegrees = rotationDegrees,
                        captured = captured,
                        lens = stream.lens,
                        params = develop,
                        writeDng = writeDng,
                        baselineExposureStops = -shot.pullStops,
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
                captureResult = results[refIndex],
                rotationDegrees = rotationDegrees,
                frameCount = frameCount,
                captured = captured,
                stats = stats,
                captureMillis = 0,
                mergeMillis = mergeMillis,
                // Keep the native readout's measured pixel count with both
                // files, so the gallery can distinguish it from a 2x canvas.
                tag = if (stream.config.fullResolution)
                    "${stream.config.megapixels.roundToInt()}mp" else outputTag,
                lens = stream.lens,
                lensProfile = lensProfile,
                outputFormat = outputFormat,
                handoverMicros = handoverMicros,
                burstSpanMillis = spanMillis,
                streamStats = statsAfter.toString(),
                dcgActive = stream.dcg,
                develop = develop,
                baselineExposureStops = -shot.pullStops,
                writeDng = writeDng,
                plan = plan,
                analysisUses = uses,
                analysis = analysis,
            ).copy(superRes = outcome)
        } catch (e: Exception) {
            Log.e(TAG, "ZSL merge failed", e)
            burst.close()
            return RawBurstResult(
                frameCount, captured, null, 0, mergeMillis, 0, 0, null, null,
                "ZSL merge failed: ${e.message}",
                handoverMicros, 0, statsBefore,
            )
        } finally {
            burst.close()
            canvas?.close()
            merger?.close()
        }
    }

    /**
     * The reference among the newest [ReferenceChooser.CANDIDATES] frames
     * (index 0 is the newest), and its proxy so it is not built twice; null
     * where a frame was unreadable. Under the model the model's use is added
     * to [uses]. Where collection is on, each candidate with a gyro reading
     * is saved with its measured blur as the frame task's label.
     */
    private fun chooseReference(
        context: Context, m: NativeMerge, burst: ZslBurst, results: List<android.hardware.camera2.TotalCaptureResult?>,
        stride: Int, analysis: AnalysisChoices, uses: MutableList<AnalysisUse>,
    ): Pair<Int, Plane>? = runCatching {
        val n = minOf(ReferenceChooser.CANDIDATES, burst.count)
        val proxies = (0 until n).map { i -> m.lumaProxy(burst.frames.buffer(i) ?: return null, stride) }
        val model = analysis.frameModel.takeIf { analysis.reference == ReferenceChoice.MODEL }
        val pick = if (model != null) {
            uses += model.use()
            ReferenceChooser.byBlur(FloatArray(n) { model.run(ReferenceChooser.FrameContract.input(proxies[it]))[0] })
        } else {
            ReferenceChooser.bySharpness(FloatArray(n) { ReferenceChooser.sharpness(proxies[it]) })
        }
        val blurAt = analysis.blurPixels
        if (analysis.collect && blurAt != null) for (i in 0 until n) {
            val exposure = results[i]?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: continue
            // Sensor pixels to proxy pixels: the proxy is one per CFA cell.
            val blur = blurAt(burst.frames.timestampsNs[i], exposure) ?: continue
            AnalysisModels.collect(context, AnalysisTask.FRAME, ReferenceChooser.FrameContract.input(proxies[i]), floatArrayOf(blur / 2f))
        }
        if (pick != 0) Log.i(TAG, "reference: frame $pick of the newest $n is sharper than the newest")
        Pair(pick, proxies[pick])
    }.onFailure { Log.w(TAG, "reference choice declined", it) }.getOrNull()

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
            submitSubPixelFrames(
                alternateCount = aligned.size,
                setReference = { canvas.setReference(refBuffer, stride) },
                addFrame = { i ->
                    val f = aligned[i]
                    onProgress("placing ${i + 2}/${aligned.size + 1} at 2x")
                    canvas.addFrame(f.buffer, stride, f.field, f.gainScale)
                },
            )
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
