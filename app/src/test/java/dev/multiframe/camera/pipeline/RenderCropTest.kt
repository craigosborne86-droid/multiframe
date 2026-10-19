package dev.multiframe.camera.pipeline
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class RenderCropTest {
    @Test fun uncroppedRawGetsRequestedZoomAfterDevelop() {
        val crop = RenderCrop.between(SensorRect(1000, 750, 3000, 2250), SensorRect(0, 0, 4000, 3000))
        assertThat(crop.pixels(8000, 6000)).isEqualTo(SensorRect(2000, 1500, 6000, 4500))
    }
    @Test fun sensorCenterReadoutIsNotCroppedTwice() {
        val region = SensorRect(1000, 750, 3000, 2250)
        assertThat(RenderCrop.between(region, region)).isEqualTo(RenderCrop())
    }
    @Test fun offCenterHalCropRetainsRequestedFraming() {
        val crop = RenderCrop.between(SensorRect(1000, 750, 3000, 2250), SensorRect(500, 500, 3500, 2500))
        assertThat(crop.pixels(3000, 2000)).isEqualTo(SensorRect(500, 250, 2500, 1750))
    }
}
