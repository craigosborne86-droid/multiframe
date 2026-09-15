package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AlignerTest {

    private val width = 256
    private val height = 192
    private val tilesX = 8
    private val tilesY = 6

    private fun recoveredOffsets(shiftX: Int, shiftY: Int): Pair<List<Int>, List<Int>> {
        val ref = TestImages.texture(width, height)
        val alt = TestImages.shift(ref, width, height, shiftX, shiftY)

        val field = Aligner.align(
            Aligner.buildPyramid(Plane(width, height, ref)),
            Aligner.buildPyramid(Plane(width, height, alt)),
            tilesX,
            tilesY,
        )

        // Ignore the outer ring of tiles: clamped edges have no real correspondence.
        val dx = ArrayList<Int>()
        val dy = ArrayList<Int>()
        for (ty in 1 until tilesY - 1) {
            for (tx in 1 until tilesX - 1) {
                dx.add(field.dx[ty * tilesX + tx])
                dy.add(field.dy[ty * tilesX + tx])
            }
        }
        return dx to dy
    }

    @Test
    fun `recovers a pure horizontal shift`() {
        val (dx, dy) = recoveredOffsets(3, 0)
        assertThat(dx.toSet()).containsExactly(3)
        assertThat(dy.toSet()).containsExactly(0)
    }

    @Test
    fun `recovers a diagonal shift`() {
        val (dx, dy) = recoveredOffsets(3, -2)
        assertThat(dx.toSet()).containsExactly(3)
        assertThat(dy.toSet()).containsExactly(-2)
    }

    @Test
    fun `recovers a shift larger than the per-level search radius`() {
        // Search radius is 4 per level, so 9px only resolves via the pyramid.
        val (dx, dy) = recoveredOffsets(9, 7)
        assertThat(dx.toSet()).containsExactly(9)
        assertThat(dy.toSet()).containsExactly(7)
    }

    @Test
    fun `identical frames align to zero`() {
        val (dx, dy) = recoveredOffsets(0, 0)
        assertThat(dx.toSet()).containsExactly(0)
        assertThat(dy.toSet()).containsExactly(0)
    }

    @Test
    fun `pyramid halves each level`() {
        val pyramid = Aligner.buildPyramid(Plane(width, height, TestImages.texture(width, height)))
        assertThat(pyramid).hasSize(Aligner.LEVELS)
        assertThat(pyramid[0].width).isEqualTo(256)
        assertThat(pyramid[1].width).isEqualTo(128)
        assertThat(pyramid[2].width).isEqualTo(64)
        assertThat(pyramid[3].width).isEqualTo(32)
    }

    // --- Tiles the fit declined borrow their neighbours' fraction ---

    @Test
    fun `a tile with no sub-pixel fit takes the median of the fitted tiles around it`() {
        // Three by three; the centre and a corner have no fit.
        val n = 9
        val fx = floatArrayOf(0.2f, 0.25f, 0.3f, 0.2f, 0f, 0.3f, 0.2f, 0.25f, 0f)
        val fy = floatArrayOf(-0.1f, -0.1f, -0.1f, -0.15f, 0f, -0.15f, -0.2f, -0.2f, 0f)
        val field = AlignmentField(3, 3, IntArray(n), IntArray(n), fx, fy)
        val filled = field.withFlatTilesBorrowed()
        // The centre: median of its eight fitted neighbours (the corner at 8
        // is not fitted, so seven of them).
        assertThat(filled.fracDx[4]).isWithin(1e-6f).of(0.25f)
        assertThat(filled.fracDy[4]).isWithin(1e-6f).of(-0.15f)
        // The corner: its three neighbours, of which the centre is not fitted.
        assertThat(filled.fracDx[8]).isWithin(1e-6f).of(0.275f)
        assertThat(filled.fracDy[8]).isWithin(1e-6f).of(-0.175f)
        // Fitted tiles are untouched, and the integer offsets are the same arrays.
        for (i in listOf(0, 1, 2, 3, 5, 6, 7)) {
            assertThat(filled.fracDx[i]).isEqualTo(fx[i])
            assertThat(filled.fracDy[i]).isEqualTo(fy[i])
        }
        assertThat(filled.dx).isSameInstanceAs(field.dx)
    }

    @Test
    fun `a tripod burst, with no fit anywhere, is left as it is`() {
        val n = 4
        val field = AlignmentField(2, 2, IntArray(n), IntArray(n))
        assertThat(field.withFlatTilesBorrowed()).isSameInstanceAs(field)
        val all = AlignmentField(2, 2, IntArray(n), IntArray(n), FloatArray(n) { 0.1f }, FloatArray(n) { 0.2f })
        assertThat(all.withFlatTilesBorrowed()).isSameInstanceAs(all)
    }

    @Test
    fun `a tile with no fitted neighbour takes the field's median`() {
        // One row of five: only the two ends fitted.
        val fx = floatArrayOf(0.4f, 0f, 0f, 0f, 0.1f)
        val fy = floatArrayOf(0.1f, 0f, 0f, 0f, 0.3f)
        val filled = AlignmentField(5, 1, IntArray(5), IntArray(5), fx, fy).withFlatTilesBorrowed()
        // The middle tile has no fitted neighbour: the field's median, which
        // for two values is their mean.
        assertThat(filled.fracDx[2]).isWithin(1e-6f).of(0.25f)
        assertThat(filled.fracDy[2]).isWithin(1e-6f).of(0.2f)
        // Its neighbours each have one fitted neighbour: that one.
        assertThat(filled.fracDx[1]).isEqualTo(0.4f)
        assertThat(filled.fracDx[3]).isEqualTo(0.1f)
    }
}
