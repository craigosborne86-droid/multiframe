package dev.multiframe.camera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.ref.Reference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI memory/shape boundary checks. Unsafe short-array cases run only after native guards exist. */
class NativeSubPixelInputValidationTest {
    private val sensorW = 64
    private val sensorH = 160
    private val rowBytes = sensorW * 2
    // Independent native grid oracle: a 32-pixel proxy tile spans 64 sensor pixels.
    private val tilesX = maxOf(1, sensorW / 64)
    private val tilesY = maxOf(1, sensorH / 64)
    private val tileCount = tilesX * tilesY
    private val profile = SensorProfile(intArrayOf(48, 80, 112, 144), 1023, intArrayOf(1, 2, 0, 1))

    private fun canvas(): NativeSubPixel {
        assumeTrue("requires actual host JNI library", NativeMerge.isAvailable())
        return requireNotNull(NativeSubPixel.create(sensorW, sensorH, profile, hotPixelThreshold = 0f))
    }

    private fun minimumBytes(stride: Int): Int = (sensorH - 1) * stride + rowBytes

    private fun frame(stride: Int = rowBytes, extraBytes: Int = 0): ByteBuffer =
        ByteBuffer.allocateDirect(minimumBytes(stride) + extraBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (y in 0 until sensorH) for (x in 0 until sensorW) {
                val site = (y and 1) * 2 + (x and 1)
                // Spatial detail and unequal CFA pedestals reveal row/phase mistakes.
                val value = profile.blackAt(x, y) + intArrayOf(32, 48, 64)[profile.cfaPattern[site]] +
                    (x / 4 + y / 4) % 16
                putShort(y * stride + x * 2, value.toShort())
            }
        }

    private fun field(tx: Int = tilesX, ty: Int = tilesY, count: Int = tileCount) =
        AlignmentField(tx, ty, IntArray(count), IntArray(count), FloatArray(count), FloatArray(count))

    /** Bypass Kotlin's shape guard so these tests independently exercise the real JNI boundary. */
    private fun addNative(
        canvas: NativeSubPixel, buffer: ByteBuffer, stride: Int,
        dx: IntArray, dy: IntArray, fracDx: FloatArray, fracDy: FloatArray,
    ): Boolean {
        val h = NativeSubPixel::class.java.getDeclaredField("handle").apply { isAccessible = true }
            .getLong(canvas)
        val method = NativeSubPixel::class.java.declaredMethods.single { it.name == "nAddFrame" }
            .apply { isAccessible = true }
        return method.invoke(canvas, h, buffer, stride, dx, dy, fracDx, fracDy, 1f) as Boolean
    }

    private fun nativeAdd(canvas: NativeSubPixel, buffer: ByteBuffer, stride: Int, count: Int = tileCount) =
        addNative(canvas, buffer, stride, IntArray(count), IntArray(count), FloatArray(count), FloatArray(count))

    private fun assertRejected(label: String, calls: List<Pair<String, Boolean>>) {
        println("$label: " + calls.joinToString { "${it.first}=${it.second}" })
        for ((case, accepted) in calls) assertFalse("$label $case must return false", accepted)
    }

    // The first six methods are safe baseline probes: no Finish and no short-vector native copy.
    @Test fun referenceRejectsUndersizedDirectBufferWithoutReadingIt() {
        canvas().use { c ->
            val buffers = listOf(1, rowBytes - 1, minimumBytes(rowBytes) - 1).map(ByteBuffer::allocateDirect)
            val calls = buffers.map { "capacity=${it.capacity()}" to c.setReference(it, rowBytes) }
            Reference.reachabilityFence(buffers)
            assertRejected("reference capacity", calls)
        }
    }

    @Test fun referenceRejectsNonPositiveOrOverlappingRowStrideWithoutReadingIt() {
        canvas().use { c ->
            val buffer = frame()
            val calls = listOf(-1, 0, rowBytes - 1).map { "stride=$it" to c.setReference(buffer, it) }
            Reference.reachabilityFence(buffer)
            assertRejected("reference stride", calls)
        }
    }

    @Test fun alternateRejectsUndersizedDirectBufferWithoutReadingIt() {
        canvas().use { c ->
            val buffers = listOf(1, minimumBytes(rowBytes) - 1).map(ByteBuffer::allocateDirect)
            val calls = buffers.map { "capacity=${it.capacity()}" to nativeAdd(c, it, rowBytes) }
            Reference.reachabilityFence(buffers)
            assertRejected("alternate capacity", calls)
        }
    }

    @Test fun alternateRejectsNonPositiveOrOverlappingRowStrideWithoutReadingIt() {
        canvas().use { c ->
            val buffer = frame()
            val calls = listOf(-1, 0, rowBytes - 1, Int.MAX_VALUE).map { "stride=$it" to nativeAdd(c, buffer, it) }
            Reference.reachabilityFence(buffer)
            assertRejected("alternate stride/capacity", calls)
        }
    }

    @Test fun nativeRejectsEquallyOversizedArraysInsteadOfSilentlyTruncatingThem() {
        canvas().use { c ->
            val buffer = frame()
            val accepted = nativeAdd(c, buffer, rowBytes, tileCount + 1)
            Reference.reachabilityFence(buffer)
            assertRejected("oversized arrays", listOf("expected=$tileCount actual=${tileCount + 1}" to accepted))
        }
    }

