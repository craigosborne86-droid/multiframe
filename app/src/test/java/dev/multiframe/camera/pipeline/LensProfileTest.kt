package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The derived quantities on a lens profile, and the gating they drive.
 *
 * `CameraCharacteristics` is final and unconstructable off-device, so the
 * parsing in [LensProfiles.from] belongs to the device suite. What is testable
 * here is everything the parsed record then *decides*, which is the part with
 * branches in it: whether a lens may bracket, what ratio it brackets at, and
 * what its geometry works out to.
 */
class LensProfileTest {

    /** A capable rear main camera: raw, manual sensor, focus, OIS. */
    private fun profile(
        cameraId: String = "0",
        supportsRaw: Boolean = true,
        supportsManualSensor: Boolean = true,
        isoMin: Int? = 50,
        isoMax: Int? = 12800,
        dcgThresholdIso: Int? = null,
        sensorWidthMm: Float = 9.8f,
        activeArrayWidth: Int = 8160,
    ) = LensProfile(
        cameraId = cameraId,
        logicalId = null,
        focalLengthMm = 6.9f,
        equivalent35mm = 24,
        apertureF = 1.7f,
        facingBack = true,
        sensorWidthMm = sensorWidthMm,
        sensorHeightMm = 7.3f,
        activeArrayWidth = activeArrayWidth,
        activeArrayHeight = 6144,
        supportsRaw = supportsRaw,
        supportsManualSensor = supportsManualSensor,
        supportsManualFocus = true,
        hasOis = true,
        isoMin = isoMin,
        isoMax = isoMax,
        exposureMinNs = 1_000L,
        exposureMaxNs = 500_000_000L,
        distortion = null,
        intrinsics = null,
        dcgThresholdIso = dcgThresholdIso,
    )

    // --- bracketing gate ---------------------------------------------------

    @Test
    fun aCapableLensMayBracket() {
        assertThat(profile().supportsBracketing).isTrue()
    }

    /**
     * Each of the four requirements, withdrawn one at a time.
     *
     * Bracketing needs raw to merge, a manual sensor to set a sensitivity, and
     * a published range to know what sensitivities exist. Any one missing and
     * the control must not be offered -- an unavailable feature is hidden, not
     * disabled, which is the rule in CLAUDE.md.
     */
    @Test
    fun everyMissingPrerequisiteWithdrawsBracketing() {
        assertThat(profile(supportsRaw = false).supportsBracketing).isFalse()
        assertThat(profile(supportsManualSensor = false).supportsBracketing).isFalse()
        assertThat(profile(isoMin = null).supportsBracketing).isFalse()
        assertThat(profile(isoMax = null).supportsBracketing).isFalse()
    }

    // --- bracket ratio -----------------------------------------------------

    @Test
    fun withNoMeasuredThresholdTheSoftwareDefaultStands() {
        assertThat(profile(dcgThresholdIso = null).bracketRatio(default = 4))
            .isEqualTo(4)
    }

    @Test
    fun aMeasuredThresholdSetsTheRatioAgainstBaseSensitivity() {
        // Switch at ISO 400 on a sensor whose floor is 50: eight times base.
        assertThat(profile(isoMin = 50, dcgThresholdIso = 400).bracketRatio(4))
            .isEqualTo(8)
        assertThat(profile(isoMin = 100, dcgThresholdIso = 400).bracketRatio(4))
            .isEqualTo(4)
    }

    /**
     * The ratio is clamped rather than trusted.
     *
     * A threshold close to base would produce a ratio of one, which is not a
     * bracket at all -- both halves would shoot the same exposure and the merge
     * would gain nothing while the preview ran at half rate. A very distant one
     * would put the high frame so far up that it is noise all the way down.
     */
    @Test
    fun anAbsurdRatioIsClampedToSomethingWorthShooting() {
        assertThat(profile(isoMin = 50, dcgThresholdIso = 60).bracketRatio(4))
            .isEqualTo(2)
        assertThat(profile(isoMin = 50, dcgThresholdIso = 12800).bracketRatio(4))
            .isEqualTo(8)
    }

