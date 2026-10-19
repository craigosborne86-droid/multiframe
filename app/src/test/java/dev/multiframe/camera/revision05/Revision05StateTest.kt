package dev.multiframe.camera.revision05

import dev.multiframe.camera.pipeline.*
import dev.multiframe.camera.ui.*
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class Revision05StateTest {
    @Test fun saveOutcomeOnlyAnnouncesOutputsThatActuallyExist() {
        val failed = CaptureSaveFeedback.forOutputs(false,false,true)
        assertFalse(failed.hasSavedOutput)
        assertNotNull(failed.message)
        val onlyDng = CaptureSaveFeedback.forOutputs(false,true,true)
        assertTrue(onlyDng.hasSavedOutput)
        assertTrue(onlyDng.message!!.contains("DNG saved"))
        val missingDng = CaptureSaveFeedback.forOutputs(true,false,true)
        assertTrue(missingDng.hasSavedOutput)
        assertTrue(missingDng.message!!.contains("DNG could not be saved"))
        assertNull(CaptureSaveFeedback.forOutputs(true,false,false).message)
        assertNull(CaptureSaveFeedback.forOutputs(true,true,true).message)
    }
    @Test fun completedMergeNeverLeavesTheShutterProgressRingFilled() {
        val active = MergeUiProgress(1,14,28)
        assertEquals(.5f,active.shutterFraction!!,0f)
        assertNull(active.copy(processed=28,finished=true).shutterFraction)
    }
    @Test fun temporarySweepRestoresTheCompleteShootingConfiguration() {
        val before = CaptureConfigurationSnapshot(ManualSettings(manualExposure = true, iso = 800,
            exposureTimeNs = 6_000_000_000L, whiteBalanceMode = WhiteBalanceMode.MANUAL, kelvin = 5500, tint = 12),
            CaptureMode.NIGHT, 28, true, 10, "6", 2f, true, true)
        assertEquals(before, CaptureConfigurationSnapshot.decode(before.encode()))
        assertNull(CaptureConfigurationSnapshot.decode(emptyMap()))
    }
    private val calibration = ColorScience.Calibration(Mat3.IDENTITY, Mat3.IDENTITY,
        illuminant1Kelvin = 2850, illuminant2Kelvin = 6500)
    @Test fun manualAndWhiteBalancePersistIndependently() {
        val manual = ManualSettings(manualExposure = true, iso = 800, exposureTimeNs = 6_000_000_000,
            manualFocus = true, focusDiopters = 2f, whiteBalanceMode = WhiteBalanceMode.MANUAL, kelvin = 5100, tint = -12)
        val encoded = AppSettings(manual = manual, manualBurst = true, burstFrames = 28, captureMode = CaptureMode.NIGHT).encode()
        val restored = AppSettings.decode(encoded)
        assertEquals(manual, restored.manual)
        assertTrue(restored.manualBurst)
        val auto = restored.manual.returnToAuto()
        assertFalse(auto.manualExposure); assertFalse(auto.manualFocus)
        assertEquals(6_000_000_000L, auto.exposureTimeNs)
        assertEquals(manual.whiteBalanceMode, auto.whiteBalanceMode)
        assertEquals(5100, auto.kelvin); assertEquals(-12, auto.tint)
        assertEquals(auto, restored.copy(manual = auto, captureMode = CaptureMode.PHOTO).manual)
    }
    @Test fun calibrationRangeAndValidationAreTruthful() {
        assertEquals(2850..6500, CalibratedWhiteBalance.range(calibration))
        assertNull(CalibratedWhiteBalance.resolve(calibration, 1000, 0))
        assertNull(CalibratedWhiteBalance.resolve(calibration, 5000, 101))
        assertNull(CalibratedWhiteBalance.resolve(null, 5000, 0))
        val solution = CalibratedWhiteBalance.resolve(calibration, 5000, 0)!!
        assertTrue(solution.gains.all { it.isFinite() && it >= 1f })
        val out = FloatArray(3)
        solution.transform.apply(solution.neutral[0] * solution.gains[0],
            solution.neutral[1] * solution.gains[1], solution.neutral[2] * solution.gains[3], out)
        assertEquals(out[0], out[1], 0.002f); assertEquals(out[1], out[2], 0.002f)
    }
    @Test fun tintChangesNeutralRatherThanExposureSettings() {
        val a = CalibratedWhiteBalance.resolve(calibration, 5000, -20)!!
        val b = CalibratedWhiteBalance.resolve(calibration, 5000, 20)!!
        assertFalse(a.neutral.contentEquals(b.neutral))
        val base = ManualSettings()
        val wb = base.copy(whiteBalanceMode = WhiteBalanceMode.MANUAL, kelvin = 5000, tint = 20)
        assertEquals(base.manualExposure, wb.manualExposure); assertEquals(base.manualFocus, wb.manualFocus)
    }
    @Test fun captureSummaryUsesTheSuppliedRegionWithoutChangingCaptureValues() {
        val settings = ManualSettings(manualExposure = true, iso = 800, exposureTimeNs = 6_000_000_000L)
        val expected = settings.copy()
        assertTrue(NextCaptureSummary.of(settings, 28, true, 1f, false, java.util.Locale.FRANCE).contains("6,0s"))
        assertTrue(NextCaptureSummary.of(settings, 28, true, 1f, false, java.util.Locale.US).contains("6.0s"))
        assertEquals(expected, settings)
        assertEquals("1/125", settings.copy(exposureTimeNs = 8_000_000L).shutterLabel(java.util.Locale.FRANCE))
    }
    @Test fun captureSummaryIsStillConfigurationNotCappedPreview() {
        val s = NextCaptureSummary.of(ManualSettings(manualExposure = true, exposureTimeNs = 6_000_000_000), 28, true, 1f, true)
        assertTrue(s.contains("6.0s")); assertTrue(s.contains("Burst: 28 frames")); assertTrue(s.contains("DNG: On"))
    }
    @Test fun previewGeometryDoesNotDependOnPanelsOrTransientBars() {
        for ((window, aspect) in listOf(IntSize(1280,2856) to .75f, IntSize(2856,1280) to 4f/3f)) {
            for (reverse in listOf(false,true)) {
                val safe = CaptureSafeInsets(top = 96, left = if(reverse) 96 else 0, bottom = 72)
                val frozen = revision05Geometry(window, aspect, safe, 144, 960, reverse)
                repeat(10) { assertEquals(frozen, revision05Geometry(window, aspect, safe, 144,960,reverse)) }
                assertEquals(aspect, frozen.image.width / frozen.image.height, .002f)
                assertFalse(frozen.image.overlaps(frozen.controls))
                assertEquals(.5f, imagePoint(frozen.image, frozen.image.center)!!.x, .0001f)
                assertNull(imagePoint(frozen.image, frozen.controls.center))
            }
        }
    }
    @Test fun shortPortraitWindowsReserveTheRealPrimaryControlHeight() {
        val minimum = 684 // 228dp at the fixture's density of 3 px/dp.
        for (height in listOf(1800,2160,2400,2856)) {
            val geometry = revision05Geometry(IntSize(1280,height),.75f,
                CaptureSafeInsets(top=96,bottom=72),144,960,false,minimum)
            assertTrue(geometry.controls.height >= minimum)
            assertEquals(.75f,geometry.image.width/geometry.image.height,.002f)
            assertFalse(geometry.image.overlaps(geometry.controls))
            assertTrue(geometry.controls.bottom <= height-72)
        }
    }
}
