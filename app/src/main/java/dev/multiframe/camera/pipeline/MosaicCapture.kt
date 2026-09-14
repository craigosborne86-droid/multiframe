package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "Multiframe"

/** How a sweep ended. */
data class MosaicResult(
    val tiles: Int,
    val rejected: Int,
    val coverage: Float,
    val megapixels: Double,
    val savedName: String?,
    val elapsedMillis: Long,
    val message: String,
)

/** One node as the screen should draw it. */
data class NodeView(
    val node: SweepNode,
    val state: GuidedSweep.NodeState,
    /**
     * Where the node's centre falls in the current frame, as fractions of the
     * sensor frame's width and height, or null when the frame could not be
     * registered. May lie outside 0..1: a node off the edge of the view is
     * drawn at the edge, pointing the way.
     */
    val frameX: Float?,
    val frameY: Float?,
)

/** Everything the screen needs to draw the guidance, once per poll. */
data class GuidedSweepView(
    val nodes: List<NodeView>,
    val captured: Int,
    val total: Int,
    /** Whether the current frame registered against the sweep so far. */
    val located: Boolean,
    /** Fraction of the canvas covered, as the assembler counts it. */
    val coverage: Float,
)

/** Moments the screen answers with a haptic or a flash. */
sealed interface SweepEvent {
    /** The anchor frame was taken; the nodes are laid out. */
    data object Anchored : SweepEvent

    /** The reticle has held on [node]; its burst is being taken. */
    data class Locked(val node: SweepNode) : SweepEvent

    /** [node]'s burst is composited. */
    data class Captured(val node: SweepNode) : SweepEvent

    /** [node]'s burst could not be used and it is pending again. */
    data class Rejected(val node: SweepNode, val reason: String) : SweepEvent

    /** The pan was too fast for a sharp frame. */
    data object TooFast : SweepEvent

    /** Every node is captured. */
    data object Complete : SweepEvent
}

/**
 * Drives a guided telephoto sweep.
 *
 * ### The shape of it
 *
 * The first frame after the sweep starts is the anchor: wherever the user is
 * pointing becomes the centre of the canvas, and the plan's nodes -- one per
 * tile -- are laid out around it. Then, ten times a second, the newest preview
 * frame is registered against the anchor and every tile placed so far, which
 * says where the reticle is pointing on the canvas; [GuidedSweep] turns that
 * into node states and, when the reticle has held on a node, the decision to
 * capture. A capture is a burst from the ring, merged and developed like any
 * other photograph, corrected for the lens, and composited at the place its
 * reference frame registered to. Registration is against *frames*, not the
 * nodes' ideal positions: a node says where to point, the frame says where
 * the tile actually is.
 *
 * ### Why registration runs on every poll
 *
 * The dots have to be drawn where the tiles are in the current view, and the
 * only thing that knows where the current view is relative to the canvas is
 * a registration of the current frame. The gyroscope could say so faster and
 * would drift; the frame cannot drift. Registration runs on a proxy a few
 * hundred pixels across and costs tens of milliseconds, which is under the
 * poll interval.
 *
 * ### Why only bursts are developed
 *
 * Developing a full-resolution tile costs around half a second. Ten frames a
 * second are registered and only the ones a node asks for are developed, so
 * a sweep of nine tiles develops nine bursts and not the hundreds of frames
 * the user panned through.
 */
object MosaicCapture {

    /** Halvings below the half-resolution luma proxy the merge already builds. */
    private const val PROXY_HALVINGS = 3

    /** How often to look for a new frame. Faster than a person can pan. */
    private const val POLL_MILLIS = 90L

