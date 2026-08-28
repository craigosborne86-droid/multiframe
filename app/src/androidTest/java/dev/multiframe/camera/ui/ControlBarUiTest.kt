package dev.multiframe.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * The control row and the action strip, rendered.
 *
 * [ControlBarTest] covers which controls exist and what state they are in,
 * which needs no device. This covers the part that does: that a spec reaches
 * the screen, that touching it reports the id the screen dispatches on, and
 * that a control the state says is disabled cannot be fired anyway.
 *
 * The last one is the point. The separation between settings and actions is
 * enforced by two functions that cannot return each other's kinds, but the
 * *enabling* is a flag, and a flag that the renderer ignored would let a tap
 * take a photograph in the middle of another one.
 */
class ControlBarUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val capable = ControlBarState(
        captureModeLabel = "AUTO",
        highlightGuardOffered = true,
        zslOffered = true,
        sweepOffered = true,
        rawBurstOffered = true,
        dngOffered = true,
    )

    private fun showRow(state: ControlBarState): MutableList<String> {
        val fired = mutableListOf<String>()
        compose.setContent {
            MultiframeTheme {
                ControlRow(ControlBar.modes(state), onControl = { fired.add(it) })
            }
        }
        compose.assumeRendered()
        return fired
    }

    private fun showActions(state: ControlBarState): MutableList<String> {
        val fired = mutableListOf<String>()
        compose.setContent {
            MultiframeTheme {
                ActionStrip(ControlBar.actions(state), onControl = { fired.add(it) })
            }
        }
        compose.assumeRendered()
        return fired
    }

    /**
     * The row scrolls, and on a narrow screen most of it starts off the edge --
     * which is deliberate, and does mean a test has to bring a control into
     * view before touching it, exactly as a thumb would.
     */
    @Test
    fun touchingAControlReportsTheIdTheScreenDispatchesOn() {
        val fired = showRow(capable)
        compose.onNodeWithContentDescription("MERGE ON").performScrollTo().performClick()
        compose.onNodeWithContentDescription("GUIDES GUIDES").performScrollTo().performClick()
        assertThat(fired).containsExactly(ControlBar.MERGE, ControlBar.GUIDES).inOrder()
    }

    @Test
    fun aValueIsShownBesideItsLabelRatherThanInsideIt() {
        showRow(capable.copy(burstFrames = 12))
        // Two separate texts, which is what lets the value be set in the accent
        // and the label not be.
        compose.onNodeWithText("FRAMES").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12").performScrollTo().assertIsDisplayed()
    }

    /**
     * A capture in flight must not be reconfigurable, and the renderer has to
     * honour that rather than merely dimming it.
     */
    @Test
    fun aDisabledControlCannotBeFired() {
        val fired = showRow(capable.copy(busy = true))
        compose.onNodeWithContentDescription("MERGE ON").assertIsNotEnabled()
        compose.onNodeWithContentDescription("MERGE ON").performScrollTo().performClick()
        assertThat(fired).isEmpty()
    }

    @Test
    fun theGuidesStayLiveWhileACaptureRuns() {
        val fired = showRow(capable.copy(busy = true))
        compose.onNodeWithContentDescription("GUIDES GUIDES").performScrollTo().performClick()
        assertThat(fired).containsExactly(ControlBar.GUIDES)
    }

    @Test
    fun theCapturesThatAreNotTheShutterAreInTheirOwnStrip() {
        val fired = showActions(capable.copy(burstFrames = 4))
        compose.onNodeWithContentDescription("RAW ×4").assertIsDisplayed()
        compose.onNodeWithContentDescription("DNG").performClick()
        assertThat(fired).containsExactly(ControlBar.DNG)
    }

    @Test
    fun noActionCanBeFiredWhileACaptureRuns() {
        val fired = showActions(capable.copy(busy = true))
        compose.onNodeWithContentDescription("DNG").assertIsNotEnabled()
        compose.onNodeWithContentDescription("DNG").performClick()
        compose.onNodeWithContentDescription("RAW ×4").performClick()
        assertThat(fired).isEmpty()
    }

    @Test
    fun aRunningSweepCanStillBeStopped() {
        val fired = showActions(capable.copy(busy = true, sweepRunning = true))
        compose.onNodeWithContentDescription("STOP SWEEP").performClick()
        assertThat(fired).containsExactly(ControlBar.SWEEP)
    }

    @Test
    fun aCameraWithoutRawRendersNoRawControls() {
        showActions(capable.copy(rawBurstOffered = false, dngOffered = false))
        compose.onNodeWithContentDescription("DNG").assertDoesNotExist()
        compose.onNodeWithContentDescription("RAW ×4").assertDoesNotExist()
    }
}
