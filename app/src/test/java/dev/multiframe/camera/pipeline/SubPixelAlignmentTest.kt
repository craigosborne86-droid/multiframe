package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The sub-pixel refinement: the fit itself, and whether it finds a shift the
 * integer search cannot express.
 *
 * The second half is the one that matters. An integer search on a scene moved
 * by 2.4 pixels lands on 2 and stops; the refinement should read the residual
 * 0.4 out of the cost curve's asymmetry. That is what sub-pixel accumulation
 * needs from it, and it is the property a parabola fit can get wrong quietly --
 * by reading the asymmetry with the wrong sign, which the last test pins.
 */
class SubPixelAlignmentTest {

    private val width = 256
    private val height = 192
    private val tilesX = 8
    private val tilesY = 6

    // --- the fit ------------------------------------------------------------

    @Test
    fun aSymmetricMinimumSitsExactlyOnTheInteger() {
        assertThat(Aligner.subPixelOffset(100, 50, 100)).isEqualTo(0f)
    }

    /**
     * Lower cost to the right means the true minimum is to the right. The
     * vertex of the parabola through (-1, 100), (0, 50), (+1, 60) is at
     * (100 - 60) / (2 * (100 - 100 + 60)) = 40 / 120 = 1/3.
     */
    @Test
    fun anAsymmetricMinimumLeansTowardTheLowerNeighbour() {
        val right = Aligner.subPixelOffset(100, 50, 60)
        assertThat(right).isWithin(1e-6f).of(1f / 3f)

        val left = Aligner.subPixelOffset(60, 50, 100)
        assertThat(left).isWithin(1e-6f).of(-1f / 3f)
    }

    /** A flat curve does not describe a minimum; the fit says nothing. */
    @Test
    fun aFlatCostCurveIsNotRefined() {
        assertThat(Aligner.subPixelOffset(50, 50, 50)).isEqualTo(0f)
    }

    /** Neighbours *lower* than the centre mean the search did not find a minimum. */
    @Test
    fun anInvertedCurvatureIsNotRefined() {
        assertThat(Aligner.subPixelOffset(40, 50, 40)).isEqualTo(0f)
        assertThat(Aligner.subPixelOffset(30, 50, 45)).isEqualTo(0f)
    }

    /**
     * A vertex past half a pixel means the integer search was off by at least
     * one, and a parabola through these three points cannot fix that. Declining
     * is more honest than clamping to +-0.5, which would assert a precision the
     * fit does not have.
     */
    @Test
    fun aVertexBeyondHalfAPixelIsDeclined() {
        // (100 - 51) / (2 * (100 - 100 + 51)) = 49 / 102 = 0.48: just inside.
        assertThat(Aligner.subPixelOffset(100, 50, 51)).isWithin(1e-6f).of(49f / 102f)
        // (100 - 50) / (2 * (100 - 100 + 50)) = 50 / 100 = 0.5: on the edge, kept.
        assertThat(Aligner.subPixelOffset(100, 50, 50)).isWithin(1e-6f).of(0.5f)
        // (1000 - 51) / (2 * (1000 - 100 + 51)) = 949 / 1902 = 0.499: inside.
        // (100 - 49) / (2 * (100 - 100 + 49)) = 51 / 98 = 0.52: outside, declined.
        assertThat(Aligner.subPixelOffset(100, 50, 49)).isEqualTo(0f)
    }

    /** An early-exit cost reads as the sentinel and is not a measurement. */
    @Test
    fun theSentinelIsNotAMeasurement() {
        assertThat(Aligner.subPixelOffset(Long.MAX_VALUE, 50, 60)).isEqualTo(0f)
        assertThat(Aligner.subPixelOffset(100, 50, Long.MAX_VALUE)).isEqualTo(0f)
        assertThat(Aligner.subPixelOffset(100, Long.MAX_VALUE, 60)).isEqualTo(0f)
    }

    /**
     * Costs are sums over a tile and run to millions; the fit works on their
     * differences. Checked at realistic magnitude so a precision loss in the
     * arithmetic would show.
     */
    @Test
    fun theFitHoldsAtTheMagnitudeRealCostsReach() {
        val base = 4_000_000L
        val v = Aligner.subPixelOffset(base + 900, base, base + 300)
        // (900 - 300) / (2 * (900 + 300)) = 600 / 2400 = 0.25
        assertThat(v).isWithin(1e-6f).of(0.25f)
    }

