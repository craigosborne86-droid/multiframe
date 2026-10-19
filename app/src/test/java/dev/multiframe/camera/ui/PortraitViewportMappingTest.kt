package dev.multiframe.camera.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.multiframe.camera.pipeline.SensorRect
import dev.multiframe.camera.pipeline.TouchFocus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortraitViewportMappingTest {
    @Test fun fullWidthPhotoAndRemainingBandMeetWithoutCropping() {
        assertThat(portraitPhotoHeight(1280, .75f)).isEqualTo(1707)
        assertThat(portraitControlHeight(1280, 2856, .75f, 304)).isEqualTo(845)
        assertThat(304 + portraitPhotoHeight(1280, .75f) +
            portraitControlHeight(1280, 2856, .75f, 304)).isEqualTo(2856)
    }

    @Test fun letterboxedImagePointsUseOnlyImageBounds() {
        val image = Rect(0f, 300f, 1200f, 1900f)
        assertThat(imagePoint(image, image.topLeft)).isEqualTo(Offset.Zero)
        assertThat(imagePoint(image, image.center)).isEqualTo(Offset(.5f, .5f))
        assertThat(imagePoint(image, Offset(300f, 700f))).isEqualTo(Offset(.25f, .25f))
        for (point in listOf(Offset(-1f, 400f), Offset(1200f, 400f),
            Offset(600f, 299f), Offset(600f, 1900f), Offset(600f, 2200f))) {
            assertThat(imagePoint(image, point)).isNull()
        }
        assertThat(imagePoint(null, Offset.Zero)).isNull()
        assertThat(imagePoint(Rect.Zero, Offset.Zero)).isNull()
    }

    @Test fun knownNormalizedCoordinatesMapToActiveArrayWithOrientationAndZoom() {
        val image = Rect(0f, 300f, 1200f, 1900f)
        val active = SensorRect(100, 200, 4100, 3200)
        val p = imagePoint(image, Offset(300f, 700f))!!
        val expected = listOf(1100 to 950, 1100 to 2450, 3100 to 2450, 3100 to 950)
        for ((rotation, sensorPoint) in listOf(0, 90, 180, 270).zip(expected)) {
            assertThat(TouchFocus.toSensorPoint(p.x, p.y, rotation, active)).isEqualTo(sensorPoint)
        }
        assertThat(TouchFocus.toSensorPoint(0f, 0f, 90, active)).isEqualTo(100 to 3200)
        assertThat(TouchFocus.toSensorPoint(1f, 0f, 90, active)).isEqualTo(100 to 200)
        assertThat(TouchFocus.toSensorPoint(0f, 1f, 90, active)).isEqualTo(4100 to 3200)
        assertThat(TouchFocus.toSensorPoint(1f, 1f, 90, active)).isEqualTo(4100 to 200)
        val zoom = TouchFocus.cropForZoom(active, 2f, 10f)
        assertThat(zoom).isEqualTo(SensorRect(1100, 950, 3100, 2450))
        assertThat(TouchFocus.toSensorPoint(p.x, p.y, 90, zoom)).isEqualTo(1600 to 2075)
        // At corners, AF/AE boxes slide within the array instead of being clipped.
        assertThat(TouchFocus.regionAt(0f, 0f, 90, active)).isEqualTo(SensorRect(100, 2750, 700, 3200))
        assertThat(TouchFocus.regionAt(1f, 1f, 90, active)).isEqualTo(SensorRect(3500, 200, 4100, 650))
    }
}
