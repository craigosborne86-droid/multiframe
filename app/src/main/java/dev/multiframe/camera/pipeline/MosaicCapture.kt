package dev.multiframe.camera.pipeline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "Multiframe"

/** How a mosaic ended. */
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
    val node: MosaicNode,
    val state: MosaicGuide.NodeState,
    /**
     * Where the node's centre falls in the current frame, as fractions of the
     * sensor frame's width and height, or null when the phone's attitude is
     * unknown or the node is behind the camera. May lie outside 0..1: a node
     * off the edge of the view is drawn at the edge, pointing the way.
     */
    val frameX: Float?,
    val frameY: Float?,
)

/** Everything the screen needs to draw the guidance, once per poll. */
data class MosaicGuideView(
    val nodes: List<NodeView>,
    val captured: Int,
    val total: Int,
    /** Whether the reticle's place on the canvas is known this poll: the attitude has been read. */
    val located: Boolean,
    /** Fraction of the canvas covered, as the assembler counts it. */
    val coverage: Float,
    /**
     * The canvas's four corners in the current frame, as fractions of the
     * frame's width and height, top-left, top-right, bottom-right,
     * bottom-left -- the outline of the photograph being composed. Under a
     * rotation it is a quadrilateral, not a rectangle. Null when the attitude
     * is unknown or a corner is behind the camera.
     */
    val canvasCorners: List<Pair<Float, Float>>?,
    /** How far the hold on the locking node has progressed, 0 to 1. */
    val holdProgress: Float = 0f,
    /** Whether the last poll read as still, on both of the gate's measures. */
    val steady: Boolean = false,
    /** Why it did not, for the hint; null when it did or when nothing is locking. */
    val unsteady: MosaicGuide.Unsteady? = null,
    /** The lens taking the tiles, for the screen to say so. */
    val captureLensLabel: String = "",
    /** Whether the mosaic has anchored; before it, the outline is where the anchor would put it. */
    val anchored: Boolean = true,
)

/** Moments the screen answers with a haptic or a flash. */
sealed interface MosaicEvent {
    /** The anchor frame was taken; the nodes are laid out. */
    data object Anchored : MosaicEvent

    /** The reticle has held on [node]; its burst is being taken. */
    data class Locked(val node: MosaicNode) : MosaicEvent

    /** [node]'s burst is composited. */
    data class Captured(val node: MosaicNode) : MosaicEvent

    /** [node]'s burst could not be used and it is pending again. */
    data class Rejected(val node: MosaicNode, val reason: String) : MosaicEvent

    /** The pan was too fast for a sharp frame. */
    data object TooFast : MosaicEvent

    /** Every node is captured. */
    data object Complete : MosaicEvent
}

/**
 * Drives a guided telephoto guide.
 *
 * ### The shape of it
 *
 * The first frame after the mosaic starts is the anchor: wherever the user is
 * pointing becomes the centre of the canvas, and the plan's nodes -- one per
 * tile -- are laid out around it as directions in the world, fixed from the
 * phone's attitude at that moment. Then, thirty times a second, the attitude
 * is read and [ImuGuidance] says where the reticle points on the canvas and
 * where each node falls in the frame; [MosaicGuide] turns that into node
 * states and, when the reticle has held on a node, the decision to capture. A
 * capture is a burst from the ring, merged and developed like any other
 * photograph, corrected for the lens, and composited at the place its
 * reference frame *registers* to against the tiles already placed. The node
 * says where to point, the attitude says where the phone points, and the
 * frame's own pixels say where the tile actually is -- and the attitude, in
 * turn, is what a registration has to agree with to be believed.
 *
 * ### Why the guidance is inertial and the placement is visual
 *
 * The first version registered every preview frame to draw the dots. On a
 * lens seven degrees across, a pan of a few degrees carries the anchor's
 * features out of the frame and the registration fails or clings; the dots
 * swam, stuck and vanished, and a hand cannot follow a dot that does that.
 * The attitude never fails to answer -- it does not look at the scene -- and
 * it answers in a millisecond. The dots it places are static: directions in
 * the world, fixed at the anchor, never moved by anything that happens
 * afterwards. Nothing about where a tile is placed comes from the attitude
 * -- but a registration that puts a tile far from where the attitude says the
 * phone was pointing is a wrong registration, not a wrong attitude, and is
 * refused: the field test's ghosts and holes were tiles a weak fit had put in
 * the wrong place, and a refused tile is captured again while a misplaced one
 * ruins the canvas.
 *
 * ### Why only bursts are developed
 *
 * Developing a full-resolution tile costs around half a second. Ten frames a
 * second are registered and only the ones a node asks for are developed, so
 * a mosaic of nine tiles develops nine bursts and not the hundreds of frames
 * the user panned through.
 */