    @Test fun wrapperRejectsTransposedGridWithTheSameArrayCount() {
        canvas().use { c ->
            val buffer = frame()
            val accepted = c.addFrame(buffer, rowBytes, field(tilesY, tilesX))
            Reference.reachabilityFence(buffer)
            assertRejected("grid shape", listOf("expected=${tilesX}x$tilesY actual=${tilesY}x$tilesX" to accepted))
        }
    }

    @Test fun nativeRejectsEveryMismatchedArrayBeforeCopying() {
        canvas().use { c ->
            val buffer = frame()
            val calls = listOf(
                "short dy" to addNative(c, buffer, rowBytes, IntArray(tileCount), IntArray(tileCount - 1), FloatArray(tileCount), FloatArray(tileCount)),
                "short fracDx" to addNative(c, buffer, rowBytes, IntArray(tileCount), IntArray(tileCount), FloatArray(tileCount - 1), FloatArray(tileCount)),
                "short fracDy" to addNative(c, buffer, rowBytes, IntArray(tileCount), IntArray(tileCount), FloatArray(tileCount), FloatArray(tileCount - 1)),
                "long dy" to addNative(c, buffer, rowBytes, IntArray(tileCount), IntArray(tileCount + 1), FloatArray(tileCount), FloatArray(tileCount)),
                "long fracDx" to addNative(c, buffer, rowBytes, IntArray(tileCount), IntArray(tileCount), FloatArray(tileCount + 1), FloatArray(tileCount)),
                "long fracDy" to addNative(c, buffer, rowBytes, IntArray(tileCount), IntArray(tileCount), FloatArray(tileCount), FloatArray(tileCount + 1)),
            )
            Reference.reachabilityFence(buffer)
            assertRejected("mismatched arrays", calls)
        }
    }

    // Do not run this on the baseline: AddFrame copies tileCount items from shorter vectors.
    @Test fun nativeRejectsEquallyShortAndEmptyArraysBeforeCopying() {
        canvas().use { c ->
            val buffer = frame()
            val calls = listOf(0, tileCount - 1).map { "count=$it" to nativeAdd(c, buffer, rowBytes, it) }
            Reference.reachabilityFence(buffer)
            assertRejected("equally short arrays", calls)
        }
    }

    private data class Output(
        val rgb: ShortArray, val holes: Float, val interiorHoles: Float,
        val channelHoles: FloatArray, val contribution: Float, val count: Int,
    )

    private fun output(stride: Int = rowBytes, extraBytes: Int = 0, rejectInvalid: Boolean = false): Output =
        canvas().use { c ->
            val buffer = frame(stride, extraBytes)
            assertTrue(c.setReference(buffer, stride))
            assertTrue(c.addFrame(buffer, stride, field()))
            val badBuffer = ByteBuffer.allocateDirect(1)
            if (rejectInvalid) {
                assertFalse(c.setReference(badBuffer, rowBytes))
                assertFalse(c.setReference(buffer, rowBytes - 1))
                assertFalse(nativeAdd(c, badBuffer, rowBytes))
                assertFalse(nativeAdd(c, buffer, rowBytes - 1))
                assertFalse(nativeAdd(c, buffer, stride, tileCount - 1))
                assertFalse(c.addFrame(buffer, stride, field(tilesY, tilesX)))
            }
            repeat(14) { assertTrue(c.addFrame(buffer, stride, field())) }
            val result = requireNotNull(c.finish())
            val samples = ShortArray(result.width * result.height * 3)
            result.rgb16.asShortBuffer().get(samples)
            Reference.reachabilityFence(buffer)
            Reference.reachabilityFence(badBuffer)
            Output(samples, result.holeFraction, result.interiorHoleFraction,
                result.interiorHolesPerChannel.copyOf(), result.meanContribution, result.framesMerged)
        }

    private fun assertSame(expected: Output, actual: Output) {
        assertArrayEquals("RGB16 canvas", expected.rgb, actual.rgb)
        assertEquals("hole fraction", expected.holes, actual.holes, 0f)
        assertEquals("interior hole fraction", expected.interiorHoles, actual.interiorHoles, 0f)
        assertArrayEquals("per-channel holes", expected.channelHoles, actual.channelHoles, 0f)
        assertEquals("mean contribution", expected.contribution, actual.contribution, 0f)
        assertEquals("frame count", expected.count, actual.count)
    }

    @Test fun validPackedAndPaddedFramesAreAnExactNull() {
        val expected = output()
        assertEquals(16, expected.count)
        assertEquals(1f, expected.contribution, 0f)
        // Odd padding is valid: native RAW16 reads bytes, without alignment assumptions.
        // The last row contains exactly rowBytes bytes and has no trailing padding.
        assertSame(expected, output(rowBytes + 1))
        assertSame(expected, output(rowBytes + 16, extraBytes = 32))
    }

    // Candidate-only: the baseline would later read the deliberately invalid buffers/fields.
    @Test fun rejectedSubmissionsLeaveTheValidCanvasAndFrameCountUnchanged() {
        assertSame(output(), output(rejectInvalid = true))
    }
}
