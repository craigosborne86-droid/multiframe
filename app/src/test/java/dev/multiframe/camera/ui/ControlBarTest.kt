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
        ControlBar.settings(state) + ControlBar.chrome(state) + ControlBar.carousel(state) + ControlBar.pro(state)

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

    @Test
    fun everyActionIsAnAction() {
        assertThat(ControlBar.actions(fullyCapable.copy(superRes = Phase.RUNNING)).map { it.kind })
            .containsExactly(ControlKind.Action)
    }

    /**
     * The shutter is the only capture. `RAW xN` and `DNG` were squared chips
     * floating over it; the DNG is a setting beside every JPEG now, and the
     * sequential burst is a fallback with no chip of its own.
     */
    @Test
    fun theShutterBarCarriesNoCaptureButTheShutter() {
        assertThat(ControlBar.actions(fullyCapable)).isEmpty()
        assertThat(ControlBar.actions(fullyCapable.copy(superRes = Phase.ARMED))).isEmpty()
        assertThat(ids(ControlBar.actions(fullyCapable.copy(superRes = Phase.RUNNING)))).containsExactly(ControlBar.STOP)
    }

    // --- the chrome ---------------------------------------------------------

    /**
     * The top of the viewfinder was six settings and two openers, every one
     * a word over the photograph. It is one opener now; the six are behind it.
     */
    @Test
    fun theOnlyThingOverThePictureIsTheSettingsOpener() {
        val chrome = ControlBar.chrome(fullyCapable)
        assertThat(ids(chrome)).containsExactly(ControlBar.SETTINGS)
        assertThat(chrome.single().kind).isEqualTo(ControlKind.Opener)
        // And it is reachable whatever is happening: the settings screen's
        // own controls are the ones that hold still.
        assertThat(ControlBar.chrome(fullyCapable.copy(busy = true, superRes = Phase.RUNNING)).single().enabled).isTrue()
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
            val labels = (toggles(state) + ControlBar.actions(state)).map { it.label.uppercase() }
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

    // --- the manual drawer --------------------------------------------------------

    @Test
    fun theProOpenerSitsBesideTheShutterWhereTheHardwareTakesManualControl() {
        val pro = ControlBar.pro(fullyCapable).single()
        assertThat(pro.id).isEqualTo(ControlBar.PRO)
        assertThat(pro.kind).isEqualTo(ControlKind.Opener)
        assertThat(pro.active).isFalse()
        assertThat(ControlBar.pro(fullyCapable.copy(proOpen = true)).single().active).isTrue()
        // A camera with no manual sensor and no focusing lens has no drawer.
        assertThat(ControlBar.pro(fullyCapable.copy(manualOffered = false))).isEmpty()
        // And it is not over the picture: the chrome is the settings opener alone.
        assertThat(ids(ControlBar.chrome(fullyCapable))).doesNotContain(ControlBar.PRO)
    }

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

    // --- the carousel -----------------------------------------------------------

    @Test
    fun theCarouselOffersOnlyPhotoAndNightOnEveryCamera() {
        for (state in listOf(fullyCapable, bare)) {
            assertThat(ControlBar.carousel(state).map { it.label }).containsExactly("PHOTO", "NIGHT").inOrder()
        }
    }

    @Test
    fun selectedModeIsTheHeavyOne() {
        for (mode in CaptureMode.entries) {
            assertThat(ControlBar.carousel(fullyCapable.copy(mode = mode.label)).single { it.active }.label)
                .isEqualTo(mode.label)
        }
    }

    @Test
    fun theCarouselIsHeldWhileACaptureIsInFlight() {
        for (state in listOf(fullyCapable.copy(busy = true), fullyCapable.copy(superRes = Phase.RUNNING), fullyCapable.copy(superRes = Phase.STOPPING))) {
            assertThat(ControlBar.carousel(state).map { it.enabled }).doesNotContain(true)
        }
        // Armed is not in flight: the user can still change their mind.
        assertThat(ControlBar.carousel(fullyCapable.copy(superRes = Phase.ARMED)).map { it.enabled }).doesNotContain(false)
    }

    @Test
    fun modeIdsRoundTrip() {
        for (mode in CaptureMode.entries) {
            assertThat(ControlBar.modeOf(ControlBar.modeId(mode))).isEqualTo(mode)
        }
        assertThat(ControlBar.modeOf(ControlBar.FRAMES)).isNull()
        assertThat(ControlBar.modeOf("mode.TELEPATHY")).isNull()
    }

    // --- the actions ------------------------------------------------------------

    /**
     * A Super Res in progress has to be stoppable even though it makes the app
     * busy. Without this the only way out of one is to leave the app -- which
     * is what the first field test did.
     */
    @Test
    fun aRunningSuperResCanStillBeStopped() {
        val running = fullyCapable.copy(busy = true, superRes = Phase.RUNNING)
        val stop = ControlBar.actions(running).single { it.id == ControlBar.STOP }
        assertThat(stop.enabled).isTrue()
        assertThat(stop.label).isEqualTo("STOP")
    }

    @Test
    fun aStopAlreadyAskedForIsShownLandingAndTakesNoSecondTap() {
        val stopping = fullyCapable.copy(busy = true, superRes = Phase.STOPPING)
        val stop = ControlBar.actions(stopping).single { it.id == ControlBar.STOP }
        assertThat(stop.enabled).isFalse()
        assertThat(stop.label).isEqualTo("STOPPING")
    }

    @Test
    fun theOnlyActionIsStopAndOnlyWhileRunning() {
        for (phase in Phase.entries) {
            val actions = ControlBar.actions(fullyCapable.copy(superRes = phase, busy = phase.inFlight))
            if (phase.inFlight) assertThat(ids(actions)).containsExactly(ControlBar.STOP)
            else assertThat(actions).isEmpty()
        }
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
        for (spec in toggles(fullyCapable) + ControlBar.actions(fullyCapable)) {
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
        assertThat(ControlBar.actions(fullyCapable)).isEqualTo(ControlBar.actions(fullyCapable))
    }

    /** Nothing anywhere is still called a mosaic or a sweep. */
    @Test
    fun theModeIsCalledSuperResEverywhere() {
        for (phase in Phase.entries) {
            val state = fullyCapable.copy(superRes = phase, mode = "SUPER RES")
            for (spec in toggles(state) + ControlBar.actions(state)) {
                assertThat(spec.label.lowercase()).doesNotContain("mosaic")
                assertThat(spec.label.lowercase()).doesNotContain("sweep")
                assertThat(spec.id.lowercase()).doesNotContain("mosaic")
            }
        }
    }

    @Test
    fun burstSizesNeverExceedWhatTheRingCanHold() {
        assertThat(ControlBar.burstStepsUpTo(10)).containsExactly(1, 2, 4, 8).inOrder()
        assertThat(ControlBar.burstStepsUpTo(32)).containsExactly(1, 2, 4, 8, 12, 16, 24, 28).inOrder()
        // A ring too small for even the smallest step still has to offer one.
        assertThat(ControlBar.burstStepsUpTo(0)).containsExactly(1)
    }

    @Test
    fun burstCyclingWrapsAndStaysWithinTheCeiling() {
        assertThat(ControlBar.nextBurst(4, 10)).isEqualTo(8)
        assertThat(ControlBar.nextBurst(8, 10)).isEqualTo(1)
        assertThat(ControlBar.nextBurst(24, 32)).isEqualTo(28)
        assertThat(ControlBar.nextBurst(28, 32)).isEqualTo(1)
        // A count above the ceiling -- the ring shrank under it -- drops back
        // to a legal one rather than wrapping from a position that is not there.
        assertThat(ControlBar.nextBurst(32, 10)).isEqualTo(1)
    }

    @Test
    fun theTimerCyclesOffThreeTenAndBack() {
        assertThat(ControlBar.nextTimer(0)).isEqualTo(3)
        assertThat(ControlBar.nextTimer(3)).isEqualTo(10)
        assertThat(ControlBar.nextTimer(10)).isEqualTo(0)
    }
}
