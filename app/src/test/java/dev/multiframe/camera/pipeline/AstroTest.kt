package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** The night sky's plan, its hot pixels, and which pixels are sky -- measured, not guessed. */
@RunWith(JUnit4::class)
class AstroTest {

    @Test fun framesAreShortEnoughNotToTrailAndTheSpanIsWhatWasAsked() {
        // The 11 Pro's main lens: f = 2852.8 px. Trailing limit 1.5 / (f·ω) ≈ 7.2 s.
        val plan = AstroPlanner.solve(4, 2852.8f, meterIso = 6000, meterExposureNs = 1_000_000_000L,
            exposureMaxNs = 30_000_000_000L, isoMin = 22, isoMax = 11277, maxAnalogIso = 704)
        assertThat(plan.trailLimitNs / 1e9).isWithin(0.1).of(7.21)
        assertThat(plan.exposureNs).isEqualTo(plan.trailLimitNs)
        assertThat(plan.iso).isAtMost(704) // never past the analog ceiling
        assertThat(plan.totalSeconds).isWithin(8.0).of(240.0)
        // A sensor that stops at one second plans 240 one-second frames.
        val short = AstroPlanner.solve(4, 2852.8f, 6000, 1_000_000_000L, 1_000_000_000L, 22, 11277, 704)
        assertThat(short.exposureNs).isEqualTo(1_000_000_000L)
        assertThat(short.frames).isEqualTo(240)
        // Past four minutes: fifteen is offered and planned.
        assertThat(AstroPlanner.MINUTES).containsAtLeast(8, 15)
        assertThat(AstroPlanner.solve(15, 2852.8f, 6000, 1_000_000_000L, 1_000_000_000L, 22, 11277, 704).frames).isEqualTo(900)
    }

    @Test fun aWideLensMayExposeLongerThanATelephoto() {
        val wide = AstroPlanner.solve(1, 1406.8f, 6000, 1_000_000_000L, 30_000_000_000L, 22, 11277, 704)
        val tele = AstroPlanner.solve(1, 12378.1f, 6000, 1_000_000_000L, 30_000_000_000L, 22, 11277, 704)
        assertThat(wide.exposureNs).isGreaterThan(4 * tele.exposureNs)
    }

    private val w = 128
    private val h = 96

    @Test fun aPixelHotInEveryFrameIsMappedAndAStarThatMovesIsNot() {
        val map = HotPixelMap(w, h)
        val hot = 40 * w + 50
        repeat(4) { f ->
            val star = (20 + f * 3) * w + 30 + f * 5 // moves every frame
            map.sample(FocusStack.CfaSource { x, y ->
                val i = y * w + x
                when (i) { hot -> 900; star -> 900; else -> 100 }
            })
        }
        val marked = map.settle()
        assertThat(marked.toList()).contains(hot)
        assertThat(marked.size).isEqualTo(1)
        var repaired = -1
        map.repair(FocusStack.CfaSource { x, y -> if (y * w + x == hot) 900 else 100 }) { _, _, v -> repaired = v }
        assertThat(repaired).isEqualTo(100)
    }

    @Test fun theSkyIsWhereTheStackFitsTheAlignmentAndTheGroundWhereItFitsStandingStill() {
        // Proxy 128x96: rows above 48 are sky (stars drifting right one pixel a
        // frame), rows below are textured ground that does not move.
        val pw = 128; val ph = 96
        val rnd = Random(3)
        val stars = List(200) { Pair(rnd.nextInt(pw), rnd.nextInt(ph / 2)) }
        val ground = ByteArray(pw * ph) { i -> if (i / pw >= ph / 2) (40 + rnd.nextInt(80)).toByte() else 8 }
        fun frame(shift: Int): Plane {
            val d = ground.copyOf()
            for ((sx, sy) in stars) { val x = sx + shift; if (x in 0 until pw) d[sy * pw + x] = 120 }
            return Plane(pw, ph, d)
        }
        val ref = frame(0)
        val mask = SkyMask(pw, ph)
        val tilesX = pw / 32; val tilesY = ph / 32
        for (f in 1..8) {
            // The field the sky stack would use: every tile follows the stars.
            val field = AlignmentField(tilesX, tilesY, IntArray(tilesX * tilesY) { f }, IntArray(tilesX * tilesY))
            mask.add(ref, frame(f), field)
        }
        val m = mask.finish()
        val skyMean = (0 until pw * (ph / 2 - 8)).map { m[it] }.average()
        val groundMean = ((ph / 2 + 8) * pw until pw * ph).map { m[it] }.average()
        println("sky mask: sky %.2f, ground %.2f".format(skyMean, groundMean))
        assertThat(skyMean).isGreaterThan(0.75)
        assertThat(groundMean).isLessThan(0.25)
    }

    @Test fun theBlendTakesTheSkyStackOverTheSkyAndTheGroundStackOverTheGround() {
        val ww = 4; val hh = 4
        fun buf(v: Int) = ByteBuffer.allocateDirect(ww * hh * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> repeat(ww * hh) { b.putShort(it * 2, v.toShort()) } }
        val out = ByteBuffer.allocateDirect(ww * hh * 2)
        SkyMask.blend(buf(1000), buf(200), floatArrayOf(1f, 0f, 1f, 0f), ww, hh, out)
        val o = out.order(ByteOrder.LITTLE_ENDIAN)
        assertThat(o.getShort(0).toInt()).isEqualTo(1000)      // cell (0,0): sky
        assertThat(o.getShort(2 * 2).toInt()).isEqualTo(200)   // cell (1,0): ground
    }

    @Test fun groundTilesBorrowTheSkysMotionAndTheTrackingFollowsIt() {
        val f = AlignmentField(4, 1, intArrayOf(5, 5, 0, 5), intArrayOf(1, 1, 0, 1))
        val s = AstroCapture.withSkyMotion(f)
        assertThat(s.dx.toList()).containsExactly(5, 5, 5, 5).inOrder()
        assertThat(AstroCapture.skyMotion(f)).isEqualTo(Pair(5, 1))
        val still = AlignmentField(4, 1, IntArray(4), IntArray(4))
        assertThat(AstroCapture.skyMotion(still)).isNull()
        // The search sees the frame from where the sky is predicted to be.
        val p = Plane(4, 1, byteArrayOf(1, 2, 3, 4))
        assertThat(AstroCapture.shiftedProxy(p, 1, 0).data.toList()).containsExactly(2.toByte(), 3.toByte(), 4.toByte(), 4.toByte()).inOrder()
    }
}
