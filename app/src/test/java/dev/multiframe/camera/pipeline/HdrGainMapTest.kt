package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.log2

@RunWith(JUnit4::class)
class HdrGainMapTest {

    private val headroom = DevelopParams.HDR_HEADROOM
    private val sensor = SensorProfile.DEFAULT          // GBRG, black 64, white 1023

    /** The develop's parameters as resolved for a gain: knee and white placed. */
    private fun resolved(gain: Float) = DevelopParams().withGain(gain)

    @Test
    fun `the ratio is one at and under the knee`() {
        for (gain in listOf(0.6f, 1f, 2f, 4f)) {
            val p = resolved(gain)
            for (i in 0..100) {
                val m = p.shoulderKnee * i / 100f
                assertThat(HdrGainMap.ratio(m, p, headroom)).isEqualTo(1f)
            }
        }
    }

    @Test
    fun `the ratio is HDR over SDR, and at the clip it is the clip`() {
        // A daylight frame: the standing bias makes the gain about two, the
        // clip lands at two, and the HDR rendition shows it a stop over SDR white.
        val p = resolved(2f)
        assertThat(p.shoulderWhite).isEqualTo(2f)
        assertThat(HdrGainMap.ratio(2f, p, headroom)).isWithin(1e-5f).of(2f)
        assertThat(HdrGainMap.ratio(9f, p, headroom)).isWithin(1e-5f).of(2f)
        // In between it is the scene over the SDR shoulder: identity over the log.
        val m = 1.3f
        assertThat(HdrGainMap.ratio(m, p, headroom))
            .isWithin(1e-5f).of(m / ToneCurve.shoulder(m, p.shoulderKnee, p.shoulderWhite))
        // A night frame at the gain's ceiling reaches the full two stops.
        assertThat(HdrGainMap.ratio(4f, resolved(4f), headroom)).isWithin(1e-5f).of(4f)
    }

    @Test
    fun `the ratio is clamped at two stops and never under one`() {
        for (white in listOf(0.5f, 0.8f, 1f, 2f, 4f, 6f, 16f)) {
            val p = DevelopParams(shoulderWhite = white).withGain(2f)
            var last = 1f
            for (i in 0..400) {
                val m = i / 400f * white * 2f
                val r = HdrGainMap.ratio(m, p, headroom)
                assertThat(r).isAtLeast(1f)
                assertThat(r).isAtMost(headroom)
                assertThat(log2(r)).isAtMost(2f)
                assertThat(r).isAtLeast(last - 1e-4f)      // monotonic in the scene
                last = r
            }
        }
        // A white past the HDR peak is compressed to it: exactly two stops at the clip.
        val wide = DevelopParams(shoulderWhite = 16f).withGain(2f)
        assertThat(HdrGainMap.ratio(16f, wide, headroom)).isWithin(1e-4f).of(4f)
    }

    @Test
    fun `the relaxed shoulder at a peak of one is the SDR shoulder`() {
        for (white in listOf(1.5f, 2f, 4f)) {
            for (i in 0..200) {
                val x = i / 200f * white
                assertThat(HdrGainMap.relaxedShoulder(x, 0.7f, white, 1f))
                    .isWithin(1e-5f).of(ToneCurve.shoulder(x, 0.7f, white))
            }
        }
    }

    @Test
    fun `the relaxed shoulder has a slope of one at the knee and reaches the peak at the white`() {
        val knee = 0.7f
        val white = 12f
        val e = 1e-3f
        val slope = (HdrGainMap.relaxedShoulder(knee + e, knee, white, headroom) - knee) / e
        assertThat(slope).isWithin(5e-3f).of(1f)
        assertThat(HdrGainMap.relaxedShoulder(white, knee, white, headroom)).isWithin(1e-4f).of(headroom)
        assertThat(HdrGainMap.relaxedShoulder(white * 3, knee, white, headroom)).isEqualTo(headroom)
    }

