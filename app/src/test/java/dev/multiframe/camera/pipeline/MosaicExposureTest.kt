package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exposure normalisation between the tiles of a sweep.
 *
 * The stream locks exposure, so the tiles should already agree; this is the
 * arithmetic that holds if they do not, and it has to be a ratio of what the
 * sensor gathered rather than a second automatic guess per tile.
 */
class MosaicExposureTest {

    @Test
    fun `a tile at the anchor's exposure gets the anchor's gain`() {
        assertThat(MosaicSession.normalisedGain(2.5f, 100.0 * 8e6, 100.0 * 8e6)).isWithin(1e-6f).of(2.5f)
    }

    @Test
    fun `a tile that gathered twice the light is developed at half the gain`() {
        // ISO doubled with the exposure unchanged, or the exposure doubled:
        // either way the sensor read twice as much and the develop undoes it.
        assertThat(MosaicSession.normalisedGain(2.5f, 100.0 * 8e6, 200.0 * 8e6)).isWithin(1e-6f).of(1.25f)
        assertThat(MosaicSession.normalisedGain(2.5f, 100.0 * 8e6, 100.0 * 16e6)).isWithin(1e-6f).of(1.25f)
    }

    @Test
    fun `no anchor gain means the develop chooses for itself`() {
        assertThat(MosaicSession.normalisedGain(0f, 1.0, 1.0)).isEqualTo(DevelopParams.AUTO_EXPOSURE)
    }

    @Test
    fun `a missing exposure on either side is not a correction`() {
        assertThat(MosaicSession.normalisedGain(2.5f, 0.0, 800e6)).isEqualTo(2.5f)
        assertThat(MosaicSession.normalisedGain(2.5f, 800e6, 0.0)).isEqualTo(2.5f)
    }
}
