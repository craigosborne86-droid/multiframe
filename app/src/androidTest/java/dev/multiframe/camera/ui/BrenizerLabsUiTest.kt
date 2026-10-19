package dev.multiframe.camera.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.MosaicPreset
import org.junit.Rule
import org.junit.Test

class BrenizerLabsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun gridsAreBehindLabsAndLaunchTheirOwnSession() {
        var chosen: MosaicPreset? = null
        var launched = false
        compose.setContent {
            MultiframeTheme {
                SettingsScreen(chips = emptyList(), onControl = {}, onDismiss = {}, labs = {
                    BrenizerLabs(MosaicPreset.DETAIL_BOOST, emptyMap(), true,
                        onPreset = { chosen = it }, onLaunch = { launched = true })
                }) {}
            }
        }
        compose.onNodeWithText("Brenizer Sweep (Beta) · Best on tripods or distant scenes (>5m)").assertDoesNotExist()
        compose.onNodeWithContentDescription("Labs (Technology Previews)").performScrollTo().performClick()
        compose.onNodeWithText("Brenizer Sweep (Beta) · Best on tripods or distant scenes (>5m)").assertIsDisplayed()
        compose.onNodeWithText("1-SHOT").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brenizer Sweep 3×3").performScrollTo().performClick()
        compose.runOnIdle { assertThat(chosen).isEqualTo(MosaicPreset.LARGE_FORMAT) }
        compose.onNodeWithContentDescription("Arm Brenizer Sweep (Beta)").performScrollTo().performClick()
        compose.runOnIdle { assertThat(launched).isTrue() }
    }
}