    @Test
    fun `the gain map is single-channel because the shoulder scales all three channels alike`() {
        // ToneCurve.renderLinear with the shoulder alone (no desaturation):
        // each channel comes out as its input times S(m)/m, so HDR/SDR is the
        // same number in R, G and B.
        val p = resolved(2f).copy(highlightDesaturation = 0f)
        val rgb = floatArrayOf(1.6f, 0.9f, 0.4f)
        val sdr = rgb.copyOf().also { ToneCurve.renderLinear(it, p) }
        val m = rgb.max()
        val hdr = FloatArray(3) { rgb[it] * HdrGainMap.relaxedShoulder(m, p.shoulderKnee, p.shoulderWhite, headroom) / m }
        val ratios = FloatArray(3) { hdr[it] / sdr[it] }
        assertThat(ratios[1]).isWithin(1e-5f).of(ratios[0])
        assertThat(ratios[2]).isWithin(1e-5f).of(ratios[0])
        assertThat(ratios[0]).isWithin(1e-5f).of(HdrGainMap.ratio(m, p, headroom))
    }

    // --- Measurement from the CFA ---

    private val w = 64
    private val h = 48

    private fun field(data: ShortArray, gain: Float = 2f) = HdrGainMap.measure(
        ShortBuffer.wrap(data), w, h, sensor, ColorProfile.NEUTRAL, resolved(gain), null, headroom,
    )

    @Test
    fun `a clipped patch reads the clip and a dark frame reads one`() {
        val data = ShortArray(w * h) { 200 }
        for (y in 16 until 32) for (x in 32 until 48) data[y * w + x] = 1023
        val f = field(data)
        assertThat(f.width).isEqualTo(w / 4)
        assertThat(f.height).isEqualTo(h / 4)
        // The patch's blocks: (1023 − 64) / 959 × 2 = 2, the clip, a ratio of two.
        for (by in 4 until 8) for (bx in 8 until 12) assertThat(f.log2[by * f.width + bx]).isWithin(1e-4f).of(1f)
        // The dark blocks: 0.28, under the knee.
        assertThat(f.log2[0]).isEqualTo(0f)
        assertThat(f.log2[2 * f.width + 2]).isEqualTo(0f)
    }

    @Test
    fun `a hot pixel in a dark block does not read as a highlight`() {
        val data = ShortArray(w * h) { 200 }
        data[9 * w + 10] = 1023
        data[20 * w + 21] = 1023
        val f = field(data)
        assertThat(f.log2.max()).isEqualTo(0f)
    }

    @Test
    fun `measuring changes nothing in the mosaic`() {
        val data = ShortArray(w * h) { (64 + (it * 37) % 959).toShort() }
        val before = data.copyOf()
        field(data)
        assertThat(data).isEqualTo(before)
    }

    // --- The picture unchanged ---

