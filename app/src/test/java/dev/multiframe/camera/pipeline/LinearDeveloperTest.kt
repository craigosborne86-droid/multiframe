package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * The linear develop, held to what it removes and what it keeps.
 *
 * It removes the demosaic, because the canvas is already full colour. It
 * keeps everything else about how a photograph is rendered here, and the
 * test that matters most is the last: a flat field accumulated onto the
 * canvas and developed by this must come out the same grey the CFA develop
 * makes of the frame it was built from. Same tone, twice the pixels.
 */
class LinearDeveloperTest {

    private val profile = SensorProfile(
        blackLevel = intArrayOf(64, 64, 64, 64),
        whiteLevel = 1023,
        cfaPattern = intArrayOf(0, 1, 1, 2),
    )

    /** Tone off, sharpen off, exposure fixed: the arithmetic and nothing else. */
    private val plain = DevelopParams(
        exposureGain = 1f, shoulderKnee = 1f, contrast = 0f,
        highlightDesaturation = 0f, blackPoint = 0f,
        sharpen = Sharpen.Params(amount = 0f),
    )

    private fun canvas(w: Int, h: Int, fill: (x: Int, y: Int, c: Int) -> Int): ShortArray =
        ShortArray(w * h * 3) { i ->
            val c = i % 3
            val p = i / 3
            fill(p % w, p / w, c).coerceIn(0, 65535).toShort()
        }

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    @Test
    fun channelBlackFollowsTheSites() {
        val p = SensorProfile(
            blackLevel = intArrayOf(60, 64, 68, 72), whiteLevel = 1023,
            cfaPattern = intArrayOf(0, 1, 1, 2),
        )
        val black = LinearDeveloper.channelBlack(p)
        assertThat(black[0]).isEqualTo(60f)
        assertThat(black[1]).isEqualTo(66f)
        assertThat(black[2]).isEqualTo(72f)
    }

    @Test
    fun channelGainsAverageTheGreens() {
        val color = ColorProfile(floatArrayOf(2f, 1f, 1.2f, 1.5f), ColorProfile.NEUTRAL.matrix)
        val g = LinearDeveloper.channelGains(color)
        assertThat(g[0]).isEqualTo(2f)
        assertThat(g[1]).isWithin(1e-6f).of(1.1f)
        assertThat(g[2]).isEqualTo(1.5f)
    }

    @Test
    fun aValueAtBlackDevelopsToBlack() {
        val rgb = canvas(8, 8) { _, _, _ -> 64 }
        val out = LinearDeveloper.develop(rgb, 8, 8, profile, ColorProfile.NEUTRAL, plain)
        assertThat(out.all { red(it) == 0 && green(it) == 0 && blue(it) == 0 }).isTrue()
    }

    @Test
    fun aFlatGreyStaysGrey() {
        val rgb = canvas(16, 16) { _, _, _ -> 64 + 400 }
        val out = LinearDeveloper.develop(rgb, 16, 16, profile, ColorProfile.NEUTRAL, plain)
        for (p in out) {
            assertThat(red(p)).isEqualTo(green(p))
            assertThat(green(p)).isEqualTo(blue(p))
        }
        // 400 of 959 is 0.417 linear, which sRGB encodes to 173.
        assertThat(green(out[0])).isEqualTo(173)
    }

    @Test
    fun whiteBalanceNeutralisesATint() {
        // A canvas the sensor saw as red-heavy and blue-light, and gains that say so.
        val rgb = canvas(8, 8) { _, _, c -> 64 + when (c) { 0 -> 600; 2 -> 240; else -> 400 } }
        val color = ColorProfile(
            gains = floatArrayOf(400f / 600f, 1f, 1f, 400f / 240f),
            matrix = ColorProfile.NEUTRAL.matrix,
        )
        val out = LinearDeveloper.develop(rgb, 8, 8, profile, color, plain)
        val p = out[0]
        assertThat(abs(red(p) - green(p))).isAtMost(1)
        assertThat(abs(blue(p) - green(p))).isAtMost(1)
    }

