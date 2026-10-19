package dev.multiframe.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Direct guide oracle; no alignment or displacement is involved. */
@RunWith(Parameterized::class)
class CfaGuidePedestalTest(
    private val layout: String,
    private val cfa: IntArray,
    private val gain: Float,
) {
    private val unequalBlack = intArrayOf(48, 80, 112, 144)
    private val equalBlack = intArrayOf(48, 48, 48, 48)
    private val colouredLight = intArrayOf(240, 360, 600)
    private val darkness = intArrayOf(0, 0, 0)

    /** Independent sensor model: exposure scales light above each site black. */
    private fun frame(profile: SensorProfile, light: IntArray): BayerFrame =
        BayerFrame(2, 2, ShortArray(4) { site ->
            val code = profile.blackLevel[site] + gain * light[cfa[site]]
            require(code >= 0f && code < profile.whiteLevel && code == code.toInt().toFloat())
            code.toInt().toShort()
        })

    private fun profile(black: IntArray) = SensorProfile(black.copyOf(), 4095, cfa.copyOf())

    /** A Bayer cell contains R+G+G+B light, independently of its stored RAW codes. */
    private fun expectedGuide(profile: SensorProfile, light: IntArray): ByteArray {
        val cellLight = light[0] + 2 * light[1] + light[2]
        val range = 4095 - requireNotNull(profile.blackLevel.minOrNull())
        return byteArrayOf((255.0 * cellLight / (4 * range)).toInt().toByte())
    }

    private fun kotlinGuide(profile: SensorProfile, light: IntArray): Plane {
        val accumulator = BayerAccumulator(2, 2, profile)
        try {
            // Keep the production guide private; inspect only this small,
            // independently specified result instead of adding a test API.
            val method = BayerAccumulator::class.java.getDeclaredMethod(
                "lumaProxy", BayerFrame::class.java, Float::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            return method.invoke(accumulator, frame(profile, light), gain) as Plane
        } finally {
            accumulator.release()
        }
    }

    private fun nativeGuide(profile: SensorProfile, light: IntArray): Plane {
        assumeTrue("opt-in host JNI / physical native library", NativeMerge.isAvailable())
        val sensor = frame(profile, light)
        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            asShortBuffer().put(sensor.data)
        }
        return requireNotNull(NativeMerge.create(2, 2, profile)).use { native ->
            val result = native.lumaProxy(buffer, 4, gain)
            Reference.reachabilityFence(buffer)
            result
        }
    }

    private fun assertGuide(label: String, profile: SensorProfile, light: IntArray, actual: Plane) {
        assertEquals("$label width", 1, actual.width)
        assertEquals("$label height", 1, actual.height)
        assertArrayEquals("$layout gain=$gain $label", expectedGuide(profile, light), actual.data)
    }

    @Test fun kotlinUnequalBlackDarknessIsAnExactNull() {
        val profile = profile(unequalBlack)
        assertGuide("Kotlin unequal-black dark", profile, darkness, kotlinGuide(profile, darkness))
    }

    @Test fun kotlinUnequalBlackColouredSignalMatchesTheIndependentCellSum() {
        val profile = profile(unequalBlack)
        assertGuide("Kotlin unequal-black signal", profile, colouredLight, kotlinGuide(profile, colouredLight))
    }

    @Test fun kotlinEqualBlackDarknessAndSignalKeepTheExistingResult() {
        val profile = profile(equalBlack)
        for (light in listOf(darkness, colouredLight)) {
            assertGuide("Kotlin equal-black control", profile, light, kotlinGuide(profile, light))
        }
    }

    /** Opt in with -Pmf.host.lib=<directory containing the host JNI library>. */
    @Test fun nativeUnequalBlackDarknessIsAnExactNull() {
        val profile = profile(unequalBlack)
        assertGuide("native unequal-black dark", profile, darkness, nativeGuide(profile, darkness))
    }

    @Test fun nativeUnequalBlackColouredSignalMatchesTheIndependentCellSum() {
        val profile = profile(unequalBlack)
        assertGuide("native unequal-black signal", profile, colouredLight, nativeGuide(profile, colouredLight))
    }

    @Test fun nativeEqualBlackDarknessAndSignalKeepTheExistingResult() {
        val profile = profile(equalBlack)
        for (light in listOf(darkness, colouredLight)) {
            assertGuide("native equal-black control", profile, light, nativeGuide(profile, light))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} gain={2}")
        fun cases(): List<Array<Any>> = listOf(
            "RGGB" to intArrayOf(0, 1, 1, 2),
            "GRBG" to intArrayOf(1, 0, 2, 1),
            "GBRG" to intArrayOf(1, 2, 0, 1),
            "BGGR" to intArrayOf(2, 1, 1, 0),
        ).flatMap { (name, cfa) ->
            listOf(0.5f, 1f, 2f).map { gain -> arrayOf<Any>(name, cfa, gain) }
        }
    }
}