object MosaicCapture {

    /**
     * The thread the mosaic runs on: one of its own, a step below the
     * default priority.
     *
     * The guidance polls ten times a second and a node's burst is merged,
     * developed and composited across every core; both were running on the
     * shared default dispatcher at the same priority as the threads that draw
     * the viewfinder. Native threads inherit the nice value of the thread that
     * spawns them, so a runner one step less favourable puts every merge and
     * develop thread a step behind the preview and the camera's own threads,
     * and a tile takes a little longer while the viewfinder keeps its frames.
     * Not the background group -- that is confined to the little cores and a
     * merge there would take seconds.
     */
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
                r.run()
            }, "mosaic").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * The registration view's size: what a burst's reference frame and the
     * anchor are reduced to before they are registered. Registration at this
     * size costs about 25 ms (see `ZslStreamDeviceTest.trackingIsPricedAgainstAnIdleStream`).
     */
    const val TRACK_WIDTH = 320
    const val TRACK_HEIGHT = 240

    /** How often the attitude is read and the dots redrawn: the viewfinder's own rate. */
    const val POLL_MILLIS = 33L

    /** How often to look for the anchor frame before the mosaic has one. */
    private const val ANCHOR_POLL_MILLIS = 90L

    /** How long to wait for the camera's controls to settle before locking them. */
    private const val CONVERGENCE_TIMEOUT_MILLIS = 3_000L

    /**
     * Runs a guided mosaic until every node is captured, the user stops, or
     * nothing can be registered.
     *
     * [frames] is the burst length per node. [shouldContinue] is polled so the
     * user can stop early and keep what they have.
     */
    suspend fun run(
        context: Context,
        stream: ZslRawStream,
        session: MosaicSession,
        guide: MosaicGuide,
        settings: ManualSettings,
        caps: CameraCapabilities,
        frames: Int,
        lensProfile: LensProfile?,
        onView: (MosaicGuideView) -> Unit,
        onEvent: (MosaicEvent) -> Unit,
        shouldContinue: () -> Boolean,
        /**
         * The inertial sensors: the attitude for the guidance and the
         * gyroscope for the gate. The mode is not offered on a phone without
         * an attitude sensor, so a null here or a sensor that reports none is
         * a refusal, not a fallback.
         */
        motion: MotionSensor? = null,
    ): MosaicResult {
        val started = System.currentTimeMillis()
        if (motion == null || !motion.hasAttitude) {
            return MosaicResult(0, 0, 0f, session.plan.megapixels, null, 0L, "no attitude sensor for the guidance")
        }
        val profile = SensorProfile.from(stream.characteristics)
        val captureLabel = stream.lens.label
        val sensorOrientation = stream.characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION,
        ) ?: 90
        // Whether the camera stamps frames on the sensors' clock; where it
        // does, a frame is paired with the attitude it was exposed at.
        val realtimeStamps = stream.characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
        ) == android.hardware.camera2.CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

        // The sensors first, before anything is waited on: the rotation
        // vector's fusion starts from nowhere and takes most of a second to
        // converge, and an anchor taken before it has lays the canvas tens of
        // degrees from where the phone points. The screen usually has them
        // running already, from the moment the mode was armed; this is a
        // no-op then.
        motion.start()

        // The camera's controls have to have settled before they are pinned.
        // Locked on a fresh stream -- a lens the user just switched to -- they
        // froze wherever they were, which on the phone was unity white balance,
        // and the whole mosaic came out the green of unbalanced raw. Waiting is
        // a second or so; the alternative was the field test's result.
        val converged = awaitConvergence(stream, shouldContinue)
        if (!converged) Log.w(TAG, "camera controls did not converge; locking anyway")

        // Everything pinned for the duration. Auto-exposure drifting between
        // tiles leaves each a different brightness, and feathering turns a hard
        // seam into a soft gradient rather than removing it.
        stream.lockForSweep(settings, caps)

        // The stability gate's ceiling, from the exposure the lock holds and
        // the lens's focal length in pixels. Logged, because it is the number
        // that decides how still a hand has to be.
        val exposureNs = stream.latestExposure()?.second ?: 0L
        val pixelsPerRadian = pixelsPerRadian(stream)
        Log.i(TAG, "guidance focal length: %.0f px/rad (%s)".format(
            pixelsPerRadian,
            if (stream.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.getOrNull(0)?.let { it > 0f } == true) "intrinsic calibration"
            else "focal length over sensor pitch",
        ))
        val maxSpeed = StabilityGate.maxAngularSpeed(exposureNs, pixelsPerRadian)
        Log.i(
            TAG,
            "stability gate: exposure %.1f ms, %.0f px/rad, ceiling %.3f rad/s (%.1f deg/s), gyro %s".format(
                exposureNs / 1e6, pixelsPerRadian, maxSpeed, Math.toDegrees(maxSpeed.toDouble()),
                if (motion.isAvailable) "present" else "absent",
            ),
        )
        guide.configureGate(
            maxAngularSpeed = maxSpeed,
            settleMillis = MosaicGuide.settleFor(frames, stream.config.minFrameDurationNs),
            canvasPixelsPerRadian = pixelsPerRadian * session.plan.tileScale,
        )
        val imu = ImuGuidance(session.plan, pixelsPerRadian, sensorOrientation)

        var lastPollNs = motion.nowNs()
        var anchorAttempts = 0
        var settleAttempts = 0
        var lastGateLog = 0L

        // The registration view's scale: full frame to registration pixels.
        val scale = stream.config.width.toFloat() / TRACK_WIDTH

        try {
            while (shouldContinue() && !guide.complete) {
                if (!imu.anchored) {
                    // The colour and the exposure every tile will be developed
                    // with, and the origin. The gain is measured from the anchor
                    // frame the way a single capture would measure it, and then
                    // held: each tile developed with its own automatic gain is
                    // a mosaic of nine exposures. The frame's own result has to
                    // carry the white balance gains, or there is nothing to
                    // develop with; a frame without them is waited past.
                    val burst = stream.snapshot(1)
                    val result = burst.referenceResult
                    val color = ColorProfile.calibrated(stream.characteristics, result)
                    val buffer = burst.frames.buffer(0)
                    // And the attitude has to be reporting and settled, or
                    // there is nothing to anchor the directions to. Waited
                    // for up to a few seconds; a fusion that never settles is
                    // anchored anyway and said so, and the tiles' corrections
                    // then carry what they can.
                    val settled = motion.attitudeSettled() || settleAttempts++ >= MAX_SETTLE_WAIT_POLLS
                    val rotation = frameRotation(motion, result, realtimeStamps)
                    if (burst.count == 0 || buffer == null || result == null || rotation == null || !settled ||
                        (color.isNeutralGains && anchorAttempts++ < MAX_ANCHOR_WAIT_POLLS)
                    ) {
                        burst.close()
                        delay(ANCHOR_POLL_MILLIS)
                        continue
                    }
                    if (color.isNeutralGains) Log.w(TAG, "anchoring with unity white balance: the result carried no gains")
                    if (!motion.attitudeSettled()) Log.w(TAG, "anchoring on an attitude that has not settled")
                    val stride = burst.frames.rowStride
                    session.lockColour(color, ShadingMap.from(result))
                    NativeMerge.create(stream.config.width, stream.config.height, profile)?.use { m ->
                        m.setReference(buffer, stride)
                        val (merged, _) = m.finish()
                        session.lockExposure(m.autoExposure(merged, color), exposureProduct(result))
                    }
                    // The anchor's proxy is the frame the result describes,
                    // read through the same sampling as the tracking view.
                    val anchorTrack = ByteArray(TRACK_WIDTH * TRACK_HEIGHT)
                    RawRing.trackingLumaOf(buffer, stream.config.width, stream.config.height, stride,
                        anchorTrack, TRACK_WIDTH, TRACK_HEIGHT, profile)
                    burst.close()
                    session.anchor(Plane(TRACK_WIDTH, TRACK_HEIGHT, anchorTrack))
                    // The directions of the nodes are fixed from the attitude
                    // the anchor frame was exposed at.
                    imu.anchor(rotation)
                    Log.i(TAG, "anchored: gains %s, develop gain %.2f".format(
                        color.gains.joinToString(",") { "%.2f".format(it) }, session.developGain))
                    onEvent(MosaicEvent.Anchored)
                    onView(imuView(guide, session, imu, rotation, captureLabel, System.currentTimeMillis()))
                    lastPollNs = motion.nowNs()
                    delay(POLL_MILLIS)
                    continue
                }

                // The attitude now, and the gyroscope's peak since the last
                // poll: the fastest the phone turned in between.
                val rotation = motion.rotation()
                val nowNs = motion.nowNs()
                val peak = motion.peakSpeedSince(lastPollNs)
                lastPollNs = nowNs

                val now = System.currentTimeMillis()
                val reticle = rotation?.let { imu.reticleOnCanvas(it) }
                val decision = guide.observe(reticle?.first, reticle?.second, now, peak)
                onView(imuView(guide, session, imu, rotation, captureLabel, now))
                // The gate's readings, once a second: what the hold is waiting on.
                if (now - lastGateLog > 1000L) {
                    lastGateLog = now
                    Log.i(
                        TAG,
                        "gate: reticle %s, gyro peak %.4f rad/s of %.4f, %s%s, hold %.2f".format(
                            reticle?.let { "(%.0f, %.0f)".format(it.first, it.second) } ?: "off-canvas",
                            peak, guide.maxAngularSpeed,
                            if (guide.steady) "steady" else "unsteady",
                            guide.unsteadyReason?.let { " ($it)" } ?: "",
                            guide.holdProgress(now),
                        ),
                    )
                }

                when (decision) {
                    is MosaicGuide.Decision.TooFast -> onEvent(MosaicEvent.TooFast)
                    is MosaicGuide.Decision.Capture -> {
                        val outcome = runCatching {
                            captureNode(stream, session, profile, frames, lensProfile, scale, imu, motion, realtimeStamps) {
                                // The burst is in hand: the haptic can fire.
                                onEvent(MosaicEvent.Locked(decision.node))
                            }
                        }.onFailure { Log.w(TAG, "node ${decision.node.index} failed", it) }
                            .getOrDefault("capture failed")
                        if (outcome == null) {
                            guide.captured(decision.node)
                            onEvent(MosaicEvent.Captured(decision.node))
                            if (guide.complete) onEvent(MosaicEvent.Complete)
                        } else {
                            guide.rejected(decision.node, System.currentTimeMillis())
                            onEvent(MosaicEvent.Rejected(decision.node, outcome))
                        }
                        onView(imuView(guide, session, imu, motion.rotation(), captureLabel, System.currentTimeMillis()))
                        // The capture took time; the gyroscope's window restarts.
                        lastPollNs = motion.nowNs()
                    }
                    MosaicGuide.Decision.Idle -> Unit
                }
                delay(POLL_MILLIS)
            }
        } finally {
            // Whatever ended the loop: the stream is given back its exposure,
            // its white balance and its focus. The sensors are the caller's --
            // the screen keeps them running while the mode is armed, so the
            // next run anchors on an attitude that has long since settled.
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
                guide.complete -> "mosaic complete: ${progress.placed} of ${guide.total} tiles"
                else -> "partial mosaic: ${progress.placed} of ${guide.total} tiles"
            },
        )
    }

    /**
     * The screen's view of a running mosaic: every node's state and, from the
     * attitude, where each falls in the frame and where the canvas's outline
     * is. With no attitude yet, the states alone.
     */
    private fun imuView(
        guide: MosaicGuide,
        session: MosaicSession,
        imu: ImuGuidance,
        rotation: FloatArray?,
        captureLabel: String,
        nowMillis: Long,
    ): MosaicGuideView {
        val positions = rotation?.let { imu.nodesInFrame(it) }
        val nodes = guide.nodes.mapIndexed { i, node ->
            val p = positions?.getOrNull(i)
            NodeView(node = node, state = guide.stateOf(node), frameX = p?.first, frameY = p?.second)
        }
        return MosaicGuideView(
            nodes = nodes,
            captured = guide.captured,
            total = guide.total,
            located = rotation != null,
            coverage = session.progress.coverage,
            canvasCorners = rotation?.let { imu.cornersInFrame(it) },
            holdProgress = guide.holdProgress(nowMillis),
            steady = guide.steady,
            unsteady = if (guide.lockingNodeOrNull != null) guide.unsteadyReason else null,
            captureLensLabel = captureLabel,
            anchored = true,
        )
    }

    /**
     * The attitude the phone had when [result]'s frame was exposed, where the
     * camera stamps frames on the sensors' clock; the current attitude where
     * it does not. Null before the attitude sensor has reported.
     */
    private fun frameRotation(
        motion: MotionSensor,
        result: android.hardware.camera2.TotalCaptureResult?,
        realtimeStamps: Boolean,
    ): FloatArray? {
        val stamp = result?.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP)
        val exposure = result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        // The stamp is the start of exposure; the middle is the attitude the
        // frame's centre of mass was taken at.
        return if (realtimeStamps && stamp != null) motion.rotationAt(stamp + exposure / 2) else motion.rotation()
    }

    /**
     * The screen's view of the mosaic: every node's state and, when the frame
     * registered, where each falls in it, and the canvas's outline.
     */
    fun viewOf(
        guide: MosaicGuide,
        plan: MosaicPlan,
        transform: Homography?,
        captureLabel: String,
        nowMillis: Long,
        coverage: Float = 0f,
        anchored: Boolean,
    ): MosaicGuideView {
        val inverse = transform?.invert()
        val w = plan.tileWidthPx.toFloat()
        val h = plan.tileHeightPx.toFloat()
        // Canvas to frame fractions: through the frame's registration when
        // there is one, else as the anchor would place the frame -- centred.
        fun toFrame(cx: Float, cy: Float): Pair<Float, Float>? {
            if (w <= 0f || h <= 0f) return null
            if (inverse != null) {
                val p = inverse.apply(cx, cy)
                if (p[0].isNaN() || p[1].isNaN()) return null
                return p[0] / w to p[1] / h
            }
            if (anchored) return null
            val originX = (plan.canvasWidth - plan.placedTileWidth) / 2f
            val originY = (plan.canvasHeight - plan.placedTileHeight) / 2f
            return (cx - originX) / plan.placedTileWidth to (cy - originY) / plan.placedTileHeight
        }
        val nodes = guide.nodes.map { node ->
            val p = toFrame(node.canvasX, node.canvasY)
            NodeView(node = node, state = guide.stateOf(node), frameX = p?.first, frameY = p?.second)
        }
        val cw = plan.canvasWidth.toFloat()
        val ch = plan.canvasHeight.toFloat()
        val corners = listOf(0f to 0f, cw to 0f, cw to ch, 0f to ch).map { (x, y) -> toFrame(x, y) }
        return MosaicGuideView(
            nodes = nodes,
            captured = guide.captured,
            total = guide.total,
            located = transform != null,
            coverage = coverage,
            canvasCorners = if (corners.all { it != null }) corners.map { it!! } else null,
            holdProgress = guide.holdProgress(nowMillis),
            steady = guide.steady,
            unsteady = if (guide.lockingNodeOrNull != null) guide.unsteadyReason else null,
            captureLensLabel = captureLabel,
            anchored = anchored,
        )
    }

    /** The view before the mosaic begins: the outline and the nodes as the anchor would place them. */
    fun armedView(plan: MosaicPlan, captureLabel: String): MosaicGuideView =
        viewOf(MosaicGuide(plan), plan, null, captureLabel, System.currentTimeMillis(), anchored = false)

    /**
     * Waits for the camera's controls to converge, up to a bound. True when
     * they did; false when the wait ran out or the user stopped.
     */
    private suspend fun awaitConvergence(stream: ZslRawStream, shouldContinue: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + CONVERGENCE_TIMEOUT_MILLIS
        while (shouldContinue() && System.currentTimeMillis() < deadline) {
            if (stream.controlsConverged() == true) return true
            delay(50)
        }
        return stream.controlsConverged() == true
    }

    /**
     * The lens's focal length in the frame's pixels, from what the camera
     * publishes about itself, most calibrated source first: the intrinsic
     * calibration's `f_x`, in active-array pixels, scaled to the frame; then
     * the focal length in millimetres over the sensor's physical pitch; then
     * the 35mm equivalent. This is the `f` of `f · tan(Δyaw)`: one pixel of
     * it wrong is one pixel the dot lands off the doorknob at a degree.
     */
    internal fun pixelsPerRadian(stream: ZslRawStream): Float {
        val c = stream.characteristics
        val array = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val intrinsics = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (intrinsics != null && intrinsics.size >= 2 && array != null && array.width() > 0 && intrinsics[0] > 0f) {
            // [f_x, f_y, c_x, c_y, s] in active-array pixels; the frame is
            // the active array at the stream's size.
            return intrinsics[0] * (stream.config.width.toFloat() / array.width())
        }
        val focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val physical = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        // The physical size is of the whole sensor and the frame is the active
        // array; the ratio scales the width the frame actually spans.
        val fullPx = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width ?: array?.width() ?: 0
        val frameMm = if (physical != null && fullPx > 0 && array != null) physical.width * (array.width().toFloat() / fullPx) else 0f
        val fromPhysical = StabilityGate.pixelsPerRadian(focal, frameMm, stream.config.width)
        return if (fromPhysical > 0f) fromPhysical
        else StabilityGate.pixelsPerRadianFromEquivalent(stream.lens.equivalent35mm, stream.config.width)
    }

    /** Polls to wait for a frame whose result carries white balance gains before anchoring without. */
    private const val MAX_ANCHOR_WAIT_POLLS = 30

    /** Polls to wait for the attitude to settle before anchoring on it as it is: about four seconds. */
    private const val MAX_SETTLE_WAIT_POLLS = 45

    /**
     * Takes the burst for a node: the ring's newest [frames], merged, developed
     * with the mosaic's locked colour, corrected for the lens, and composited at
     * the place the burst's reference frame registered to -- if that place is
     * where the attitude says the phone was pointing, to within
     * [MAX_ATTITUDE_DISAGREEMENT] of a tile, and the fit is a tile's size.
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
        imu: ImuGuidance,
        motion: MotionSensor,
        realtimeStamps: Boolean,
        onSnapshotted: () -> Unit,
    ): String? {
        val burst = stream.snapshot(frames)
        if (burst.count == 0) {
            burst.close()
            return "ring empty"
        }
        // The frames a haptic could have blurred are locked in the ring now.
        onSnapshotted()
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

            // Where this burst is, from its own reference frame, read through
            // the same sampling as the tracking view so it registers at the
            // tracking view's scale. The node said where to point; the frame
            // says where the tile is. The slots go back to the ring before
            // the registration, which needs nothing from them.
            val smallData = ByteArray(TRACK_WIDTH * TRACK_HEIGHT)
            RawRing.trackingLumaOf(refBuffer, stream.config.width, stream.config.height, stride,
                smallData, TRACK_WIDTH, TRACK_HEIGHT, profile)
            val small = Plane(TRACK_WIDTH, TRACK_HEIGHT, smallData)
            burst.close()
            val located = session.locate(small, proxyScale)
            if (located == null) return@use "could not register the burst"

            // The registration has to agree with the attitude. Where the
            // attitude at the reference frame's exposure put the reticle,
            // against where the frame's pixels put it: a residual of a few
            // percent of a tile is drift and timing and is fine; a residual of
            // a quarter of a tile is a fit onto the wrong features, and the
            // tile it would place is the field test's ghost. And the fit has
            // to be a tile's size: a homography that shrinks or stretches the
            // frame by more than a sixth has fitted noise.
            val w = stream.config.width.toFloat()
            val h = stream.config.height.toFloat()
            val actual = located.transform.apply(w / 2f, h / 2f)
            val predicted = frameRotation(motion, referenceResult, realtimeStamps)?.let { imu.reticleOnCanvas(it) }
            val plan = session.plan
            if (predicted != null) {
                val residual = kotlin.math.hypot(actual[0] - predicted.first, actual[1] - predicted.second)
                Log.i(TAG, "tile registered %.0f px from where the attitude put it (%d inliers)".format(residual, located.inliers))
                if (residual > plan.placedTileWidth * MAX_ATTITUDE_DISAGREEMENT) {
                    return@use "registration %.0f px from where the phone pointed".format(residual)
                }
            }
            val fittedWidth = kotlin.math.hypot(
                located.transform.apply(w, h / 2f)[0] - located.transform.apply(0f, h / 2f)[0],
                located.transform.apply(w, h / 2f)[1] - located.transform.apply(0f, h / 2f)[1],
            )
            val scaleError = fittedWidth / plan.placedTileWidth
            if (scaleError < 1f - MAX_SCALE_ERROR || scaleError > 1f + MAX_SCALE_ERROR) {
                return@use "registration fitted the tile at %.2fx its size".format(scaleError)
            }

            val (merged, _) = m.finish()
            val color = session.color ?: ColorProfile.NEUTRAL
            // The anchor's gain, scaled by how this burst's exposure differed
            // from the anchor's -- which under the mosaic's lock should be not
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

    /**
     * How far a registration may put a tile from where the attitude says the
     * phone was pointing, as a fraction of a placed tile's width. The
     * attitude's own error over a capture -- drift, the frame's timing, the
     * anchor -- is a few percent; a quarter is a wrong fit.
     */
    const val MAX_ATTITUDE_DISAGREEMENT = 0.25f

    /** How far a registration's fitted tile width may differ from a tile's. */
    const val MAX_SCALE_ERROR = 0.17f

    /** ISO times exposure, the sensor's own measure of how much light a frame gathered. */
    private fun exposureProduct(result: android.hardware.camera2.TotalCaptureResult?): Double {
        val iso = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return 0.0
        val ns = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return 0.0
        return iso.toDouble() * ns.toDouble()
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
}
