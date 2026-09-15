package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Mosaic geometry. Pure arithmetic, so the answers can be checked exactly.
 */
class MosaicPlannerTest {

    private fun lens(equivalent: Int, id: String = "x") =
        Lens(id, true, 1f, equivalent, 1f, true, 1.7f, 50f)

    private val ultraWide = lens(12, "3")
    private val main = lens(24, "0")
    private val tele = lens(110, "4")
    private val superTele = lens(220, "6")

    // Full sensor readout on this device.
    private val tileW = 4080
    private val tileH = 3072

    @Test
    fun `field of view matches the standard focal lengths`() {
        // A 50mm lens is about 40 degrees across, which is the number every
        // photographer knows. If this is wrong, every canvas size is wrong.
        assertThat(MosaicPlanner.horizontalFovDegrees(50)).isWithin(0.5).of(39.6)
        assertThat(MosaicPlanner.horizontalFovDegrees(24)).isWithin(0.5).of(73.7)
        assertThat(MosaicPlanner.horizontalFovDegrees(110)).isWithin(0.5).of(18.6)
    }

    @Test
    fun `a longer lens sees less`() {
        assertThat(MosaicPlanner.horizontalFovDegrees(220))
            .isLessThan(MosaicPlanner.horizontalFovDegrees(110))
        assertThat(MosaicPlanner.horizontalFovDegrees(12))
            .isGreaterThan(MosaicPlanner.horizontalFovDegrees(24))
    }

    @Test
    fun `the telephoto over the main framing is a large resolution gain`() {
        val plan = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 0.0)!!

