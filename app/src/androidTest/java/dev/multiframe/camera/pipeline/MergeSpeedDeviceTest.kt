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
 * not time them, and it does them *the way a capture does* -- natively. An
 * earlier version left the gap out entirely and reported sixty milliseconds a
 * frame where a capture logged under twenty: accumulations run back to back
 * leave the memory system saturated in a way the real loop never sees.
 *
 * A later one put the gap back but filled it with the Kotlin aligner, and this
 * project carried a note for several sessions saying that made the harness read
 * high -- a pyramid per frame is some 44 MB of garbage a burst, collected on
 * the cores being timed. **Measured, it does not.** The harness now runs both
 * gaps, alternating, and the Kotlin one reads 0.97x and 1.03x on two runs, 7 of
 * 16 pairs, which is a coin flip in both directions. The native gap is a tenth
 * the length of the Kotlin one and the accumulation cannot tell them apart, so
 * whatever removing the gap entirely was doing, thirty milliseconds of it is
 * already enough.
 *
 * The gap is native now because a harness should do what a capture does, not
 * because it was reading high. The paired form is there because a phone cannot
 * be trusted to hold still between one burst and the next; see
 * [ShadingSpeedDeviceTest].
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
 *
 * **What it reports is what a capture pays.** Read against a capture on the
 * same phone minutes apart: 39 ms per accumulated frame here, 37 ms there. The
 * belief that this harness read high came from setting its figure beside a
 * capture figure recorded on another day, which is the one comparison this
 * project's own rules forbid.
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

            val nativeGap = LongArray(PAIRS)
            val jvmGap = LongArray(PAIRS)
            val controlMillis = LongArray(BURSTS)
            for (b in 0 until BURSTS) {
                val pair = b / 2
                // Which aligner fills the gap, alternating within the pair and
                // alternating the order of the pair, so a phone that warms over
                // the measurement warms for both of them equally.
                val useNative = if (pair % 2 == 0) b % 2 == 0 else b % 2 == 1

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
                val refPyramid = if (useNative) null else Aligner.buildPyramid(refProxy)
                var accumulate = 0L
                for (frame in 1 until BURST_FRAMES) {
                    // Untimed, and deliberately not skipped. The proxy and the
                    // alignment are what a capture does between one
                    // accumulation and the next, and leaving them out is what
                    // made an earlier version of this test report sixty
                    // milliseconds a frame where a capture logs under twenty:
                    // back-to-back accumulations leave the memory system
                    // saturated in a way the real loop never sees.
                    val proxy = m.lumaProxy(alternate, stride)
                    val field = if (useNative) {
                        Aligner.alignNative(refProxy, proxy, tilesX, tilesY)!!
                    } else {
                        Aligner.align(
                            refPyramid!!, Aligner.buildPyramid(proxy), tilesX, tilesY,
                        )
                    }

                    mark = System.nanoTime()
                    m.addFrame(alternate, stride, field)
                    accumulate += System.nanoTime() - mark
                }
                if (useNative) {
                    nativeGap[pair] = accumulate / 1_000_000
                } else {
                    jvmGap[pair] = accumulate / 1_000_000
                }
            }

            val a = nativeGap.sorted()
            val j = jvmGap.sorted()
            val c = controlMillis.sorted()
            val wins = (0 until PAIRS).count { nativeGap[it] < jvmGap[it] }
            val ratios = (0 until PAIRS).map { jvmGap[it].toDouble() / nativeGap[it] }.sorted()
            Log.i(
                TAG,
                "accumulate over ${BURST_FRAMES - 1} frames of ${width}x$height, " +
                    "$PAIRS bursts each. gap aligned natively, as a capture does: " +
                    nativeGap.joinToString(", ") { "${it}ms" } +
                    "; min ${a.first()}ms median ${a[PAIRS / 2]}ms max ${a.last()}ms",
            )
            Log.i(
                TAG,
                "the same, with the gap aligned on the JVM as this harness used to: " +
                    jvmGap.joinToString(", ") { "${it}ms" } +
                    "; min ${j.first()}ms median ${j[PAIRS / 2]}ms max ${j.last()}ms",
            )
            Log.i(
                TAG,
                "the JVM gap was reading %.2fx, and did so in %d of %d pairs".format(
                    ratios[ratios.size / 2], wins, PAIRS,
                ),
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
            // Asserted on the natively aligned bursts, because those are the
            // ones that describe a capture. Not a budget, and deliberately
            // loose enough that no machine fails it: what this catches is the
            // loop having stopped being a loop over pixels at all.
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
        const val BURSTS = 16

        /** Bursts of each kind of gap. */
        const val PAIRS = BURSTS / 2

        /** What the app defaults to, so the figure is the one a shot pays. */
        const val BURST_FRAMES = 8
    }
}
