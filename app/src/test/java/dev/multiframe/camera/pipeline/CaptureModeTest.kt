package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Capture modes.
 *
 * The interesting cases are the contradictions: a night scene and a moving
 * subject want opposite things from every parameter, and AUTO has to decide
 * which of those it is allowed to guess at.
 */
class CaptureModeTest {

    private fun scene(highlight: Float, clipped: Float = 0f) =
        SceneAnalysis(
            clippedFraction = clipped,
            highlightLevel = highlight,
            shadowLevel = highlight / 8f,
            dynamicRangeStops = 3f,
        )

    private val bright = scene(highlight = 0.7f)
    private val dark = scene(highlight = 0.03f)

    // ------------------------------------------------------------------

    @Test
    fun `night takes every frame the ring can give`() {
        // The scene is still, so each extra frame is a real improvement.
        val plan = CaptureModes.plan(CaptureMode.NIGHT, dark, availableFrames = 28, requestedFrames = 8)

        assertThat(plan.frames).isEqualTo(28)
        assertThat(plan.maxExposureNs).isEqualTo(CaptureModes.NIGHT_EXPOSURE_NS)
    }

    @Test
    fun `night does not protect highlights`() {
        // A dark scene has none worth protecting, and pulling exposure would
        // spend the shadow detail the mode exists for.
        val plan = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8)

