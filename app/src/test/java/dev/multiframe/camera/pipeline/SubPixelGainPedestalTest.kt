package dev.multiframe.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Exposure changes scale camera-linear radiance above the CFA pedestal.
 * Four unequal site pedestals also distinguish the two green lattices.
 */
class SubPixelGainPedestalTest {
    private val sensorW = 64
    // The optional native check crosses the 256-row canvas strip boundary.
    private val sensorH = 160
    private val black = intArrayOf(48, 80, 112, 144)
    private val radiance = intArrayOf(32, 48, 64)
    private val layouts = listOf(
        "RGGB" to intArrayOf(0, 1, 1, 2),
        "GRBG" to intArrayOf(1, 0, 2, 1),
        "GBRG" to intArrayOf(1, 2, 0, 1),
        "BGGR" to intArrayOf(2, 1, 1, 0),
    )

    private data class Sample(val frame: BayerFrame, val field: AlignmentField, val gain: Float)

    private fun profile(cfa: IntArray) = SensorProfile(black.copyOf(), 1023, cfa.copyOf())

    /** Independent sensor model: the black code stays fixed as exposure changes. */
    private fun frame(profile: SensorProfile, gain: Float, light: IntArray = radiance): BayerFrame =
        BayerFrame(sensorW, sensorH, ShortArray(sensorW * sensorH) { i ->
            val x = i % sensorW
            val y = i / sensorW
            val site = (y and 1) * 2 + (x and 1)
            val code = profile.blackAt(x, y) + gain * light[profile.cfaPattern[site]]
            require(code >= 0f && code < profile.whiteLevel && code == code.toInt().toFloat())
            code.toInt().toShort()
        })

    private fun field(sx: Float = 0f, sy: Float = 0f): AlignmentField {
        val tilesX = maxOf(1, (sensorW / 2) / SubPixelAccumulator.TILE_TARGET)
        val tilesY = maxOf(1, (sensorH / 2) / SubPixelAccumulator.TILE_TARGET)
        val n = tilesX * tilesY
        val px = -sx / 2f
        val py = -sy / 2f
        val ix = kotlin.math.round(px).toInt()
        val iy = kotlin.math.round(py).toInt()
        return AlignmentField(
            tilesX, tilesY, IntArray(n) { ix }, IntArray(n) { iy },
            FloatArray(n) { px - ix }, FloatArray(n) { py - iy },
        )
    }

    private fun lattice(profile: SensorProfile, gain: Float, light: IntArray = radiance): List<Sample> =
        buildList {
            for (sy in listOf(0f, 0.5f, 1f, 1.5f)) for (sx in listOf(0f, 0.5f, 1f, 1.5f)) {
                if (sx != 0f || sy != 0f) add(Sample(frame(profile, gain, light), field(sx, sy), gain))
            }
        }

    private fun accumulate(profile: SensorProfile, reference: BayerFrame, alts: List<Sample>): SubPixelResult {
        val accumulator = SubPixelAccumulator(sensorW, sensorH, profile, hotPixelThreshold = 0f)
        accumulator.setReference(reference)
        for (alt in alts) accumulator.add(alt.frame, alt.field, alt.gain)
        return accumulator.finish()
    }

    private fun assertSameCanvas(label: String, expected: SubPixelResult, actual: SubPixelResult) {
        // The binary gains and even integer signals normalise exactly; all
        // samples and their order then match the gain-one control.
        assertArrayEquals("$label RGB", expected.rgb, actual.rgb, 0f)
        assertEquals("$label holes", expected.holeFraction, actual.holeFraction, 0f)
        assertEquals("$label interior holes", expected.interiorHoleFraction, actual.interiorHoleFraction, 0f)
        assertArrayEquals("$label per-channel holes", expected.interiorHolesPerChannel, actual.interiorHolesPerChannel, 0f)
    }

    private fun assertGainPreservesPedestal(gain: Float) {
        for ((name, cfa) in layouts) for (light in listOf(radiance, intArrayOf(0, 0, 0))) {
            val profile = profile(cfa)
            val reference = frame(profile, 1f, light)
            val control = accumulate(profile, reference, lattice(profile, 1f, light))
            val actual = accumulate(profile, reference, lattice(profile, gain, light))
            val label = "$name gain=$gain light=${light.joinToString()}"
            assertSameCanvas(label, control, actual)
            assertEquals("$label accepted radiance", 1f, actual.meanContribution, 0f)
            assertEquals("$label frame count", 16, actual.framesMerged)
        }
    }

    @Test fun aHigherGainScalesRadianceAndPreservesEveryCfaPedestal() =
        assertGainPreservesPedestal(2f)

    @Test fun aLowerGainScalesRadianceAndPreservesEveryCfaPedestal() =
        assertGainPreservesPedestal(0.5f)

    @Test fun gainOneDuplicateFramesAreAnExactNull() {
        for ((name, cfa) in layouts) {
            val profile = profile(cfa)
            val reference = frame(profile, 1f)
            val control = accumulate(profile, reference, emptyList())
            val actual = accumulate(profile, reference, List(15) { Sample(reference, field(), 1f) })
            assertSameCanvas("$name gain-one null", control, actual)
            assertEquals("$name accepted duplicates", 1f, actual.meanContribution, 0f)
            assertEquals("$name frame count", 16, actual.framesMerged)
        }
    }