    /**
     * Runs a guided sweep until every node is captured, the user stops, or
     * nothing can be registered.
     *
     * [frames] is the burst length per node. [shouldContinue] is polled so the
     * user can stop early and keep what they have.
     */
    suspend fun run(
        context: Context,
        stream: ZslRawStream,
        session: MosaicSession,
        sweep: GuidedSweep,
        settings: ManualSettings,
        caps: CameraCapabilities,
        frames: Int,
        lensProfile: LensProfile?,
        onView: (GuidedSweepView) -> Unit,
        onEvent: (SweepEvent) -> Unit,
        shouldContinue: () -> Boolean,
    ): MosaicResult {
        val started = System.currentTimeMillis()
        val profile = SensorProfile.from(stream.characteristics)

        // Everything pinned for the duration. Auto-exposure drifting between
        // tiles leaves each a different brightness, and feathering turns a hard
        // seam into a soft gradient rather than removing it.
        stream.lockForSweep(settings, caps)

        var lastTimestamp = -1L
        var anchored = false
        var lastLocated: MosaicAssembler.Located? = null

        // One merger for the whole poll loop, for its luma proxy alone. Its
        // accumulator is never fed; creating one per poll was a hundred
        // megabytes faulted in and freed ten times a second.
        val prober = NativeMerge.create(stream.config.width, stream.config.height, profile)
            ?: return MosaicResult(0, 0, 0f, session.plan.megapixels, null, 0, "no memory for the sweep")

        try {
            while (shouldContinue() && !sweep.complete) {
                val burst = stream.snapshot(1)
                if (burst.count == 0) {
                    burst.close()
                    delay(POLL_MILLIS)
                    continue
                }
                val timestamp = burst.frames.timestampsNs.firstOrNull() ?: -1L
                if (timestamp == lastTimestamp) {
                    burst.close()
                    delay(POLL_MILLIS)
                    continue
                }
                lastTimestamp = timestamp

                val buffer = burst.frames.buffer(0)
                if (buffer == null) {
                    burst.close()
                    delay(POLL_MILLIS)
                    continue
                }
                val stride = burst.frames.rowStride

                // The proxy, and with it the scale from proxy to tile.
                val proxy = proxyFor(prober, buffer, stride)
                val scale = prober.width.toFloat() / maxOf(1, proxy.width)
                if (proxy.width == 0) {
                    burst.close()
                    delay(POLL_MILLIS)
                    continue
                }

                if (!anchored) {
                    // The colour and the exposure every tile will be developed
                    // with, and the origin. The gain is measured from the anchor
                    // frame the way a single capture would measure it, and then
                    // held: each tile developed with its own automatic gain is
                    // a mosaic of nine exposures.
                    val color = ColorProfile.calibrated(stream.characteristics, burst.referenceResult)
                    session.lockColour(color, ShadingMap.from(burst.referenceResult))
                    NativeMerge.create(stream.config.width, stream.config.height, profile)?.use { m ->
                        m.setReference(buffer, stride)
                        val (merged, _) = m.finish()
                        session.lockExposure(m.autoExposure(merged, color), exposureProduct(burst.referenceResult))
                    }
                    session.anchor(proxy)
                    anchored = true
                    burst.close()
                    onEvent(SweepEvent.Anchored)
                    onView(viewOf(sweep, session, null))
                    delay(POLL_MILLIS)
                    continue
                }

                val located = session.locate(proxy, scale)
                lastLocated = located ?: lastLocated
                burst.close()

                val now = System.currentTimeMillis()
                val decision = if (located != null) {
                    val centre = located.transform.apply(stream.config.width / 2f, stream.config.height / 2f)
                    sweep.observe(centre[0], centre[1], now)
                } else {
                    sweep.observe(null, null, now)
                }
                onView(viewOf(sweep, session, located?.transform))

                when (decision) {
                    is GuidedSweep.Decision.TooFast -> onEvent(SweepEvent.TooFast)
                    is GuidedSweep.Decision.Capture -> {
                        onEvent(SweepEvent.Locked(decision.node))
                        val outcome = runCatching {
                            captureNode(stream, session, profile, frames, lensProfile, scale)
                        }.onFailure { Log.w(TAG, "node ${decision.node.index} failed", it) }
                            .getOrDefault("capture failed")
                        if (outcome == null) {
                            sweep.captured(decision.node)
                            onEvent(SweepEvent.Captured(decision.node))
                            if (sweep.complete) onEvent(SweepEvent.Complete)
                        } else {
                            sweep.rejected(decision.node, System.currentTimeMillis())
                            onEvent(SweepEvent.Rejected(decision.node, outcome))
                        }
                        onView(viewOf(sweep, session, lastLocated?.transform))
                    }
                    GuidedSweep.Decision.Idle -> Unit
                }
                delay(POLL_MILLIS)
            }
        } finally {
            prober.close()
            stream.unlock(settings, caps)
        }

        val progress = session.progress
        val name = if (progress.placed > 0) {
            session.save(context, "MF_${stamp()}_mosaic_${progress.placed}t.jpg")
        } else null

        val elapsed = System.currentTimeMillis() - started
        return MosaicResult(
            tiles = progress.placed,
            rejected = progress.rejected,
            coverage = progress.coverage,
            megapixels = session.plan.megapixels,
            savedName = name,
            elapsedMillis = elapsed,
            message = when {
                progress.placed == 0 -> "no tiles were captured"
                name == null -> "captured ${progress.placed} tiles but could not save"
                sweep.complete -> "sweep complete: ${progress.placed} of ${sweep.total} tiles"
                else -> "partial sweep: ${progress.placed} of ${sweep.total} tiles"
            },
        )
    }

