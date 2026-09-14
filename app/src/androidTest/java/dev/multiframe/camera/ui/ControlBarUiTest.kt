package dev.multiframe.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.SuperResMachine.Phase
import org.junit.Rule
import org.junit.Test

/**
 * The controls, rendered: the settings chips, the mode carousel, the chrome
 * opener and the action strip.
 *
 * [ControlBarTest] covers which controls exist and what state they are in,
 * which needs no device. This covers the part that does: that a spec reaches
 * the screen, that touching it reports the id the screen dispatches on, and
 * that a control the state says is disabled cannot be fired anyway.
 *
 * The last one is the point. The separation between settings and actions is
 * enforced by functions that cannot return each other's kinds, but the
 * *enabling* is a flag, and a flag that the renderer ignored would let a tap
 * take a photograph in the middle of another one.
 */
class ControlBarUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val capable = ControlBarState(
        streamOffered = true,
        superResOffered = true,
        rawBurstOffered = true,
        dngOffered = true,
    )

    private fun showSettings(state: ControlBarState): MutableList<String> {
        val fired = mutableListOf<String>()
        compose.setContent {
            MultiframeTheme {
                SettingsChips(ControlBar.settings(state), onControl = { fired.add(it) })
            }
        }
        compose.assumeRendered()
        return fired
    }

    private fun showCarousel(state: ControlBarState): MutableList<String> {
        val fired = mutableListOf<String>()
        compose.setContent {
            MultiframeTheme {
                ModeCarousel(ControlBar.carousel(state), onControl = { fired.add(it) })
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

    @Test
    fun touchingASettingReportsTheIdTheScreenDispatchesOn() {
        val fired = showSettings(capable)
        compose.onNodeWithContentDescription("MERGE ON").performClick()
        compose.onNodeWithContentDescription("GUIDES OFF").performClick()
        compose.onNodeWithContentDescription("HISTOGRAM OFF").performClick()
        assertThat(fired).containsExactly(ControlBar.MERGE, ControlBar.GUIDES, ControlBar.HISTOGRAM).inOrder()
    }

    @Test
    fun aValueIsShownBesideItsLabelRatherThanInsideIt() {
        showSettings(capable.copy(burstFrames = 12))
        // Two separate texts, which is what lets the value be set in the accent
        // and the label not be.
        compose.onNodeWithText("FRAMES").assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
    }

    /**
     * A capture in flight must not be reconfigurable, and the renderer has to
     * honour that rather than merely dimming it.
     */
    @Test
    fun aDisabledSettingCannotBeFired() {
        val fired = showSettings(capable.copy(busy = true))
        compose.onNodeWithContentDescription("MERGE ON").assertIsNotEnabled()
        compose.onNodeWithContentDescription("MERGE ON").performClick()
        assertThat(fired).isEmpty()
    }

    @Test
    fun theGuidesStayLiveWhileACaptureRuns() {
        val fired = showSettings(capable.copy(busy = true))
        compose.onNodeWithContentDescription("GUIDES OFF").performClick()
        assertThat(fired).containsExactly(ControlBar.GUIDES)
    }

    /** The settings screen wraps its chips; every one is on screen without scrolling. */
    @Test
    fun everySettingIsOnScreenAtOnce() {
        showSettings(capable)
        for (spec in ControlBar.settings(capable)) {
            compose.onNodeWithContentDescription(spec.value?.let { "${spec.label} $it" } ?: spec.label).assertIsDisplayed()
        }
    }

    // --- the carousel -----------------------------------------------------------

    @Test
    fun theCarouselShowsEveryModeAndReportsTheOneTouched() {
        val fired = showCarousel(capable)
        compose.onNodeWithContentDescription("AUTO").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("NIGHT").assertIsDisplayed()
        compose.onNodeWithContentDescription("SUPER RES").performScrollTo().performClick()
        assertThat(fired).containsExactly(ControlBar.modeId(CaptureMode.SUPER_RES))
        assertThat(ControlBar.modeOf(fired.single())).isEqualTo(CaptureMode.SUPER_RES)
    }

    @Test
    fun theCarouselIsHeldWhileSuperResRuns() {
        val fired = showCarousel(capable.copy(mode = "SUPER RES", superRes = Phase.RUNNING))
        compose.onNodeWithContentDescription("AUTO").assertIsNotEnabled()
        compose.onNodeWithContentDescription("AUTO").performClick()
        assertThat(fired).isEmpty()
    }

    @Test
    fun autoCarriesWhatItSettledOn() {
        showCarousel(capable.copy(resolvedMode = "NIGHT"))
        compose.onNodeWithContentDescription("AUTO NIGHT").assertIsDisplayed()
    }

    // --- the actions ------------------------------------------------------------

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
    fun aRunningSuperResCanStillBeStopped() {
        val fired = showActions(capable.copy(busy = true, superRes = Phase.RUNNING))
        compose.onNodeWithContentDescription("STOP").performClick()
        assertThat(fired).containsExactly(ControlBar.STOP)
    }

    @Test
    fun aStopAlreadyLandingTakesNoSecondTap() {
        val fired = showActions(capable.copy(busy = true, superRes = Phase.STOPPING))
        compose.onNodeWithContentDescription("STOPPING").assertIsNotEnabled()
        compose.onNodeWithContentDescription("STOPPING").performClick()
        assertThat(fired).isEmpty()
    }

    @Test
    fun aCameraWithoutRawRendersNoRawControls() {
        showActions(capable.copy(rawBurstOffered = false, dngOffered = false))
        compose.onNodeWithContentDescription("DNG").assertDoesNotExist()
        compose.onNodeWithContentDescription("RAW ×4").assertDoesNotExist()
    }

    // --- the chrome ---------------------------------------------------------------

    @Test
    fun theSettingsOpenerIsTheOnlyControlOverThePictureAndReportsItself() {
        val fired = mutableListOf<String>()
        compose.setContent {
            MultiframeTheme {
                ChromeOpeners(ControlBar.chrome(capable), onControl = { fired.add(it) })
            }
        }
        compose.assumeRendered()
        compose.onNodeWithContentDescription("SETTINGS").assertIsDisplayed().performClick()
        assertThat(fired).containsExactly(ControlBar.SETTINGS)
        compose.onNodeWithContentDescription("INFO").assertDoesNotExist()
        compose.onNodeWithContentDescription("PRO").assertDoesNotExist()
    }
}
