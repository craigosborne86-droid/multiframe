package dev.multiframe.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.round

/** Short profiles must mean the same thing as explicitly repeating blackAt over four CFA sites. */
@RunWith(Parameterized::class)
class ShortBlackLevelParityTest(
    private val layout: String,
    private val cfa: IntArray,
    private val blackKind: String,
    private val black: IntArray,
    private val gain: Float,
) {
    private val width = 64
    private val height = 64
    private val white = 1023
    private val radiance = intArrayOf(32, 48, 64)
    private val profile get() = SensorProfile(black.copyOf(), white, cfa.copyOf())

    /** Canonical API oracle. No zero-padding or native wrapper is used to form it. */
    private fun expanded(p: SensorProfile) = SensorProfile(
        IntArray(4) { site -> p.blackAt(site and 1, site shr 1) }, white, cfa.copyOf(),
    )

    /** The sensor pedestal remains fixed; only light above it changes with exposure. */
    private fun frame(p: SensorProfile, exposure: Float, light: IntArray = radiance) =
        BayerFrame(width, height, ShortArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val code = p.blackAt(x, y) + exposure * light[p.cfaPattern[(y and 1) * 2 + (x and 1)]]
            require(code >= 0f && code < white && code == code.toInt().toFloat())
            code.toInt().toShort()
        })

    private fun buffer(f: BayerFrame) = ByteBuffer.allocateDirect(f.data.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(f.data) }

    private fun field(sx: Float = 0f, sy: Float = 0f): AlignmentField {
        val tilesX = maxOf(1, (width / 2) / SubPixelAccumulator.TILE_TARGET)
        val tilesY = maxOf(1, (height / 2) / SubPixelAccumulator.TILE_TARGET)
        val n = tilesX * tilesY
        val px = -sx / 2f
        val py = -sy / 2f
        val ix = round(px).toInt()
        val iy = round(py).toInt()
        return AlignmentField(tilesX, tilesY, IntArray(n) { ix }, IntArray(n) { iy },
            FloatArray(n) { px - ix }, FloatArray(n) { py - iy })
    }

    private fun kotlinGuide(p: SensorProfile, f: BayerFrame): Plane {
        val a = BayerAccumulator(width, height, p)
        try {
            val method = BayerAccumulator::class.java.getDeclaredMethod(
                "lumaProxy", BayerFrame::class.java, Float::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            return method.invoke(a, f, gain) as Plane
        } finally { a.release() }
    }

    private fun assertGuide(label: String, p: SensorProfile, light: IntArray, actual: Plane) {
        val span = white - expanded(p).blackLevel.minOrNull()!!
        // Independent CFA-cell light oracle: one red, two greens, one blue.
        val expected = (255.0 * (light[0] + 2 * light[1] + light[2]) / (4 * span)).toInt().toByte()
        assertEquals("$label width", width / 2, actual.width)
        assertEquals("$label height", height / 2, actual.height)
        assertArrayEquals(label, ByteArray(width / 2 * (height / 2)) { expected }, actual.data)
    }

    @Test fun kotlinGuideMatchesTheFourSiteOracleIncludingDarkness() {
        val p = profile
        for (light in listOf(intArrayOf(0, 0, 0), radiance)) {
            val f = frame(p, gain, light)
            val actual = kotlinGuide(p, f)
            assertGuide("Kotlin $blackKind", p, light, actual)
            assertArrayEquals(kotlinGuide(expanded(p), f).data, actual.data)
        }
    }

    @Test fun nativeGuideMatchesTheFourSiteOracleIncludingDarkness() {
        assumeTrue("opt-in host JNI / physical native library", NativeMerge.isAvailable())
        val p = profile
        requireNotNull(NativeMerge.create(width, height, p)).use { native ->
            for (light in listOf(intArrayOf(0, 0, 0), radiance)) {
                val f = frame(p, gain, light)
                val b = buffer(f)
                val actual = native.lumaProxy(b, width * 2, gain)
                Reference.reachabilityFence(b)
                assertGuide("native $blackKind", p, light, actual)
                assertArrayEquals(kotlinGuide(expanded(p), f).data, actual.data)
            }
        }
    }

    private fun kotlinBayer(p: SensorProfile): Pair<BayerFrame, BayerMergeStats> {
        val a = BayerAccumulator(width, height, p)
        try {
            a.setReference(frame(p, 1f))
            a.add(frame(p, gain), field(), gain)
            return a.finish()
        } finally { a.release() }
    }

    private fun assertBayer(label: String, expected: Pair<BayerFrame, BayerMergeStats>,
                            actual: Pair<BayerFrame, BayerMergeStats>) {
        assertArrayEquals("$label RAW", expected.first.data, actual.first.data)
        // A rejected alternate can leave the reference pixel-identical; require its contribution too.
        assertEquals("$label accepted photons", 1f, actual.second.meanContribution, 1e-6f)
        assertEquals("$label oracle contribution", expected.second.meanContribution, actual.second.meanContribution, 1e-6f)
        assertEquals("$label frames", 2, actual.second.framesMerged)
    }

    @Test fun kotlinBayerGainNormalizationMatchesTheExpandedProfile() {
        val p = profile
        val expected = kotlinBayer(expanded(p))
        assertArrayEquals("expanded physical null", frame(p, 1f).data, expected.first.data)
        assertBayer("Kotlin $blackKind", expected, kotlinBayer(p))
    }

    @Test fun nativeBayerGainNormalizationMatchesTheExpandedProfile() {
        assumeTrue("opt-in host JNI / physical native library", NativeMerge.isAvailable())
        val p = profile
        val expected = kotlinBayer(expanded(p))
        val ref = buffer(frame(p, 1f))
        val alt = buffer(frame(p, gain))
        requireNotNull(NativeMerge.create(width, height, p)).use { native ->
            native.setReference(ref, width * 2)
            native.addFrame(alt, width * 2, field(), gain)
            val (raw, stats) = native.finish()
            val data = ShortArray(width * height)
            raw.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
            Reference.reachabilityFence(ref)
            Reference.reachabilityFence(alt)
            assertBayer("native $blackKind", expected, BayerFrame(width, height, data) to stats)
        }
    }

    private fun kotlinSr(p: SensorProfile): SubPixelResult {
        val a = SubPixelAccumulator(width, height, p, hotPixelThreshold = 0f)
        a.setReference(frame(p, 1f))
        val alt = frame(p, gain)
        for (sy in listOf(0f, .5f, 1f, 1.5f)) for (sx in listOf(0f, .5f, 1f, 1.5f)) {
            if (sx != 0f || sy != 0f) a.add(alt, field(sx, sy), gain)
        }
        return a.finish()
    }

    private fun assertSr(label: String, expected: SubPixelResult, actual: SubPixelResult, tolerance: Float) {
        assertEquals("$label dimensions", expected.width * expected.height * 3, actual.rgb.size)
        var maxError = 0f
        var first = -1
        for (i in actual.rgb.indices) {
            val error = abs(expected.rgb[i] - actual.rgb[i])
            if (error > maxError) { maxError = error; first = i }
        }
        assertTrue("$label RGB maximum error=$maxError at $first", maxError <= tolerance)
        assertEquals("$label accepted photons", 1f, actual.meanContribution, 1e-6f)
        assertEquals("$label frames", 16, actual.framesMerged)
        assertEquals("$label holes", expected.holeFraction, actual.holeFraction, 1e-6f)
        assertEquals("$label interior holes", expected.interiorHoleFraction, actual.interiorHoleFraction, 1e-6f)
        assertArrayEquals("$label per-channel holes", expected.interiorHolesPerChannel, actual.interiorHolesPerChannel, 1e-6f)
    }

    @Test fun kotlinSrPedestalMatchesTheExpandedProfile() {
        val p = profile
        assertSr("Kotlin $blackKind", kotlinSr(expanded(p)), kotlinSr(p), 0f)
    }

    @Test fun nativeSrPedestalMatchesTheExpandedProfile() {
        assumeTrue("opt-in host JNI / physical native library", NativeMerge.isAvailable())
        val p = profile
        val expected = kotlinSr(expanded(p))
        val ref = buffer(frame(p, 1f))
        val alt = buffer(frame(p, gain))
        requireNotNull(NativeSubPixel.create(width, height, p, hotPixelThreshold = 0f)).use { native ->
            assertTrue(native.setReference(ref, width * 2))
            for (sy in listOf(0f, .5f, 1f, 1.5f)) for (sx in listOf(0f, .5f, 1f, 1.5f)) {
                if (sx != 0f || sy != 0f) assertTrue(native.addFrame(alt, width * 2, field(sx, sy), gain))
            }
            val result = requireNotNull(native.finish())
            val raw = ShortArray(result.width * result.height * 3)
            result.rgb16.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw)
            Reference.reachabilityFence(ref)
            Reference.reachabilityFence(alt)
            val actual = SubPixelResult(result.width, result.height,
                FloatArray(raw.size) { (raw[it].toInt() and 0xffff).toFloat() }, result.holeFraction,
                result.interiorHoleFraction, result.meanContribution, result.framesMerged, result.interiorHolesPerChannel)
            // Native resolves to integer sensor DN; Kotlin keeps fractional green averages.
            assertSr("native $blackKind", expected, actual, 1f)
        }
    }

    @Test fun linearCanvasBlackLevelsMatchExpandedProfile() {
        val p = profile
        assertArrayEquals("$layout $blackKind linear canvas black", LinearDeveloper.channelBlack(expanded(p)),
            LinearDeveloper.channelBlack(p), 0f)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} black={2} gain={4}")
        fun cases(): List<Array<Any>> = listOf(
            "RGGB" to intArrayOf(0, 1, 1, 2), "GRBG" to intArrayOf(1, 0, 2, 1),
            "GBRG" to intArrayOf(1, 2, 0, 1), "BGGR" to intArrayOf(2, 1, 1, 0),
        ).flatMap { (name, cfa) ->
            listOf("empty" to intArrayOf(), "single" to intArrayOf(64), "two" to intArrayOf(48, 96)).flatMap { (kind, black) ->
                listOf(.5f, 1f, 2f).map { gain -> arrayOf<Any>(name, cfa, kind, black, gain) }
            }
        }
    }
}
