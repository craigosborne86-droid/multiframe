package dev.multiframe.camera.pipeline

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "OpticsParity"

/**
 * The native optics pass against the Kotlin reference, and against the clock.
 *
 * The parity half holds Optics.cpp to `Optics.applyReference` within one code
 * value per channel. Not bit equality: the build has -ffast-math, which lets
 * the compiler fuse and reassociate the same expression differently in two
 * surroundings, and a last-place difference on a float that is then rounded to
 * eight bits can land on the far side of a rounding boundary. One code value
 * is the bound that permits and the bound an 8-bit output can show. What it
 * rules out is the thing that would matter: a wrong channel, a swapped byte
 * order, a coordinate off by one -- any of which is a whole-number difference,
 * not a last-place one.
 *
 * The byte order is the likely mistake. An RGBA_8888 bitmap's locked buffer has
 * red in the low byte; getPixels() returns ARGB with red at bit 16. The native
 * pass reads one and the reference reads the other, and if either has them
 * confused the picture comes back with red and blue exchanged -- which this
 * test sees as a difference of up to 255 and the eye sees as a wrong picture.
 *
 * The speed half is what the port was for. The Kotlin pass measured 1.05
 * seconds banded; the target is under 30 ms.
 */
@RunWith(AndroidJUnit4::class)
class OpticsParityTest {

    private val width = 1024
    private val height = 768

