package dev.multiframe.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.R
import dev.multiframe.camera.pipeline.CaptureMode
import dev.multiframe.camera.pipeline.CameraCapabilities
import dev.multiframe.camera.pipeline.ManualSettings
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Sensor-free interaction tests: positions come from the rendered control state. */
class RangefinderMechanismsTest {
    @get:Rule val compose = createComposeRule()

    private fun highlight() = compose.onNodeWithTag("rangefinder-turret-highlight")
        .fetchSemanticsNode().config[TurretHighlightIndex]

    @Test fun lensAndModeAreIndividuallyAccessibleAndExposeSelection() {
        var lens by mutableIntStateOf(1)
        var mode by mutableStateOf(CaptureMode.PHOTO)
        compose.setContent {
            Revision05ConsoleFixture(lens = lens, onLens = { lens = it },
                mode = mode, onMode = { mode = it })
        }
        val lensNode = compose.onNodeWithTag("mechanical-lens-2")
        lensNode.assertHasClickAction().assertIsNotSelected()
        lensNode.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        lensNode.assertIsSelected()
        assertThat(lens).isEqualTo(2)
        val modeNode = compose.onNodeWithTag("rangefinder-mode-night")
        modeNode.assertHasClickAction().assertIsNotSelected()
        modeNode.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        modeNode.assertIsSelected()
        assertThat(mode).isEqualTo(CaptureMode.NIGHT)
        assertThat(modeNode.fetchSemanticsNode().config[SemanticsProperties.Role])
            .isEqualTo(androidx.compose.ui.semantics.Role.Button)
    }

