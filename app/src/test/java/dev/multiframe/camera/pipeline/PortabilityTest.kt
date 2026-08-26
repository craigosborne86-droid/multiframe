package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraMetadata
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The app is developed against one phone but must not assume it. These cover
 * hardware profiles we cannot test physically.
 */
@RunWith(JUnit4::class)
class PortabilityTest {

    private fun caps(
        manualSensor: Boolean = true,
        isoMin: Int? = 50,
        isoMax: Int? = 6400,
        expMin: Long? = 100_000L,
        expMax: Long? = 1_000_000_000L,
        minFocus: Float = 10f,
        afModes: List<Int> = listOf(
            CameraMetadata.CONTROL_AF_MODE_OFF,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        ),
        nrModes: List<Int> = listOf(
            CameraMetadata.NOISE_REDUCTION_MODE_OFF,
            CameraMetadata.NOISE_REDUCTION_MODE_FAST,
        ),
        edgeModes: List<Int> = listOf(
            CameraMetadata.EDGE_MODE_OFF,
            CameraMetadata.EDGE_MODE_FAST,
        ),
        awbModes: List<Int> = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        ),
        evMin: Int = -24,
        evMax: Int = 24,
        evStep: Float = 1f / 6f,
        outputFormats: Set<Int> = setOf(0, 2),
    ) = CameraCapabilities(
        hasManualSensor = manualSensor,
        isoMin = isoMin, isoMax = isoMax,
        exposureMinNs = expMin, exposureMaxNs = expMax,
        minFocusDiopters = minFocus,
        awbModes = awbModes,
        afModes = afModes,
        noiseReductionModes = nrModes,
        edgeModes = edgeModes,
        evMin = evMin, evMax = evMax, evStep = evStep,
        sensorOrientation = 90,
        supportsRaw = true,
        supportedOutputFormats = outputFormats,
    )

    @Test
    fun `a fixed-focus lens offers no manual focus`() {
        val fixed = caps(minFocus = 0f, afModes = emptyList())
        assertThat(fixed.hasManualFocus).isFalse()
        assertThat(fixed.autoAfMode()).isNull()
    }

    @Test
    fun `manual focus needs both a focusable lens and an AF-off mode`() {
        assertThat(caps().hasManualFocus).isTrue()
        // Focusable lens, but the driver exposes no way to turn AF off.
        assertThat(
            caps(afModes = listOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)).hasManualFocus
        ).isFalse()
    }

    @Test
    fun `autofocus falls back when continuous picture is unavailable`() {
        assertThat(caps().autoAfMode())
            .isEqualTo(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        assertThat(caps(afModes = listOf(CameraMetadata.CONTROL_AF_MODE_AUTO)).autoAfMode())
            .isEqualTo(CameraMetadata.CONTROL_AF_MODE_AUTO)
        assertThat(caps(afModes = emptyList()).autoAfMode()).isNull()
    }

    @Test
    fun `ISP suppression is only claimed where the device supports it`() {
        assertThat(caps().canDisableNoiseReduction()).isTrue()
        assertThat(caps().canDisableEdgeEnhancement()).isTrue()

        val fixedIsp = caps(
            nrModes = listOf(CameraMetadata.NOISE_REDUCTION_MODE_FAST),
            edgeModes = listOf(CameraMetadata.EDGE_MODE_FAST),
        )
        assertThat(fixedIsp.canDisableNoiseReduction()).isFalse()
        assertThat(fixedIsp.canDisableEdgeEnhancement()).isFalse()
        assertThat(fixedIsp.summary()).contains("fixed noise reduction")
    }

    @Test
    fun `settings are clamped to what the hardware reports`() {
        val c = caps(isoMin = 100, isoMax = 800, expMin = 1_000_000L, expMax = 50_000_000L)
        val wild = ManualSettings(
            iso = 999_999,
            exposureTimeNs = 30_000_000_000L,
            focusDiopters = 500f,
            evIndex = 9999,
        )
        assertThat(wild.effectiveIso(c)).isEqualTo(800)
        assertThat(wild.effectiveExposureTimeNs(c)).isEqualTo(50_000_000L)
        assertThat(wild.effectiveFocusDiopters(c)).isEqualTo(10f)
        assertThat(wild.effectiveEvIndex(c)).isEqualTo(24)

        val tiny = ManualSettings(iso = 1, exposureTimeNs = 1L, evIndex = -9999)
        assertThat(tiny.effectiveIso(c)).isEqualTo(100)
        assertThat(tiny.effectiveExposureTimeNs(c)).isEqualTo(1_000_000L)
        assertThat(tiny.effectiveEvIndex(c)).isEqualTo(-24)
    }

    @Test
    fun `an unsupported white balance mode falls back to auto`() {
        val c = caps(awbModes = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO))
        val s = ManualSettings(awbMode = CameraMetadata.CONTROL_AWB_MODE_SHADE)
        assertThat(s.effectiveAwbMode(c)).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    @Test
    fun `a device without manual sensor never enters manual exposure`() {
        val basic = caps(manualSensor = false, isoMin = null, isoMax = null, expMin = null, expMax = null)
        val s = ManualSettings(manualExposure = true)
        assertThat(s.manualExposureActive(basic)).isFalse()
        assertThat(basic.hasIsoRange).isFalse()
        assertThat(basic.hasExposureRange).isFalse()
        assertThat(basic.summary()).contains("Auto only")
    }

    @Test
    fun `no exposure compensation support pins EV to zero`() {
        val c = caps(evStep = 0f, evMin = 0, evMax = 0)
        assertThat(c.supportsExposureCompensation).isFalse()
        assertThat(ManualSettings(evIndex = 12).effectiveEvIndex(c)).isEqualTo(0)
    }
}
