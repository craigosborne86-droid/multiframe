package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The screen's view of a mosaic: where the outline and the nodes fall in the
 * frame, before the anchor and under a registration.
 */
class MosaicViewTest {

    private fun lens(equivalent: Int, id: String) =
        Lens(id, true, 1f, equivalent, 1f, true, 1.7f, 50f)

    private val plan = MosaicPlanner.planGrid(
        target = lens(24, "0"), capture = lens(110, "4"),
        tileWidth = 4080, tileHeight = 3072, columns = 2, rows = 2,
    )!!

    @Test
    fun `before the anchor the outline is centred on the frame and larger than it`() {
        val v = MosaicCapture.armedView(plan, "110mm")
        assertThat(v.anchored).isFalse()
        assertThat(v.located).isFalse()
        assertThat(v.captureLensLabel).isEqualTo("110mm")
        val corners = v.canvasCorners!!
        assertThat(corners).hasSize(4)
        // The outline is the canvas, which for a 2x2 is wider than the frame
        // -- the two tiles' far edges, plus the margin -- and overhangs it
        // symmetrically.
        val (tlx, tly) = corners[0]
        val (brx, bry) = corners[2]
        val overhangX = (plan.canvasWidth / plan.placedTileWidth - 1f) / 2f
        val overhangY = (plan.canvasHeight / plan.placedTileHeight - 1f) / 2f
        assertThat(overhangX).isGreaterThan(0.3f)
        assertThat(tlx).isWithin(0.01f).of(-overhangX)
        assertThat(brx).isWithin(0.01f).of(1f + overhangX)
        assertThat(tly).isWithin(0.01f).of(-overhangY)
        assertThat(bry).isWithin(0.01f).of(1f + overhangY)
        assertThat((tlx + brx) / 2).isWithin(1e-3f).of(0.5f)
        assertThat((tly + bry) / 2).isWithin(1e-3f).of(0.5f)
        // The nodes sit inside it, a step apart, around the frame's centre.
        val xs = v.nodes.map { it.frameX!! }
        val halfStep = plan.stepX / plan.placedTileWidth / 2f
        assertThat(halfStep).isWithin(0.02f).of(0.35f)
        assertThat(xs.min()).isWithin(0.01f).of(0.5f - halfStep)
        assertThat(xs.max()).isWithin(0.01f).of(0.5f + halfStep)
        assertThat(v.nodes.all { it.state == MosaicGuide.NodeState.PENDING }).isTrue()
    }

    @Test
    fun `under a registration the outline moves with the frame`() {
        val guide = MosaicGuide(plan)
        // The frame is looking at the top-left node: its transform places the
        // frame's origin so that the frame's centre is that node.
        val node = plan.nodes[0]
        val t = Homography.translation(
            (node.canvasX - plan.placedTileWidth / 2f).toDouble(),
            (node.canvasY - plan.placedTileHeight / 2f).toDouble(),
        )
        val v = MosaicCapture.viewOf(guide, plan, t, "110mm", 0L, anchored = true)
        assertThat(v.located).isTrue()
        val n0 = v.nodes[0]
        assertThat(n0.frameX!!).isWithin(0.01f).of(0.5f)
        assertThat(n0.frameY!!).isWithin(0.01f).of(0.5f)
        // The canvas's top-left corner is now nearer the frame than before.
        val (tlx, _) = v.canvasCorners!![0]
        assertThat(tlx).isGreaterThan(-0.2f)
    }

    @Test
    fun `an unregistered frame has no outline and no dots`() {
        val v = MosaicCapture.viewOf(MosaicGuide(plan), plan, null, "110mm", 0L, anchored = true)
        assertThat(v.located).isFalse()
        assertThat(v.canvasCorners).isNull()
        assertThat(v.nodes.all { it.frameX == null }).isTrue()
    }
}