    @Test fun serviceSwitchExposesItsStateAndAction() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            ServiceRow("GUARD", if (enabled) "ON" else "OFF", active = enabled,
                onClick = { enabled = !enabled })
        }
        compose.onNodeWithContentDescription("GUARD OFF").assertIsOff().assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("GUARD ON").assertIsOn()
    }

    @Test fun proToggleHasAVisibleSelectedState() {
        var engaged by mutableStateOf(false)
        compose.setContent { Revision05ConsoleFixture(open = engaged, onPro = { engaged = !engaged }) }
        val toggle = compose.onNodeWithTag("rangefinder-pro")
        assertThat(toggle.fetchSemanticsNode().config[SemanticsProperties.Selected]).isFalse()
        toggle.performClick()
        assertThat(toggle.fetchSemanticsNode().config[SemanticsProperties.Selected]).isTrue()
        toggle.assertContentDescriptionEquals("Pro controls: Hide Pro")
    }

    @Test fun torchToggleIsReachableAndReflectsItsOnState() {
        var enabled by mutableStateOf(false)
        compose.setContent { Revision05ConsoleFixture(torch = enabled, onTorch = { enabled = !enabled }) }
        val torch = compose.onNodeWithTag("rangefinder-torch")
        torch.assertContentDescriptionEquals("Torch: Light")
        assertThat(torch.fetchSemanticsNode().config[SemanticsProperties.Selected]).isFalse()
        torch.performClick()
        torch.assertContentDescriptionEquals("Torch: Light")
        assertThat(torch.fetchSemanticsNode().config[SemanticsProperties.Selected]).isTrue()
    }

    @Test fun fixedConsoleNeverRemeasuresWhenProChanges() {
        var open by mutableStateOf(false)
        var captures by mutableIntStateOf(0)
        compose.setContent {
            Revision05ConsoleFixture(open = open, onPro = { open = !open }, onCapture = { captures++ })
        }
        val closed = compose.onNodeWithTag("camera-control-band").fetchSemanticsNode().boundsInRoot
        val shutterClosed = compose.onNodeWithTag("persistent-shutter").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("persistent-shutter").performClick()
        compose.onNodeWithTag("rangefinder-pro").performClick()
        compose.waitForIdle()
        val opened = compose.onNodeWithTag("camera-control-band").fetchSemanticsNode().boundsInRoot
        val shutterOpen = compose.onNodeWithTag("persistent-shutter").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("persistent-shutter").performClick()
        assertThat(opened).isEqualTo(closed)
        assertThat(shutterOpen).isEqualTo(shutterClosed)
        assertThat(captures).isEqualTo(2)
    }

    @Test fun focalTurretTapAndDragMoveHighlightWithOvershoot() {
        compose.mainClock.autoAdvance = false
        var selected by mutableIntStateOf(0)
        compose.setContent { MechanicalTurret(selected, true, { selected = it }, Modifier.width(400.dp)) }
        val turret = compose.onNodeWithTag("rangefinder-turret")
        turret.performTouchInput { click(Offset((center.x * 2f) * .9f, center.y)) }
        assertThat(selected).isEqualTo(4)
        val positions = mutableListOf<Float>()
        repeat(45) { compose.mainClock.advanceTimeByFrame(); positions += highlight() }
        assertThat(positions.max()).isGreaterThan(4.03f)
        assertThat(highlight()).isWithin(.03f).of(4f)

        turret.performTouchInput {
            down(Offset((center.x * 2f) * .9f, center.y))
            moveTo(Offset((center.x * 2f) * .1f, center.y))
            up()
        }
        assertThat(selected).isEqualTo(0)
        compose.mainClock.advanceTimeBy(800)
        assertThat(highlight()).isWithin(.03f).of(0f)
    }

    @Test fun subPixelLabelAppearsOnlyAtTheTwoMergePositions() {
        var selected by mutableIntStateOf(1)
        compose.setContent { MechanicalTurret(selected, true, { selected = it }, Modifier.width(400.dp)) }
        compose.onNodeWithTag("rangefinder-subpixel-label").assertDoesNotExist()
        val turret = compose.onNodeWithTag("rangefinder-turret")
        turret.performTouchInput { click(Offset((center.x * 2f) * .5f, center.y)) }
        compose.onNodeWithTag("rangefinder-subpixel-label").assertTextEquals("SUB-PIXEL OPTICAL MERGE")
        turret.performTouchInput { click(Offset((center.x * 2f) * .9f, center.y)) }
        compose.onNodeWithTag("rangefinder-subpixel-label").assertTextEquals("SUB-PIXEL OPTICAL MERGE")
        turret.performTouchInput { click(Offset((center.x * 2f) * .7f, center.y)) }
        compose.onNodeWithTag("rangefinder-subpixel-label").assertDoesNotExist()
        // Each fresh gesture must use the current selection, including a tap
        // back to the stop selected when this control was first composed.
        turret.performTouchInput { click(Offset((center.x * 2f) * .3f, center.y)) }
        assertThat(selected).isEqualTo(1)
        compose.onNodeWithTag("mechanical-lens-1").assertIsSelected()
    }

    @Test fun enlargedFontTurretLabelsStayOnOneLineInsideTheirStops() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                MechanicalTurret(1,true,{},Modifier.width(320.dp))
            }
        }
        for (label in listOf("13mm","24mm","48mm","120mm","240mm")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(label,useUnmergedTree=true).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertThat(layouts).hasSize(1)
            assertThat(layouts.single().lineCount).isEqualTo(1)
            assertThat(layouts.single().hasVisualOverflow).isFalse()
        }
    }

    @Test fun photoNightSelectionRetainsManualProfileAndPersistentShutter() {
        var mode by mutableStateOf(CaptureMode.PHOTO)
        var open by mutableStateOf(true)
        compose.setContent { Revision05ConsoleFixture(mode = mode, onMode = { mode = it },
            open = open, onPro = { open = !open }, manual = true) }
        val shutter = compose.onNodeWithTag("persistent-shutter").fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithTag("fixture-preview").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("rangefinder-mode-night").performClick().assertIsSelected()
        compose.onNodeWithTag("rangefinder-pro").performClick().assertIsSelected()
        compose.onNodeWithTag("revision05-replacement-panel").assertDoesNotExist()
        compose.onNodeWithTag("rangefinder-mode-photo").performClick().assertIsSelected()
        compose.onNodeWithTag("mechanical-mode-2").assertDoesNotExist()
        assertThat(compose.onNodeWithTag("persistent-shutter").fetchSemanticsNode().boundsInRoot).isEqualTo(shutter)
        assertThat(compose.onNodeWithTag("fixture-preview").fetchSemanticsNode().boundsInRoot).isEqualTo(preview)
    }

    @Test fun framesDialReachesTheSharedTwentyEightFrameCeilingAndTracksExternalChanges() {
        var frames by mutableIntStateOf(16)
        compose.setContent {
            RangefinderDial(ManualSettings(), cameraCaps(), frames = frames, frameCeiling = 28,
                tab = RangefinderTab.FRAMES, onTab = {}, onSettings = {},
                onFrames = { frames = it }, modifier = Modifier.width(400.dp))
        }
        compose.onNodeWithText("16f", substring = true).assertExists()
        compose.onNodeWithText("›").performClick()
        assertThat(frames).isEqualTo(24)
        compose.onNodeWithText("›").performClick()
        assertThat(frames).isEqualTo(28)
        compose.onNodeWithText("28f", substring = true).assertExists()
        compose.onNodeWithText("›").performClick()
        assertThat(frames).isEqualTo(28)
        compose.onNodeWithText("dB", substring = true).assertDoesNotExist()

        compose.runOnIdle { frames = 8 }
        compose.onNodeWithText("8f", substring = true).assertExists()
    }

    @Test fun dialCoastsAfterReleaseAndVelocityDecays() {
        compose.mainClock.autoAdvance = false
        var settings by mutableStateOf(ManualSettings())
        var valueChanges = 0
        compose.setContent {
            RangefinderDial(settings, cameraCaps(), frames = 8, frameCeiling = 28,
                tab = RangefinderTab.SHUTTER, onTab = {}, onSettings = {
                    settings = it
                    valueChanges++
                },
                onFrames = {}, modifier = Modifier.width(400.dp))
        }
        val ruler = compose.onNodeWithTag("rangefinder-dial-ruler")
        ruler.performTouchInput {
            swipe(Offset((center.x * 2f) * .8f, center.y), Offset((center.x * 2f) * .2f, center.y), 96)
        }
        fun travel() = ruler.fetchSemanticsNode().config[DialTravelPx]
        fun offset() = ruler.fetchSemanticsNode().config[DialOffsetPx]
        val released = travel()
        val changesAtRelease = valueChanges
        compose.mainClock.advanceTimeByFrame() // first frame seeds the decay clock
        compose.mainClock.advanceTimeByFrame()
        val first = travel()
        compose.mainClock.advanceTimeByFrame()
        val second = travel()
        compose.mainClock.advanceTimeByFrame()
        val third = travel()
        assertThat(first).isLessThan(released)
        val d1 = kotlin.math.abs(second - first)
        val d2 = kotlin.math.abs(third - second)
        assertThat(d1).isGreaterThan(.28f)
        assertThat(d2 / d1).isAtLeast(.70f)
        assertThat(d2 / d1).isAtMost(1.05f)
        compose.mainClock.advanceTimeBy(800)
        assertThat(valueChanges - changesAtRelease).isAtMost(1)
        assertThat(kotlin.math.abs(offset())).isLessThan(.01f)
        val settled = travel()
        compose.mainClock.advanceTimeBy(800)
        assertThat(travel()).isEqualTo(settled)
    }

    @Test fun activeWhiteBalancePresetLabelsHaveSeparateTouchBounds() {
        var settings by mutableStateOf(ManualSettings())
        val caps = cameraCaps().copy(awbModes = listOf(1, 2, 3, 5, 6, 8), supportsAwbLock = true)
        compose.setContent {
            androidx.compose.foundation.layout.Column(Modifier.width(320.dp)) {
                WhiteBalancePanel(settings, caps, { settings = it })
            }
        }
        compose.onNodeWithTag("revision05-wb-presets").performClick()
        val boxes = listOf(2,3,5,6,8).map { mode ->
            compose.onNodeWithTag("revision05-wb-preset-$mode", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        }
        boxes.zipWithNext().forEach { (left,right) ->
            assertThat(left.right.value).isAtMost(right.left.value)
        }
        compose.onNodeWithTag("revision05-wb-preset-2").performScrollTo().performClick().assertIsSelected()
        assertThat(settings.awbMode).isEqualTo(2)
        assertThat(settings.manualExposure).isFalse()
        assertThat(settings.manualFocus).isFalse()
    }

    @Test fun colorSpellingFollowsEnglishRegionResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun localized(locale: Locale): String {
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration)
                .getString(R.string.rangefinder_color_tab)
        }
        assertThat(localized(Locale.UK)).isEqualTo("COLOUR")
        assertThat(localized(Locale.US)).isEqualTo("COLOR")
    }

    @Test fun shutterPressScalesAndProgressTracksTheRealPipelineOnly() {
        compose.mainClock.autoAdvance = false
        var frames by mutableIntStateOf(4)
        var progress by mutableFloatStateOf(0f)
        var enabled by mutableStateOf(true)
        var captures = 0
        compose.setContent { MechanicalShutter(enabled, false, frames,
            onCapture = { captures++ }, onFlash = {}, captureProgress = progress) }
        val shutter = compose.onNodeWithTag("rangefinder-shutter")
        fun scale() = shutter.fetchSemanticsNode().config[ShutterScale]
        fun shownProgress() = shutter.fetchSemanticsNode().config[ShutterProgress]
        assertThat(shownProgress()).isEqualTo(0f)
        shutter.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        assertThat(scale()).isWithin(.01f).of(.94f)
        shutter.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(160)
        assertThat(captures).isEqualTo(1)
        assertThat(scale()).isWithin(.01f).of(1f)
        assertThat(shownProgress()).isEqualTo(0f)
        progress = .25f; frames = 28
        compose.mainClock.advanceTimeByFrame()
        assertThat(shownProgress()).isEqualTo(.25f)
        compose.mainClock.advanceTimeBy(800)
        assertThat(shownProgress()).isEqualTo(.25f)
        progress = .75f
        compose.mainClock.advanceTimeByFrame()
        assertThat(shownProgress()).isEqualTo(.75f)
        enabled = false
        compose.mainClock.advanceTimeByFrame()
        shutter.performTouchInput { click() }
        assertThat(captures).isEqualTo(1)
    }

    @Test fun touchingAndDraggingTheViewCannotFalsifyTheSensorLevel() {
        val tilt = 1.6f
        compose.setContent {
            Box(Modifier.size(320.dp).background(Ink.Ground).testTag("live-view")) {
                TitaniumReticle(measuredTilt = tilt, manualFocus = false,
                    focusAssistAvailable = false, focusAssistOpen = false,
                    focusAnchor = Offset(.5f, .5f), onFocusAnchorChange = {}, onFocusInsetBounds = {})
            }
        }
        val reticle = compose.onNodeWithTag("rangefinder-view-reticle")
        val bounds = reticle.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("rangefinder-reticle").performTouchInput { click() }
        assertThat(reticle.fetchSemanticsNode().config[ReticleTilt]).isEqualTo(tilt)
        compose.onNodeWithTag("live-view").performTouchInput {
            swipe(Offset((center.x * 2f) * .2f, 20f), Offset((center.x * 2f) * .8f, 20f), 200)
        }
        assertThat(reticle.fetchSemanticsNode().config[ReticleTilt]).isEqualTo(tilt)
        assertThat(reticle.fetchSemanticsNode().boundsInRoot).isEqualTo(bounds)
    }

    @Test fun manualFocusTargetDragsAndMagnifierTogglesWithoutMovingThePreview() {
        var anchor by mutableStateOf(Offset(.5f, .5f))
        var insetOpen by mutableStateOf(false)
        var insetBounds: androidx.compose.ui.geometry.Rect? = null
        compose.setContent {
            Box(Modifier.size(width = 360.dp, height = 600.dp).testTag("fixed-preview")) {
                TitaniumReticle(measuredTilt = 0f, manualFocus = true,
                    focusAssistAvailable = true,
                    focusAssistOpen = insetOpen,
                    focusAnchor = anchor,
                    onFocusAnchorChange = { anchor = it },
                    onFocusInsetBounds = { insetBounds = it })
                MechanicalUtility("FOCUS ASSIST", "Toggle magnified focus assist",
                    active = insetOpen, tag = "rangefinder-focus-assist-toggle",
                    modifier = Modifier.align(Alignment.BottomStart),
                    onClick = { insetOpen = !insetOpen })
            }
        }
        val fixed = compose.onNodeWithTag("fixed-preview").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("rangefinder-focus-inset").assertDoesNotExist()
        compose.onNodeWithTag("rangefinder-focus-assist-toggle").performClick()
        compose.onNodeWithTag("rangefinder-focus-inset").assertExists()
        assertThat(insetBounds).isNotNull()
        compose.onNodeWithTag("rangefinder-focus-target").performTouchInput {
            down(center)
            moveBy(Offset(80f, 40f))
            up()
        }
        assertThat(anchor.x).isGreaterThan(.5f)
        assertThat(anchor.y).isGreaterThan(.5f)
        compose.onNodeWithTag("rangefinder-reticle").performTouchInput { click() }
        assertThat(compose.onNodeWithTag("rangefinder-view-reticle")
            .fetchSemanticsNode().config[ReticleTilt]).isEqualTo(0f)
        assertThat(compose.onNodeWithTag("fixed-preview").fetchSemanticsNode().boundsInRoot).isEqualTo(fixed)
        compose.onNodeWithTag("rangefinder-focus-assist-toggle").performClick()
        compose.onNodeWithTag("rangefinder-focus-inset").assertDoesNotExist()
    }

    private fun cameraCaps() = CameraCapabilities(
        hasManualSensor = true, isoMin = 50, isoMax = 12800,
        exposureMinNs = 100_000L, exposureMaxNs = 6_000_000_000L,
        minFocusDiopters = 10f, awbModes = listOf(1, 2, 5), afModes = listOf(0),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -3, evMax = 3, evStep = .33f, sensorOrientation = 90,
        supportsRaw = true, supportedOutputFormats = emptySet(),
    )
}
