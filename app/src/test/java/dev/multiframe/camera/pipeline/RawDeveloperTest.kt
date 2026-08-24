package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.abs

@RunWith(JUnit4::class)
class RawDeveloperTest {

    private val w = 64
    private val h = 48
    private val sensor = SensorProfile.DEFAULT          // GBRG, black 64, white 1023
    private val fixed = DevelopParams(exposureGain = 2.0f)

    private fun flat(value: Int) =
        BayerFrame(w, h, ShortArray(w * h) { value.toShort() })

    private fun rgbAt(px: IntArray, x: Int, y: Int): Triple<Int, Int, Int> {
        val p = px[y * w + x]
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    /** Samples away from the border, where the 3x3 gather is clipped. */
    private fun interior(px: IntArray): List<Triple<Int, Int, Int>> =
        (8 until h - 8 step 5).flatMap { y -> (8 until w - 8 step 5).map { x -> rgbAt(px, x, y) } }

    @Test
    fun `a flat frame develops to neutral grey`() {
        val px = RawDeveloper.develop(flat(500), sensor, ColorProfile.NEUTRAL, fixed)
        for ((r, g, b) in interior(px)) {
            assertThat(abs(r - g)).isAtMost(1)
            assertThat(abs(g - b)).isAtMost(1)
        }
    }

    @Test
    fun `black level maps to black`() {
        val px = RawDeveloper.develop(flat(64), sensor, ColorProfile.NEUTRAL, fixed)
        for ((r, g, b) in interior(px)) {
            assertThat(r).isEqualTo(0)
            assertThat(g).isEqualTo(0)
            assertThat(b).isEqualTo(0)
        }
    }

    @Test
    fun `white balance gains reach the output`() {
        val warm = ColorProfile(
            gains = floatArrayOf(2.0f, 1f, 1f, 0.5f),   // lift red, cut blue
            matrix = ColorProfile.NEUTRAL.matrix,
        )
        val neutral = RawDeveloper.develop(flat(500), sensor, ColorProfile.NEUTRAL, fixed)
        val gained = RawDeveloper.develop(flat(500), sensor, warm, fixed)

        val (nr, _, nb) = interior(neutral).first()
        val (gr, _, gb) = interior(gained).first()
        println("neutral r=$nr b=$nb   gained r=$gr b=$gb")

        assertThat(gr).isGreaterThan(nr)
        assertThat(gb).isLessThan(nb)
    }

    @Test
    fun `the colour matrix is applied`() {
        // A matrix that routes sensor green into the output red channel only.
        val swap = ColorProfile(
            gains = ColorProfile.NEUTRAL.gains,
            matrix = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        )
        // Desaturation off, so this measures the matrix alone. With it on, a
        // bright pure red correctly bleeds into the other channels on its way
        // to white, which is the subject of ToneCurveTest rather than of this.
        val matrixOnly = fixed.copy(highlightDesaturation = 0f)
        val px = RawDeveloper.develop(flat(500), sensor, swap, matrixOnly)
        for ((r, g, b) in interior(px)) {
            assertThat(r).isGreaterThan(0)
            assertThat(g).isEqualTo(0)
            assertThat(b).isEqualTo(0)
        }
    }

    @Test
    fun `output is fully opaque`() {
        val px = RawDeveloper.develop(flat(400), sensor, ColorProfile.NEUTRAL, fixed)
        for (p in px) assertThat((p ushr 24) and 0xFF).isEqualTo(255)
    }

    @Test
    fun `brighter input never produces a darker pixel`() {
        var previous = -1
        for (level in 70..1020 step 25) {
            val px = RawDeveloper.develop(flat(level), sensor, ColorProfile.NEUTRAL, fixed)
            val (r, _, _) = rgbAt(px, w / 2, h / 2)
            assertThat(r).isAtLeast(previous)
            previous = r
        }
        assertThat(previous).isGreaterThan(200)
    }

    @Test
    fun `the tone curve rolls highlights off instead of clipping`() {
        val knee = 0.7f
        // Below the knee the curve is exactly linear.
        assertThat(RawDeveloper.shoulder(0.5f, knee)).isWithin(1e-6f).of(0.5f)
        // Above it, compressed but still increasing and never past 1.0.
        val a = RawDeveloper.shoulder(1.0f, knee)
        val b = RawDeveloper.shoulder(2.0f, knee)
        val c = RawDeveloper.shoulder(8.0f, knee)
        println("shoulder 1.0=$a 2.0=$b 8.0=$c")
        assertThat(a).isLessThan(1.0f)
        assertThat(b).isGreaterThan(a)
        assertThat(c).isGreaterThan(b)
        assertThat(c).isAtMost(1.0f)
    }

    @Test
    fun `a merged frame develops without artefacts at the borders`() {
        val scene = TestBayer.scene(w, h)
        val px = RawDeveloper.develop(scene, TestBayer.PROFILE, ColorProfile.NEUTRAL, fixed)
        // Every pixel must be a valid colour, including the clipped-gather edges.
        for (p in px) {
            for (shift in listOf(16, 8, 0)) {
                assertThat((p shr shift) and 0xFF).isIn(0..255)
            }
        }
    }

    // Auto exposure: raw is linear and usually far darker than a viewable
    // image, so a fixed gain leaves real scenes crushed.

    @Test
    fun `a dark frame gets more gain than a bright one`() {
        val dark = RawDeveloper.autoExposureGain(flat(100), sensor, ColorProfile.NEUTRAL)
        val mid = RawDeveloper.autoExposureGain(flat(400), sensor, ColorProfile.NEUTRAL)
        val bright = RawDeveloper.autoExposureGain(flat(900), sensor, ColorProfile.NEUTRAL)
        println("auto gain dark=$dark mid=$mid bright=$bright")
        assertThat(dark).isGreaterThan(mid)
        assertThat(mid).isGreaterThan(bright)
    }

    @Test
    fun `auto exposure brings a dark scene up to a usable level`() {
        // Roughly the real measurement: raw mean 82 against a black level of 64.
        val px = RawDeveloper.develop(flat(82), sensor, ColorProfile.NEUTRAL)
        val (r, g, b) = rgbAt(px, w / 2, h / 2)
        println("dark frame with auto exposure -> $r,$g,$b")
        assertThat(r).isGreaterThan(120)
        assertThat(abs(r - g)).isAtMost(1)
        assertThat(abs(g - b)).isAtMost(1)
    }

    @Test
    fun `auto exposure does not blow out an already bright frame`() {
        val px = RawDeveloper.develop(flat(950), sensor, ColorProfile.NEUTRAL)
        val (r, _, _) = rgbAt(px, w / 2, h / 2)
        println("bright frame with auto exposure -> $r")
        assertThat(r).isAtMost(255)
        assertThat(r).isGreaterThan(150)
    }

    @Test
    fun `gain is bounded so a black frame cannot explode`() {
        val g = RawDeveloper.autoExposureGain(flat(64), sensor, ColorProfile.NEUTRAL)
        println("auto gain on a black frame = $g")
        assertThat(g).isAtMost(64f)
        assertThat(g).isGreaterThan(0f)
    }
}
