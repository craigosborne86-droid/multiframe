package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.atan
import kotlin.math.hypot

/**
 * The attitude guidance, held to the geometry it claims: turn the phone by a
 * known angle and the dots move by the known number of pixels.
 */
class ImuGuidanceTest {

    private fun lens(equivalent: Int, id: String) =
        Lens(id, true, 1f, equivalent, 1f, true, 1.7f, 50f)

    // 4080 px across a 105mm-equivalent frame: 11,900 px/rad, 19.6 degrees.
    private val f = StabilityGate.pixelsPerRadianFromEquivalent(105, 4080)
    private val plan = MosaicPlanner.planGrid(null, lens(105, "4"), 4080, 3072, 3, 3)!!

    private fun guidance(orientation: Int = 0) = ImuGuidance(plan, f, orientation).also {
        it.anchor(ImuGuidance.IDENTITY)
    }

    @Test
    fun `at the anchor the reticle is at the canvas centre and the nodes are where the plan put them`() {
        val g = guidance()
        val (rx, ry) = g.reticleOnCanvas(ImuGuidance.IDENTITY)!!
        assertThat(rx).isWithin(0.01f).of(plan.canvasWidth / 2f)
        assertThat(ry).isWithin(0.01f).of(plan.canvasHeight / 2f)

        val nodes = g.nodesInFrame(ImuGuidance.IDENTITY)
        val armed = MosaicCapture.armedView(plan, "105mm")
        for ((i, n) in nodes.withIndex()) {
            assertThat(n!!.first).isWithin(1e-3f).of(armed.nodes[i].frameX!!)
            assertThat(n.second).isWithin(1e-3f).of(armed.nodes[i].frameY!!)
        }
        val corners = g.cornersInFrame(ImuGuidance.IDENTITY)!!
        assertThat(corners[0].first).isWithin(1e-3f).of(armed.canvasCorners!![0].first)
        assertThat(corners[2].second).isWithin(1e-3f).of(armed.canvasCorners!![2].second)
    }

    @Test
    fun `panning onto a node brings it to the reticle and the reticle to it`() {
        val g = guidance()
        // The middle-right node: a step to the right of the centre, level with it.
        val node = plan.nodes[5]
        val dx = (node.canvasX - plan.canvasWidth / 2f) / plan.tileScale
        val alpha = atan(dx / f)
        // A right-hand rotation about +y turns the camera left; turning right
        // by alpha is the negative.
        val now = ImuGuidance.yaw(-alpha)

        val (rx, ry) = g.reticleOnCanvas(now)!!
        assertThat(rx).isWithin(0.5f).of(node.canvasX)
        assertThat(ry).isWithin(0.5f).of(node.canvasY)

        val inFrame = g.nodesInFrame(now)[5]!!
        assertThat(inFrame.first).isWithin(1e-4f).of(0.5f)
        assertThat(inFrame.second).isWithin(1e-4f).of(0.5f)
        // And the centre node has moved a step to the left of the reticle.
        val centre = g.nodesInFrame(now)[4]!!
        assertThat(centre.first).isLessThan(0.5f)
        assertThat(centre.second).isWithin(1e-3f).of(0.5f)
    }