    @Test
    fun `the SDR picture is the same with the HDR rendition on`() {
        val data = ShortArray(w * h) { i -> (64 + ((i % w) * 15 + (i / w) * 9) % 959).toShort() }
        for (y in 10 until 30) for (x in 20 until 50) data[y * w + x] = 1023
        val base = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON)
        val sdr = RawDeveloper.develop(BayerFrame(w, h, data.copyOf()), sensor, ColorProfile.NEUTRAL, base)
        val withHdr = RawDeveloper.develop(
            BayerFrame(w, h, data.copyOf()), sensor, ColorProfile.NEUTRAL,
            base.copy(hdrHeadroom = DevelopParams.HDR_HEADROOM),
        )
        assertThat(withHdr).isEqualTo(sdr)
    }

    // --- Placement: the map follows the picture back to the sensor ---

    /** A quarter turn clockwise as `Matrix.postRotate` and `NativeRotate` make it. */
    private fun turn(src: IntArray, sw: Int, sh: Int, degrees: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until sh) for (x in 0 until sw) {
            val v = src[y * sw + x]
            when (degrees) {
                90 -> out[x * sh + (sh - 1 - y)] = v
                180 -> out[(sh - 1 - y) * sw + (sw - 1 - x)] = v
                270 -> out[(sw - 1 - x) * sh + y] = v
                else -> out[y * sw + x] = v
            }
        }
        return out
    }

    @Test
    fun `every quarter turn is undone pixel for pixel`() {
        val sw = 7
        val sh = 5
        val src = IntArray(sw * sh) { it }
        val s = FloatArray(2)
        for (degrees in listOf(0, 90, 180, 270, -90, 450)) {
            val norm = ((degrees % 360) + 360) % 360
            val turned = turn(src, sw, sh, norm)
            val (tw, th) = if (norm == 90 || norm == 270) sh to sw else sw to sh
            val placement = HdrGainMap.Placement(tw, th, degrees)
            for (y in 0 until th) for (x in 0 until tw) {
                placement.sensorOf(x.toFloat(), y.toFloat(), s)
                val back = src[s[1].toInt() * sw + s[0].toInt()]
                assertThat(back).isEqualTo(turned[y * tw + x])
            }
        }
    }

    @Test
    fun `the framing and the optics inset are added back, then the lens model`() {
        val s = FloatArray(2)
        val inset = LensDistortion.Inset(11, 7, 13, 9)
        HdrGainMap.Placement(100, 80, 0, framingLeft = 5, framingTop = 3, inset = inset).sensorOf(10f, 20f, s)
        assertThat(s[0]).isEqualTo(10f + 5 + 11)
        assertThat(s[1]).isEqualTo(20f + 3 + 7)

        val model = LensDistortion.Model(fx = 85f, fy = 85f, cx = 48f, cy = 40f, k1 = .08f, skew = 0f, k2 = 0f, k3 = 0f, p1 = 0f, p2 = 0f)
        HdrGainMap.Placement(100, 80, 0, 5, 3, inset, model).sensorOf(10f, 20f, s)
        val expected = FloatArray(2).also { LensDistortion.sourceFor(model, 26f, 30f, it) }
        assertThat(s[0]).isWithin(1e-5f).of(expected[0])
        assertThat(s[1]).isWithin(1e-5f).of(expected[1])
    }

    @Test
    fun `an unmoved picture's map is the field block for block`() {
        val fw = 16
        val fh = 12
        val log2 = FloatArray(fw * fh) { i -> ((i % fw) + (i / fw)) / 26f * 2f }
        val f = HdrGainMap.Field(fw, fh, 4, log2)
        val map = HdrGainMap.encode(f, HdrGainMap.Placement(fw * 4, fh * 4))!!
        assertThat(map.width).isEqualTo(fw)
        assertThat(map.height).isEqualTo(fh)
        assertThat(map.maxRatio).isWithin(1e-4f).of(4f)
        for (y in 0 until fh) for (x in 0 until fw) {
            val want = 2f.toDouble().let { Math.pow(it, log2[y * fw + x].toDouble()).toFloat() }
            // Eight bits over two stops: within half a code, under 0.3%.
            assertThat(abs(map.ratioAt(x, y) / want - 1f)).isLessThan(0.003f)
        }
        assertThat(map.codes.maxOf { it.toInt() and 0xFF }).isEqualTo(255)
    }

    @Test
    fun `a scene with nothing over the knee writes no map`() {
        val f = HdrGainMap.Field(4, 4, 4, FloatArray(16))
        assertThat(HdrGainMap.encode(f, HdrGainMap.Placement(16, 16))).isNull()
    }

    @Test
    fun `the map is a quarter of the picture, rounded up`() {
        val f = HdrGainMap.Field(4, 4, 4, FloatArray(16) { 1f })
        val map = HdrGainMap.encode(f, HdrGainMap.Placement(4081, 3061, 90))!!
        assertThat(map.width).isEqualTo(1021)
        assertThat(map.height).isEqualTo(766)
    }
}
