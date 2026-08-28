package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "DevelopSpeed"

/**
 * What the render costs, at the size that actually ships.
 *
 * The same argument as [SharpenSpeedDeviceTest]: measured through a capture,
 * this figure shares the phone with the camera, the merge, a DNG writer thread
 * and a MediaStore publish, and swings by half between neighbouring shots. That
 * is wide enough to hide anything but a very large change, and the render is
 * now the largest item left in a capture -- 199 ms of it, against 55-65 for
 * sharpening and 140 for the encode and publish.
 *
 * Sharpening is turned off, so what is timed is the auto-exposure pass, the
 * bitmap and the render: black level, hot pixels, shading, demosaic and tone.
 * `MultiframeNative` logs the split between those.
 *
 * ### Read this before quoting a number from it
 *
 * **It reads about twice what a capture pays, and nobody knows why.** A capture
 * logs black 15 ms, hot pixels 14, shading 38, demosaic and tone 130; this logs
 * roughly 55, 49, 82 and 200 for the same work on the same image size. The gap
 * is on every stage, including the two that do identical work whatever the
 * picture contains, so it is not the scene.
 *
 * Three explanations were tried and none of them held:
 *
 *  * **Clock ramp.** Running the rounds back to back instead of with a gap
 *    moved the median from 433 ms to 364, so idle clocks are part of it -- but
 *    only part, and back-to-back rounds then heat the phone and drift upward
 *    within a single run, from 227 ms to 380 over ten. The gap is kept because
 *    it is what makes the instrument repeatable.
 *  * **Exposure.** The first version fixed the gain at 2.6, which on this scene
 *    put nearly every pixel over the tone curve's knee and into its exponential
 *    shoulder. Letting auto-exposure choose showed the truth: it picks 0.62,
 *    a factor of four lower, and the demosaic-and-tone figure did not move.
 *  * **Scheduling.** A capture holds a camera session and this does not, so the
 *    obvious guess was that a compute-only test is treated as background work.
 *    Running it with the app's own Activity in the foreground made it slower,
 *    not faster, because the preview then competes for the same cores.
 *
 * So this is a **comparison instrument, not a source of absolute figures**, and
 * a blunter one than it first appeared. Three consecutive runs of one binary
 * gave medians of 406, 414 and 415 ms, which looked like two per cent -- but
 * those three runs shared one install, and an A/B comparison cannot. Reinstall
 * between runs and the same binary measured against itself gives per-round
 * medians of 188, 193, 232 and 486 against 201, 191, 478 and 222. **It
 * manufactures differences of two and a half times out of nothing.**
 *
 * What follows from that:
 *
 *  * **Run an A/A before believing an A/B.** Install the same APK under two
 *    names and compare them. If the instrument separates those, it will
 *    separate anything.
 *  * **Alternate the order**, not only the builds. A fixed order -- always
 *    installing A before B within a round -- produced a clean-looking 79 ms
 *    difference that vanished when the same change was built and measured
 *    directly.
 *  * **Trust separation, not medians.** The one result that survived everything
 *    here was total separation: forty samples of each build with no overlap at
 *    all, reproduced under balanced ordering.
 *
 * The capture path remains the authority on what a capture actually costs, and
 * any figure quoted as what a photograph pays should come from there.
 */
@RunWith(AndroidJUnit4::class)
class DevelopSpeedDeviceTest {

    private val width = 4080
    private val height = 3072
    private val profile = SensorProfile.DEFAULT

