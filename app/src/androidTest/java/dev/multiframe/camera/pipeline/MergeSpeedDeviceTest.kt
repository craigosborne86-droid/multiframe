package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

private const val TAG = "MergeSpeed"

/**
 * What the accumulation costs over a burst, at the size that actually ships.
 *
 * The accumulate figure in this project's log has so far come from pressing the
 * shutter and reading the merge breakdown out of logcat, which gives three
 * samples if the tester is patient. This runs the same loop on synthetic frames
 * at 4080x3072 over the default eight-frame burst, so a change to it can be
 * measured repeatedly under conditions that hold still. The number it reports
 * is the same quantity `ZslCapture` logs as `accumulate`, and comparable with
 * the figures already in the log.
 *
 * **Three things it is careful about.**
 *
 * It does the proxy and the alignment between accumulations even though it does
 * not time them. An earlier version left them out and reported sixty
 * milliseconds a frame where a capture logs under twenty -- accumulations run
 * back to back leave the memory system saturated in a way the real loop, which
 * has twenty milliseconds of alignment between one frame and the next, never
 * sees. A benchmark that removes the gaps is not measuring the same loop.
 *
 * A phone does not hold still long enough to compare two runs directly -- these
 * samples have varied by a factor of two on a device doing nothing else, as it
 * warms, as the scheduler moves work between fast and slow cores, and as memory
 * fills up over an afternoon of measuring. So an unchanged pass is timed
 * against every burst. It is a check on whether the machine itself moved, not a
 * divisor: at twelve samples of a ten-millisecond sweep its own noise is larger
 * than the drift it is there to reveal. Two builds are compared by alternating
 * them several times, and by reading the run medians rather than any one
 * sample.
 *
 * And every measurement here is a claim about a phone. On the emulator the
 * numbers are fiction, which is why the assertion is only a ceiling loose
 * enough to catch a collapse rather than a budget -- the evidence is in the
 * logged samples, read on hardware.
 */
@RunWith(AndroidJUnit4::class)
class MergeSpeedDeviceTest {

    private val width = 4080
    private val height = 3072
    private val profile = SensorProfile.DEFAULT