    /**
     * The screen's view of the sweep: every node's state and, when the frame
     * registered, where each falls in it.
     */
    private fun viewOf(sweep: GuidedSweep, session: MosaicSession, transform: Homography?): GuidedSweepView {
        val inverse = transform?.invert()
        val w = session.plan.tileWidthPx.toFloat()
        val h = session.plan.tileHeightPx.toFloat()
        val nodes = sweep.nodes.map { node ->
            val p = inverse?.apply(node.canvasX, node.canvasY)
            val ok = p != null && !p[0].isNaN() && !p[1].isNaN() && w > 0f && h > 0f
            NodeView(
                node = node,
                state = sweep.stateOf(node),
                frameX = if (ok) p!![0] / w else null,
                frameY = if (ok) p!![1] / h else null,
            )
        }
        return GuidedSweepView(
            nodes = nodes,
            captured = sweep.captured,
            total = sweep.total,
            located = transform != null,
            coverage = session.progress.coverage,
        )
    }

    /**
     * Takes the burst for a node: the ring's newest [frames], merged, developed
     * with the sweep's locked colour, corrected for the lens, and composited at
     * the place the burst's reference frame registered to.
     *
     * Returns null on success, or the reason it could not be used.
     */
    private fun captureNode(
        stream: ZslRawStream,
        session: MosaicSession,
        profile: SensorProfile,
        frames: Int,
        lensProfile: LensProfile?,
        proxyScale: Float,
    ): String? {
        val burst = stream.snapshot(frames)
        if (burst.count == 0) {
            burst.close()
            return "ring empty"
        }
        val merger = NativeMerge.create(stream.config.width, stream.config.height, profile)
            ?: run { burst.close(); return "no memory for the merge" }

        return merger.use { m ->
            val stride = burst.frames.rowStride
            // The newest frame is the reference: it is the one the user saw
            // when the lock fired, and the one the node's dot was drawn from.
            val refIndex = 0
            val referenceResult = burst.results.getOrNull(refIndex) ?: burst.referenceResult
            val refBuffer = burst.frames.buffer(refIndex) ?: run { burst.close(); return@use "reference unreadable" }
            m.setReference(refBuffer, stride)
            val refProxy = m.lumaProxy(refBuffer, stride)
            val tilesX = maxOf(1, m.proxyWidth / TILE_TARGET)
            val tilesY = maxOf(1, m.proxyHeight / TILE_TARGET)
            for (i in 0 until burst.count) {
                if (i == refIndex) continue
                val buffer = burst.frames.buffer(i) ?: continue
                val proxy = m.lumaProxy(buffer, stride)
                val field = Aligner.alignNative(refProxy, proxy, tilesX, tilesY)
                    ?: Aligner.align(Aligner.buildPyramid(refProxy), Aligner.buildPyramid(proxy), tilesX, tilesY)
                m.addFrame(buffer, stride, field)
            }

            // Where this burst is, from its own reference frame. The node said
            // where to point; the frame says where the tile is.
            val small = proxyFor(m, refBuffer, stride)
            val located = session.locate(small, proxyScale)
            burst.close()
            if (located == null) return@use "could not register the burst"

            val (merged, _) = m.finish()
            val color = session.color ?: ColorProfile.NEUTRAL
            // The anchor's gain, scaled by how this burst's exposure differed
            // from the anchor's -- which under the sweep's lock should be not
            // at all, and this is what holds if it was.
            val gain = session.gainFor(exposureProduct(referenceResult))
            var tile = m.develop(
                merged, color, DevelopParams(exposureGain = gain), shading = session.shading,
            ) ?: return@use "develop failed"

            // The lens's geometry, before the tile is placed by a homography
            // that assumes a rectilinear image. Distortion left in would make
            // every seam a mismatch of curves.
            opticsFor(lensProfile, tile)?.let { params ->
                val (corrected, _) = Optics.apply(tile, params)
                if (corrected !== tile) {
                    tile.recycle()
                    tile = corrected
                }
            }

            val placed = session.place(tile, small, located)
            tile.recycle()
            if (placed == null) "the canvas would not take it" else null
        }
    }

    private const val TILE_TARGET = 32

    /** ISO times exposure, the sensor's own measure of how much light a frame gathered. */
    private fun exposureProduct(result: android.hardware.camera2.TotalCaptureResult?): Double {
        val iso = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return 0.0
        val ns = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return 0.0
        return iso.toDouble() * ns.toDouble()
    }

    /** Successively halved luma, small enough for corner detection to be quick. */
    private fun proxyFor(m: NativeMerge, buffer: java.nio.ByteBuffer, stride: Int): Plane {
        var plane = m.lumaProxy(buffer, stride)
        repeat(PROXY_HALVINGS) {
            if (plane.width > 64 && plane.height > 64) {
                plane = Aligner.downsampleByTwo(plane)
            }
        }
        return plane
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
}