    /**
     * A falloff map the shape the camera reports.
     *
     * Without one the shading pass takes its own shortcut -- a null map means
     * every gain is 1 and the interpolation never runs -- and the stage reads
     * a third of what a capture pays. The first version of this harness had no
     * map and reported 11-34 ms against the 38-45 ms a real capture logs, which
     * is the harness measuring a branch no capture takes.
     */
    private fun shading(): ShadingMap {
        val columns = 17
        val rows = 13
        val gains = FloatArray(columns * rows * 4)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                // Roughly 1.8 stops in the corners, as this phone's 24mm gives.
                val dx = (c - (columns - 1) / 2f) / ((columns - 1) / 2f)
                val dy = (r - (rows - 1) / 2f) / ((rows - 1) / 2f)
                val falloff = 1f + 2.5f * (dx * dx + dy * dy) / 2f
                for (channel in 0 until 4) {
                    // The colour channels fall off at slightly different rates,
                    // which is why the map has four of them at all.
                    gains[(r * columns + c) * 4 + channel] =
                        falloff * (1f + 0.04f * (channel - 1.5f))
                }
            }
        }
        return ShadingMap(columns, rows, gains)
    }

    /** A Bayer mosaic with edges, colour, highlights and a little noise. */
    private fun merged(w: Int, h: Int): ByteBuffer {
        val rnd = Random(5)
        val data = ShortArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val colour = profile.cfaPattern[(y and 1) * 2 + (x and 1)]
                val fine = 40 * sin(x * 0.09) * sin(y * 0.07)
                val mid = 150 * sin(x * 0.0011 + y * 0.0004) + 90 * cos(y * 0.0006)
                // A bright quarter, so the shoulder and the desaturation both run.
                val sky = if (y < h / 4) 520.0 else 0.0
                val tint = when (colour) {
                    0 -> 1.15f
                    2 -> 0.80f
                    else -> 1.0f
                }
                val v = (330 + mid + fine + sky) * tint + rnd.nextInt(-6, 6)
                data[y * w + x] = v.toInt().coerceIn(64, 1023).toShort()
            }
        }
        val buffer = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(data)
        buffer.rewind()
        return buffer
    }

    @Test
    fun developAtCaptureSize() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val buffer = merged(width, height)
        val colour = ColorProfile(
            gains = floatArrayOf(1.9f, 1f, 1f, 1.6f),
            matrix = floatArrayOf(1.7f, -0.6f, -0.1f, -0.2f, 1.5f, -0.3f, 0f, -0.4f, 1.4f),
        )
        // Exposure is measured from the frame rather than fixed, which costs
        // the auto-exposure pass -- a few milliseconds of this -- and is worth
        // it. The tone curve only evaluates its exponential shoulder above the
        // knee, and only desaturates above the desaturation start, so how much
        // of the render's work runs depends entirely on how the image is
        // exposed. A hand-picked gain got that wrong in the obvious direction:
        // 2.6 on this scene put nearly every pixel over the knee and reported
        // a demosaic-and-tone figure half again what a capture pays, because a
        // capture exposes for the ninety-second percentile and most of a real
        // photograph sits below the knee. Sharpening stays off; it has a
        // harness of its own.
        val params = DevelopParams(sharpen = Sharpen.Params(amount = 0f))

        val map = shading()

        val merger = NativeMerge.create(width, height, profile)
        assertThat(merger).isNotNull()
        val times = LongArray(ROUNDS)
        merger!!.use { m ->
            // The first develop faults in a hundred megabytes of scratch, which
            // a capture pays once a boot rather than once a shot.
            m.develop(buffer, colour, params, shading = map)!!.recycle()
            for (i in 0 until ROUNDS) {
                // A develop is three seconds of eight-core float work if the
                // rounds run back to back, and the phone clocks down inside
                // that: the first version of this ran them with no gap and
                // climbed from 227 ms to 380 across ten rounds, which is the
                // harness measuring its own duty cycle. A capture spends about
                // three times as long not developing as developing, so the gap
                // is what makes these rounds resemble shots.
                Thread.sleep(GAP_MS)
                val started = System.nanoTime()
                val bitmap = m.develop(buffer, colour, params, shading = map)
                times[i] = (System.nanoTime() - started) / 1_000_000
                assertThat(bitmap).isNotNull()
                bitmap!!.recycle()
            }
        }

        times.sort()
        Log.i(
            TAG,
            "develop at ${width}x$height: median ${times[ROUNDS / 2]}ms, " +
                "${times.first()}-${times.last()}ms over $ROUNDS rounds",
        )
        DeviceKind.warnIfNotAPhone(TAG)

        // Not a regression threshold on the number, which would fail on a warm
        // phone for reasons that have nothing to do with the code.
        assertThat(times[ROUNDS / 2]).isGreaterThan(0)
        assertThat(times[ROUNDS / 2]).isLessThan(2_000)
    }

    private companion object {
        const val ROUNDS = 9
        const val GAP_MS = 600L
    }
}
