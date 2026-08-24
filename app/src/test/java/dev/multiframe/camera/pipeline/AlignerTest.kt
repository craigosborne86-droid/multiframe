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
}
