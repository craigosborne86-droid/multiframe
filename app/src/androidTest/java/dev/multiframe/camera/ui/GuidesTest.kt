package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.Attitude
import org.junit.Rule
import org.junit.Test

/**
 * The viewfinder overlays.
 *
 * These are drawn on a Canvas and have no semantics to assert against, so what
 * is being tested is that they compose and draw at all. That is worth having:
 * every one of them was written without a screen to look at, and a Canvas that
 * divides by zero or indexes an empty array takes the whole viewfinder down
 * rather than merely looking wrong.
 */
class GuidesTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Content is set once and then driven by state.
     *
     * setContent may only be called once per test, so a loop that calls it per
     * case fails on the second iteration -- which is exactly what happened, and
     * is worth a note because it looks like a rendering failure rather than a
     * test-structure one.
     */
    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
        // Without this the test renders nothing, asserts nothing, and passes.
        compose.assumeRendered()
    }

    @Test
    fun everyGuideModeDraws() {
        var mode by mutableStateOf(GuideMode.OFF)
        render { Guides(mode = mode, attitude = Attitude(3f, 10f, false)) }

        for (next in GuideMode.entries) {
            mode = next
            compose.waitForIdle()
        }
    }

    @Test
    fun theSweepMapDrawsAsItFillsIn() {
        // A sweep's map is the one overlay whose content changes on every
        // frame, so it is drawn empty, part-covered and full.
        var grid by mutableStateOf(Array(32) { BooleanArray(32) })
        render { SweepMap(grid = grid, aspect = 1.33f, modifier = Modifier.size(120.dp)) }

        grid = Array(32) { r -> BooleanArray(32) { c -> (r + c) % 3 == 0 } }
        compose.waitForIdle()

        grid = Array(32) { BooleanArray(32) { true } }
        compose.waitForIdle()
    }

    @Test
    fun theSweepMapSurvivesAGridWithNoCells() {
        // The session can be asked for its coverage before a single frame has
        // been placed, and a Canvas that divides by a zero column count takes
        // the viewfinder down rather than merely looking wrong.
        var grid by mutableStateOf(emptyArray<BooleanArray>())
        render { SweepMap(grid = grid, aspect = 1.5f, modifier = Modifier.size(120.dp)) }

        grid = arrayOf(BooleanArray(0))
        compose.waitForIdle()

        // And a canvas with no shape at all, which would otherwise be an
        // aspectRatio of zero.
        grid = Array(4) { BooleanArray(4) { true } }
        compose.waitForIdle()
    }

    @Test
    fun theSweepMapToleratesARaggedGrid() {
        // Rows of differing length are not something the assembler produces,
        // but drawing indexes row by row and an assumption like that is worth
        // one line of guarding rather than a crash in the viewfinder.
        val ragged = arrayOf(
            BooleanArray(8) { true },
            BooleanArray(3) { true },
            BooleanArray(8),
        )
        render { SweepMap(grid = ragged, aspect = 1f, modifier = Modifier.size(80.dp)) }
        compose.waitForIdle()
    }

    @Test
    fun theLevelDrawsWithoutAnAttitude() {
        // The sensor has not reported yet, or the device has no accelerometer.
        render { Guides(mode = GuideMode.GRID_AND_LEVEL, attitude = null) }
    }

    @Test
    fun theLevelDrawsWhenPointingStraightDown() {
        // The branch where roll is undefined and a ring is drawn instead.
        render {
            Guides(
                mode = GuideMode.GRID_AND_LEVEL,
                attitude = Attitude(0f, 89f, facingUpOrDown = true),
            )
        }
    }

    @Test
    fun theLevelDrawsAtExtremeRoll() {
        var attitude by mutableStateOf(Attitude(0f, 0f, facingUpOrDown = false))
        render { Guides(mode = GuideMode.GRID_AND_LEVEL, attitude = attitude) }

        for (roll in listOf(-179f, -90f, 0f, 90f, 179f)) {
            attitude = Attitude(roll, 0f, facingUpOrDown = false)
            compose.waitForIdle()
        }
    }

    @Test
    fun theHistogramDrawsForOrdinaryData() {
        render {
            Histogram(
                bins = IntArray(48) { it * 100 },
                modifier = Modifier.size(width = 132.dp, height = 44.dp),
            )
        }
    }

    @Test
    fun theHistogramSurvivesDegenerateData() {
        // An empty ring gives all zeroes; a blown scene gives one enormous bin.
        // Dividing by a zero peak would crash the viewfinder.
        var bins by mutableStateOf(IntArray(48))
        render {
            Histogram(bins = bins, modifier = Modifier.size(width = 132.dp, height = 44.dp))
        }

        val cases = listOf(
            IntArray(0),
            IntArray(48),
            IntArray(48).also { it[47] = 1_000_000 },
            IntArray(1) { 5 },
        )
        for (case in cases) {
            bins = case
            compose.waitForIdle()
        }
    }

    @Test
    fun guideModesCycleBackToTheStart() {
        var mode = GuideMode.OFF
        repeat(GuideMode.entries.size) { mode = mode.next() }

        assertThat(mode).isEqualTo(GuideMode.OFF)
        assertThat(GuideMode.OFF.showsGrid).isFalse()
        assertThat(GuideMode.GRID.showsGrid).isTrue()
        assertThat(GuideMode.GRID.showsLevel).isFalse()
        assertThat(GuideMode.GRID_AND_LEVEL.showsLevel).isTrue()
    }
}
