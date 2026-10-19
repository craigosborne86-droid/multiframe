package dev.multiframe.camera.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class MergeProcessingIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consoleShowsPersistenceFailureAfterItsFinishedMergeIndicatorClears() {
        compose.mainClock.autoAdvance = false
        var progress by mutableStateOf<MergeUiProgress?>(MergeUiProgress(3, 0, 28))
        var status by mutableStateOf("")
        compose.setContent { Revision05ConsoleFixture(status = status, progress = progress) }
        compose.mainClock.advanceTimeBy(MergeIndicatorTiming.SHOW_DELAY_MS + 40)
        compose.onNodeWithTag("merge-processing-indicator").assertExists()
        compose.runOnIdle {
            progress = MergeUiProgress(3, 28, 28, finished = true)
            status = "DNG saved. Rendered image could not be saved."
        }
        compose.mainClock.advanceTimeBy(MergeIndicatorTiming.MIN_VISIBLE_MS + 100)
        compose.onNodeWithTag("merge-processing-indicator").assertDoesNotExist()
        compose.onNodeWithText(status).assertIsDisplayed()
        val shutter = compose.onNodeWithTag("persistent-shutter").fetchSemanticsNode().boundsInRoot
        val message = compose.onNodeWithText(status).fetchSemanticsNode().boundsInRoot
        assertThat(message.overlaps(shutter)).isFalse()
    }

    @Test fun twoFrameMergeCompletingBeforeTheVisibilityThresholdNeverFlashes() {
        compose.mainClock.autoAdvance = false
        var progress by mutableStateOf<MergeUiProgress?>(MergeUiProgress(1, 0, 2))
        compose.setContent { MergeProcessingIndicator(progress) }

        compose.mainClock.advanceTimeBy(MergeIndicatorTiming.SHOW_DELAY_MS / 2)
        compose.runOnIdle { progress = MergeUiProgress(1, 2, 2, finished = true) }
        compose.mainClock.advanceTimeBy(MergeIndicatorTiming.MIN_VISIBLE_MS + 100)
        compose.onNodeWithTag("merge-processing-indicator").assertDoesNotExist()
    }

    @Test fun twentyEightFrameMergeShowsRealProgressThenClearsWithoutAFlash() {
        compose.mainClock.autoAdvance = false
        var progress by mutableStateOf<MergeUiProgress?>(MergeUiProgress(2, 0, 28))
        compose.setContent { MergeProcessingIndicator(progress) }

        compose.mainClock.advanceTimeBy(MergeIndicatorTiming.SHOW_DELAY_MS + 40)
        compose.onNodeWithTag("merge-processing-indicator").assertExists()
        compose.runOnIdle { progress = MergeUiProgress(2, 14, 28) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("merge-processing-indicator")
            .assertContentDescriptionEquals("Processing 14 of 28 frames")
        assertThat(compose.onNodeWithTag("merge-processing-indicator").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current).isEqualTo(.5f)

        compose.mainClock.advanceTimeBy(MergeIndicatorTiming.MIN_VISIBLE_MS)
        compose.runOnIdle { progress = MergeUiProgress(2, 28, 28, finished = true) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("merge-processing-indicator")
            .assertContentDescriptionEquals("Merge complete, 28 frames")
        compose.mainClock.advanceTimeBy(40)
        compose.onNodeWithTag("merge-processing-indicator").assertDoesNotExist()
    }
}