    @Test fun aGainChangeDoesNotTurnThePedestalIntoAnInflatedNoiseTolerance() {
        for ((name, cfa) in layouts) {
            val profile = profile(cfa)
            val reference = frame(profile, 1f)
            // Plenty of flat samples per brightness bin establish the noise
            // floor from a physically identical frame at another exposure.
            val clean = Sample(frame(profile, 2f), field(), 2f)
            val control = accumulate(profile, reference, listOf(clean))
            // A later 24-DN signal error is well outside the 1.5-DN tolerance.
            // Correcting placement alone leaves pedestal-biased empirical
            // noise and incorrectly accepts this frame.
            val wrong = Sample(frame(profile, 2f, IntArray(3) { radiance[it] + 24 }), field(), 2f)
            val actual = accumulate(profile, reference, listOf(clean, wrong))
            assertSameCanvas("$name empirical noise", control, actual)
            assertEquals("$name only the clean alternate contributes", 0.5f, actual.meanContribution, 0f)
            assertEquals("$name frame count", 3, actual.framesMerged)
        }
    }

    private fun nativeAccumulate(
        profile: SensorProfile, reference: BayerFrame, alts: List<Sample>,
    ): SubPixelResult {
        val native = requireNotNull(NativeSubPixel.create(sensorW, sensorH, profile, hotPixelThreshold = 0f))
        return native.use { n ->
            fun buffer(f: BayerFrame) = ByteBuffer.allocateDirect(f.data.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(f.data) }
            val buffers = listOf(buffer(reference)) + alts.map { buffer(it.frame) }
            assertTrue(n.setReference(buffers.first(), sensorW * 2))
            for ((index, alt) in alts.withIndex()) {
                assertTrue(n.addFrame(buffers[index + 1], sensorW * 2, alt.field, alt.gain))
            }
            val result = requireNotNull(n.finish())
            val values = ShortArray(result.width * result.height * 3)
            result.rgb16.asShortBuffer().get(values)
            Reference.reachabilityFence(buffers)
            SubPixelResult(
                result.width, result.height, FloatArray(values.size) { (values[it].toInt() and 0xFFFF).toFloat() },
                result.holeFraction, result.interiorHoleFraction, result.meanContribution,
                result.framesMerged, result.interiorHolesPerChannel.copyOf(),
            )
        }
    }

    private fun assertNativeParity(label: String, expected: SubPixelResult, actual: SubPixelResult) {
        assertEquals(expected.rgb.size, actual.rgb.size)
        for (i in actual.rgb.indices) {
            assertTrue("$label RGB[$i]", abs(actual.rgb[i] - expected.rgb[i]) <= 1f)
        }
        assertEquals("$label contribution", expected.meanContribution, actual.meanContribution, 1e-6f)
        assertEquals("$label frames", expected.framesMerged, actual.framesMerged)
        assertEquals("$label holes", expected.holeFraction, actual.holeFraction, 1e-6f)
        assertEquals("$label interior holes", expected.interiorHoleFraction, actual.interiorHoleFraction, 1e-6f)
        assertArrayEquals("$label per-channel holes", expected.interiorHolesPerChannel, actual.interiorHolesPerChannel, 1e-6f)
    }

    /** Opt in with -Pmf.host.lib=<directory containing the host JNI library>. */
    @Test fun nativeAndKotlinPreserveThePedestalAcrossCfaLayoutsGainsAndStrips() {
        assumeTrue("opt-in host JNI / physical native library", NativeMerge.isAvailable())
        for ((name, cfa) in layouts) for (gain in listOf(0.5f, 1f, 2f)) {
            val profile = profile(cfa)
            val reference = frame(profile, 1f)
            val alts = lattice(profile, gain)
            val control = accumulate(profile, reference, lattice(profile, 1f))
            val kotlin = accumulate(profile, reference, alts)
            assertSameCanvas("$name gain=$gain Kotlin physical control", control, kotlin)
            assertNativeParity("$name gain=$gain", kotlin, nativeAccumulate(profile, reference, alts))
        }
    }

    @Test fun nativeAndKotlinRejectTheSameResidualAfterAGainChange() {
        assumeTrue("opt-in host JNI / physical native library", NativeMerge.isAvailable())
        for ((name, cfa) in layouts) {
            val profile = profile(cfa)
            val reference = frame(profile, 1f)
            val clean = Sample(frame(profile, 2f), field(), 2f)
            val wrong = Sample(frame(profile, 2f, IntArray(3) { radiance[it] + 24 }), field(), 2f)
            val alts = listOf(clean, wrong)
            val control = accumulate(profile, reference, listOf(clean))
            val kotlin = accumulate(profile, reference, alts)
            assertSameCanvas("$name Kotlin empirical noise", control, kotlin)
            assertEquals("$name only the clean alternate contributes", 0.5f, kotlin.meanContribution, 0f)
            assertNativeParity("$name empirical noise", kotlin, nativeAccumulate(profile, reference, alts))
        }
    }
}