    /**
     * A frame written straight into native memory.
     *
     * Twelve and a half million shorts is 25 MB, and building it as a Kotlin
     * array first would put two of those on a 256 MB heap for no reason. Rows
     * are assembled one at a time and pushed through.
     */
    private fun frameBuffer(seed: Int, shiftX: Int, shiftY: Int): ByteBuffer {
        val rnd = Random(seed)
        val buffer = ByteBuffer
            .allocateDirect(width * height * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buffer.asShortBuffer()
        val row = ShortArray(width)
        for (y in 0 until height) {
            val sy = y + shiftY
            for (x in 0 until width) {
                val sx = x + shiftX
                val colour = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                val base = 400 + 300 * kotlin.math.sin(sx * 0.004) * kotlin.math.cos(sy * 0.006)
                val tint = when (colour) {
                    0 -> 1.15f
                    2 -> 0.80f
                    else -> 1.0f
                }
                row[x] = (base * tint + rnd.nextInt(-30, 30)).toInt().coerceIn(64, 1023).toShort()
            }
            shorts.put(row)
        }
        buffer.rewind()
        return buffer
    }

    @Test
    fun accumulateCostOverABurst() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val stride = width * 2
        val reference = frameBuffer(seed = 3, shiftX = 0, shiftY = 0)
        val alternate = frameBuffer(seed = 9, shiftX = 4, shiftY = 2)

        val merge = NativeMerge.create(width, height, profile)
        assertThat(merge).isNotNull()
        merge!!.use { m ->
            val tilesX = maxOf(1, m.proxyWidth / 32)
            val tilesY = maxOf(1, m.proxyHeight / 32)

            val accumulateMillis = LongArray(BURSTS)
            val controlMillis = LongArray(BURSTS)
            for (b in 0 until BURSTS) {
                // Timed as the control: unchanged native code sweeping the same
                // buffers, 25 MB in and 125 MB out through the same band
                // scheduler. If the phone throttles or another process takes a
                // core this moves with the burst that follows it, and a change
                // in accumulate that this does not share is a change in
                // accumulate.
                var mark = System.nanoTime()
                m.setReference(reference, stride)
                controlMillis[b] = (System.nanoTime() - mark) / 1_000_000

                val refProxy = m.lumaProxy(reference, stride)
                val refPyramid = Aligner.buildPyramid(refProxy)
                var accumulate = 0L
                for (frame in 1 until BURST_FRAMES) {
                    // Untimed, and deliberately not skipped. The proxy and the
                    // alignment are what a capture does between one
                    // accumulation and the next, and leaving them out is what
                    // made an earlier version of this test report sixty
                    // milliseconds a frame where a capture logs under twenty:
                    // back-to-back accumulations leave the memory system
                    // saturated in a way the real loop never sees.
                    //
                    // The alignment here is the Kotlin one, where a capture
                    // takes the native path. That makes this harness read high
                    // -- the Kotlin aligner builds a pyramid per frame, some
                    // 44 MB of garbage a burst, and collecting it runs on the
                    // same cores as the accumulation being timed. Swapping it
                    // is the obvious next improvement, and has to be done on a
                    // phone that is not already short of memory, or the
                    // measurement it is meant to sharpen is swamped.
                    val proxy = m.lumaProxy(alternate, stride)
                    val field = Aligner.align(
                        refPyramid, Aligner.buildPyramid(proxy), tilesX, tilesY,
                    )

                    mark = System.nanoTime()
                    m.addFrame(alternate, stride, field)
                    accumulate += System.nanoTime() - mark
                }
                accumulateMillis[b] = accumulate / 1_000_000
            }

            val a = accumulateMillis.sorted()
            val c = controlMillis.sorted()
            Log.i(
                TAG,
                "accumulate over ${BURST_FRAMES - 1} frames of ${width}x$height, " +
                    "$BURSTS bursts: " + accumulateMillis.joinToString(", ") { "${it}ms" } +
                    "; min ${a.first()}ms median ${a[BURSTS / 2]}ms max ${a.last()}ms",
            )
            Log.i(
                TAG,
                "control setReference: " + controlMillis.joinToString(", ") { "${it}ms" } +
                    "; min ${c.first()}ms median ${c[BURSTS / 2]}ms max ${c.last()}ms",
            )

            DeviceKind.warnIfNotAPhone(TAG)

            val contribution = m.finish().second.meanContribution
            Log.i(TAG, "mean contribution %.4f".format(contribution))
            // The control resets the accumulation before every burst, so the
            // frame count is not a running total here. What is worth checking
            // is that the loop did the work at all: the mean contribution is
            // the weight it assigned per pixel, above zero because pixels
            // contributed and below one because some were rejected.
            assertThat(contribution).isGreaterThan(0f)
            assertThat(contribution).isLessThan(1f)
            // Not a budget, and deliberately loose enough that no machine
            // fails it: what this catches is the loop having stopped being a
            // loop over pixels at all.
            //
            // It used to say an emulator takes far longer. Measured, it does
            // not -- an arm64 image on Apple silicon has the host's cores and
            // memory bandwidth, and runs some of these passes ten times faster
            // than the phone. That is why the rule is that no timing comes from
            // it, and why the warning above is in the log beside the figure.
            assertThat(a.first()).isGreaterThan(0)
            assertThat(a.first()).isLessThan(30_000)
        }
    }

    companion object {
        /**
         * Bursts to time.
         *
         * Enough that one stray scheduling decision does not decide it: on a
         * phone doing nothing else these samples span a wide range, and a
         * handful of them still moved the median by a quarter between two runs
         * of the identical binary -- coarser than any change worth making to
         * this loop.
         */
        const val BURSTS = 12

        /** What the app defaults to, so the figure is the one a shot pays. */
        const val BURST_FRAMES = 8
    }
}
