package dev.multiframe.camera.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "AlignerParity"

/**
 * The native alignment search against the Kotlin it replaced.
 *
 * Alignment was 83% of the merge, so it moved to C++ where the inner loop is a
 * sixteen-byte instruction rather than a byte at a time. The Kotlin stays as the
 * reference implementation -- it is the one the unit tests cover -- and this
 * holds the two to the same answer, in the same arrangement the develop stage
 * already uses.
 *
 * Exact equality, not a tolerance. Both are integer searches over the same
 * costs; anything other than an identical field means the port changed a
 * decision somewhere, and a tolerance would hide precisely the tie-breaking and
 * edge-clamping differences that are the likely way to get this wrong.
 */
@RunWith(AndroidJUnit4::class)
class AlignerParityTest {

    private val w = 320
    private val h = 256
    private val tilesX = 10
    private val tilesY = 8

    /** Texture at several scales, so a tile has something to match on. */
    private fun scene(seed: Int, w: Int = this.w, h: Int = this.h): Plane {
        val rnd = Random(seed)
        val d = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = 120 + 50 * sin(x * 0.11) * cos(y * 0.07) + 25 * sin((x + y) * 0.31)
                d[y * w + x] = v.toInt().coerceIn(0, 255).toByte()
            }
        }
        repeat(w * h / 200) {
            val cx = rnd.nextInt(4, w - 4)
            val cy = rnd.nextInt(4, h - 4)
            val v = rnd.nextInt(0, 256)
            for (dy in -2..2) for (dx in -2..2) {
                d[(cy + dy) * w + (cx + dx)] = v.toByte()
            }
        }
        return Plane(w, h, d)
    }

    /** The same scene seen from a camera that moved by [sx], [sy]. */
    private fun shifted(src: Plane, sx: Int, sy: Int): Plane {
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val ay = (y + sy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val ax = (x + sx).coerceIn(0, w - 1)
                out[y * w + x] = src.data[ay * w + ax]
            }
        }
        return Plane(w, h, out)
    }

    private fun assertParity(
        ref: Plane,
        alt: Plane,
        what: String,
        tilesX: Int = this.tilesX,
        tilesY: Int = this.tilesY,
    ) {
        val kotlinField = Aligner.align(
            Aligner.buildPyramid(ref), Aligner.buildPyramid(alt), tilesX, tilesY,
        )
        val nativeField = Aligner.alignNative(ref, alt, tilesX, tilesY)
        assertThat(nativeField).isNotNull()

        var worst = 0
        var differing = 0
        for (i in kotlinField.dx.indices) {
            val ddx = kotlinField.dx[i] - nativeField!!.dx[i]
            val ddy = kotlinField.dy[i] - nativeField.dy[i]
            if (ddx != 0 || ddy != 0) {
                differing++
                worst = maxOf(worst, maxOf(kotlin.math.abs(ddx), kotlin.math.abs(ddy)))
            }
        }
        Log.i(
            TAG,
            "$what: ${kotlinField.dx.size} tiles, $differing differing, worst $worst",
        )
        assertThat(differing).isEqualTo(0)
    }

    @Test
    fun aStationaryPairAgrees() {
        val s = scene(11)
        assertParity(s, s, "no motion")
    }

    @Test
    fun aTranslatedPairAgrees() {
        val s = scene(12)
        assertParity(s, shifted(s, 3, 2), "shift 3,2")
    }

    @Test
    fun anOddTranslationAgrees() {
        // Odd components matter: the finest level samples every second pixel,
        // so an odd shift is the case where the two implementations could
        // disagree about which sample they are comparing.
        val s = scene(13)
        assertParity(s, shifted(s, 5, 7), "shift 5,7")
    }

    @Test
    fun aNegativeTranslationAgrees() {
        // Drives the search off the left and top edges, where reads clamp to
        // the edge pixel -- the part of the native version that is split out
        // from the vectorised run and most likely to be got wrong.
        val s = scene(14)
        assertParity(s, shifted(s, -6, -4), "shift -6,-4")
    }

    @Test
    fun aFlatSceneAgrees() {
        // No texture anywhere, so every candidate ties and the winner is
        // decided purely by the order the two implementations try them in.
        val flat = Plane(w, h, ByteArray(w * h) { 128.toByte() })
        assertParity(flat, flat, "flat")
    }

    @Test
    fun theRealProxySizeIsSafeAndAgrees() {
        // At the size the app actually uses, not a convenient small one.
        //
        // This is here because the first version of the native search crashed
        // on exactly this and passed every smaller case. The proxy is 2040x1536
        // -- 3,133,440 bytes, which is 765 pages exactly -- so its buffer ends
        // flush against a page boundary and a wide load reading a single byte
        // past the last sample lands on an unmapped page. A smaller plane has
        // slack after it and hides the fault.
        val w = 2040
        val h = 1536
        val ref = scene(21, w, h)
        val alt = shifted(ref, 3, -2)
        // The ratio the merge uses: a tile every 32 proxy pixels.
        assertParity(ref, alt, "proxy 2040x1536", tilesX = w / 32, tilesY = h / 32)
    }

    @Test
    fun theNativeSearchIsFaster() {
        // Not a tight bound -- this is a correctness suite, and the real
        // measurement is on a live burst. It only catches the native path
        // silently becoming the slow one.
        val ref = scene(15)
        val alt = shifted(ref, 4, 3)

        val kotlinStart = System.nanoTime()
        repeat(3) { Aligner.align(Aligner.buildPyramid(ref), Aligner.buildPyramid(alt), tilesX, tilesY) }
        val kotlinMillis = (System.nanoTime() - kotlinStart) / 3_000_000.0

        val nativeStart = System.nanoTime()
        repeat(3) { Aligner.alignNative(ref, alt, tilesX, tilesY) }
        val nativeMillis = (System.nanoTime() - nativeStart) / 3_000_000.0

        Log.i(TAG, "align %.1fms kotlin vs %.1fms native".format(kotlinMillis, nativeMillis))
        assertThat(nativeMillis).isLessThan(kotlinMillis)
    }
}
