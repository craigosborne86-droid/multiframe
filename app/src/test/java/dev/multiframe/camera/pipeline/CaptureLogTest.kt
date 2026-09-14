package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Turning a list of files into a list of photographs.
 *
 * Testable on the JVM because [CaptureFile] carries its URI as the string the
 * log stores rather than as a parsed `android.net.Uri` -- which is a stub here
 * with a package-private constructor, so it can be neither built nor faked.
 * The pairing is about names and times; the URI is carried, never inspected.
 */
class CaptureLogTest {

    private fun file(name: String, at: Long): CaptureFile =
        CaptureFile("content://$name", name, at)

    // --- base names --------------------------------------------------------

    @Test
    fun theBaseNameIsEverythingBeforeTheExtension() {
        assertThat(CaptureLog.baseName("MF_20260910_120000_zsl_8f.jpg"))
            .isEqualTo("MF_20260910_120000_zsl_8f")
        assertThat(CaptureLog.baseName("MF_20260910_120000_zsl_8f.dng"))
            .isEqualTo("MF_20260910_120000_zsl_8f")
    }

    @Test
    fun aNameWithNoExtensionIsItsOwnBase() {
        assertThat(CaptureLog.baseName("MF_odd")).isEqualTo("MF_odd")
        assertThat(CaptureLog.baseName("")).isEqualTo("")
    }

    /** A dotted stem keeps everything up to the *last* dot. */
    @Test
    fun onlyTheFinalExtensionIsStripped() {
        assertThat(CaptureLog.baseName("MF_1.2.3_raw.dng")).isEqualTo("MF_1.2.3_raw")
    }

    // --- pairing -----------------------------------------------------------

    /**
     * The whole point. One press of DNG writes two files and they are one
     * photograph -- shown once, and deleted together.
     */
    @Test
    fun aJpegAndItsDngAreOnePhotograph() {
        val records = CaptureLog.pair(
            listOf(
                file("MF_a_zsl_8f.jpg", 1000L),
                file("MF_a_zsl_8f.dng", 1010L),
            )
        )

        assertThat(records).hasSize(1)
        val record = records.single()
        assertThat(record.hasRaw).isTrue()
        assertThat(record.files).hasSize(2)
        assertThat(record.jpeg!!.displayName).isEqualTo("MF_a_zsl_8f.jpg")
        assertThat(record.dng!!.displayName).isEqualTo("MF_a_zsl_8f.dng")
    }

    /**
     * The JPEG is the preview, because a DNG has no thumbnail this app can
     * cheaply decode.
     */
    @Test
    fun theJpegIsThePreviewWhenThereIsOne() {
        val record = CaptureLog.pair(
            listOf(file("MF_a.dng", 1010L), file("MF_a.jpg", 1000L))
        ).single()
        assertThat(record.preview.displayName).isEqualTo("MF_a.jpg")
    }

    @Test
    fun aLoneDngIsStillAPhotograph() {
        val record = CaptureLog.pair(listOf(file("MF_a.dng", 1000L))).single()
        assertThat(record.hasRaw).isTrue()
        assertThat(record.jpeg).isNull()
        assertThat(record.preview.displayName).isEqualTo("MF_a.dng")
    }

    @Test
    fun aLoneJpegHasNoRawBadge() {
        val record = CaptureLog.pair(listOf(file("MF_a.jpg", 1000L))).single()
        assertThat(record.hasRaw).isFalse()
        assertThat(record.files).hasSize(1)
    }

    @Test
    fun differentPhotographsStayApart() {
        val records = CaptureLog.pair(
            listOf(
                file("MF_a.jpg", 1000L),
                file("MF_a.dng", 1005L),
                file("MF_b.jpg", 2000L),
                file("MF_c.jpg", 3000L),
            )
        )
        assertThat(records).hasSize(3)
    }

