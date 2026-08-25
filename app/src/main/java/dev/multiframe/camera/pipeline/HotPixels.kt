package dev.multiframe.camera.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Suppresses defective sensor sites.
 *
 * ### Why the merge cannot deal with this
 *
 * Every sensor has sites that read wrong: stuck bright, stuck dark, or simply
 * far more leaky than their neighbours. On a fifty-megapixel sensor there are
 * typically hundreds, and they are not random -- a given site is wrong in the
 * same way in every frame.
 *
 * That is exactly why burst merging does not help. Averaging suppresses anything
 * that varies between frames, which is what noise is; a defect that is identical
 * in all of them survives untouched, and averaging away the surrounding noise
 * only makes it stand out more clearly. The cleaner the merge, the more obvious
 * the dots.
 *
 * ### Same-colour neighbours
 *
 * Comparison is against the four sites two pixels away, which on a Bayer grid
 * are the nearest sites of the same colour. Comparing against immediate
 * neighbours would compare red against green and flag every pixel in the image.
 *
 * ### The conservative rule
 *
 * A pixel is only replaced when it lies outside the range of *all four* of those
 * neighbours by a margin. A genuine highlight -- a specular glint, a distant
 * streetlight -- is usually shared with at least one neighbour, so it survives.
 * That deliberately leaves some defects uncorrected: taking a real star out of
 * an astrophotograph would be a worse failure than leaving a dot in.
 */
object HotPixels {

    /**
     * How far beyond its neighbours a site must sit before it is called broken,
     * as a fraction of full scale.
     */
    const val DEFAULT_THRESHOLD = 0.10f

    /**
     * Replaces outliers in a normalised CFA plane, in place.
     *
     * [plane] holds one value per sensor site, in the sensor's own colour filter
     * layout. Returns how many sites were replaced, which is worth logging: a
     * sudden change in that count means something is wrong with the sensor or
     * with the threshold.
     */
    fun suppress(
        plane: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Int {
        if (width < 5 || height < 5) return 0
        require(plane.size >= width * height) { "plane is too small" }

        var replaced = 0
        // Two-pixel border left alone: the same-colour neighbours do not exist
        // there, and inventing them would corrupt the edge of every frame.
        for (y in 2 until height - 2) {
            val row = y * width
            for (x in 2 until width - 2) {
                val here = plane[row + x]

                // The four nearest sites of the same colour.
                val a = plane[row + x - 2]
                val b = plane[row + x + 2]
                val c = plane[row - 2 * width + x]
                val d = plane[row + 2 * width + x]

                val highest = max(max(a, b), max(c, d))
                val lowest = min(min(a, b), min(c, d))

                if (here > highest + threshold) {
                    plane[row + x] = highest
                    replaced++
                } else if (here < lowest - threshold) {
                    plane[row + x] = lowest
                    replaced++
                }
            }
        }
        return replaced
    }

    /**
     * The same correction applied to a raw CFA frame in place.
     *
     * The Kotlin developer has no normalised plane to work on -- building one
     * would be a 50 MB allocation on a heap capped at 256 MB, which is the
     * constraint the whole pipeline is shaped around -- so it corrects the CFA
     * itself. Black level is per CFA site and same-colour neighbours share it,
     * so comparing raw codes is equivalent to comparing black-subtracted ones,
     * and the threshold simply scales by the sensor's range.
     */
    fun suppressInFrame(
        frame: BayerFrame,
        sensor: SensorProfile,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Int {
        val width = frame.width
        val height = frame.height
        if (width < 5 || height < 5 || threshold <= 0f) return 0

        val data = frame.data
        val margin = (threshold * sensor.range).toInt()
        var replaced = 0

        // Read from a copy so a corrected site never becomes a neighbour's
        // reference, which would let one defect propagate along a row.
        val source = data.copyOf()
        for (y in 2 until height - 2) {
            val row = y * width
            for (x in 2 until width - 2) {
                val here = source[row + x].toInt() and 0xFFFF
                val a = source[row + x - 2].toInt() and 0xFFFF
                val b = source[row + x + 2].toInt() and 0xFFFF
                val c = source[row - 2 * width + x].toInt() and 0xFFFF
                val d = source[row + 2 * width + x].toInt() and 0xFFFF

                val highest = max(max(a, b), max(c, d))
                val lowest = min(min(a, b), min(c, d))

                if (here > highest + margin) {
                    data[row + x] = highest.toShort()
                    replaced++
                } else if (here < lowest - margin) {
                    data[row + x] = lowest.toShort()
                    replaced++
                }
            }
        }
        return replaced
    }

    /**
     * Proportion of sites replaced, for reporting.
     *
     * A healthy sensor sits far below a tenth of a percent. Well above that
     * means the threshold is wrong rather than the sensor being unusual, since
     * a genuinely defective sensor in those numbers would have failed testing.
     */
    fun defectRate(replaced: Int, width: Int, height: Int): Float {
        val total = width.toLong() * height
        return if (total == 0L) 0f else replaced.toFloat() / total
    }
}
