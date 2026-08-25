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
}
