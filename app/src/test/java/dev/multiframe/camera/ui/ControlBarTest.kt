package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.SuperResMachine.Phase
import org.junit.Test

/**
 * The controls' gating and grouping.
 *
 * This is the part of the interface that used to need a phone to check. Every
 * control is supposed to be gated on something the hardware reported, and the
 * only way to see that a camera without raw support offers no raw controls was
 * to hold such a camera -- which the development device is not. Building the
 * controls from plain state makes it a unit test.
 *
 * The invariant worth the most here is the separation: a control that takes a
 * photograph must never appear among the toggles. That is what went wrong in
 * the first version of this interface, where `DNG` and `MERGE ON` were the same
 * kind of object and one of them wrote a file.
 */
class ControlBarTest {

    private val bare = ControlBarState()

    private val fullyCapable = ControlBarState(
        streamOffered = true,
        manualOffered = true,
        superResOffered = true,
    )

    private fun ids(specs: List<ControlSpec>) = specs.map { it.id }

    /** Everything that is not an action, over every place it is drawn. */
    private fun toggles(state: ControlBarState) =
        ControlBar.settings(state)

    @Test
    fun nothingThatTakesAPhotographAppearsAmongTheToggles() {
        // Over every combination of the capabilities that gate a control, not
        // just the one the development phone happens to have.
        for (superRes in listOf(false, true)) {
            for (manual in listOf(false, true)) {
                for (busy in listOf(false, true)) {
                    for (phase in Phase.entries) {
                        val state = fullyCapable.copy(
                            superResOffered = superRes, manualOffered = manual,
                            busy = busy, superRes = phase,
                        )
                        assertThat(toggles(state).map { it.kind })
                            .doesNotContain(ControlKind.Action)
                    }
                }
            }
        }
    }

    // --- the settings screen --------------------------------------------------

    @Test
    fun theSettingsCarryWhatWasOverThePictureAndNothingADeveloperFlips() {
        val settings = ids(ControlBar.settings(fullyCapable))
        assertThat(settings).containsExactly(
            ControlBar.FRAMES, ControlBar.TIMER, ControlBar.DNG,
            ControlBar.GUIDES, ControlBar.HISTOGRAM, ControlBar.ABOUT,
        ).inOrder()
        // The capture group first, the display after, the opener last.
        assertThat(settings.first()).isEqualTo(ControlBar.FRAMES)
        assertThat(settings.last()).isEqualTo(ControlBar.ABOUT)
    }

    /**
     * The ring, the merge, the A/B pair, the bracket and the ISP switch were
     * user-facing once. None is a photograph's decision; each is the engine
     * or an ablation, and none has a chip anywhere any more.
     */
    @Test
    fun noDeveloperSwitchIsUserFacing() {
        for (phase in Phase.entries) {
            val state = fullyCapable.copy(superRes = phase)
            val labels = toggles(state).map { it.label.uppercase() }
            for (word in listOf("ZSL", "MERGE", "A/B", "DCG", "ISP", "RAW")) {
                assertThat(labels.none { it.contains(word) }).isTrue()
            }
        }
    }

    @Test
    fun theDngIsASettingOnByDefault() {
        val dng = ControlBar.settings(fullyCapable).single { it.id == ControlBar.DNG }
        assertThat(dng.kind).isEqualTo(ControlKind.Mode)
        assertThat(dng.label).isEqualTo("DNG ON")
        assertThat(dng.active).isTrue()
        assertThat(ControlBar.settings(fullyCapable.copy(dngOn = false)).single { it.id == ControlBar.DNG }.label).isEqualTo("DNG OFF")
    }

    // --- during a capture ---------------------------------------------------------

    @Test
    fun captureIsNotReconfiguredWhileOneIsRunning() {
        for (state in listOf(fullyCapable.copy(busy = true), fullyCapable.copy(superRes = Phase.RUNNING), fullyCapable.copy(superRes = Phase.STOPPING))) {
            val settings = ControlBar.settings(state)
            val changesTheCapture = settings.filter {
                it.id !in setOf(ControlBar.GUIDES, ControlBar.HISTOGRAM, ControlBar.ABOUT)
            }
            assertThat(changesTheCapture.map { it.enabled }).doesNotContain(true)
            // The guides, the histogram and the opener touch nothing the
            // capture reads, so they stay live -- a frozen interface during a
            // four-second night capture reads as a hang.
            for (id in listOf(ControlBar.GUIDES, ControlBar.HISTOGRAM, ControlBar.ABOUT)) {
                assertThat(settings.single { it.id == id }.enabled).isTrue()
            }
        }
    }

    // --- mode ids -----------------------------------------------------------------

    @Test
    fun modeIdsRoundTrip() {
        for (mode in CaptureMode.entries) {
            assertThat(ControlBar.modeOf(ControlBar.modeId(mode))).isEqualTo(mode)
        }
        assertThat(ControlBar.modeOf(ControlBar.FRAMES)).isNull()
        assertThat(ControlBar.modeOf("mode.TELEPATHY")).isNull()
    }

