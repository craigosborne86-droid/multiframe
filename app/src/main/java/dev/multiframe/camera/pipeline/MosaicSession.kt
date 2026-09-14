package dev.multiframe.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

private const val TAG = "Multiframe"

/** How a sweep is going, for the capture guide to draw. */
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
         * Coverage at which a sweep is called finished.
         *
         * Not 1.0: the canvas corners are reached only by tiles that hang off
         * the edge, and holding out for them means a sweep that never ends.
         */
        const val COMPLETE_COVERAGE = 0.90f
    }
}

/**
 * One telephoto sweep, from the first frame to the saved image.
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
     * differed. The sweep locks exposure for the same reason; this locks
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
     * The stream locks the sensor's exposure for the sweep, so the frames
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

    // --- the guided sweep --------------------------------------------------

    /** Makes [proxy] the origin. See [MosaicAssembler.anchor]. */
    fun anchor(proxy: Plane): Placement = assembler.anchor(proxy)

    /** Where a frame is, or null when nothing placed can see it. Pure. */
    fun locate(proxy: Plane, proxyScale: Float): MosaicAssembler.Located? =
        if (closed) null else assembler.locate(proxy, proxyScale)

    /**
     * Records a captured tile at its located transform and draws it.
     *
     * Returns the placement, or null if the canvas would not take it -- in
     * which case the node stays pending and the user is asked again.
     */
    fun place(tile: Bitmap, proxy: Plane, located: MosaicAssembler.Located): Placement? {
        if (closed) return null
        val placement = assembler.place(proxy, located.transform, located.inliers, located.inlierRatio)
        if (placement == null) {
            rejected++
            lastReason = RejectionReason.OUT_OF_BOUNDS
            return null
        }
        return if (composite(tile, placement)) placement else null
    }

    /** Coverage as a grid, for drawing the sweep guide. */
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
     * around half a second, and most frames a sweep produces are rejected.
     * Paying that price before finding out would make a sweep unusable.
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
     * one. Locking exposure across the sweep is what would earn them.
     */
    private fun describe() = ExifWriter.from(
        result = null,
        characteristics = null,
        lens = plan.targetLens,
        frames = assembler.composited.size,
        kind = CaptureKind.MOSAIC,
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
         * Starts a sweep, or returns null when the canvas cannot be allocated.
         *
         * Null is recoverable: the caller can re-plan against a smaller cap.
         */
        fun start(plan: MosaicPlan, tileWidth: Int, tileHeight: Int): MosaicSession? {
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
            )
        }
    }
}
