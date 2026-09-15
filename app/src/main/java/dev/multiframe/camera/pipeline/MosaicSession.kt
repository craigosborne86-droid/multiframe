package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

private const val TAG = "Multiframe"

/** How a mosaic is going, for the capture guide to draw. */
data class MosaicProgress(
    val placed: Int,
    val planned: Int,
    val coverage: Float,
    val rejected: Int,
    val lastReason: RejectionReason?,
) {
    val complete: Boolean get() = coverage >= COMPLETE_COVERAGE

    companion object {
        /**
         * Coverage at which a mosaic is called finished.
         *
         * Not 1.0: the canvas corners are reached only by tiles that hang off
         * the edge, and holding out for them means a mosaic that never ends.
         */
        const val COMPLETE_COVERAGE = 0.90f
    }
}

/**
 * One telephoto mosaic, from the first frame to the saved image.
 *
 * Holds the three pieces together: the assembler decides where a frame belongs,
 * the canvas is what it is drawn onto, and the plan says how much there is to
 * cover. Tiles are composited as they arrive rather than collected and
 * assembled at the end, so memory holds one frame plus the canvas instead of
 * forty-nine frames plus the canvas -- which at full resolution is the
 * difference between 300 MB and 1.5 GB.
 */
class MosaicSession private constructor(
    val plan: MosaicPlan,
    private val canvas: MosaicCanvas,
    private val assembler: MosaicAssembler,
    /**
     * The clockwise turn the canvas needs to be upright, for the file's
     * orientation tag. The tiles are developed in the sensor's orientation
     * and composited as they are -- turning a canvas this size would be a
     * second copy of it -- so the first field test's mosaic, shot upright,
     * arrived in the gallery on its side.
     */
    private val rotationDegrees: Int,
) : AutoCloseable {

    private var rejected = 0
    private var lastReason: RejectionReason? = null
    private var closed = false

    val progress: MosaicProgress
        get() = MosaicProgress(
            placed = assembler.composited.size,
            planned = plan.tileCount,
            coverage = assembler.coverage,
            rejected = rejected,
            lastReason = lastReason,
        )

    /**
     * The colour every tile is developed with, fixed by the first.
     *
     * Read from each burst's own result, the white balance drifted between
     * tiles as the auto algorithm saw more or less sky, and the seams were
     * colour seams that no blend could hide because the tiles genuinely
     * differed. The mosaic locks exposure for the same reason; this locks
     * colour and shading beside it.
     */
    var color: ColorProfile? = null
        private set
    var shading: ShadingMap? = null
        private set

    fun lockColour(color: ColorProfile, shading: ShadingMap?) {
        if (this.color == null) {
            this.color = color
            this.shading = shading
        }
    }

    /**
     * The develop gain every tile is rendered with, and the exposure it was
     * measured at, both fixed by the anchor.
     *
     * The stream locks the sensor's exposure for the mosaic, so the frames
     * should agree already; the normalisation is what holds if they do not.
     * A tile whose ISO times exposure came out at some other product than
     * the anchor's is scaled by the ratio, so the developed tiles sit on one
     * scale whatever the sensor did between them.
     */
    var developGain: Float = 0f
        private set
    private var anchorExposureProduct = 0.0

    fun lockExposure(gain: Float, isoTimesExposureNs: Double) {
        if (developGain <= 0f && gain > 0f) {
            developGain = gain
            anchorExposureProduct = isoTimesExposureNs
        }
    }

    /** The gain for a tile shot at [isoTimesExposureNs], relative to the anchor's. */
    fun gainFor(isoTimesExposureNs: Double): Float =
        normalisedGain(developGain, anchorExposureProduct, isoTimesExposureNs)

    /**
     * Tells the canvas the ISO the mosaic's lock pinned, so the blend can
     * set its noise floor for it: see [MosaicCanvas.detailAttenuationFor].
     * Once, with the lock; every tile is shot at the same ISO.
     */
    fun lockNoise(iso: Int?) {
        if (closed) return
        val attenuation = MosaicCanvas.detailAttenuationFor(iso)
        canvas.setNoiseFloor(attenuation)
        if (attenuation > 0f) {
            Log.i(TAG, "noise floor: ISO $iso, %.0f%% of the detail band in shadowed overlaps".format(attenuation * 100))
        }
    }

    // --- the guided mosaic --------------------------------------------------

    /** Makes [proxy] the origin. See [MosaicAssembler.anchor]. */
    fun anchor(proxy: Plane): Placement = assembler.anchor(proxy)

    /**
     * Where a frame is, or null when nothing placed can see it. Pure.
     *
     * [maxCandidates] bounds how many placed frames it is registered against:
     * the guidance tracking ten times a second wants the newest and its
     * nearest neighbour, a tile being placed wants every neighbour it has.
     */
    fun locate(proxy: Plane, proxyScale: Float, maxCandidates: Int = Int.MAX_VALUE): MosaicAssembler.Located? =
        if (closed) null else assembler.locate(proxy, proxyScale, maxCandidates)

    /** Why the last [locate] failed, for the log. */
    val lastLocateFailure: String? get() = assembler.lastFailure

    /**
     * Records a captured tile at its located transform and draws it.
     *
     * Returns the placement, or null if the canvas would not take it -- in
     * which case the node stays pending and the user is asked again.
     */
    fun place(tile: Bitmap, proxy: Plane, located: MosaicAssembler.Located): Placement? =
        place(tile, proxy, located.transform, located.inliers, located.inlierRatio)

    /**
     * Records a captured tile at [transform] -- from its registration, or
     * from the attitude where the registration found nothing -- and draws it.
     * [inliers] is zero for an attitude placement, which is what later
     * registrations read to weigh it.
     */
    fun place(tile: Bitmap, proxy: Plane, transform: Homography, inliers: Int, inlierRatio: Float): Placement? {
        if (closed) return null
        val placement = assembler.place(proxy, transform, inliers, inlierRatio)
        if (placement == null) {
            rejected++
            lastReason = RejectionReason.OUT_OF_BOUNDS
            return null
        }
        return if (composite(tile, placement)) placement else null
    }

    /** Coverage as a grid, for drawing the mosaic guide. */
    fun coverageGrid(): Array<BooleanArray> = assembler.coverageGrid()

    val canvasWidth: Int get() = canvas.width
    val canvasHeight: Int get() = canvas.height

    /** Where a tile ended up, for anyone checking the assembly. */
    val placements: List<Placement> get() = assembler.placed

    /** Renders the mosaic so far into a bitmap of exactly the canvas size. */
    fun renderInto(bitmap: Bitmap): Boolean = !closed && canvas.renderInto(bitmap)

    /**
     * Offers one developed frame with its luma proxy.
     *
     * The proxy is what registration runs on; the full frame is what gets
     * composited. Both describe the same capture.
     */
    fun offer(tile: Bitmap, proxy: Plane, proxyScale: Float): OfferResult {
        val decision = evaluate(proxy, proxyScale)
        if (decision is OfferResult.Placed && !composite(tile, decision.placement)) {
            return OfferResult.Rejected(RejectionReason.OUT_OF_BOUNDS)
        }
        return decision
    }

    /**
     * Decides where a frame belongs without drawing it.
     *
     * Separate from compositing because developing a full-resolution tile costs
     * around half a second, and most frames a mosaic produces are rejected.
     * Paying that price before finding out would make a mosaic unusable.
     */
    fun evaluate(proxy: Plane, proxyScale: Float): OfferResult {
        if (closed) return OfferResult.Rejected(RejectionReason.OUT_OF_BOUNDS)

        val result = assembler.offer(proxy, proxyScale)
        if (result is OfferResult.Rejected) {
            rejected++
            lastReason = result.reason
        }
        return result
    }

    /** Draws an accepted tile onto the canvas. */
    fun composite(tile: Bitmap, placement: Placement): Boolean {
        if (closed) return false
        val touched = canvas.addTile(tile, placement.transform, FEATHERS)
        if (touched == 0L) {
            // The assembler thought it landed on the canvas and the canvas
            // disagreed. Reported rather than silently counted as placed, since
            // coverage would then overstate itself.
            rejected++
            lastReason = RejectionReason.OUT_OF_BOUNDS
            return false
        }
        Log.i(
            TAG,
            "mosaic tile ${placement.index}: ${placement.inliers} inliers, " +
                "coverage %.2f".format(assembler.coverage),
        )
        return true
    }

    /**
     * Renders and saves what has been swept so far.
     *
     * Returns null when nothing was placed. A partial sweep still saves: the
     * uncovered canvas is transparent, so the result is honestly the shape of
     * what was actually shot rather than a rectangle with black bars.
     */
    fun save(context: Context, name: String): String? {
        if (closed || assembler.composited.isEmpty()) return null

        // Not through a Bitmap. The canvas is 610 MB at the default cap and its
        // rendered form would be another 320 MB, which is why this used to
        // carry an OutOfMemoryError branch that returned no photograph at all.
        // The canvas compresses out of its own pages instead.
        var written: MosaicCanvas.Written? = null
        val uri = ImageSaver.saveJpegFrom(context, name, metadata = describe()) { fd ->
            written = canvas.compressTo(fd)
            written != null
        }

        val size = written
        if (uri == null || size == null) {
            Log.e(TAG, "mosaic could not be written")
            return null
        }
        Log.i(
            TAG,
            ("mosaic saved: ${assembler.composited.size} tiles, %.0f MP written " +
                "from a %.0f MP canvas, coverage %.2f").format(
                size.megapixels, canvas.megapixels, assembler.coverage,
            ),
        )
        return name
    }

    /**
     * What the file says about itself.
     *
     * The lens recorded is the *target* framing rather than the lens that shot
     * the tiles: the photograph has a 24mm field of view, whatever swept it.
     *
     * Exposure and ISO are left out entirely. The tiles were taken under
     * whatever the meter decided at the time, so there is no single honest
     * number to record -- and this module's rule is no tag rather than a wrong
     * one. Locking exposure across the mosaic is what would earn them.
     */
    private fun describe() = ExifWriter.from(
        result = null,
        characteristics = null,
        lens = plan.targetLens,
        frames = assembler.composited.size,
        kind = CaptureKind.MOSAIC,
        rotationDegrees = rotationDegrees,
    )

    override fun close() {
        if (closed) return
        closed = true
        canvas.close()
    }

    companion object {
        /**
         * The develop gain for a tile, from the anchor's gain and the two
         * exposures. Pure, so the arithmetic is checkable on the JVM.
         *
         * No anchor gain yet means the develop's own automatic choice; a
         * missing exposure on either side means the anchor's gain unchanged,
         * since a ratio with nothing on one side is not a correction.
         */
        fun normalisedGain(anchorGain: Float, anchorProduct: Double, tileProduct: Double): Float {
            if (anchorGain <= 0f) return DevelopParams.AUTO_EXPOSURE
            if (anchorProduct <= 0.0 || tileProduct <= 0.0) return anchorGain
            return (anchorGain * (anchorProduct / tileProduct)).toFloat()
        }

        /**
         * Blend widths at tile edges, one per frequency band.
         *
         * There used to be one, at 96 pixels, and it was the compromise a
         * single feather always is: wide enough to hide a step in exposure and
         * it doubled fine detail across the overlap; narrow enough to keep the
         * detail and the step showed. The bands let each frequency have the
         * width it needs. See [MosaicCanvas.BLEND_FEATHERS].
         */
        val FEATHERS: FloatArray get() = MosaicCanvas.BLEND_FEATHERS

        /**
         * Starts a mosaic, or returns null when the canvas cannot be allocated.
         *
         * Null is recoverable: the caller can re-plan against a smaller cap.
         */
        fun start(plan: MosaicPlan, tileWidth: Int, tileHeight: Int, rotationDegrees: Int = 0): MosaicSession? {
            val canvas = MosaicCanvas.create(plan) ?: return null
            Log.i(TAG, "mosaic session: $plan")
            return MosaicSession(
                plan,
                canvas,
                MosaicAssembler(
                    canvasWidth = plan.canvasWidth,
                    canvasHeight = plan.canvasHeight,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                    tileScale = plan.tileScale,
                ),
                rotationDegrees,
            )
        }
    }
}
