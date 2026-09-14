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
        superResOffered = true,
        rawBurstOffered = true,
        dngOffered = true,
    )

    private fun ids(specs: List<ControlSpec>) = specs.map { it.id }

    /** Everything that is not an action, over every place it is drawn. */
    private fun toggles(state: ControlBarState) =
        ControlBar.settings(state) + ControlBar.chrome(state) + ControlBar.carousel(state)

    @Test
    fun nothingThatTakesAPhotographAppearsAmongTheToggles() {
        // Over every combination of the capabilities that gate an action, not
        // just the one the development phone happens to have.
        for (superRes in listOf(false, true)) {
            for (raw in listOf(false, true)) {
                for (dng in listOf(false, true)) {
                    for (busy in listOf(false, true)) {
                        for (phase in Phase.entries) {
                            val state = fullyCapable.copy(
                                superResOffered = superRes, rawBurstOffered = raw,
                                dngOffered = dng, busy = busy, superRes = phase,
                            )
                            assertThat(toggles(state).map { it.kind })
                                .doesNotContain(ControlKind.Action)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun everyActionIsAnAction() {
        assertThat(ControlBar.actions(fullyCapable).map { it.kind })
            .containsExactly(ControlKind.Action, ControlKind.Action)
        assertThat(ControlBar.actions(fullyCapable.copy(superRes = Phase.RUNNING)).map { it.kind })
            .containsExactly(ControlKind.Action)
    }

    @Test
    fun aCameraWithoutRawOffersNoRawControls() {
        val noRaw = fullyCapable.copy(rawBurstOffered = false, dngOffered = false)
        assertThat(ids(ControlBar.actions(noRaw)))
            .containsNoneOf(ControlBar.RAW_BURST, ControlBar.DNG)
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
    fun theSettingsCarryWhatWasOverThePictureAndTheDeveloperSwitches() {
        val settings = ids(ControlBar.settings(fullyCapable))
        assertThat(settings).containsAtLeast(
            ControlBar.MERGE, ControlBar.FRAMES, ControlBar.TIMER, ControlBar.ZSL,
            ControlBar.GUIDES, ControlBar.HISTOGRAM, ControlBar.ABOUT,
        )
        // The capture group first, the display after, the opener last.
        assertThat(settings.first()).isEqualTo(ControlBar.MERGE)
        assertThat(settings.last()).isEqualTo(ControlBar.ABOUT)
    }

    @Test
    fun aCameraWithoutAZeroShutterLagStreamOffersNoRingSwitch() {
        assertThat(ids(ControlBar.settings(bare))).doesNotContain(ControlBar.ZSL)
    }

    /**
     * Super Res needs the ring and switches it on itself. While the mode is
     * selected the switch is not the user's to flip: off would pull the ground
     * from under the mode.
     */
    @Test
    fun theRingSwitchIsHeldWhileSuperResIsSelected() {
        for (phase in Phase.entries) {
            val zsl = ControlBar.settings(fullyCapable.copy(superRes = phase, zslOn = true)).single { it.id == ControlBar.ZSL }
            assertThat(zsl.enabled).isEqualTo(phase == Phase.IDLE)
        }
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
    fun theCarouselOffersAutoNightActionAndSuperResWhereTheHardwareAllows() {
        assertThat(ids(ControlBar.carousel(fullyCapable))).containsExactly(
            ControlBar.modeId(CaptureMode.AUTO), ControlBar.modeId(CaptureMode.NIGHT),
            ControlBar.modeId(CaptureMode.ACTION), ControlBar.modeId(CaptureMode.SUPER_RES),
        ).inOrder()
        assertThat(ControlBar.carousel(fullyCapable).map { it.label }).containsExactly("AUTO", "NIGHT", "ACTION", "SUPER RES").inOrder()
    }

    @Test
    fun aPhoneWithNoLongerLensOrNoAttitudeIsOfferedNoSuperRes() {
        assertThat(ids(ControlBar.carousel(fullyCapable.copy(superResOffered = false))))
            .doesNotContain(ControlBar.modeId(CaptureMode.SUPER_RES))
    }

    @Test
    fun withoutARingThereIsOnlyAutoAndNoCarouselToDraw() {
        // The burst modes are plans for the ring; a phone without one has
        // AUTO, which is not a choice. `ModeCarousel` draws nothing for a
        // list of one.
        assertThat(ids(ControlBar.carousel(bare))).containsExactly(ControlBar.modeId(CaptureMode.AUTO))
    }

    @Test
    fun theSelectedModeIsTheHeavyOneAndAutoCarriesWhatItSettledOn() {
        val night = ControlBar.carousel(fullyCapable.copy(mode = "NIGHT"))
        assertThat(night.single { it.active }.label).isEqualTo("NIGHT")
        // AUTO's reading is what the scene made of it, shown only on AUTO.
        val auto = ControlBar.carousel(fullyCapable.copy(mode = "AUTO", resolvedMode = "NIGHT"))
        assertThat(auto.single { it.id == ControlBar.modeId(CaptureMode.AUTO) }.value).isEqualTo("NIGHT")
        assertThat(auto.single { it.id == ControlBar.modeId(CaptureMode.NIGHT) }.value).isNull()
        // Settled on itself: no reading.
        val plain = ControlBar.carousel(fullyCapable.copy(mode = "AUTO", resolvedMode = "AUTO"))
        assertThat(plain.single { it.id == ControlBar.modeId(CaptureMode.AUTO) }.value).isNull()
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
        assertThat(ControlBar.modeOf(ControlBar.MERGE)).isNull()
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
    fun withSuperResSelectedTheShutterIsTheOnlyCapture() {
        assertThat(ControlBar.actions(fullyCapable.copy(superRes = Phase.ARMED))).isEmpty()
        assertThat(ids(ControlBar.actions(fullyCapable.copy(superRes = Phase.RUNNING)))).containsExactly(ControlBar.STOP)
        assertThat(ids(ControlBar.actions(fullyCapable))).containsExactly(ControlBar.RAW_BURST, ControlBar.DNG).inOrder()
    }

    @Test
    fun everyActionIsRefusedWhileBusyUnlessItIsStoppingASuperRes() {
        val busy = ControlBar.actions(fullyCapable.copy(busy = true))
        assertThat(busy.map { it.enabled }).doesNotContain(true)
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
        // A ring too small for even the smallest step still has to offer one.
        assertThat(ControlBar.burstStepsUpTo(0)).containsExactly(1)
    }

    @Test
    fun burstCyclingWrapsAndStaysWithinTheCeiling() {
        assertThat(ControlBar.nextBurst(4, 10)).isEqualTo(8)
        assertThat(ControlBar.nextBurst(8, 10)).isEqualTo(1)
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
