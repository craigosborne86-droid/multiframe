package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Formatting capture metadata for EXIF.
 *
 * The interesting cases are the extremes of exposure: a thousandth of a second
 * and a four-second night exposure have to survive the same conversion, and a
 * rational that rounds to zero would leave a photograph claiming an exposure of
 * nothing.
 */
class ExifWriterTest {

    private fun metadata(
        frames: Int = 8,
        lens: String? = "24mm",
    ) = CaptureMetadata(
        exposureTimeNs = 4_000_000L,
        iso = 400,
        apertureF = 1.7f,
        focalLengthMm = 6.9f,
        equivalent35mm = 24,
        frames = frames,
        takenAtMillis = 0L,
        lensLabel = lens,
    )

    // ------------------------------------------------------------------

    @Test
    fun `exposure is written as a decimal, which is what the framework accepts`() {
        // Not "1/250". ExifInterface converts a decimal into the rational EXIF
        // actually stores, and silently discards a value already written as
        // one -- measured on device, "1/250" reads back as null. The tag was
        // missing from every photograph until that was found.
        assertThat(ExifWriter.exposureSeconds(4_000_000L)).isEqualTo("0.004")
        assertThat(ExifWriter.exposureSeconds(1_000_000L)).isEqualTo("0.001")
    }

    @Test
    fun `a long night exposure keeps its magnitude`() {
        assertThat(ExifWriter.exposureSeconds(4_000_000_000L)).isEqualTo("4")
        assertThat(ExifWriter.exposureSeconds(250_000_000L)).isEqualTo("0.25")
    }

    @Test
    fun `a very short exposure does not round away to nothing`() {
        // The sensor's minimum on this device is 26 microseconds, and six
        // decimal places is what keeps it from becoming zero.
        val tag = ExifWriter.exposureSeconds(26_000L)

        assertThat(tag).isEqualTo("0.000026")
        assertThat(tag!!.toDouble()).isGreaterThan(0.0)
    }

    @Test
    fun `an absurd exposure produces no tag rather than a wrong one`() {
        assertThat(ExifWriter.exposureSeconds(0L)).isNull()
        assertThat(ExifWriter.exposureSeconds(-5L)).isNull()
    }

    @Test
    fun `aperture is a decimal and focal length is a rational`() {
        // Not a choice. The framework's ExifInterface silently discards a value
        // in the wrong form, and it wants different forms for these two tags.
        assertThat(ExifWriter.decimalValue(1.7f)).isEqualTo("1.7")
        assertThat(ExifWriter.decimalValue(2.0f)).isEqualTo("2")
        assertThat(ExifWriter.rationalValue(6.9f)).isEqualTo("690/100")
    }

    @Test
    fun `a missing value produces no tag`() {
        for (bad in listOf(0f, -1f, Float.NaN)) {
            assertThat(ExifWriter.decimalValue(bad)).isNull()
            assertThat(ExifWriter.rationalValue(bad)).isNull()
        }
    }

    @Test
    fun `the description says the shutter speed the way a photographer would`() {
        // The tag has to be a decimal; a person reading the description should
        // still see "1/250".
        assertThat(ExifWriter.shutterLabel(4_000_000L)).isEqualTo("1/250")
        assertThat(ExifWriter.shutterLabel(1_000_000_000L)).isEqualTo("1s")
        assertThat(ExifWriter.shutterLabel(0L)).isNull()
    }

    // ------------------------------------------------------------------

    @Test
    fun `the description says how the picture was made`() {
        // The one thing about these files that is not obvious from the other
        // tags: a merged burst and a single frame at the same settings are
        // otherwise indistinguishable in a library.
        val merged = ExifWriter.describeCapture(metadata(frames = 8))

        assertThat(merged).contains("8 frames merged")
        assertThat(merged).contains("24mm")
        assertThat(merged).startsWith(ExifWriter.SOFTWARE)
    }

    @Test
    fun `a single frame says so`() {
        assertThat(ExifWriter.describeCapture(metadata(frames = 1)))
            .contains("single frame")
    }

    @Test
    fun `a mosaic says its tiles were stitched, not merged`() {
        // The opposite trade from a merge: frames spent on resolution across a
        // scene rather than on signal-to-noise at one framing. Calling a
        // sweep's tiles "merged" would describe the wrong photograph.
        val described = ExifWriter.describeCapture(
            metadata(frames = 49).copy(kind = CaptureKind.MOSAIC),
        )

        assertThat(described).contains("49 tiles stitched")
        assertThat(described).doesNotContain("merged")
    }

    @Test
    fun `a mosaic of one tile is not one tiles`() {
        assertThat(
            ExifWriter.describeCapture(metadata(frames = 1).copy(kind = CaptureKind.MOSAIC)),
        ).contains("1 tile stitched")
    }

    @Test
    fun `an unknown lens is simply omitted`() {
        val described = ExifWriter.describeCapture(metadata(lens = null))

        assertThat(described).contains("frames merged")
        assertThat(described).doesNotContain("null")
    }
}