    /**
     * Newest first, and by the *photograph's* time rather than by each file's.
     *
     * A DNG written a few milliseconds after its JPEG must not sort between two
     * other photographs, which is what an unpaired sort would do.
     */
    @Test
    fun photographsAreNewestFirstAndPairsDoNotStraddle() {
        val records = CaptureLog.pair(
            listOf(
                file("MF_old.jpg", 1000L),
                file("MF_old.dng", 1005L),
                file("MF_new.jpg", 3000L),
                file("MF_new.dng", 3005L),
                file("MF_mid.jpg", 2000L),
            )
        )
        assertThat(records.map { it.baseName })
            .containsExactly("MF_new", "MF_mid", "MF_old").inOrder()
    }

    @Test
    fun anEmptyLogIsAnEmptyGallery() {
        assertThat(CaptureLog.pair(emptyList())).isEmpty()
    }

    /** A record with no files is not a photograph and refuses to be one. */
    @Test
    fun aRecordMustHaveAtLeastOneFile() {
        try {
            CaptureRecord("MF_a", jpeg = null, dng = null)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("no files")
        }
    }

    // --- encoding ----------------------------------------------------------

    @Test
    fun aRecordSurvivesTheRoundTrip() {
        val line = CaptureLog.encode("content://media/1", "MF_a.jpg", 1234L)
        val decoded = CaptureLog.decode(line)

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.displayName).isEqualTo("MF_a.jpg")
        assertThat(decoded.takenAtMillis).isEqualTo(1234L)
        assertThat(decoded.uriString).isEqualTo("content://media/1")
    }

    /**
     * A corrupt line is skipped, not thrown on. The log is a convenience and
     * one bad entry must not cost the user the rest of their gallery.
     */
    @Test
    fun malformedLinesAreSkippedRatherThanThrown() {
        assertThat(CaptureLog.decode("")).isNull()
        assertThat(CaptureLog.decode("only-one-field")).isNull()
        assertThat(
            CaptureLog.decode(
                CaptureLog.encode("content://media/1", "MF_a.jpg", 1234L)
                    .replace("1234", "not-a-time"),
            )
        ).isNull()
    }

    @Test
    fun aLineWithNoUriIsSkipped() {
        assertThat(CaptureLog.decode(CaptureLog.encode("", "MF_a.jpg", 5L))).isNull()
    }

    @Test
    fun aLineWithNoNameIsSkipped() {
        assertThat(CaptureLog.decode(CaptureLog.encode("content://m/1", "", 5L)))
            .isNull()
    }

    /**
     * A name containing the separators would split into the wrong fields. The
     * separators are chosen to be illegal in a MediaStore display name, and
     * this pins that a realistic name round-trips whole.
     */
    @Test
    fun aRealisticNameRoundTripsIntact() {
        val name = "MF_20260910_143022_zsl_merged_28f.jpg"
        val decoded = CaptureLog.decode(
            CaptureLog.encode("content://media/external/images/media/1234", name, 99L)
        )
        assertThat(decoded!!.displayName).isEqualTo(name)
    }

    // --- description -------------------------------------------------------

    /** The gallery labels a shot from the name the pipeline chose for it. */
    @Test
    fun aRecordDescribesHowItWasTaken() {
        assertThat(CaptureLog.pair(listOf(file("MF_a_mosaic_49t.jpg", 1L)))
            .single().description).isEqualTo("mosaic")
        assertThat(CaptureLog.pair(listOf(file("MF_a_zsl_8f.jpg", 1L)))
            .single().description).isEqualTo("zero shutter lag")
        assertThat(CaptureLog.pair(listOf(file("MF_a_raw.dng", 1L)))
            .single().description).isEqualTo("single frame")
    }

    @Test
    fun aTwoXPhotographWearsItsBadge() {
        val records = CaptureLog.pair(
            listOf(file("MF_1_sr2x_8f.jpg", 10), file("MF_1_sr2x_8f.dng", 11), file("MF_2_zsl_8f.jpg", 5)),
        )
        assertThat(records).hasSize(2)
        assertThat(records[0].isSuperRes).isTrue()
        assertThat(records[0].hasRaw).isTrue()
        assertThat(records[0].description).isEqualTo("super-res 2x")
        assertThat(records[1].isSuperRes).isFalse()
    }
}
