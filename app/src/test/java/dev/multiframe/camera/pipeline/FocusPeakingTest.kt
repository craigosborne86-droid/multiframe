package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Focus peaking.
 *
 * The claims are comparative -- sharp reads higher than soft, dark subjects read
 * like bright ones -- because an absolute threshold means nothing without a
 * scene to calibrate against.
 */
class FocusPeakingTest {

    private val width = 96
    private val height = 72

    /** A pattern of edges at a given contrast around a given brightness. */
    private fun bars(brightness: Int, contrast: Int, period: Int = 8): Plane {
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val high = (x / (period / 2)) % 2 == 0
                val v = if (high) brightness + contrast / 2 else brightness - contrast / 2
                data[y * width + x] = v.coerceIn(0, 255).toByte()
            }
        }
        return Plane(width, height, data)
    }

    /** Blurs a plane, which is what defocus does. */
    private fun blur(plane: Plane, passes: Int): Plane {
        var current = plane
        repeat(passes) {
            val out = ByteArray(current.width * current.height)
            for (y in 0 until current.height) {
                for (x in 0 until current.width) {
                    var sum = 0
                    var n = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, current.width - 1)
                        val sy = (y + dy).coerceIn(0, current.height - 1)
                        sum += current.data[sy * current.width + sx].toInt() and 0xFF
                        n++
                    }
                    out[y * current.width + x] = (sum / n).toByte()
                }
            }
            current = Plane(current.width, current.height, out)
        }
        return current
    }

    private fun sharpnessOf(plane: Plane): Float = FocusPeaking.focusScore(plane)

    private fun markedFractionOf(plane: Plane): Float {
        val mask = ByteArray(plane.width * plane.height)
        FocusPeaking.detect(plane, mask)
        return FocusPeaking.markedFraction(mask, plane.width * plane.height)
    }

    // ------------------------------------------------------------------

    @Test
    fun `a sharp image reads higher than a defocused one`() {
        val sharp = bars(brightness = 128, contrast = 90)
        val soft = blur(sharp, passes = 3)

        val sharpReading = sharpnessOf(sharp)
        val softReading = sharpnessOf(soft)

        println("sharpness: focused %.3f, defocused %.3f".format(sharpReading, softReading))
        assertThat(sharpReading).isGreaterThan(softReading)
    }

    @Test
    fun `the reading falls steadily as focus is lost`() {
        // So it can drive an indicator rather than merely a yes or no.
        val sharp = bars(brightness = 128, contrast = 90)
        val readings = (0..4).map { sharpnessOf(blur(sharp, it)) }

        println("defocus series: " + readings.joinToString { "%.3f".format(it) })
        for (i in 1 until readings.size) {
            assertThat(readings[i]).isAtMost(readings[i - 1] + 1e-4f)
        }
    }

    @Test
    fun `a flat field marks nothing`() {
        val flat = Plane(width, height, ByteArray(width * height) { 140.toByte() })

        assertThat(sharpnessOf(flat)).isEqualTo(0f)
        assertThat(markedFractionOf(flat)).isEqualTo(0f)
    }

    @Test
    fun `counting marked pixels would have got this backwards`() {
        // Recorded because the obvious metric is wrong in an interesting way.
        // Blur spreads an edge across more pixels rather than removing it, so a
        // defocused frame can have *more* pixels carrying some gradient than a
        // focused one. Squared gradient measures concentration, which is what
        // focus actually is.
        val sharp = bars(brightness = 128, contrast = 90)
        val soft = blur(sharp, passes = 3)

        assertThat(FocusPeaking.focusScore(sharp))
            .isGreaterThan(FocusPeaking.focusScore(soft))
        println("squared-gradient score: sharp %.4f, soft %.4f".format(
            FocusPeaking.focusScore(sharp), FocusPeaking.focusScore(soft)))
    }

    @Test
    fun `a dark subject reads like a bright one at equal sharpness`() {
        // The failure this design exists to avoid. Raw gradient magnitude scales
        // with brightness, so peaking on it lights up every highlight and
        // ignores the shadows -- the opposite of useful when focusing on
        // something dark.
        val bright = bars(brightness = 200, contrast = 60)
        val dark = bars(brightness = 60, contrast = 18)   // same relative contrast

        val brightReading = sharpnessOf(bright)
        val darkReading = sharpnessOf(dark)

        println("equal relative contrast: bright %.3f, dark %.3f"
            .format(brightReading, darkReading))
        assertThat(darkReading).isGreaterThan(brightReading * 0.6f)
    }

    @Test
    fun `only the sharp half of a frame is marked`() {
        // A real scene is not uniformly focused, and peaking has to show where
        // the focal plane actually is.
        val sharp = bars(brightness = 128, contrast = 90)
        val soft = blur(sharp, 3)
        val mixed = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val source = if (x < width / 2) sharp else soft
                mixed[y * width + x] = source.data[y * width + x]
            }
        }

        val mask = ByteArray(width * height)
        FocusPeaking.detect(Plane(width, height, mixed), mask)

        // Summed strength, not a count. The overlay fades with contrast, and
        // counting marked pixels runs into the same trap as the focus metric
        // did: a soft edge is spread across more pixels, so it can mark as many
        // as a sharp one while being visibly fainter.
        var left = 0L
        var right = 0L
        for (y in 2 until height - 2) {
            for (x in 2 until width / 2 - 2) left += (mask[y * width + x].toInt() and 0xFF)
            for (x in width / 2 + 2 until width - 2) {
                right += (mask[y * width + x].toInt() and 0xFF)
            }
        }
        println("peaking strength: sharp half $left, soft half $right")
        assertThat(left).isGreaterThan(right * 2)
    }

    @Test
    fun `noise alone is not mistaken for focus`() {
        // Otherwise peaking is brightest at high ISO, where focusing is hardest.
        val rnd = Random(8)
        val noise = Plane(
            width, height,
            ByteArray(width * height) { (128 + rnd.nextInt(-4, 5)).toByte() },
        )

        val reading = sharpnessOf(noise)

        println("marked fraction of pure noise: %.4f".format(markedFractionOf(noise)))
        assertThat(markedFractionOf(noise)).isLessThan(0.02f)
        assertThat(reading).isLessThan(0.02f)
    }

    @Test
    fun `strength rises with contrast rather than being on or off`() {
        // So the overlay can fade rather than flicker at the threshold.
        val gentle = bars(brightness = 128, contrast = 20)
        val strong = bars(brightness = 128, contrast = 120)

        fun peakStrength(plane: Plane): Int {
            val mask = ByteArray(plane.width * plane.height)
            FocusPeaking.detect(plane, mask)
            return mask.maxOf { it.toInt() and 0xFF }
        }

        assertThat(peakStrength(strong)).isGreaterThan(peakStrength(gentle))
    }

    @Test
    fun `a tiny plane is not a crash`() {
        val tiny = Plane(2, 2, ByteArray(4) { 100.toByte() })
        val mask = ByteArray(4)

        FocusPeaking.detect(tiny, mask)

        assertThat(mask.all { it.toInt() == 0 }).isTrue()
    }

    @Test
    fun `an undersized output is refused rather than overrunning`() {
        val plane = bars(128, 90)

        try {
            FocusPeaking.detect(plane, ByteArray(10))
            throw AssertionError("expected a rejection")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("too small")
        }
    }

    @Test
    fun `the sharper comparison has a dead band`() {
        // Two readings a fraction of a percent apart are the same reading, and
        // an indicator that flickers on noise is worse than none.
        assertThat(FocusPeaking.isSharper(0.201f, 0.200f)).isFalse()
        assertThat(FocusPeaking.isSharper(0.210f, 0.200f)).isTrue()
        assertThat(FocusPeaking.isSharper(0.190f, 0.200f)).isFalse()
    }
}
