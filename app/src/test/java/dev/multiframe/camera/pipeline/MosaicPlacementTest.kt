package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

/**
 * The correction fitted from what the canvas measured about a tile. Canvas
 * coordinates throughout; a match says where the placement put a block and
 * where the canvas found it.
 */
class MosaicPlacementTest {

    private val cx = 2040f
    private val cy = 1532f
    private val corners = listOf(
        floatArrayOf(0f, 0f), floatArrayOf(4080f, 0f), floatArrayOf(0f, 3064f), floatArrayOf(4080f, 3064f),
    )

    private fun match(x: Float, y: Float, dx: Float, dy: Float, ncc: Float = 0.9f) =
        MosaicCanvas.BlockMatch(x, y, x + dx, y + dy, ncc)

    @Test
    fun `no blocks is no correction, and blocks that agree the placement was right leave it alone`() {
        assertThat(MosaicPlacement.correctionFor(emptyList(), corners)).isNull()
        val right = listOf(match(1000f, 800f, 0.2f, -0.1f), match(3000f, 2200f, -0.1f, 0.3f))
        assertThat(MosaicPlacement.correctionFor(right, corners)).isNull()
    }

    @Test
    fun `one block fixes a translation`() {
        val c = MosaicPlacement.correctionFor(listOf(match(1000f, 800f, 37f, -12f)), corners)!!
        assertThat(c.homography).isFalse()
        assertThat(c.blocks).isEqualTo(1)
        val moved = c.transform.apply(cx, cy)
        assertThat(moved[0]).isWithin(1e-3f).of(cx + 37f)
        assertThat(moved[1]).isWithin(1e-3f).of(cy - 12f)
        assertThat(c.shiftPx).isWithin(1e-2f).of(hypot(37f, 12f))
    }

    @Test
    fun `a block that matched something else is outvoted`() {
        val blocks = listOf(
            match(1000f, 800f, 40f, 10f), match(3000f, 800f, 41f, 9f),
            match(1000f, 2200f, 39f, 11f), match(3000f, 2200f, 40f, 10f, ncc = 0.95f),
            // Sky matched on noise: a hundred pixels off, weakly.
            match(2000f, 1500f, -60f, 80f, ncc = 0.5f),
        )
        val c = MosaicPlacement.correctionFor(blocks, corners)!!
        assertThat(c.blocks).isEqualTo(4)
        val moved = c.transform.apply(cx, cy)
        assertThat(moved[0]).isWithin(0.5f).of(cx + 40f)
        assertThat(moved[1]).isWithin(0.5f).of(cy + 10f)
    }

    @Test
    fun `enough blocks fit a homography when it explains them better than a translation`() {
        // A small rotation about the tile's centre: half a degree, which
        // moves a corner some fifteen pixels and the centre not at all.
        val angle = Math.toRadians(0.5)
        val cos = Math.cos(angle).toFloat()
        val sin = Math.sin(angle).toFloat()
        val blocks = ArrayList<MosaicCanvas.BlockMatch>()
        for (by in 0 until 4) for (bx in 0 until 4) {
            val x = 600f + bx * 950f
            val y = 500f + by * 700f
            val rx = cx + (x - cx) * cos - (y - cy) * sin
            val ry = cy + (x - cx) * sin + (y - cy) * cos
            blocks.add(MosaicCanvas.BlockMatch(x, y, rx + 5f, ry + 3f, 0.9f))
        }
        val c = MosaicPlacement.correctionFor(blocks, corners)!!
        assertThat(c.homography).isTrue()
        assertThat(c.blocks).isEqualTo(16)
        // The fit reproduces the blocks to well under a pixel.
        for (b in blocks) {
            val p = c.transform.apply(b.fromX, b.fromY)
            assertThat(hypot(p[0] - b.toX, p[1] - b.toY)).isLessThan(0.1f)
        }
    }

    @Test
    fun `a fit that would rescale the tile is refused for the translation`() {
        // Blocks that say the tile is ten percent larger than placed: not a
        // correction a nearly-right placement needs, so the translation.
        val blocks = ArrayList<MosaicCanvas.BlockMatch>()
        for (by in 0 until 4) for (bx in 0 until 4) {
            val x = 600f + bx * 950f
            val y = 500f + by * 700f
            blocks.add(MosaicCanvas.BlockMatch(x, y, cx + (x - cx) * 1.1f, cy + (y - cy) * 1.1f, 0.9f))
        }
        // Their offsets disagree by far more than the outlier bound, so only
        // the ones near the median survive; whatever survives, no homography.
        val c = MosaicPlacement.correctionFor(blocks, corners)
        if (c != null) assertThat(c.homography).isFalse()
    }
}
