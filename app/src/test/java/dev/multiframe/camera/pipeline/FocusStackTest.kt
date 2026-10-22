package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Each part of a stacked picture comes from the frame where it is sharp. */
@RunWith(JUnit4::class)
class FocusStackTest {

    private val w = 256
    private val h = 192
    private val profile = TestBayer.PROFILE

    /**
     * Fine texture everywhere: 3×3 blocks of random level. Not periodic -- a
     * checker would let alignment lock onto the wrong period, which is a
     * property of the scene, not of the stack.
     */
    private val sharp: ShortArray = run {
        val rnd = java.util.Random(4242)
        val bw = w / 3 + 1
        val levels = IntArray(bw * (h / 3 + 1)) { 200 + rnd.nextInt(700) }
        ShortArray(w * h) { i -> levels[(i / w / 3) * bw + (i % w) / 3].toShort() }
    }

    /** [sharp] with a 9×9 box blur where [blurLeft] says (left half or right half). */
    private fun defocused(blurLeft: Boolean): ShortArray {
        val out = sharp.copyOf()
        for (y in 4 until h - 4) for (x in 4 until w - 4) {
            if ((x < w / 2) != blurLeft) continue
            var s = 0
            for (dy in -4..4) for (dx in -4..4) s += sharp[(y + dy) * w + x + dx]
            out[y * w + x] = (s / 81).toShort()
        }
        return out
    }

    private fun rms(a: ShortArray, x0: Int, x1: Int): Double {
        var s = 0.0; var n = 0
        for (y in 16 until h - 16) for (x in x0 until x1) {
            val d = (a[y * w + x] - sharp[y * w + x]).toDouble(); s += d * d; n++
        }
        return sqrt(s / n)
    }

    @Test fun eachHalfComesFromTheFrameWhereItIsSharp() {
        val nearSharp = defocused(blurLeft = false) // left half sharp
        val farSharp = defocused(blurLeft = true)   // right half sharp
        val stack = FocusStack(w, h, profile)
        stack.add(FocusStack.CfaSource { x, y -> nearSharp[y * w + x].toInt() and 0xFFFF })
        stack.add(FocusStack.CfaSource { x, y -> farSharp[y * w + x].toInt() and 0xFFFF })
        val buf = stack.finish().order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(w * h) { buf.getShort(it * 2) }

        // Away from the seam, each half is within a few codes of the sharp scene,
        // where the frame defocused there is hundreds of codes off.
        val leftStack = rms(out, 16, w / 2 - 24)
        val rightStack = rms(out, w / 2 + 24, w - 16)
        val leftFar = rms(farSharp, 16, w / 2 - 24)
        val rightNear = rms(nearSharp, w / 2 + 24, w - 16)
        println("focus stack rms: left $leftStack (blurred input $leftFar), right $rightStack (blurred input $rightNear)")
        assertThat(leftStack).isLessThan(leftFar * 0.1)
        assertThat(rightStack).isLessThan(rightNear * 0.1)
        assertThat(stack.frames).isEqualTo(2)
    }

    @Test fun distancesRunNearToFarEvenlyInDiopters() {
        val d = FocusStack.distances(10f, 5)
        assertThat(d).containsExactly(10f, 7.5f, 5f, 2.5f, 0f).inOrder()
    }
}