    @Test
    fun theMatrixIsApplied() {
        // A matrix that swaps red and blue, on a canvas that is only red.
        val rgb = canvas(8, 8) { _, _, c -> 64 + if (c == 0) 400 else 0 }
        val swap = ColorProfile(
            gains = floatArrayOf(1f, 1f, 1f, 1f),
            matrix = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f),
        )
        // The purity compression off: a colour of purity one is otherwise
        // brought in toward the ceiling, and this measures the matrix alone.
        val out = LinearDeveloper.develop(rgb, 8, 8, profile, swap, plain.copy(purityThreshold = 0f))
        assertThat(red(out[0])).isEqualTo(0)
        assertThat(blue(out[0])).isEqualTo(173)
    }

    @Test
    fun shadingIsReadAtHalfTheCanvasCoordinates() {
        // The sensor is 8x8 and its map says the right edge needs twice the
        // gain. The canvas is 16x16 and a cell at x=15 is sensor column 7.
        val w = 16
        val h = 16
        val rgb = canvas(w, h) { x, _, _ -> 64 + if (x >= w / 2) 200 else 400 }
        val map = ShadingMap(
            columns = 2, rows = 1,
            gains = FloatArray(2 * 1 * 4) { i -> if (i / 4 == 0) 1f else 2f },
        )
        val out = LinearDeveloper.develop(rgb, w, h, profile, ColorProfile.NEUTRAL, plain, map)
        // Interpolated across the sensor, so only the two edges are exact.
        val left = green(out[0])
        val right = green(out[w - 1])
        // Right: 200 * 2.0 = 400 linear counts; left: 400 * 1.0. Equal.
        assertThat(abs(left - right)).isAtMost(1)
        // And the middle, where the map reads 1.5, sits between.
        val mid = green(out[w / 2 + 1])
        assertThat(mid).isLessThan(left)
    }

    @Test
    fun autoExposureBringsTheBrightEndToTheTarget() {
        // Half the canvas at one level and half at a quarter of it; the 92nd
        // percentile is the bright half and it should land at the target.
        val w = 40
        val h = 40
        val rgb = canvas(w, h) { x, _, _ -> 64 + if (x < w / 2) 80 else 20 }
        val params = DevelopParams()
        val gain = LinearDeveloper.autoExposureGain(rgb, w, h, profile, ColorProfile.NEUTRAL, params)
        val bright = 80f / 959f
        assertThat(gain).isWithin(0.02f).of(params.highlightTarget / bright)
    }

    @Test
    fun sharpeningIsTheSameOperatorAsTheCfaDevelop() {
        // An edge on the canvas. Developed twice, once with the capture
        // sharpen and once without; the difference is Sharpen.apply on the
        // unsharpened result, because that is exactly what the develop does.
        val w = 24
        val h = 12
        val rgb = canvas(w, h) { x, _, _ -> 64 + if (x < w / 2) 150 else 450 }
        val soft = LinearDeveloper.develop(rgb, w, h, profile, ColorProfile.NEUTRAL, plain)
        val sharp = LinearDeveloper.develop(
            rgb, w, h, profile, ColorProfile.NEUTRAL, plain.copy(sharpen = Sharpen.Params()),
        )
        val expected = soft.copyOf()
        Sharpen.apply(expected, w, h, Sharpen.Params())
        assertThat(sharp).isEqualTo(expected)
        assertThat(sharp.contentEquals(soft)).isFalse()
    }

    /**
     * The one that matters. A flat field through the accumulator and this
     * develop is the same grey as the same flat field through the CFA develop.
     */
    @Test
    fun aFlatFieldRendersTheSameToneAsTheCfaDevelop() {
        val sensorW = 32
        val sensorH = 24
        val level = 64 + 300
        val frame = BayerFrame(sensorW, sensorH, ShortArray(sensorW * sensorH) { level.toShort() })

        // The CFA develop of the frame, tone and all, sharpen off since a
        // flat field has nothing to sharpen and the operator is tested above.
        val params = DevelopParams(sharpen = Sharpen.Params(amount = 0f), hotPixelThreshold = 0f)
        val cfa = RawDeveloper.develop(frame, profile, ColorProfile.NEUTRAL, params)

        // The same frame accumulated as a reference alone, then developed.
        val acc = SubPixelAccumulator(sensorW, sensorH, profile)
        acc.setReference(frame)
        val result = acc.finish()
        val rgb16 = ShortArray(result.rgb.size) { i ->
            Math.round(result.rgb[i]).coerceIn(0, 65535).toShort()
        }
        val linear = LinearDeveloper.develop(rgb16, result.width, result.height, profile, ColorProfile.NEUTRAL, params)

        // Interior pixels of each, away from the borders the demosaic and the
        // fill treat differently.
        val cfaPixel = cfa[(sensorH / 2) * sensorW + sensorW / 2]
        val linPixel = linear[(result.height / 2) * result.width + result.width / 2]
        assertThat(abs(green(cfaPixel) - green(linPixel))).isAtMost(1)
        assertThat(abs(red(cfaPixel) - red(linPixel))).isAtMost(1)
        assertThat(abs(blue(cfaPixel) - blue(linPixel))).isAtMost(1)
        // And it is a real grey at a real level, not two zeros agreeing.
        assertThat(green(linPixel)).isGreaterThan(64)
    }
}