    // --- readings ---------------------------------------------------------------

    @Test
    fun aValueCarryingControlSaysWhatItIsAndWhatItReads() {
        val specs = ControlBar.settings(fullyCapable.copy(burstFrames = 8, timerSeconds = 3))
        val frames = specs.single { it.id == ControlBar.FRAMES }
        assertThat(frames.kind).isEqualTo(ControlKind.Cycle)
        // The label names the thing and the value carries the reading, rather
        // than "8 FRAMES" as one string -- which is what let a value be
        // mistaken for a mode.
        assertThat(frames.label).isEqualTo("FRAMES")
        assertThat(frames.value).isEqualTo("8")
        assertThat(specs.single { it.id == ControlBar.TIMER }.value).isEqualTo("3s")
    }

    /**
     * Pulling the label and the value apart put "GUIDES GUIDES" on the screen,
     * because the mode's name was also the string it showed when off.
     */
    @Test
    fun noControlRepeatsItsOwnNameAsItsValue() {
        for (spec in toggles(fullyCapable)) {
            assertThat(spec.value).isNotEqualTo(spec.label)
        }
    }

    /**
     * The accent is spent on numbers and live readings. "OFF" is neither, and
     * colouring it made a timer that was switched off look like one running.
     */
    @Test
    fun theWordForHavingNoValueIsNotDressedAsAReading() {
        val off = ControlBar.settings(fullyCapable.copy(timerSeconds = 0, guidesOn = false))
        assertThat(off.single { it.id == ControlBar.TIMER }.valueIsReading).isFalse()
        assertThat(off.single { it.id == ControlBar.GUIDES }.valueIsReading).isFalse()

        val on = ControlBar.settings(fullyCapable.copy(timerSeconds = 3, guidesOn = true))
        assertThat(on.single { it.id == ControlBar.TIMER }.valueIsReading).isTrue()
        assertThat(on.single { it.id == ControlBar.GUIDES }.valueIsReading).isTrue()
        // A frame count is always a reading -- there is no "off" for it.
        assertThat(on.single { it.id == ControlBar.FRAMES }.valueIsReading).isTrue()
    }

    @Test
    fun theControlsAreStableForTheSameState() {
        assertThat(toggles(fullyCapable)).isEqualTo(toggles(fullyCapable))
            }

    /** Nothing anywhere is still called a mosaic or a sweep. */
    @Test
    fun theModeIsCalledSuperResEverywhere() {
        for (phase in Phase.entries) {
            val state = fullyCapable.copy(superRes = phase, mode = "SUPER RES")
            for (spec in toggles(state)) {
                assertThat(spec.label.lowercase()).doesNotContain("mosaic")
                assertThat(spec.label.lowercase()).doesNotContain("sweep")
                assertThat(spec.id.lowercase()).doesNotContain("mosaic")
            }
        }
    }

    @Test
    fun burstSizesNeverExceedWhatTheRingCanHold() {
        assertThat(dev.multiframe.camera.pipeline.FrameCountPolicy.stepsUpTo(10)).containsExactly(2, 4, 8).inOrder()
        assertThat(dev.multiframe.camera.pipeline.FrameCountPolicy.stepsUpTo(32)).containsExactly(2, 4, 8, 12, 16, 24, 28).inOrder()
        // A ring too small for even the smallest step still has to offer one.
        assertThat(dev.multiframe.camera.pipeline.FrameCountPolicy.stepsUpTo(0)).containsExactly(2)
    }

    @Test
    fun burstCyclingWrapsAndStaysWithinTheCeiling() {
        assertThat(ControlBar.nextBurst(4, 10)).isEqualTo(8)
        assertThat(ControlBar.nextBurst(8, 10)).isEqualTo(2)
        assertThat(ControlBar.nextBurst(24, 32)).isEqualTo(28)
        assertThat(ControlBar.nextBurst(28, 32)).isEqualTo(2)
        // A count above the ceiling -- the ring shrank under it -- drops back
        // to a legal one rather than wrapping from a position that is not there.
        assertThat(ControlBar.nextBurst(32, 10)).isEqualTo(2)
    }

    /** A press never takes one frame, so no setting offers one; a stored 1 folds up. */
    @Test
    fun noBurstSettingTakesASingleFrame() {
        assertThat(dev.multiframe.camera.pipeline.FrameCountPolicy.STEPS).doesNotContain(1)
        assertThat(dev.multiframe.camera.pipeline.FrameCountPolicy.normalize(1)).isEqualTo(2)
        assertThat(dev.multiframe.camera.pipeline.FrameCountPolicy.normalize(0)).isEqualTo(2)
    }

    @Test
    fun theTimerCyclesOffThreeTenAndBack() {
        assertThat(ControlBar.nextTimer(0)).isEqualTo(3)
        assertThat(ControlBar.nextTimer(3)).isEqualTo(10)
        assertThat(ControlBar.nextTimer(10)).isEqualTo(0)
    }
}
