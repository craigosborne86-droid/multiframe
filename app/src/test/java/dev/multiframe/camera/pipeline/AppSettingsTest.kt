package dev.multiframe.camera.pipeline

import android.hardware.camera2.CameraMetadata
import com.google.common.truth.Truth.assertThat
import dev.multiframe.camera.pipeline.AppSettings.Companion.reconcile
import org.junit.Test

/**
 * Persisted settings.
 *
 * The interesting cases are not the round trip -- they are what happens when
 * stored values meet a camera that cannot honour them.
 */
class AppSettingsTest {

    private val caps = CameraCapabilities(
        hasManualSensor = true,
        isoMin = 50, isoMax = 6400,
        exposureMinNs = 100_000, exposureMaxNs = 500_000_000,
        minFocusDiopters = 10f,
        awbModes = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        ),
        afModes = listOf(CameraMetadata.CONTROL_AF_MODE_OFF),
        noiseReductionModes = emptyList(), edgeModes = emptyList(),
        evMin = -12, evMax = 12, evStep = 1f / 6f,
        sensorOrientation = 90,
        supportsRaw = true,
        supportedOutputFormats = emptySet(),
    )

    private val lenses = listOf(
        Lens("0", true, 6.9f, 24, 1f, true, 1.7f, 70f),
        Lens("4", true, 30f, 110, 4.6f, true, 2.8f, 25f),
    )

    @Test
    fun `settings survive a round trip`() {
        val original = AppSettings(
            manual = ManualSettings(
                manualExposure = true, iso = 800, exposureTimeNs = 4_000_000L,
                manualFocus = true, focusDiopters = 2.5f,
                awbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                evIndex = -6, suppressIspProcessing = false,
            ),
            burstFrames = 16,
            lensId = "4",
            mergeEnabled = false,
            highlightGuard = false,
            zslEnabled = true,
            captureMode = CaptureMode.NIGHT,
        )

        val restored = AppSettings.decode(original.encode())

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `nothing stored gives the defaults`() {
        val restored = AppSettings.decode(emptyMap())

        assertThat(restored).isEqualTo(AppSettings())
        assertThat(restored.highlightGuard).isTrue()
    }

    @Test
    fun `a corrupt value costs only that setting`() {
        // Each key is independent on purpose. A stored blob that cannot be read
        // by a later version either crashes on upgrade or has to be
        // version-gated forever; independent keys degrade one at a time.
        val stored = AppSettings(burstFrames = 16).encode().toMutableMap()
        stored[AppSettings.KEY_ISO] = "not a number"
        stored[AppSettings.KEY_GUARD] = "perhaps"

        val restored = AppSettings.decode(stored)

        assertThat(restored.manual.iso).isEqualTo(ManualSettings().iso)
        assertThat(restored.highlightGuard).isEqualTo(AppSettings().highlightGuard)
        // The settings either side of the damage survive.
        assertThat(restored.burstFrames).isEqualTo(16)
    }

    @Test
    fun `an unrecognised capture mode falls back rather than failing`() {
        // A mode name written by a later version, or a corrupted one.
        val stored = AppSettings().encode().toMutableMap()
        stored[AppSettings.KEY_MODE] = "TELEPATHY"

        assertThat(AppSettings.decode(stored).captureMode).isEqualTo(CaptureMode.AUTO)
    }

    @Test
    fun `an unknown key is ignored rather than fatal`() {
        val stored = AppSettings().encode() + ("somethingFromTheFuture" to "42")

        assertThat(AppSettings.decode(stored)).isEqualTo(AppSettings())
    }

    @Test
    fun `an absurd burst count is brought back into range`() {
        val stored = AppSettings().encode().toMutableMap()
        stored[AppSettings.KEY_BURST] = "9999"

        assertThat(AppSettings.decode(stored).burstFrames).isAtMost(32)

        stored[AppSettings.KEY_BURST] = "-4"
        assertThat(AppSettings.decode(stored).burstFrames).isAtLeast(1)
    }

    // ------------------------------------------------------------------
    // Meeting real hardware.
    // ------------------------------------------------------------------

    @Test
    fun `a lens that is not on this device is forgotten`() {
        // Settings restored from a backup of another phone would otherwise name
        // a camera that does not exist.
        val restored = AppSettings(lensId = "99").reconcile(caps, lenses)

        assertThat(restored.lensId).isNull()
    }

    @Test
    fun `a lens that is present is kept`() {
        assertThat(AppSettings(lensId = "4").reconcile(caps, lenses).lensId).isEqualTo("4")
    }

    @Test
    fun `manual modes are dropped on hardware that cannot do them`() {
        // Sending manual exposure to a camera without MANUAL_SENSOR has the
        // whole request rejected, which loses every other setting with it.
        val basic = caps.copy(hasManualSensor = false, minFocusDiopters = 0f)
        val wanted = AppSettings(
            manual = ManualSettings(manualExposure = true, manualFocus = true)
        )

        val reconciled = wanted.reconcile(basic, lenses)

        assertThat(reconciled.manual.manualExposure).isFalse()
        assertThat(reconciled.manual.manualFocus).isFalse()
    }

    @Test
    fun `an unsupported white balance falls back to auto`() {
        val wanted = AppSettings(
            manual = ManualSettings(awbMode = CameraMetadata.CONTROL_AWB_MODE_SHADE)
        )

        val reconciled = wanted.reconcile(caps, lenses)

        assertThat(reconciled.manual.awbMode)
            .isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    @Test
    fun `exposure compensation is clamped to the device range`() {
        val narrow = caps.copy(evMin = -2, evMax = 2, evStep = 1f)
        val wanted = AppSettings(manual = ManualSettings(evIndex = -30))

        assertThat(wanted.reconcile(narrow, lenses).manual.evIndex).isAtLeast(-2)
    }

    @Test
    fun `reconciling settings that are already valid changes nothing`() {
        val fine = AppSettings(
            manual = ManualSettings(
                manualExposure = true,
                awbMode = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                evIndex = -3,
            ),
            lensId = "0",
        )

        assertThat(fine.reconcile(caps, lenses)).isEqualTo(fine)
    }
}
