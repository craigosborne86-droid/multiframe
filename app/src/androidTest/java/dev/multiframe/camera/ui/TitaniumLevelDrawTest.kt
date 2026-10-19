package dev.multiframe.camera.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Sensor-free rendered coverage; it does not establish a physical device's tilt response. */
class TitaniumLevelDrawTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sensorTiltIsTheOnlyReportedHorizon() {
        val measured = TitaniumTokens.LevelToleranceDegrees * 2f
        compose.setContent { Reticle(measured) }
        assertThat(compose.onNodeWithTag("rangefinder-view-reticle")
            .fetchSemanticsNode().config[ReticleTilt]).isEqualTo(measured)
    }

    @Test fun greenHorizonUsesInclusiveHalfDegreeBoundary() {
        val limit = TitaniumTokens.LevelToleranceDegrees
        val outside = Math.nextUp(limit)
        val measured = mutableStateOf<Float?>(outside)
        compose.setContent { Reticle(measured.value) }

        val report = JSONArray()
        val cases = listOf(
            Triple("outside-negative", -outside, false),
            Triple("boundary-negative", -limit, true),
            Triple("zero", 0f, true),
            Triple("boundary-positive", limit, true),
            Triple("outside-positive", outside, false),
        )
        for ((name, value, expectedGreen) in cases) {
            compose.runOnIdle {
                measured.value = value
            }
            inspect(name, value, expectedGreen, report)
        }
    }

    @Test fun unavailableSensorNeverShowsGreenEvenAfterTouch() {
        compose.setContent { Reticle(null) }
        val report = JSONArray()
        inspect("unknown-before-touch", null, false, report)
        compose.onNodeWithTag("rangefinder-reticle").performTouchInput { click() }
        assertThat(compose.onNodeWithTag("rangefinder-view-reticle")
            .fetchSemanticsNode().config[ReticleTilt].isNaN()).isTrue()
        inspect("unknown-after-touch", null, false, report)
    }

    @Test fun touchCannotChangeMeasuredTiltOrBounds() {
        val measured = TitaniumTokens.LevelToleranceDegrees * 2f
        compose.setContent { Reticle(measured) }
        val reticle = compose.onNodeWithTag("rangefinder-view-reticle")
        val bounds = reticle.fetchSemanticsNode().boundsInRoot
        val report = JSONArray()
        inspect("measured-before-touch", measured, false, report)
        compose.onNodeWithTag("rangefinder-reticle").performTouchInput { click() }
        assertThat(reticle.fetchSemanticsNode().config[ReticleTilt]).isEqualTo(measured)
        inspect("measured-after-touch", measured, false, report)
        assertThat(reticle.fetchSemanticsNode().boundsInRoot).isEqualTo(bounds)
    }

    @Composable
    private fun Reticle(measured: Float?) {
        Box(Modifier.size(320.dp, 480.dp).background(TitaniumTokens.Chassis)) {
            TitaniumReticle(
                measuredTilt = measured, manualFocus = false, focusAssistAvailable = false, focusAssistOpen = false,
                focusAnchor = Offset(.5f, .5f), onFocusAnchorChange = {}, onFocusInsetBounds = {},
            )
        }
    }

    private fun inspect(name: String, measured: Float?, expectedGreen: Boolean,
        report: JSONArray) {
        val node = compose.onNodeWithTag("rangefinder-view-reticle")
        val image = node.captureToImage().asAndroidBitmap()
        val samples = JSONArray()
        try {
            // Sample the horizontal line away from the central crosshair and corner framelines.
            // Every production colour there is neutral/blue except the green level line.
            val density = image.width / 320f
            val columns = listOf(-18f, 0f, 18f).map { (image.width / 2f + it * density).toInt() }
            val lineY = (36f * density).toInt()
            val observed = columns.map { x ->
                val pixels = (lineY - 2..lineY + 2)
                    .map { y -> image.getPixel(x, y) }
                val green = pixels.any { colour ->
                    AndroidColor.green(colour) > AndroidColor.red(colour) &&
                        AndroidColor.green(colour) > AndroidColor.blue(colour)
                }
                samples.put(JSONObject().put("x", x).put("green", green)
                    .put("argb", JSONArray(pixels)))
                green
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, "titanium-level-$name.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            report.put(JSONObject().put("name", name)
                .put("measured", measured ?: JSONObject.NULL).put("expectedGreen", expectedGreen)
                .put("width", image.width).put("height", image.height).put("samples", samples))
            File(context.cacheDir, "titanium-level-report-$name.json").writeText(report.toString(2))
            assertThat(observed).containsExactly(expectedGreen, expectedGreen, expectedGreen).inOrder()
        } finally {
            image.recycle()
        }
    }
}