        println("plan: $plan")
        // Four and a half times the linear resolution is twenty times the pixels.
        assertThat(plan.linearGain).isGreaterThan(4f)
        assertThat(plan.megapixels).isGreaterThan(150.0)
        assertThat(plan.tileCount).isGreaterThan(4)
    }

    @Test
    fun `canvas size follows the tangent ratio, not the angle ratio`() {
        // A rectilinear projection is linear in the tangent of the angle, not
        // in the angle. Sizing the canvas by the ratio of fields of view is the
        // natural mistake and it undersizes by 14% at this pairing, with the
        // error growing as the target gets wider -- exactly where the feature
        // is most useful.
        //
        // The tangent ratio turns out to equal the focal length ratio exactly,
        // since tan(hfov/2) is 18/f by construction. That identity is worth
        // pinning: it is the reason the arithmetic can be trusted.
        val plan = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 0.0)!!

        val focalRatio = 110f / 24f
        val angleRatio = (MosaicPlanner.horizontalFovDegrees(24) /
            MosaicPlanner.horizontalFovDegrees(110)).toFloat()

        assertThat(plan.linearGain).isWithin(0.02f).of(focalRatio)
        assertThat(plan.linearGain).isGreaterThan(angleRatio * 1.05f)
        println("linear gain %.3f, focal ratio %.3f, naive angle ratio %.3f"
            .format(plan.linearGain, focalRatio, angleRatio))
    }

    @Test
    fun `more overlap means more tiles`() {
        val sparse = MosaicPlanner.plan(main, tele, tileW, tileH, overlap = 0.2f, maxMegapixels = 0.0)!!
        val dense = MosaicPlanner.plan(main, tele, tileW, tileH, overlap = 0.5f, maxMegapixels = 0.0)!!

        assertThat(dense.tileCount).isGreaterThan(sparse.tileCount)
        // Overlap costs frames but not resolution.
        assertThat(dense.megapixels).isWithin(1.0).of(sparse.megapixels)
    }

    @Test
    fun `a pointless pairing is refused`() {
        // Nothing is gained by sweeping a lens over its own framing, or by
        // sweeping a wider lens over a narrower one.
        assertThat(MosaicPlanner.plan(main, main, tileW, tileH)).isNull()
        assertThat(MosaicPlanner.plan(tele, main, tileW, tileH)).isNull()
        assertThat(MosaicPlanner.plan(main, ultraWide, tileW, tileH)).isNull()
    }

    @Test
    fun `the canvas is capped and says so`() {
        val uncapped = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 0.0)!!
        val capped = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 100.0)!!

        assertThat(uncapped.capped).isFalse()
        assertThat(capped.capped).isTrue()
        assertThat(capped.megapixels).isAtMost(101.0)
        // Framing must survive the cap.
        val uncappedAspect = uncapped.canvasWidth.toDouble() / uncapped.canvasHeight
        val cappedAspect = capped.canvasWidth.toDouble() / capped.canvasHeight
        assertThat(cappedAspect).isWithin(0.01).of(uncappedAspect)
    }

    @Test
    fun `an ultra-wide target with a super telephoto is an enormous sweep`() {
        // The extreme pairing. It should still be described honestly rather
        // than refused, so the count is what warns the user off.
        val plan = MosaicPlanner.plan(ultraWide, superTele, tileW, tileH, maxMegapixels = 0.0)!!

        println("extreme: $plan")
        assertThat(plan.tileCount).isGreaterThan(100)
    }

    @Test
    fun `the offered pairing is the best one that stays practical`() {
        // The longest lens wins unless the sweep would be unreasonable, in
        // which case a shorter one that finishes is the better answer.
        val lenses = listOf(ultraWide, main, tele, superTele)

        val chosen = MosaicPlanner.bestPairing(lenses, main, tileW, tileH)

        assertThat(chosen).isNotNull()
        assertThat(chosen!!.tileCount).isAtMost(MosaicPlanner.MAX_REASONABLE_TILES)
        println("offered for 24mm: $chosen")
    }

    @Test
    fun `nothing is offered when there is no longer lens`() {
        assertThat(MosaicPlanner.bestPairing(listOf(main), main, tileW, tileH)).isNull()
        assertThat(MosaicPlanner.bestPairing(emptyList(), main, tileW, tileH)).isNull()
    }

    @Test
    fun `a lens with no reported geometry does not produce a plan`() {
        val unknown = lens(0, "?")

        assertThat(MosaicPlanner.plan(unknown, tele, tileW, tileH)).isNull()
        assertThat(MosaicPlanner.plan(main, unknown, tileW, tileH)).isNull()
        assertThat(MosaicPlanner.plan(main, tele, 0, 0)).isNull()
    }

    @Test
    fun `capping the canvas also shrinks the tiles`() {
        // Found by running a sweep against the live camera: a capped canvas is
        // smaller than the sweep's true extent, so a tile placed at native size
        // claims a larger share of it than it saw. One frame reported 56% of a
        // canvas the plan said needed twenty-five, and the sweep would have
        // called itself complete after two.
        val uncapped = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 0.0)!!
        val capped = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 60.0)!!

        assertThat(uncapped.tileScale).isWithin(1e-3f).of(1f)
        assertThat(capped.tileScale).isLessThan(1f)

        // A tile's share of the canvas must be the same either way, because it
        // saw the same fraction of the scene.
        fun share(plan: MosaicPlan): Double {
            val w = tileW * plan.tileScale.toDouble()
            val h = tileH * plan.tileScale.toDouble()
            return (w * h) / (plan.canvasWidth.toDouble() * plan.canvasHeight)
        }
        assertThat(share(capped)).isWithin(0.005).of(share(uncapped))
        println("tile share of canvas: uncapped %.4f, capped %.4f"
            .format(share(uncapped), share(capped)))
    }

    @Test
    fun `the canvas memory cost is stated`() {
        val plan = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 100.0)!!

        // Has to be a number someone can look at and veto.
        assertThat(plan.canvasBytes).isGreaterThan(100L * 1024 * 1024)
        println("100 MP canvas costs %.0f MB".format(plan.canvasBytes / (1024.0 * 1024.0)))
    }

    // --- the presets and their nodes --------------------------------------

    @Test
    fun `a fixed grid is sized to the grid, not the framing`() {
        val plan = MosaicPlanner.planGrid(main, tele, tileW, tileH, columns = 2, rows = 2)!!
        assertThat(plan.columns).isEqualTo(2)
        assertThat(plan.rows).isEqualTo(2)
        // The canvas reaches the outer tiles' far edges on the plane, with
        // the margin: f * tan(step/2 + FOV/2), where the two nodes sit half a
        // step either side of the centre.
        val g = MosaicPlanner.gridGeometry(tele, tileW, tileH, 2, 2)!!
        val expectedW = 2 * g.focalPx * kotlin.math.tan(g.stepYaw / 2 + g.fovH / 2) * (1 + MosaicPlanner.CANVAS_MARGIN)
        assertThat(plan.canvasWidth.toDouble()).isWithin(2.0).of(kotlin.math.ceil(expectedW))
        // Which is more than a tile and a step: a tile at an angle is wider on
        // the plane than a tile at the centre, and the first version, which
        // sized the canvas as a tile plus a pixel step, clipped it.
        assertThat(plan.canvasWidth.toDouble()).isGreaterThan(tileW * (1.0 + (1 - plan.overlap)))
        assertThat(plan.capped).isFalse()
        assertThat(plan.tileScale).isWithin(1e-6f).of(1f)
        // Thirty-odd megapixels for Detail Boost on this sensor.
        assertThat(plan.megapixels).isGreaterThan(25.0)
        assertThat(plan.megapixels).isLessThan(45.0)
    }

    @Test
    fun `the nodes are directions a fixed angle apart, projected onto the canvas`() {
        val plan = MosaicPlanner.planGrid(main, tele, tileW, tileH, columns = 3, rows = 3)!!
        assertThat(plan.nodes).hasSize(9)
        val centre = plan.nodes[4]
        assertThat(centre.column).isEqualTo(1)
        assertThat(centre.row).isEqualTo(1)
        assertThat(centre.canvasX).isWithin(0.5f).of(plan.canvasWidth / 2f)
        assertThat(centre.canvasY).isWithin(0.5f).of(plan.canvasHeight / 2f)
        // The right-hand neighbour is one angular step away: seventy percent
        // of the field of view, at f * tan on the plane.
        val g = MosaicPlanner.gridGeometry(tele, tileW, tileH, 3, 3)!!
        val right = plan.nodes[5]
        assertThat(g.stepYaw).isWithin(1e-6).of(g.fovH * 0.7)
        assertThat((right.canvasX - centre.canvasX).toDouble()).isWithin(0.5).of(g.focalPx * kotlin.math.tan(g.stepYaw) * plan.tileScale)
        assertThat(right.canvasY).isWithin(0.5f).of(centre.canvasY)
        // Row-major, top-left first.
        assertThat(plan.nodes[0].canvasX).isLessThan(plan.nodes[1].canvasX)
        assertThat(plan.nodes[0].canvasY).isLessThan(plan.nodes[3].canvasY)
        // And a tile aimed at the corner node lies on the canvas, its far
        // edge included: the canvas reaches f * tan(theta + FOV/2).
        val corner = plan.nodes[0]
        val farEdge = g.focalPx * kotlin.math.tan(g.stepYaw + g.fovH / 2) * plan.tileScale
        assertThat(plan.canvasWidth / 2f - farEdge.toFloat()).isAtLeast(0f)
        assertThat(corner.canvasX).isGreaterThan(plan.placedTileWidth / 2f)
    }

    /**
     * Thirty percent of the field, as an angle: the direction a tile's edge
     * points, less the direction its neighbour's opposite edge points, is
     * thirty percent of the field of view -- on every lens, at every grid.
     */
    @Test
    fun `adjacent tiles share thirty percent of the field of view`() {
        for (lens in listOf(tele, main)) {
            for (side in 2..3) {
                val g = MosaicPlanner.gridGeometry(lens, tileW, tileH, side, side) ?: continue
                val overlapH = g.fovH - g.stepYaw
                val overlapV = g.fovV - g.stepPitch
                assertThat(overlapH / g.fovH).isWithin(1e-6).of(0.30)
                assertThat(overlapV / g.fovV).isWithin(1e-6).of(0.30)
            }
        }
        // The vertical field follows from the sensor's aspect, not a 3:2 negative.
        val g = MosaicPlanner.gridGeometry(tele, tileW, tileH, 3, 3)!!
        assertThat(kotlin.math.tan(g.fovV / 2) / kotlin.math.tan(g.fovH / 2)).isWithin(1e-6).of(tileH.toDouble() / tileW)
    }

    @Test
    fun `a grid that would reach past seventy degrees is not a picture`() {
        // A 3x3 on a 12mm-equivalent lens reaches past ninety; refused.
        val ultrawide = Lens("5", true, 2.2f, 12, 0.5f, true, 1.7f, 70f)
        assertThat(MosaicPlanner.planGrid(null, ultrawide, tileW, tileH, 3, 3)).isNull()
        // A 2x2 on the 24mm main lens fits.
        assertThat(MosaicPlanner.planGrid(null, main, tileW, tileH, 2, 2)).isNotNull()
    }

    @Test
    fun `the presets plan their grids`() {
        val boost = MosaicPlanner.planPreset(MosaicPreset.DETAIL_BOOST, main, tele, tileW, tileH)!!
        assertThat(boost.tileCount).isEqualTo(4)
        val large = MosaicPlanner.planPreset(MosaicPreset.LARGE_FORMAT, main, tele, tileW, tileH)!!
        assertThat(large.tileCount).isEqualTo(9)
        assertThat(large.megapixels).isGreaterThan(boost.megapixels)
        // Custom with no region drawn yet is not a plan.
        assertThat(MosaicPlanner.planPreset(MosaicPreset.CUSTOM, main, tele, tileW, tileH)).isNull()
        assertThat(MosaicPlanner.planPreset(MosaicPreset.ONE_SHOT, main, tele, tileW, tileH)).isNull()
    }

    @Test
    fun `a custom region is covered by the smallest grid that reaches it`() {
        // The whole framing, as a region, is the full plan's grid.
        val full = MosaicPlanner.plan(main, tele, tileW, tileH, maxMegapixels = 0.0)!!
        val whole = MosaicPlanner.planRegion(main, tele, tileW, tileH, 1f, 1f)!!
        assertThat(whole.columns).isEqualTo(full.columns)
        assertThat(whole.rows).isEqualTo(full.rows)

        // A region the size of one tile needs one tile.
        val oneTile = MosaicPlanner.planRegion(
            main, tele, tileW, tileH,
            regionWidth = tileW.toFloat() / full.canvasWidth,
            regionHeight = tileH.toFloat() / full.canvasHeight,
        )!!
        assertThat(oneTile.columns).isEqualTo(1)
        assertThat(oneTile.rows).isEqualTo(1)

        // Half the framing each way is between the two.
        val half = MosaicPlanner.planRegion(main, tele, tileW, tileH, 0.5f, 0.5f)!!
        assertThat(half.columns).isGreaterThan(1)
        assertThat(half.columns).isLessThan(full.columns)
        assertThat(half.nodes).hasSize(half.columns * half.rows)
    }

    @Test
    fun `a custom region of nothing is not a plan`() {
        assertThat(MosaicPlanner.planRegion(main, tele, tileW, tileH, 0f, 0.5f)).isNull()
        assertThat(MosaicPlanner.planRegion(main, tele, tileW, tileH, 0.5f, -1f)).isNull()
    }

    /**
     * A grid needs no wider lens. Planned from the capture lens itself, or
     * from a longer one, it records the framing it covers -- the capture
     * lens's focal length over the tiles the canvas spans -- rather than the
     * lens it was asked from.
     */
    @Test
    fun `a grid is planned from any lens and records the framing it covers`() {
        val fromItself = MosaicPlanner.planGrid(tele, tele, tileW, tileH, 2, 2)!!
        assertThat(fromItself.captureLens).isEqualTo(tele)
        // The canvas is however many tiles across the angular grid projects
        // to, and the framing is the capture lens over that.
        val across = fromItself.canvasWidth.toDouble() / tileW
        assertThat(fromItself.targetLens.equivalent35mm).isEqualTo((110 / across).toInt())
        assertThat(fromItself.targetLens.equivalent35mm).isIn(55..70)
        val fromLonger = MosaicPlanner.planGrid(superTele, tele, tileW, tileH, 2, 2)!!
        assertThat(fromLonger.targetLens.equivalent35mm).isEqualTo(fromItself.targetLens.equivalent35mm)
        // From a wider lens, that lens is the framing.
        val fromWider = MosaicPlanner.planGrid(main, tele, tileW, tileH, 2, 2)!!
        assertThat(fromWider.targetLens).isEqualTo(main)
        // And with no lens at all.
        assertThat(MosaicPlanner.planGrid(null, tele, tileW, tileH, 2, 2)!!.targetLens.equivalent35mm)
            .isEqualTo(fromItself.targetLens.equivalent35mm)
    }

    @Test
    fun `the capture lens is the longest that delivers raw`() {
        val noRawTele = superTele.copy(supportsRaw = false)
        assertThat(MosaicPlanner.captureLens(listOf(ultraWide, main, tele, noRawTele))).isEqualTo(tele)
        assertThat(MosaicPlanner.captureLens(listOf(ultraWide, main, tele, superTele))).isEqualTo(superTele)
        assertThat(MosaicPlanner.captureLens(emptyList())).isNull()
    }

    @Test
    fun `a custom region needs a wider lens to have been drawn on`() {
        assertThat(MosaicPlanner.planPreset(MosaicPreset.CUSTOM, null, tele, tileW, tileH, customRegion = 0.5f to 0.5f)).isNull()
        assertThat(MosaicPlanner.planPreset(MosaicPreset.CUSTOM, main, tele, tileW, tileH, customRegion = 0.5f to 0.5f)).isNotNull()
    }

    @Test
    fun `the preset cycles and reads back by name`() {
        assertThat(MosaicPreset.DETAIL_BOOST.next()).isEqualTo(MosaicPreset.LARGE_FORMAT)
        assertThat(MosaicPreset.CUSTOM.next()).isEqualTo(MosaicPreset.ONE_SHOT)
        assertThat(MosaicPreset.ONE_SHOT.next()).isEqualTo(MosaicPreset.DETAIL_BOOST)
        // The single-framing engine: no grid, no plan, and its own frame count.
        assertThat(MosaicPreset.ONE_SHOT.gridSide).isEqualTo(0)
        assertThat(MosaicPreset.ONE_SHOT.isMosaic).isFalse()
        assertThat(MosaicPreset.DETAIL_BOOST.isMosaic).isTrue()
        assertThat(MosaicPreset.ONE_SHOT.label).isEqualTo("1-SHOT")
        assertThat(MosaicPreset.ONE_SHOT_FRAMES).isEqualTo(16)
        assertThat(MosaicPreset.oneShotMegapixels(4080, 3064)).isWithin(0.01).of(50.0)
        assertThat(MosaicPreset.named("LARGE_FORMAT")).isEqualTo(MosaicPreset.LARGE_FORMAT)
        assertThat(MosaicPreset.named("nonsense")).isEqualTo(MosaicPreset.DETAIL_BOOST)
        assertThat(MosaicPreset.named(null)).isEqualTo(MosaicPreset.DETAIL_BOOST)
        assertThat(MosaicPreset.DETAIL_BOOST.gridSide).isEqualTo(2)
        assertThat(MosaicPreset.LARGE_FORMAT.gridSide).isEqualTo(3)
        assertThat(MosaicPreset.CUSTOM.gridSide).isEqualTo(0)
    }
}
