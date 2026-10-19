package dev.multiframe.camera.ui

import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoPreviewSizeTest {
    @Test fun sensorMarginsCannotDivertFrontPreviewToSquare() {
        assertThat(closestPhotoPreview(listOf(IntSize(1920, 1920), IntSize(1440, 1080),
            IntSize(1920, 1080)), IntSize(3440, 2448))).isEqualTo(IntSize(1440, 1080))
    }
    @Test fun equivalentAspectsPreferTheLargestSupportedPreview() {
        assertThat(closestPhotoPreview(listOf(IntSize(640, 480), IntSize(1440, 1080),
            IntSize(1280, 720)), IntSize(4080, 3064))).isEqualTo(IntSize(1440, 1080))
    }
}