    @Test
    fun aLensWithNoSensitivityRangeFallsBackRatherThanDividingByNothing() {
        assertThat(profile(isoMin = null, dcgThresholdIso = 400).bracketRatio(4))
            .isEqualTo(4)
        assertThat(profile(isoMin = 0, dcgThresholdIso = 400).bracketRatio(4))
            .isEqualTo(4)
    }

    // --- geometry ----------------------------------------------------------

    @Test
    fun pixelPitchIsSensorWidthOverTheArray() {
        // 9.8mm across 8160 photosites is 1.2 microns, which is about what a
        // phone main sensor actually measures.
        val pitch = profile(sensorWidthMm = 9.8f, activeArrayWidth = 8160)
            .pixelPitchMicrons
        assertThat(pitch).isWithin(0.01f).of(1.2f)
    }

    /**
     * A device that publishes no sensor size reports zero rather than dividing
     * by it. Physical size is optional in the Camera2 specification.
     */
    @Test
    fun missingGeometryIsZeroRatherThanInfinity() {
        assertThat(profile(sensorWidthMm = 0f).pixelPitchMicrons).isEqualTo(0f)
        assertThat(profile(activeArrayWidth = 0).pixelPitchMicrons).isEqualTo(0f)
    }

    @Test
    fun theSensorDiagonalIsThePythagoreanOne() {
        assertThat(profile().sensorDiagonalMm).isWithin(0.01f).of(12.22f)
    }

    // --- value semantics ---------------------------------------------------

    /**
     * The array fields make the generated equals wrong, so both are written by
     * hand and both are pinned here. Two profiles that differ only in their
     * distortion terms are different profiles, and a data class would have said
     * otherwise by comparing the arrays by identity.
     */
    @Test
    fun profilesCompareByContentIncludingTheirArrays() {
        val a = profile().copy(distortion = floatArrayOf(1f, 2f, 3f, 4f, 5f))
        val b = profile().copy(distortion = floatArrayOf(1f, 2f, 3f, 4f, 5f))
        val c = profile().copy(distortion = floatArrayOf(9f, 2f, 3f, 4f, 5f))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun anAbsentArrayIsNotTheSameAsAPresentOne() {
        val none = profile()
        val some = profile().copy(distortion = floatArrayOf(0f, 0f, 0f, 0f, 0f))
        assertThat(none).isNotEqualTo(some)
        assertThat(some).isNotEqualTo(none)
    }

    @Test
    fun aMeasuredThresholdMakesADifferentProfile() {
        assertThat(profile(dcgThresholdIso = null))
            .isNotEqualTo(profile(dcgThresholdIso = 400))
    }

    /** The log line is what a field report will contain, so it says the useful things. */
    @Test
    fun theDescriptionNamesTheOpticAndWhatItCanDo() {
        val text = profile(dcgThresholdIso = 400).toString()
        assertThat(text).contains("24mm")
        assertThat(text).contains("f/1.7")
        assertThat(text).contains("RAW")
        assertThat(text).contains("OIS")
        assertThat(text).contains("dcg@400")
    }

    @Test
    fun aLensReachedThroughALogicalParentSaysSo() {
        val sub = profile().copy(cameraId = "3", logicalId = "0")
        assertThat(sub.toString()).contains("via 0")
    }

    @Test
    fun nativeHighResolutionBelongsToThePhysicalSensorProfile() {
        val full = profile().copy(
            maxResolutionDimensions = FrameSize(8160, 6120),
            supportsNativeHighRes = true,
        )
        assertThat(full.maxResolutionDimensions).isEqualTo(FrameSize(8160, 6120))
        assertThat(full.toString()).contains("maxRAW=8160x6120")
        assertThat(full).isNotEqualTo(profile())
    }
}
