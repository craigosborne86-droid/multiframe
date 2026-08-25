package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Naming conventions for saved captures.
 *
 * The content provider needs a device, but the conventions the query relies on
 * do not, and they are what decides whether a thumbnail shows the right image.
 */
class RecentCaptureTest {

    @Test
    fun `our own captures are recognised`() {
        assertThat(RecentCapture.isOurs("MF_20260825_120000_zsl_8f.jpg")).isTrue()
        assertThat(RecentCapture.isOurs("MF_20260825_120000_merged_8f.dng")).isTrue()
    }

    @Test
    fun `other apps' images are not`() {
        // A thumbnail is an invitation to tap, and showing someone else's
        // photograph there would be both confusing and a small privacy failure.
        assertThat(RecentCapture.isOurs("IMG_20260825_120000.jpg")).isFalse()
        assertThat(RecentCapture.isOurs("Screenshot_20260825.png")).isFalse()
        assertThat(RecentCapture.isOurs("PXL_20260825_120000.jpg")).isFalse()
        assertThat(RecentCapture.isOurs("")).isFalse()
    }

    @Test
    fun `a raw file is identified as such`() {
        // A DNG has no preview this app can cheaply decode, so a raw-only
        // thumbnail would be a blank square.
        assertThat(RecentCapture.isRaw("MF_x_merged_8f.dng")).isTrue()
        assertThat(RecentCapture.isRaw("MF_x_merged_8f.jpg")).isFalse()
    }

    @Test
    fun `case does not matter`() {
        assertThat(RecentCapture.isOurs("MF_x.JPG")).isTrue()
        assertThat(RecentCapture.isRaw("MF_x.DNG")).isTrue()
    }

    @Test
    fun `a name says how the shot was taken`() {
        assertThat(RecentCapture.describe("MF_1_zsl_8f.jpg")).isEqualTo("zero shutter lag")
        assertThat(RecentCapture.describe("MF_1_mosaic_49t.jpg")).isEqualTo("mosaic")
        assertThat(RecentCapture.describe("MF_1_merged_8f.jpg")).isEqualTo("merged burst")
        assertThat(RecentCapture.describe("MF_1_raw.dng")).isEqualTo("single frame")
        assertThat(RecentCapture.describe("MF_1_ab_merged.jpg")).isEqualTo("A/B comparison")
        assertThat(RecentCapture.describe("MF_1_something.jpg")).isEqualTo("capture")
    }
}
