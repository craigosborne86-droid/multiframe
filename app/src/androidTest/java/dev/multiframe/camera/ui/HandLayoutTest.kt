package dev.multiframe.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * The turn that keeps the controls' words upright when the phone is on its
 * side: a quarter turn swaps the box's shape, a half turn does not, and a
 * chip under a turned hand is taller than it is wide -- the shape a rail's
 * button wants -- with its words still inside it.
 */
class HandLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aQuarterTurnSwapsTheBoxAndAHalfTurnKeepsIt() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.testTag("quarter").rotateLayout(-90)) { Box(Modifier.size(120.dp, 40.dp)) }
                Box(Modifier.testTag("half").rotateLayout(180)) { Box(Modifier.size(120.dp, 40.dp)) }
                Box(Modifier.testTag("none").rotateLayout(0)) { Box(Modifier.size(120.dp, 40.dp)) }
            }
        }
        compose.assumeRendered()
        compose.onNodeWithTag("quarter").assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(120.dp)
        compose.onNodeWithTag("half").assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(40.dp)
        compose.onNodeWithTag("none").assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(40.dp)
    }

    @Test
    fun anInstrumentControlInATurnedHandIsTallAndItsWordsStayInside() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.testTag("upright")) {
                    MechanicalUtility("HISTOGRAM", "Show histogram", tag = "upright-control") {}
                }
                CompositionLocalProvider(LocalHandRotation provides 90) {
                    Box(Modifier.testTag("turned")) {
                        UprightInHand {
                            MechanicalUtility("HISTOGRAM", "Show histogram", tag = "turned-control") {}
                        }
                    }
                }
            }
        }
        compose.assumeRendered()
        val upright = compose.onNodeWithTag("upright").getUnclippedBoundsInRoot()
        val turned = compose.onNodeWithTag("turned").getUnclippedBoundsInRoot()
        val uprightW = (upright.right - upright.left).value
        val uprightH = (upright.bottom - upright.top).value
        val turnedW = (turned.right - turned.left).value
        val turnedH = (turned.bottom - turned.top).value
        // Upright the chip is wider than tall; turned it is taller than wide,
        // and the label's length has become its height.
        assertThat(uprightW).isGreaterThan(uprightH)
        assertThat(turnedH).isGreaterThan(turnedW)
        assertThat(turnedH).isGreaterThan(uprightH)
        compose.onAllNodesWithContentDescription("Show histogram").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Show histogram")[1].assertIsDisplayed()
    }

    @Test
    fun theViewfinderFitsItsRegionWithoutStretchingOrSpilling() {
        compose.setContent {
            Box(Modifier.size(300.dp, 500.dp)) {
                Box(Modifier.testTag("fit").fitAspect(3f / 4f)) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.assumeRendered()
        // Width-bound: 300 wide, 400 tall.
        compose.onNodeWithTag("fit").assertWidthIsEqualTo(300.dp).assertHeightIsEqualTo(400.dp)
    }

    @Test
    fun theViewfinderIsHeightBoundInAShortRegion() {
        compose.setContent {
            Box(Modifier.size(300.dp, 200.dp)) {
                Box(Modifier.testTag("fit").fitAspect(3f / 4f)) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.assumeRendered()
        compose.onNodeWithTag("fit").assertWidthIsEqualTo(150.dp).assertHeightIsEqualTo(200.dp)
    }
}
