package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Random

/**
 * A faint star field registers on the plain 8-bit proxy: the baseline a night
 * sky (NIGHT's tripod plan) relies on. Brightening the proxy before the search
 * was tried and made this worse -- it amplified the read noise the 8-bit step
 * had been hiding (48 -> 41 of 48 tiles) -- see work/astro-20261022/NOTES.md.
 */
@RunWith(JUnit4::class)
class StarFieldAlignmentTest {

    private val w = 512
    private val h = 384
    private val profile = TestBayer.PROFILE

    /** The proxy exactly as `bayer_merge.cpp` / `BayerMerger` build it, gain as divisor. */
    private fun proxy(data: ShortArray, gainScale: Float): Plane {
        val pw = w / 2; val ph = h / 2
        val out = ByteArray(pw * ph)
        val range = profile.range.toFloat()
        val black = profile.blackAt(0, 0) + profile.blackAt(1, 0) + profile.blackAt(0, 1) + profile.blackAt(1, 1)
        for (y in 0 until ph) for (x in 0 until pw) {
            val s = data[(2 * y) * w + 2 * x] + data[(2 * y) * w + 2 * x + 1] +
                data[(2 * y + 1) * w + 2 * x] + data[(2 * y + 1) * w + 2 * x + 1]
            val norm = ((s - black) / (range * 4f * gainScale)).coerceIn(0f, 1f)
            out[y * pw + x] = (norm * 255f).toInt().toByte()
        }
        return Plane(pw, ph, out)
    }

    /** Sparse faint stars on a black sky with read noise, shifted by (sx, sy) Bayer pixels. */
    private fun sky(sx: Int, sy: Int, seed: Long): ShortArray {
        val stars = Random(7)
        val starAt = HashMap<Long, Int>()
        repeat(900) {
            val x = stars.nextInt(w - 8) + 4; val y = stars.nextInt(h - 8) + 4
            starAt[(y.toLong() shl 20) or x.toLong()] = 6 + stars.nextInt(16)
        }
        val noise = Random(seed)
        val black = profile.blackAt(0, 0)
        return ShortArray(w * h) { i ->
            val x = i % w - sx; val y = i / w - sy
            var v = black + 2.0 + noise.nextGaussian() * 1.5
            // Each star covers a 3x3 patch of the CFA.
            for (dy in -1..1) for (dx in -1..1) starAt[((y + dy).toLong() shl 20) or (x + dx).toLong()]?.let { v += it }
            v.toInt().coerceIn(0, profile.whiteLevel).toShort()
        }
    }

    @Test fun aFaintStarFieldRegistersOnThePlainProxy() {
        val ref = sky(0, 0, seed = 11)
        val alt = sky(4, 2, seed = 12) // two and one proxy pixels
        val tilesX = (w / 2) / 32; val tilesY = (h / 2) / 32
        val f = Aligner.align(Aligner.buildPyramid(proxy(ref, 1f)), Aligner.buildPyramid(proxy(alt, 1f)), tilesX, tilesY)
        val hits = f.dx.indices.count { f.dx[it] == 2 && f.dy[it] == 1 }
        assertThat(hits).isAtLeast((tilesX * tilesY * 0.9).toInt())
    }
}
