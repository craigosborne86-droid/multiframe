package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Align.cpp held to the Kotlin `Aligner` on the desk: the same integer
 * winners, the same tiles declined, and fractions within 1e-5 proxy pixels.
 *
 * The refinement's sums are exact integers on both sides -- the bilinear
 * weights are in 1/256 and the warp is kept in 1/256 of a code -- so the NEON
 * lanes adding in their own order cannot move them. What is left is the 2x2
 * solve in double, which -ffast-math may contract differently; that is
 * parts in 1e16, three iterations deep. 1e-5 is the CLAUDE.md bound, kept.
 *
 * Opt in with -Pmf.host.lib=<directory with the host JNI library from
 * tools/iq_forensics/build_host.py>. On a device the same comparison is
 * AlignerParityTest.
 */
class AlignerHostParityTest {

    private fun scene(w: Int, h: Int, sx: Double, sy: Double, seed: Int, sigma: Double): ByteArray {
        val waves = List(7) { k ->
            val f = 0.02 + 0.2 * ((k * 0.37 + seed * 0.11) % 1.0)
            val a = PI * ((k * 0.61 + seed * 0.29) % 1.0)
            doubleArrayOf(f * kotlin.math.cos(a), f * kotlin.math.sin(a), k * 1.3 + seed, 9.0 + k)
        }
        val noise = java.util.Random(seed * 31L + 7)
        return ByteArray(w * h) { i ->
            val x = (i % w) - sx; val y = (i / w) - sy
            val v = 128 + waves.sumOf { it[3] * sin(2 * PI * (it[0] * x + it[1] * y) + it[2]) } + sigma * noise.nextGaussian()
            v.roundToInt().coerceIn(0, 255).toByte()
        }
    }

    @Test
    fun nativeRefinementMatchesTheKotlinReference() {
        assumeTrue("opt-in host JNI / physical native library", NativeMerge.isAvailable())
        // Widths that are not a multiple of the eight-lane warp, tiles that
        // are not either, shifts that push border tiles off the plane, and
        // one pair of identical frames.
        val cases = listOf(
            Triple(256 to 192, 8 to 6, Triple(0.3, -0.45, 1.0)),
            Triple(250 to 190, 7 to 5, Triple(2.6, 1.3, 2.0)),
            Triple(320 to 240, 10 to 7, Triple(-3.7, 0.55, 4.0)),
            Triple(203 to 151, 6 to 4, Triple(0.0, 0.0, 0.0)),
            Triple(320 to 240, 10 to 7, Triple(1.49, -2.51, 8.0)),
        )
        var tiles = 0
        var fitted = 0
        var worst = 0.0
        cases.forEachIndexed { k, (size, grid, motion) ->
            val (w, h) = size; val (tx, ty) = grid; val (sx, sy, sigma) = motion
            val ref = Plane(w, h, scene(w, h, 0.0, 0.0, k, sigma))
            val alt = if (sx == 0.0 && sy == 0.0) Plane(w, h, ref.data.copyOf()) else Plane(w, h, scene(w, h, sx, sy, k, sigma).also {
                // Independent noise on the alternate.
                val n = java.util.Random(k * 977L)
                for (i in it.indices) it[i] = ((it[i].toInt() and 0xFF) + (sigma * n.nextGaussian()).roundToInt()).coerceIn(0, 255).toByte()
            })
            val kotlin = Aligner.align(Aligner.buildPyramid(ref), Aligner.buildPyramid(alt), tx, ty)
            val native = requireNotNull(Aligner.alignNative(ref, alt, tx, ty))
            assertThat(native.dx.toList()).isEqualTo(kotlin.dx.toList())
            assertThat(native.dy.toList()).isEqualTo(kotlin.dy.toList())
            for (i in kotlin.dx.indices) {
                assertThat(native.hasSubPixelAt(i)).isEqualTo(kotlin.hasSubPixelAt(i))
                worst = maxOf(worst, abs(native.fracDx[i] - kotlin.fracDx[i]).toDouble(), abs(native.fracDy[i] - kotlin.fracDy[i]).toDouble())
                tiles++
                if (kotlin.hasSubPixelAt(i)) fitted++
            }
        }
        println("host parity: $tiles tiles, $fitted fitted, worst fraction difference $worst proxy px")
        assertThat(fitted).isGreaterThan(tiles / 2)
        assertThat(worst).isLessThan(1e-5)
    }
}
