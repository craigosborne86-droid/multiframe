package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Locale

/** The account of a photograph, written into its EXIF and read back unchanged. */
@RunWith(JUnit4::class)
class CaptureReportTest {

    private val night = CaptureReport(
        kind = CaptureReport.KIND_NIGHT, framesRequested = 28, framesMerged = 26,
        meanContribution = 0.91f, spanMillis = 2_140, exposureTimeNs = 66_666_667L, iso = 1600,
        lens = "24 mm; main", alignment = CaptureReport.ALIGN_TILES, localTone = true, hdr = true,
        plan = "HANDHELD · 1/15 · ISO 1600 · 28×", mergeMillis = 3_200,
    )

    @Test fun roundTripsThroughItsOneLineForm() {
        val decoded = CaptureReport.decode(night.encode())!!
        // The separators are not allowed inside a value; they are replaced, not escaped.
        assertThat(decoded.lens).isEqualTo("24 mm, main")
        assertThat(decoded.copy(lens = night.lens)).isEqualTo(night)
        assertThat(night.encode()).startsWith("MFR1;")
    }

    @Test fun anythingElseIsNotAReport() {
        assertThat(CaptureReport.decode(null)).isNull()
        assertThat(CaptureReport.decode("Multiframe: 8 frames merged")).isNull()
        assertThat(CaptureReport.decode("MFR1;k=burst")).isNull() // no frame count
        // A later version's unknown keys are ignored.
        assertThat(CaptureReport.decode("MFR1;fm=8;zz=new;c=1.000")!!.framesMerged).isEqualTo(8)
    }

    @Test fun theNoiseFigureIsThePhotonArithmetic() {
        // 16 frames at full weight: noise / 4, two stops.
        val sixteen = night.copy(framesMerged = 16, meanContribution = 1f)
        assertThat(sixteen.noiseGainStops).isWithin(1e-4f).of(2f)
        assertThat(night.copy(framesMerged = 1).noiseGainStops).isEqualTo(0f)
    }

    @Test fun thePageSaysWhatWasDoneAndThatNothingWasGenerated() {
        val rows = night.rows(Locale.UK).toMap()
        assertThat(rows["Picture"]).isEqualTo("NIGHT burst")
        assertThat(rows["Frames"]).isEqualTo("26 merged of 28")
        assertThat(rows["Plan"]).contains("HANDHELD")
        assertThat(rows["Generative AI"]).startsWith("none")
        // A long exposure keeps every sample, so it does not claim rejection.
        val long = night.copy(kind = CaptureReport.KIND_LONG, meanContribution = 1f, alignment = CaptureReport.ALIGN_TRIPOD)
        assertThat(long.rows(Locale.UK).toMap()).doesNotContainKey("Kept")
        assertThat(long.rows(Locale.UK).toMap()["Alignment"]).contains("tripod")
    }

    @Test fun theKindFollowsTheFileTag() {
        assertThat(CaptureReport.kindForTag("long4s")).isEqualTo(CaptureReport.KIND_LONG)
        assertThat(CaptureReport.kindForTag("night")).isEqualTo(CaptureReport.KIND_NIGHT)
        assertThat(CaptureReport.kindForTag("zsl")).isEqualTo(CaptureReport.KIND_BURST)
        assertThat(CaptureReport.kindForTag("zsl", superRes = true)).isEqualTo(CaptureReport.KIND_SUPER_RES)
    }

    @Test fun nightAndLongExposureNamesKeepTheirFrameCount() {
        assertThat(RecentCapture.frameCount("MF_20261022_101500_night_26f.jpg")).isEqualTo(26)
        assertThat(RecentCapture.frameCount("MF_20261022_101500_long4s_118f.heic")).isEqualTo(118)
        assertThat(RecentCapture.describe("MF_20261022_101500_long4s_118f.jpg")).isEqualTo("long exposure")
    }
}
