package dev.multiframe.camera.pipeline
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class CapturePlumbingTest {
    @Test fun sixSecondSettingKeepsPreviewWithinOneFrame() {
        assertThat(PreviewExposure.time(6_000_000_000L)).isEqualTo(33_333_333L)
        assertThat(PreviewExposure.iso(100, 6_000_000_000L, 6400)).isEqualTo(6400)
        assertThat(PreviewExposure.time(8_000_000L)).isEqualTo(8_000_000L)
        assertThat(PreviewExposure.iso(100, 8_000_000L, 6400)).isEqualTo(100)
    }
    @Test fun dngOrientationCoversAllQuarterTurnsAndBothFacings() {
        assertThat(listOf(0, 90, 180, 270).map { CaptureOrientation.exif(it) }).containsExactly(1, 6, 3, 8).inOrder()
        assertThat(CaptureOrientation.degrees(90, 90, true)).isEqualTo(180)
        assertThat(CaptureOrientation.degrees(270, 90, false)).isEqualTo(180)
        assertThat(CaptureOrientation.degrees(90, 270, true)).isEqualTo(0)
        assertThat(CaptureOrientation.degrees(270, 270, false)).isEqualTo(0)
    }
}
