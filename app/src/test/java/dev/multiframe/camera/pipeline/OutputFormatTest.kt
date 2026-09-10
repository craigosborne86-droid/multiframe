package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The output format setting, and its round trip through storage.
 *
 * Small surface, but the failure it guards against is not: a stored value that
 * does not parse must land on JPEG, because JPEG is the format that opens
 * everywhere. Defaulting an unreadable setting to HEIF would hand someone files
 * their software cannot read on the strength of a typo.
 */
class OutputFormatTest {

    @Test
    fun jpegIsTheDefault() {
        assertThat(AppSettings().outputFormat).isEqualTo(OutputFormat.JPEG)
    }

    @Test
    fun theFormatsAreLabelledAsPhotographersNameThem() {
        assertThat(OutputFormat.JPEG.label).isEqualTo("JPEG")
        assertThat(OutputFormat.HEIF.label).isEqualTo("HEIF")
    }

    @Test
    fun cyclingVisitsBothAndReturns() {
        assertThat(OutputFormat.JPEG.next()).isEqualTo(OutputFormat.HEIF)
        assertThat(OutputFormat.HEIF.next()).isEqualTo(OutputFormat.JPEG)
    }

    @Test
    fun aStoredFormatIsRecovered() {
        assertThat(OutputFormat.named("HEIF")).isEqualTo(OutputFormat.HEIF)
        assertThat(OutputFormat.named("JPEG")).isEqualTo(OutputFormat.JPEG)
    }

    /**
     * Anything unreadable falls to JPEG rather than to the enum's first entry
     * by accident. They happen to be the same today; this pins the intent so a
     * later reordering does not silently change it.
     */
    @Test
    fun anUnreadableStoredFormatFallsBackToTheCompatibleOne() {
        assertThat(OutputFormat.named(null)).isEqualTo(OutputFormat.JPEG)
        assertThat(OutputFormat.named("")).isEqualTo(OutputFormat.JPEG)
        assertThat(OutputFormat.named("heif")).isEqualTo(OutputFormat.JPEG)
        assertThat(OutputFormat.named("AVIF")).isEqualTo(OutputFormat.JPEG)
    }

    @Test
    fun theFormatSurvivesTheSettingsRoundTrip() {
        for (format in OutputFormat.entries) {
            val encoded = AppSettings(outputFormat = format).encode()
            assertThat(encoded[AppSettings.KEY_FORMAT]).isEqualTo(format.name)
            assertThat(OutputFormat.named(encoded[AppSettings.KEY_FORMAT]))
                .isEqualTo(format)
        }
    }

    // --- encoder gating ----------------------------------------------------

    /**
     * HEVC works in macroblocks and several chipsets refuse odd dimensions
     * outright. Checked before a MediaStore row is created, so a refusal never
     * leaves a `.heic` holding nothing.
     */
    @Test
    fun oddDimensionsAreDeclined() {
        assertThat(HeifEncoder.dimensionsAcceptable(4081, 3064)).isFalse()
        assertThat(HeifEncoder.dimensionsAcceptable(4080, 3063)).isFalse()
        assertThat(HeifEncoder.dimensionsAcceptable(0, 100)).isFalse()
        assertThat(HeifEncoder.dimensionsAcceptable(-2, 100)).isFalse()
    }

    /**
     * Even dimensions are acceptable only where the runtime has the format at
     * all. On the JVM, where these tests run, it does not -- and that is the
     * assertion: the gate is closed rather than open by default.
     */
    @Test
    fun evenDimensionsStillDependOnTheRuntimeHavingTheFormat() {
        val accepted = HeifEncoder.dimensionsAcceptable(4080, 3064)
        assertThat(accepted).isEqualTo(HeifEncoder.isAvailable)
    }
}