    // --- on an image --------------------------------------------------------

    /**
     * A shifted plane at a fractional offset, by linear interpolation between
     * neighbouring source pixels. This is the diversity a burst has and an
     * integer shift does not: the scene under a sensor moves by fractions of a
     * pixel between frames, and each frame samples it on an offset grid.
     */
    private fun shiftFractional(src: ByteArray, w: Int, h: Int, sx: Float, sy: Float): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = (x - sx).coerceIn(0f, (w - 1).toFloat())
                val fy = (y - sy).coerceIn(0f, (h - 1).toFloat())
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = minOf(x0 + 1, w - 1)
                val y1 = minOf(y0 + 1, h - 1)
                val tx = fx - x0
                val ty = fy - y0
                fun at(xx: Int, yy: Int) = (src[yy * w + xx].toInt() and 0xFF).toFloat()
                val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx
                val bot = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx
                out[y * w + x] = kotlin.math.round(top + (bot - top) * ty).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    private fun interiorTiles(field: AlignmentField): List<Int> {
        val out = ArrayList<Int>()
        for (ty in 1 until tilesY - 1) for (tx in 1 until tilesX - 1) out.add(ty * tilesX + tx)
        return out
    }

    /** An integer shift has nothing fractional to find. */
    @Test
    fun anIntegerShiftRefinesToNearlyZero() {
        val ref = TestImages.texture(width, height)
        val alt = TestImages.shift(ref, width, height, 3, -2)
        val field = Aligner.align(
            Aligner.buildPyramid(Plane(width, height, ref)),
            Aligner.buildPyramid(Plane(width, height, alt)),
            tilesX, tilesY,
        )
        for (i in interiorTiles(field)) {
            assertThat(field.dx[i]).isEqualTo(3)
            assertThat(field.dy[i]).isEqualTo(-2)
            // Not exactly zero -- the texture is not symmetric about every
            // pixel -- but small. A large residual on an integer shift would
            // mean the fit is reading noise.
            assertThat(kotlin.math.abs(field.fracDx[i])).isLessThan(0.15f)
            assertThat(kotlin.math.abs(field.fracDy[i])).isLessThan(0.15f)
        }
    }

    /**
     * The property this exists for. A scene moved 2.4 pixels: the integer
     * search reads 2, and the refinement should read most of the remaining 0.4
     * -- in the right direction. Held loosely on magnitude, because a parabola
     * is a model of the cost curve and not the curve itself, and tightly on
     * sign, because a sign error is the one mistake that would make the
     * accumulation *worse* than not refining.
     */
    @Test
    fun aFractionalShiftIsRecoveredWithTheRightSign() {
        val ref = TestImages.texture(width, height)
        val alt = shiftFractional(ref, width, height, 2.4f, -1.6f)
        val field = Aligner.align(
            Aligner.buildPyramid(Plane(width, height, ref)),
            Aligner.buildPyramid(Plane(width, height, alt)),
            tilesX, tilesY,
        )
        assertThat(field.hasSubPixel).isTrue()

        var sumX = 0f
        var sumY = 0f
        var n = 0
        for (i in interiorTiles(field)) {
            val totalX = field.dx[i] + field.fracDx[i]
            val totalY = field.dy[i] + field.fracDy[i]
            sumX += totalX
            sumY += totalY
            n++
        }
        val meanX = sumX / n
        val meanY = sumY / n

        // Within a fifth of a pixel of the truth, on average over the field.
        assertThat(meanX).isWithin(0.2f).of(2.4f)
        assertThat(meanY).isWithin(0.2f).of(-1.6f)
        // And closer to the truth than the integer answer alone.
        assertThat(kotlin.math.abs(meanX - 2.4f)).isLessThan(kotlin.math.abs(2f - 2.4f))
        assertThat(kotlin.math.abs(meanY - (-1.6f))).isLessThan(kotlin.math.abs(-2f - (-1.6f)))
    }

    /** A field built without refinement -- the old way -- says so. */
    @Test
    fun aFieldWithoutRefinementReportsNone() {
        val field = AlignmentField(2, 2, IntArray(4), IntArray(4))
        assertThat(field.hasSubPixel).isFalse()
        assertThat(field.fracDx).hasLength(4)
        assertThat(field.fracDx.all { it == 0f }).isTrue()
    }
}
