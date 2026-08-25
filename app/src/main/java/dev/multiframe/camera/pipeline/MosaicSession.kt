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
            placed = assembler.placed.size,
            planned = plan.tileCount,
            coverage = assembler.coverage,
            rejected = rejected,
            lastReason = lastReason,
        )

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
        val touched = canvas.addTile(tile, placement.transform, feather = FEATHER)
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
        if (closed || assembler.placed.isEmpty()) return null

        val bitmap = try {
            Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            // The canvas fits in native memory but its rendered form may not
            // fit anywhere. Tiled writing is the answer and is not built yet,
            // so this fails honestly rather than taking the process down.
            Log.e(TAG, "mosaic too large to render in one piece", e)
            return null
        }

        if (!canvas.renderInto(bitmap)) {
            bitmap.recycle()
            return null
        }
        val uri = ImageSaver.saveJpeg(context, bitmap, name)
        bitmap.recycle()
        Log.i(
            TAG,
            "mosaic saved: ${assembler.placed.size} tiles, %.0f MP, coverage %.2f".format(
                canvas.megapixels, assembler.coverage,
            ),
        )
        return if (uri != null) name else null
    }

    override fun close() {
        if (closed) return
        closed = true
        canvas.close()
    }

    companion object {
        /**
         * Blend width at tile edges.
         *
         * Wide enough to hide the disagreement between two frames, narrow
         * enough not to smear detail across the overlap.
         */
        const val FEATHER = 96f

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
