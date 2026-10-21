package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class NightPlannerTest {
    private val ppr = 3000f
    private val classifier = MotionClassifier(ppr)
    private val ceiling = 24

    private fun solve(
        motion: MotionClass, rate: Float, iso: Int = 3200, exposureNs: Long = 33_333_333L, ceiling: Int = this.ceiling,
        isoMax: Int = 6400, exposureMaxNs: Long = 2_000_000_000L,
    ) = NightPlanner.solve(motion, rate, ppr, iso, exposureNs, 50, isoMax, 10_000L, exposureMaxNs, ceiling)

    @Test fun tableAtEachClassBoundary() {
        // HANDHELD: a hand too shaky for 1/15 s still gets 1/15 s, and 28 frames.
        solve(MotionClass.HANDHELD, 0.02f).let {
            assertThat(it.exposureNs).isEqualTo(MotionClass.HANDHELD.minExposureNs)
            assertThat(it.frames).isEqualTo(minOf(28, ceiling))
            assertThat(it.blurPixels).isGreaterThan(MotionClassifier.BLUR_BUDGET_PX)
        }
        // HANDHELD just over the braced boundary: the budget allows a quarter
        // second, the class 1/8 s, and two seconds of burst are 16 frames.
        solve(MotionClass.HANDHELD, classifier.bracedRate * 1.01f).let {
            assertThat(it.exposureNs).isEqualTo(MotionClass.HANDHELD.maxExposureNs)
            assertThat(it.frames).isEqualTo(16)
        }
        // BRACED at its boundary: exactly a quarter second, at the budget.
        solve(MotionClass.BRACED, classifier.bracedRate).let {
            assertThat(it.exposureNs).isWithin(1_000L).of(MotionClass.BRACED.minExposureNs)
            assertThat(it.frames).isEqualTo(16)
            assertThat(it.blurPixels).isWithin(0.01f).of(MotionClassifier.BLUR_BUDGET_PX)
        }
        // BRACED at the tripod boundary: half a second, eight frames.
        solve(MotionClass.BRACED, classifier.tripodRate).let {
            assertThat(it.exposureNs).isWithin(1_000L).of(MotionClass.BRACED.maxExposureNs)
            assertThat(it.frames).isEqualTo(8)
        }
        // TRIPOD at its boundary: half a second; still: a second; the ring's ceiling.
        solve(MotionClass.TRIPOD, classifier.tripodRate).let {
            assertThat(it.exposureNs).isWithin(1_000L).of(MotionClass.TRIPOD.minExposureNs)
            assertThat(it.frames).isEqualTo(ceiling)
        }
        solve(MotionClass.TRIPOD, 0f).let {
            assertThat(it.exposureNs).isEqualTo(MotionClass.TRIPOD.maxExposureNs)
            assertThat(it.frames).isEqualTo(ceiling)
        }
    }

    @Test fun framesNeverExceedTheRing() {
        for (m in MotionClass.entries) {
            assertThat(solve(m, 0.01f, ceiling = 6).frames).isAtMost(6)
        }
    }

    @Test fun theGainPaysForTheShutter() {
        val p = solve(MotionClass.TRIPOD, 0f, iso = 6400, exposureNs = 33_333_333L)
        // Thirty times the shutter, a thirtieth of the gain.
        assertThat(p.iso).isEqualTo(213)
    }

    @Test fun lightStaysWithinTheMeteredGainBoundOverTheSensorsRange() {
        val isos = listOf(50, 100, 400, 1600, 6400)
        val shutters = listOf(125_000L, 1_000_000L, 8_000_000L, 16_666_667L, 33_333_333L)
        for (m in MotionClass.entries) for (rate in listOf(0f, 0.0005f, 0.001f, 0.01f, 0.1f))
            for (iso in isos) for (t in shutters) {
                val p = solve(m, rate, iso, t)
                assertThat(NightPlanner.withinMeteredBound(p)).isTrue()
                assertThat(p.lightStops.toDouble()).isWithin(0.03).of(0.0)
                assertThat(p.iso).isIn(50..6400)
            }
    }

    @Test fun aBrightSceneShortensTheShutterRatherThanOverexpose() {
        // ISO 50 at 1/1000 s: a tripod second would be 10 stops over.
        val p = solve(MotionClass.TRIPOD, 0f, iso = 50, exposureNs = 1_000_000L)
        assertThat(p.iso).isEqualTo(50)
        assertThat(p.exposureNs).isEqualTo(1_000_000L)
    }

    @Test fun aSensorWithAShortLongestExposureRaisesTheGain() {
        val p = solve(MotionClass.TRIPOD, 0f, iso = 1600, exposureNs = 33_333_333L, exposureMaxNs = 250_000_000L)
        assertThat(p.exposureNs).isEqualTo(250_000_000L)
        assertThat(p.iso).isEqualTo(213)
    }

    @Test fun readingIsTheConsolesLine() {
        val p = solve(MotionClass.HANDHELD, 0.02f)
        assertThat(p.reading(Locale.ROOT)).isEqualTo("HANDHELD · 1/15 · ISO 1600 · 24×")
        assertThat(NightPlanner.shutterLabel(1_000_000_000L, Locale.ROOT)).isEqualTo("1.0 s")
        assertThat(NightPlanner.shutterLabel(250_000_000L, Locale.ROOT)).isEqualTo("1/4")
    }
}