    /** A scene with edges at every radius and angle, and colour to get wrong. */
    private fun scene(): IntArray {
        val out = IntArray(width * height)
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = kotlin.math.hypot(x - cx, y - cy)
                val ring = ((r / 24f).toInt()) % 2 == 0
                val base = if (ring) 200 else 60
                // Distinct per channel so a swap cannot hide.
                val rv = (base + x * 40 / width).coerceIn(0, 255)
                val gv = base
                val bv = (base - y * 40 / height).coerceIn(0, 255)
                out[y * width + x] = (0xFF shl 24) or (rv shl 16) or (gv shl 8) or bv
            }
        }
        return out
    }

    private fun model() = LensDistortion.Model(
        fx = 900f, fy = 900f, cx = width / 2f, cy = height / 2f, skew = 0f,
        k1 = -0.08f, k2 = 0.02f, k3 = 0f, p1 = 0.001f, p2 = -0.0005f,
    )

    private val aberration = ChromaticAberration.Scale(1.002f, 0.998f)

    private fun assertWithinOneCode(reference: IntArray, native: IntArray, label: String) {
        var differing = 0
        var worst = 0
        for (i in reference.indices) {
            val a = reference[i]
            val b = native[i]
            for (shift in intArrayOf(0, 8, 16, 24)) {
                val d = kotlin.math.abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
                if (d > 0) differing++
                if (d > worst) worst = d
            }
        }
        Log.i(TAG, "$label: ${reference.size} pixels, $differing channel values differ, worst $worst")
        assertThat(worst).isAtMost(1)
    }

    private fun runBoth(
        model: LensDistortion.Model?,
        scale: ChromaticAberration.Scale,
        label: String,
    ) {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val src = scene()

        val reference = IntArray(width * height)
        Optics.applyReference(src, reference, width, height, model, scale)

        val bitmap = Bitmap.createBitmap(src, width, height, Bitmap.Config.ARGB_8888)
        val corrected = NativeOptics.correct(bitmap, model, scale)
        assertThat(corrected).isNotNull()
        val native = IntArray(width * height)
        corrected!!.getPixels(native, 0, width, 0, 0, width, height)

        assertWithinOneCode(reference, native, label)
        bitmap.recycle()
        corrected.recycle()
    }

    // --- parity ------------------------------------------------------------

    @Test
    fun distortionAloneMatchesTheReference() =
        runBoth(model(), ChromaticAberration.NONE, "distortion only")

    @Test
    fun aberrationAloneMatchesTheReference() =
        runBoth(null, aberration, "aberration only")

    @Test
    fun bothTogetherMatchTheReference() =
        runBoth(model(), aberration, "fused")

    /**
     * The identity is the case where a byte-order mistake is loudest: with no
     * geometry to move anything, the only way the output can differ from the
     * input is by the channels coming back in the wrong places.
     */
    @Test
    fun theIdentityReproducesTheImage() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val src = scene()
        val bitmap = Bitmap.createBitmap(src, width, height, Bitmap.Config.ARGB_8888)
        val out = NativeOptics.correct(bitmap, null, ChromaticAberration.NONE)
        assertThat(out).isNotNull()
        val back = IntArray(width * height)
        out!!.getPixels(back, 0, width, 0, 0, width, height)
        assertThat(back).isEqualTo(src)
        bitmap.recycle()
        out.recycle()
    }

    // --- speed -------------------------------------------------------------

    /**
     * The pass alone. The destination is allocated once, outside the timing:
     * obtaining and zeroing a fresh bitmap is the graphics allocator's cost,
     * paid once per photograph, and folding it in here was making a 30 ms
     * pass look like 500.
     */
    private fun timeNative(bitmap: Bitmap, model: LensDistortion.Model?, scale: ChromaticAberration.Scale): LongArray {
        val dst = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

        // Warm the cores first. The frequency governor starts a burst low and
        // ramps under sustained load: the shading benchmark on this device
        // opens at 30-70 ms and settles to 7-19 ms only in its last dozen
        // rounds, against a documented 5-8 ms. In production this pass runs
        // after three seconds of merge, so the cores are already up. Timing
        // it cold would be measuring the governor, not the pass.
        val warmUntil = System.nanoTime() + WARMUP_NS
        while (System.nanoTime() < warmUntil) {
            NativeOptics.correctInto(bitmap, dst, model, scale)
        }

        val times = LongArray(ROUNDS)
        for (i in 0 until ROUNDS) {
            val t = System.nanoTime()
            assertThat(NativeOptics.correctInto(bitmap, dst, model, scale)).isTrue()
            times[i] = (System.nanoTime() - t) / 1000
        }
        dst.recycle()
        return times
    }

    private companion object {
        const val ROUNDS = 20

        /** Long enough for the governor to commit. */
        const val WARMUP_NS = 1_500_000_000L
    }

    private fun median(v: LongArray) = v.sorted()[v.size / 2]

    private fun describe(v: LongArray) =
        "median %.1fms, range %.1f-%.1fms".format(
            median(v) / 1000.0, v.min() / 1000.0, v.max() / 1000.0,
        )

    /**
     * The harness against itself. Before the native pass can be timed against
     * anything, two runs of it must be indistinguishable -- otherwise the
     * instrument is biased and a speedup it reports means nothing.
     */
    @Test
    fun theHarnessCannotSeparateTheNativePassFromItself() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val bitmap = Bitmap.createBitmap(scene(), width, height, Bitmap.Config.ARGB_8888)
        val dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val m = model()

        val warmUntil = System.nanoTime() + WARMUP_NS
        while (System.nanoTime() < warmUntil) NativeOptics.correctInto(bitmap, dst, m, aberration)

        // Interleaved, not two blocks. Two blocks with a warm-up each put the
        // second one three seconds hotter than the first, and on a phone that
        // is thermally capped that is a systematic loss for the right slot --
        // the null separated on drift alone, 20 wins to 0, with nothing
        // different between the slots. Alternating gives the drift to both.
        val a = LongArray(ROUNDS)
        val b = LongArray(ROUNDS)
        for (i in 0 until ROUNDS) {
            var t = System.nanoTime()
            NativeOptics.correctInto(bitmap, dst, m, aberration)
            a[i] = (System.nanoTime() - t) / 1000
            t = System.nanoTime()
            NativeOptics.correctInto(bitmap, dst, m, aberration)
            b[i] = (System.nanoTime() - t) / 1000
        }
        Log.i(TAG, "A/A left:  ${describe(a)}")
        Log.i(TAG, "A/A right: ${describe(b)}")
        DeviceKind.warnIfNotAPhone(TAG)

        val wins = a.indices.count { b[it] < a[it] }
        assertThat(wins).isAtLeast(ROUNDS * 3 / 10)
        assertThat(wins).isAtMost(ROUNDS * 7 / 10)
        bitmap.recycle()
        dst.recycle()
    }

    /**
     * Where the time goes. Three cases, each isolating one cost:
     *
     *  * identity -- no geometry, no aberration -- is the gather with every
     *    source position equal to its output position. If this is slow, the
     *    cost is memory traffic and threading, not arithmetic.
     *  * distortion only adds the polynomial and moves the source positions,
     *    so adjacent outputs read adjacent-but-shifted sources.
     *  * fused adds two more texel fetches per pixel at slightly different
     *    positions for red and blue.
     *
     * The difference between the lines is what each thing costs.
     */
    @Test
    fun theCostIsAttributed() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val bitmap = Bitmap.createBitmap(scene(), width, height, Bitmap.Config.ARGB_8888)
        val pixels = width.toLong() * height

        fun report(label: String, m: LensDistortion.Model?, s: ChromaticAberration.Scale) {
            val t = timeNative(bitmap, m, s)
            val ns = median(t) * 1000.0 / pixels
            Log.i(TAG, "%-16s %s  (%.1f ns/px)".format(label, describe(t), ns))
        }
        report("identity", null, ChromaticAberration.NONE)
        report("distortion only", model(), ChromaticAberration.NONE)
        report("aberration only", null, aberration)
        report("fused", model(), aberration)
        DeviceKind.warnIfNotAPhone(TAG)
        bitmap.recycle()
    }

    /**
     * Native against the Kotlin reference, paired, in one process.
     *
     * ### Why a ratio and not a time
     *
     * The first version of this asserted an absolute budget -- under 30 ms
     * projected to a full frame -- and it could not be trusted. The phone it
     * ran on had spent hours under the device suite: BIG cores at 78 C, six
     * gigabytes swapped, 59% of CPU time in the kernel at idle, and the
     * hotplug threads busy taking cores offline. The same pass measured 8 ms in
     * one round and 384 ms in another. The shading benchmark, documented at
     * 5-8 ms on this device, read 36. No absolute figure means anything in
     * that state, and a test cannot tell what state it is in.
     *
     * A paired ratio can. Both implementations meet the same governor, the
     * same thermal cap and the same swap in the round they share, so whatever
     * the phone is doing divides out. This is the rule `ShadingSpeedDeviceTest`
     * arrived at for the same reason and it still passes in the state above.
     *
     * The absolute projection is still logged. It is the number a person
     * wants, and on a cool phone it is the number to read; the assertion just
     * does not rest on it.
     */
    @Test
    fun theNativePassIsFasterThanTheReference() {
        assertThat(NativeMerge.isAvailable()).isTrue()
        val src = scene()
        val bitmap = Bitmap.createBitmap(src, width, height, Bitmap.Config.ARGB_8888)
        val dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val refOut = IntArray(width * height)
        val m = model()

        // Warm both, so neither pays the governor's ramp inside its timing.
        val warmUntil = System.nanoTime() + WARMUP_NS
        while (System.nanoTime() < warmUntil) {
            Optics.applyReference(src, refOut, width, height, m, aberration)
            NativeOptics.correctInto(bitmap, dst, m, aberration)
        }

        val kotlin = LongArray(ROUNDS)
        val native = LongArray(ROUNDS)
        for (i in 0 until ROUNDS) {
            // Alternated, so a drift across the run lands on both equally.
            var t = System.nanoTime()
            Optics.applyReference(src, refOut, width, height, m, aberration)
            kotlin[i] = (System.nanoTime() - t) / 1000

            t = System.nanoTime()
            assertThat(NativeOptics.correctInto(bitmap, dst, m, aberration)).isTrue()
            native[i] = (System.nanoTime() - t) / 1000
        }

        val ratios = kotlin.indices.map { kotlin[it].toDouble() / native[it] }.sorted()
        val ratioMedian = ratios[ratios.size / 2]
        val pixels = width.toLong() * height
        val nsPerPixel = median(native) * 1000.0 / pixels
        val fullFrameMs = nsPerPixel * 4080 * 3064 / 1e6

        Log.i(TAG, "kotlin reference: ${describe(kotlin)}")
        Log.i(TAG, "native:           ${describe(native)}")
        Log.i(TAG, "native is %.2fx faster by paired median (worst round %.2fx)".format(ratioMedian, ratios.first()))
        Log.i(TAG, "native %.2f ns/pixel, projected %.1fms for 4080x3064 on this device as it is now".format(nsPerPixel, fullFrameMs))
        DeviceKind.warnIfNotAPhone(TAG)

        // The Kotlin reference is itself banded across the cores, so this is
        // native-and-NEON against JIT-and-banded, not against a single thread.
        // Two is a floor a working port clears easily and a broken one does
        // not: a native pass that is only as fast as the JIT has something
        // wrong with it.
        assertThat(ratioMedian).isGreaterThan(2.0)

        bitmap.recycle()
        dst.recycle()
    }
}