        assertThat(plan.protectHighlights).isFalse()
    }

    @Test
    fun `action takes few frames on purpose`() {
        // The merge rejects frames where the subject has moved, so extra ones
        // contribute nothing while lengthening the window and making the
        // rejection worse. More frames is not simply better.
        val plan = CaptureModes.plan(CaptureMode.ACTION, bright, availableFrames = 28, requestedFrames = 16)

        assertThat(plan.frames).isEqualTo(CaptureModes.ACTION_FRAMES)
        assertThat(plan.frames).isLessThan(16)
    }

    @Test
    fun `action caps exposure far shorter than night`() {
        val action = CaptureModes.plan(CaptureMode.ACTION, bright, 28, 8)
        val night = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8)

        assertThat(action.maxExposureNs).isLessThan(night.maxExposureNs / 10)
    }

    // ------------------------------------------------------------------
    // What AUTO will and will not guess.
    // ------------------------------------------------------------------

    @Test
    fun `auto reaches for night on a dark scene`() {
        val plan = CaptureModes.plan(CaptureMode.AUTO, dark, 28, 8)

        assertThat(plan.resolved).isEqualTo(CaptureMode.NIGHT)
        assertThat(plan.reason).contains("dark")
    }

    @Test
    fun `auto leaves an ordinary scene alone`() {
        val plan = CaptureModes.plan(CaptureMode.AUTO, bright, 28, 8)

        assertThat(plan.resolved).isEqualTo(CaptureMode.AUTO)
        assertThat(plan.frames).isEqualTo(8)
    }

    @Test
    fun `auto never guesses at motion`() {
        // Whether something is moving is not visible in a brightness histogram.
        // Guessing would be wrong as often as right, and wrong in the expensive
        // direction: a four-frame burst on a still scene throws away a stop of
        // shadow quality for nothing.
        for (highlight in listOf(0.01f, 0.05f, 0.2f, 0.5f, 0.95f)) {
            val plan = CaptureModes.plan(CaptureMode.AUTO, scene(highlight), 28, 8)
            assertThat(plan.resolved).isNotEqualTo(CaptureMode.ACTION)
        }
    }

    @Test
    fun `an explicit mode is honoured even when the scene disagrees`() {
        // A user photographing a moving subject in the dark knows something the
        // histogram does not.
        val plan = CaptureModes.plan(CaptureMode.ACTION, dark, 28, 8)

        assertThat(plan.resolved).isEqualTo(CaptureMode.ACTION)
        assertThat(plan.frames).isEqualTo(CaptureModes.ACTION_FRAMES)
    }

    // ------------------------------------------------------------------
    // Bounds.
    // ------------------------------------------------------------------

    @Test
    fun `no plan asks for more frames than exist`() {
        for (mode in CaptureMode.entries) {
            for (available in listOf(1, 2, 4, 10, 28)) {
                val plan = CaptureModes.plan(mode, dark, available, requestedFrames = 32)
                assertThat(plan.frames).isAtMost(available)
                assertThat(plan.frames).isAtLeast(1)
            }
        }
    }

    @Test
    fun `an empty ring still yields a workable plan`() {
        val plan = CaptureModes.plan(CaptureMode.NIGHT, dark, availableFrames = 0, requestedFrames = 8)

        assertThat(plan.frames).isEqualTo(1)
    }

    @Test
    fun `shadow recovery follows the frame count the mode chose`() {
        // The number that makes the mode's benefit legible.
        val night = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8)
        val action = CaptureModes.plan(CaptureMode.ACTION, bright, 28, 8)

        assertThat(CaptureModes.shadowStops(night))
            .isGreaterThan(CaptureModes.shadowStops(action))
        println("shadow recovery: night %.2f stops, action %.2f stops".format(
            CaptureModes.shadowStops(night), CaptureModes.shadowStops(action)))
    }

    @Test
    fun `burst duration is reported and grows with frame count`() {
        val interval = 33_333_000L
        val night = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8)
        val action = CaptureModes.plan(CaptureMode.ACTION, bright, 28, 8)

        val nightMs = CaptureModes.burstMillis(night, interval)
        val actionMs = CaptureModes.burstMillis(action, interval)

        println("burst window: night ${nightMs}ms, action ${actionMs}ms")
        assertThat(nightMs).isGreaterThan(actionMs)
        assertThat(actionMs).isLessThan(300)
    }

    @Test
    fun `super res plans a sixteen frame single viewpoint burst`() {
        val auto = CaptureModes.plan(CaptureMode.AUTO, bright, 28, 8)
        val superRes = CaptureModes.plan(CaptureMode.SUPER_RES, bright, 28, 8)
        assertThat(superRes.frames).isEqualTo(16)
        assertThat(superRes.maxExposureNs).isEqualTo(auto.maxExposureNs)
        assertThat(superRes.resolved).isEqualTo(CaptureMode.SUPER_RES)
        assertThat(CaptureMode.SUPER_RES.isSingleCapture).isTrue()
        assertThat(CaptureMode.entries.filter { it.isSingleCapture }).containsExactly(
            CaptureMode.AUTO, CaptureMode.NIGHT, CaptureMode.ACTION, CaptureMode.SUPER_RES,
        )
        assertThat(CaptureMode.SUPER_RES.label).isEqualTo("SUPER RES")
    }

    @Test
    fun `auto takes at most ten frames however deep the ring`() {
        // Signal-to-noise grows as the root of the count: twenty frames over
        // ten is half a stop for a burst window twice as long. Night, on a
        // scene that is still, takes what the ring has.
        val deep = CaptureModes.plan(CaptureMode.AUTO, bright, availableFrames = 28, requestedFrames = 28)
        assertThat(deep.frames).isEqualTo(CaptureModes.AUTO_MAX_FRAMES)
        assertThat(CaptureModes.AUTO_MAX_FRAMES).isEqualTo(10)
        val eight = CaptureModes.plan(CaptureMode.AUTO, bright, 28, 8)
        assertThat(eight.frames).isEqualTo(8)
        val shallow = CaptureModes.plan(CaptureMode.AUTO, bright, availableFrames = 6, requestedFrames = 28)
        assertThat(shallow.frames).isEqualTo(6)
        val night = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 28)
        assertThat(night.frames).isEqualTo(28)
    }

    @Test
    fun `auto caps the frame at a sixtieth, inside the stream's own frame`() {
        // A cap at or past the frame interval is no cap: 1/30 s was the old
        // value and it never bound anything. A sixtieth binds, and the gain
        // and the merge make up the stop; see ShutterCap.
        val auto = CaptureModes.plan(CaptureMode.AUTO, bright, 28, 8)
        assertThat(auto.maxExposureNs).isEqualTo(CaptureModes.AUTO_EXPOSURE_NS)
        assertThat(auto.maxExposureNs).isLessThan(33_333_333L)
        assertThat(auto.maxExposureNs).isEqualTo(16_666_667L)
        // Night's is longer than the frame -- the stream sets no cap for it.
        val night = CaptureModes.plan(CaptureMode.NIGHT, dark, 28, 8)
        assertThat(night.maxExposureNs).isGreaterThan(33_333_333L)
        // AUTO on a dark scene resolves to night's frames and night's guard
        // and keeps AUTO's cap: it is the hand that chose the mode.
        val autoDark = CaptureModes.plan(CaptureMode.AUTO, dark, 28, 8)
        assertThat(autoDark.resolved).isEqualTo(CaptureMode.NIGHT)
        assertThat(autoDark.frames).isEqualTo(CaptureModes.AUTO_MAX_FRAMES)
        assertThat(autoDark.protectHighlights).isFalse()
        assertThat(autoDark.maxExposureNs).isEqualTo(CaptureModes.AUTO_EXPOSURE_NS)
        // Action's is shorter still.
        val action = CaptureModes.plan(CaptureMode.ACTION, bright, 28, 8)
        assertThat(action.maxExposureNs).isLessThan(auto.maxExposureNs)
    }

    @Test
    fun `modes cycle back to the start`() {
        var mode = CaptureMode.AUTO
        repeat(CaptureMode.entries.size) { mode = mode.next() }

        assertThat(mode).isEqualTo(CaptureMode.AUTO)
    }
}
