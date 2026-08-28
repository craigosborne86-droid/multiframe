package dev.multiframe.camera.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The control bar's gating and grouping.
 *
 * This is the part of the interface that used to need a phone to check. Every
 * control is supposed to be gated on something the hardware reported, and the
 * only way to see that a camera without raw support offers no raw controls was
 * to hold such a camera -- which the development device is not. Building the
 * bar from plain state makes it a unit test.
 *
 * The invariant worth the most here is the separation: a control that takes a
 * photograph must never appear among the toggles. That is what went wrong in
 * the first version of this interface, where `DNG` and `MERGE ON` were the same
 * kind of object and one of them wrote a file.
 */
class ControlBarTest {

    private val bare = ControlBarState()

    private val fullyCapable = ControlBarState(
        captureModeLabel = "AUTO",
        highlightGuardOffered = true,
        zslOffered = true,
        sweepOffered = true,
        rawBurstOffered = true,
        dngOffered = true,
    )

    private fun ids(specs: List<ControlSpec>) = specs.map { it.id }

    @Test
    fun nothingThatTakesAPhotographAppearsAmongTheToggles() {
        // Over every combination of the capabilities that gate an action, not
        // just the one the development phone happens to have.
        for (sweep in listOf(false, true)) {
            for (raw in listOf(false, true)) {
                for (dng in listOf(false, true)) {
                    for (busy in listOf(false, true)) {
                        val state = fullyCapable.copy(
                            sweepOffered = sweep, rawBurstOffered = raw,
                            dngOffered = dng, busy = busy,
                        )
                        assertThat(ControlBar.modes(state).map { it.kind })
                            .doesNotContain(ControlKind.Action)
                    }
                }
            }
        }
    }

    @Test
    fun everyActionIsAnAction() {
        assertThat(ControlBar.actions(fullyCapable).map { it.kind })
            .containsExactly(ControlKind.Action, ControlKind.Action, ControlKind.Action)
    }

    @Test
    fun aCameraWithoutRawOffersNoRawControls() {
        val noRaw = fullyCapable.copy(rawBurstOffered = false, dngOffered = false)
        assertThat(ids(ControlBar.actions(noRaw)))
            .containsNoneOf(ControlBar.RAW_BURST, ControlBar.DNG)
    }

    @Test
    fun aCameraWithoutAZeroShutterLagStreamOffersNoMode() {
        assertThat(ids(ControlBar.modes(bare)))
            .containsNoneOf(ControlBar.CAPTURE_MODE, ControlBar.ZSL)
    }

    /**
     * Six, because a phone shows six.
     *
     * The row was nine and on a Pixel 9 Pro XL about two and a half were
     * visible before the pinned openers, which reads as unfinished however
     * carefully the rest is drawn. `A/B` and `GUARD` moved into the PRO panel:
     * neither is touched while composing a shot.
     */
    @Test
    fun theRowIsShortEnoughToFitAPhone() {
        val settings = ControlBar.modes(fullyCapable)
            .filter { it.kind != ControlKind.Opener }
        assertThat(settings).hasSize(6)
        assertThat(ids(ControlBar.modes(fullyCapable)))
            .containsNoneOf(ControlBar.AB, ControlBar.GUARD)
    }

    @Test
    fun aPhoneWithNoLongerLensIsOfferedNoSweep() {
        assertThat(ids(ControlBar.actions(fullyCapable.copy(sweepOffered = false))))
            .doesNotContain(ControlBar.SWEEP)
    }

    /**
     * A sweep in progress has to be stoppable even though a sweep makes the app
     * busy. Without this the only way out of one is to leave the app.
     */
    @Test
    fun aRunningSweepCanStillBeStopped() {
        val sweeping = fullyCapable.copy(busy = true, sweepRunning = true)
        val stop = ControlBar.actions(sweeping).single { it.id == ControlBar.SWEEP }
        assertThat(stop.enabled).isTrue()
        assertThat(stop.label).isEqualTo("STOP SWEEP")
    }

    @Test
    fun captureIsNotReconfiguredWhileOneIsRunning() {
        val busy = ControlBar.modes(fullyCapable.copy(busy = true))
        val changesTheCapture = busy.filter {
            it.id !in setOf(ControlBar.GUIDES, ControlBar.PRO, ControlBar.ABOUT)
        }
        assertThat(changesTheCapture.map { it.enabled }).doesNotContain(true)
        // The guides and the two openers touch nothing the capture reads, so
        // they stay live -- a frozen interface during a four-second night
        // capture reads as a hang.
        for (id in listOf(ControlBar.GUIDES, ControlBar.PRO, ControlBar.ABOUT)) {
            assertThat(busy.single { it.id == id }.enabled).isTrue()
        }
    }

    @Test
    fun everyActionIsRefusedWhileBusyUnlessItIsStoppingASweep() {
        val busy = ControlBar.actions(fullyCapable.copy(busy = true))
        assertThat(busy.map { it.enabled }).doesNotContain(true)
    }

    @Test
    fun aValueCarryingControlSaysWhatItIsAndWhatItReads() {
        val specs = ControlBar.modes(fullyCapable.copy(burstFrames = 8, timerSeconds = 3))
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
        for (spec in ControlBar.modes(fullyCapable) + ControlBar.actions(fullyCapable)) {
            assertThat(spec.value).isNotEqualTo(spec.label)
        }
    }

    /**
     * The accent is spent on numbers and live readings. "OFF" is neither, and
     * colouring it made a timer that was switched off look like one running.
     */
    @Test
    fun theWordForHavingNoValueIsNotDressedAsAReading() {
        val off = ControlBar.modes(fullyCapable.copy(timerSeconds = 0, guidesOn = false))
        assertThat(off.single { it.id == ControlBar.TIMER }.valueIsReading).isFalse()
        assertThat(off.single { it.id == ControlBar.GUIDES }.valueIsReading).isFalse()

        val on = ControlBar.modes(fullyCapable.copy(timerSeconds = 3, guidesOn = true))
        assertThat(on.single { it.id == ControlBar.TIMER }.valueIsReading).isTrue()
        assertThat(on.single { it.id == ControlBar.GUIDES }.valueIsReading).isTrue()
        // A frame count is always a reading -- there is no "off" for it.
        assertThat(on.single { it.id == ControlBar.FRAMES }.valueIsReading).isTrue()
    }

    @Test
    fun theOpenersComeLast() {
        val order = ids(ControlBar.modes(fullyCapable))
        assertThat(order.takeLast(2)).containsExactly(ControlBar.PRO, ControlBar.ABOUT).inOrder()
    }

    @Test
    fun theRowIsStableForTheSameState() {
        assertThat(ControlBar.modes(fullyCapable)).isEqualTo(ControlBar.modes(fullyCapable))
        assertThat(ControlBar.actions(fullyCapable)).isEqualTo(ControlBar.actions(fullyCapable))
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
