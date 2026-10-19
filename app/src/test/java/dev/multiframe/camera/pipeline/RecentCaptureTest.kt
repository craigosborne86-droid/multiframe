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
        assertThat(RecentCapture.describe("MF_1_sr2x_8f.jpg")).isEqualTo("super-res 2x")
    }

    @Test
    fun `a 2x capture is recognised by its tag`() {
        // The tag is the one record of how the picture was made that travels
        // with the file, and the gallery badge reads it.
        assertThat(RecentCapture.isSuperRes("MF_20260914_020000_sr2x_8f.jpg")).isTrue()
        assertThat(RecentCapture.isSuperRes("MF_20260914_020000_sr2x_8f.dng")).isTrue()
        assertThat(RecentCapture.isSuperRes("MF_20260914_020000_zsl_8f.jpg")).isFalse()
        assertThat(RecentCapture.SUPER_RES_TAG).isEqualTo("_sr2x_")
    }

    @Test
    fun `native resolution tag is measured and distinct from 2x`() {
        val name = "MF_20260926_120000_50mp_3f.jpg"
        assertThat(RecentCapture.nativeHighResMegapixels(name)).isEqualTo(50)
        assertThat(RecentCapture.describe(name)).isEqualTo("50MP native raw")
        assertThat(RecentCapture.isSuperRes(name)).isFalse()
        assertThat(RecentCapture.nativeHighResMegapixels("MF_1_sr2x_16f.jpg")).isNull()
    }

    @Test
    fun `saved merge frame counts come only from canonical terminal count names`() {
        assertThat(RecentCapture.frameCount("MF_20261001_110907_zsl_2f")).isEqualTo(2)
        assertThat(RecentCapture.frameCount("MF_20261001_110852_zsl_28f.jpg")).isEqualTo(28)
        assertThat(RecentCapture.frameCount("MF_20261001_110852_merged_8f.dng")).isEqualTo(8)
        assertThat(RecentCapture.frameCount("MF_20261001_110852_sr2x_16f.jpg")).isEqualTo(16)
        assertThat(RecentCapture.frameCount("MF_20261001_110852_50mp_3f")).isEqualTo(3)
        assertThat(RecentCapture.frameCount("MF_20261001_110852_zsl_1f")).isEqualTo(1)
        assertThat(RecentCapture.frameCount("MF_20261001_110852_zsl_0f")).isNull()
        assertThat(RecentCapture.frameCount("MF_20261001_110852_mosaic_28t.jpg")).isNull()
        assertThat(RecentCapture.frameCount("MF_20261001_110852_raw.jpg")).isNull()
        assertThat(RecentCapture.frameCount("MF_20261001_110852_zsl_28f_copy.jpg")).isNull()
        assertThat(RecentCapture.frameCount("MF_20261001_110852_zsl_28f.png")).isNull()
        assertThat(RecentCapture.frameCount("other_20261001_110852_zsl_28f.jpg")).isNull()
        assertThat(RecentCapture.frameCount("MF_20261001_110852_zsl_999999999999999999999f")).isNull()
    }
}