    @Test
    fun `tilting moves the dots vertically`() {
        val g = guidance()
        val node = plan.nodes[1]  // top-centre
        val dy = (node.canvasY - plan.canvasHeight / 2f) / plan.tileScale  // negative: above
        val beta = atan(-dy / f)
        // The node is above; tilt the camera up by beta -- a right-hand
        // rotation about +x turns the forward axis toward +y.
        val now = ImuGuidance.pitch(beta)
        val inFrame = g.nodesInFrame(now)[1]!!
        assertThat(inFrame.first).isWithin(1e-4f).of(0.5f)
        assertThat(inFrame.second).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `rolling turns the dots about the reticle without moving them off it`() {
        val g = guidance()
        val before = g.nodesInFrame(ImuGuidance.IDENTITY)[5]!!
        val after = g.nodesInFrame(ImuGuidance.roll(Math.toRadians(90.0).toFloat()))[5]!!
        val rBefore = hypot((before.first - 0.5f) * 4080, (before.second - 0.5f) * 3072)
        val rAfter = hypot((after.first - 0.5f) * 4080, (after.second - 0.5f) * 3072)
        assertThat(rAfter).isWithin(1f).of(rBefore)
        // A node to the right of the reticle is now above or below it.
        assertThat(kotlin.math.abs(after.first - 0.5f)).isLessThan(1e-3f)
        assertThat(kotlin.math.abs(after.second - 0.5f)).isGreaterThan(0.05f)
    }

    @Test
    fun `a phone turned away has no canvas in front of it`() {
        val g = guidance()
        assertThat(g.reticleOnCanvas(ImuGuidance.yaw(Math.toRadians(120.0).toFloat()))).isNull()
        assertThat(g.cornersInFrame(ImuGuidance.yaw(Math.toRadians(120.0).toFloat()))).isNull()
    }

    /**
     * The dots are static: a pan out and back puts every dot exactly where it
     * was, and nothing between -- no registration, no lock -- moves it. A
     * version corrected the dots by each tile's residual, and they jumped.
     */
    @Test
    fun `a node stays where the world put it, whatever happens in between`() {
        val g = guidance()
        val before = g.nodesInFrame(ImuGuidance.IDENTITY)
        val away = ImuGuidance.times(ImuGuidance.yaw(0.2f), ImuGuidance.pitch(-0.1f))
        val moved = g.nodesInFrame(away)
        assertThat(moved[4]!!.first).isNotWithin(0.01f).of(before[4]!!.first)
        val back = g.nodesInFrame(ImuGuidance.IDENTITY)
        for (i in before.indices) {
            assertThat(back[i]!!.first).isWithin(1e-6f).of(before[i]!!.first)
            assertThat(back[i]!!.second).isWithin(1e-6f).of(before[i]!!.second)
        }
    }

    /**
     * The projection is the one the reference states: a node's displacement
     * from the reticle is `f * tan(delta yaw)` horizontally and `f * tan(delta
     * pitch)` vertically, in the lens's own pixels, one to one with the
     * sensor's movement.
     */
    @Test
    fun `a pan moves the dots by f times the tangent of the angle`() {
        val g = guidance()
        for (degrees in listOf(1.0, 3.0, 6.0)) {
            val a = Math.toRadians(degrees).toFloat()
            // Turning the camera right by a: the centre node moves left in the frame.
            val centre = g.nodesInFrame(ImuGuidance.yaw(-a))[4]!!
            val dxPx = (centre.first - 0.5f) * 4080
            assertThat(dxPx).isWithin(0.05f).of(-f * kotlin.math.tan(a))
            assertThat(centre.second).isWithin(1e-4f).of(0.5f)
            // Tilting up by a: it moves down.
            val tilted = g.nodesInFrame(ImuGuidance.pitch(a))[4]!!
            val dyPx = (tilted.second - 0.5f) * 3072
            assertThat(dyPx).isWithin(0.05f).of(f * kotlin.math.tan(a))
        }
    }

    @Test
    fun `on a portrait phone a pan moves the reticle along the sensor's y axis`() {
        // Sensor orientation 90: the display's horizontal is the sensor's y.
        val g = guidance(orientation = 90)
        val (x0, y0) = g.reticleOnCanvas(ImuGuidance.IDENTITY)!!
        val (x1, y1) = g.reticleOnCanvas(ImuGuidance.yaw(Math.toRadians(-2.0).toFloat()))!!
        assertThat(kotlin.math.abs(x1 - x0)).isLessThan(0.5f)
        assertThat(kotlin.math.abs(y1 - y0)).isGreaterThan(100f)
        // Two degrees at f pixels per radian, in canvas pixels.
        assertThat(kotlin.math.abs(y1 - y0)).isWithin(2f).of(kotlin.math.tan(Math.toRadians(2.0)).toFloat() * f * plan.tileScale)
    }
}
