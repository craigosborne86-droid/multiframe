package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Feature matching, tested end to end: build a scene, transform it by a known
 * amount, and check the recovered transform is the one that was applied.
 *
 * That is the only assertion worth making here. Counting how many corners were
 * found says nothing about whether they were the right ones.
 */
class FeatureMatcherTest {

    private val width = 240
    private val height = 180

    /**
     * A scene with texture everywhere, so features are available across the
     * whole frame rather than in one corner.
     */
    private fun scene(seed: Int = 3): Plane {
        val rnd = Random(seed)
        val data = ByteArray(width * height)
        // Low-frequency background, so it is not pure noise.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = 90 + 40 * sin(x * 0.04) * cos(y * 0.05)
                data[y * width + x] = base.toInt().coerceIn(0, 255).toByte()
            }
        }
        // Scattered high-contrast blobs: the things corners are found on.
        repeat(120) {
            val cx = rnd.nextInt(12, width - 12)
            val cy = rnd.nextInt(12, height - 12)
            val size = rnd.nextInt(2, 5)
            val value = if (rnd.nextBoolean()) 245 else 15
            for (dy in -size..size) {
                for (dx in -size..size) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until width && y in 0 until height) {
                        data[y * width + x] = value.toByte()
                    }
                }
            }
        }
        return Plane(width, height, data)
    }

    /** Resamples a plane through the inverse of a transform. */
    private fun warp(src: Plane, h: Homography): Plane {
        val inverse = h.invert()!!
        val out = ByteArray(src.width * src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val p = inverse.apply(x.toFloat(), y.toFloat())
                val sx = p[0].roundToInt()
                val sy = p[1].roundToInt()
                out[y * src.width + x] = if (
                    sx in 0 until src.width && sy in 0 until src.height
                ) {
                    src.data[sy * src.width + sx]
                } else 0
            }
        }
        return Plane(src.width, src.height, out)
    }

    // ------------------------------------------------------------------

    @Test
    fun `corners are found on corners, not on flat areas or edges`() {
        // A flat field has nothing to lock onto, and a straight edge can slide
        // along itself without the image changing. Only a corner is pinned in
        // both directions, which is what the Shi-Tomasi score measures.
        val flat = Plane(60, 60, ByteArray(60 * 60) { 128.toByte() })
        val edge = Plane(60, 60, ByteArray(60 * 60) { i ->
            if ((i % 60) < 30) 40.toByte() else 200.toByte()
        })
        val corner = Plane(60, 60, ByteArray(60 * 60) { i ->
            if ((i % 60) < 30 && (i / 60) < 30) 40.toByte() else 200.toByte()
        })

        val flatScore = FeatureMatcher.cornerStrength(flat, 30, 30)
        val edgeScore = FeatureMatcher.cornerStrength(edge, 30, 30)
        val cornerScore = FeatureMatcher.cornerStrength(corner, 30, 30)

        println("corner response: flat %.1f, edge %.1f, corner %.1f"
            .format(flatScore, edgeScore, cornerScore))

        assertThat(flatScore).isLessThan(1f)
        assertThat(edgeScore).isLessThan(cornerScore)
        assertThat(cornerScore).isGreaterThan(100f)
    }

    @Test
    fun `corners are spread across the frame rather than clustered`() {
        // A homography fitted to points from one corner of the image
        // extrapolates terribly across the rest of it.
        val corners = FeatureMatcher.detect(scene())
        assertThat(corners).isNotEmpty()

        val quadrants = IntArray(4)
        for (c in corners) {
            val q = (if (c.x > width / 2) 1 else 0) + (if (c.y > height / 2) 2 else 0)
            quadrants[q]++
        }
        println("corners per quadrant: ${quadrants.toList()}")

        for (count in quadrants) assertThat(count).isGreaterThan(0)
    }

    @Test
    fun `a flat frame yields no descriptors to match on`() {
        val flat = Plane(120, 120, ByteArray(120 * 120) { 100.toByte() })

        assertThat(FeatureMatcher.detect(flat)).isEmpty()
        assertThat(FeatureMatcher.match(flat, flat)).isEmpty()
    }

    // ------------------------------------------------------------------
    // Recovering known motion.
    // ------------------------------------------------------------------

    @Test
    fun `recovers a translation between two frames`() {
        val a = scene()
        val truth = Homography.translation(14.0, -9.0)
        val b = warp(a, truth)

        val fit = FeatureMatcher.register(a, b)

        assertThat(fit).isNotNull()
        println("translation: %d inliers, ratio %.2f"
            .format(fit!!.inliers.size, fit.inlierRatio))
        val moved = fit.homography.apply(120f, 90f)
        assertThat(moved[0]).isWithin(1.5f).of(134f)
        assertThat(moved[1]).isWithin(1.5f).of(81f)
    }

    @Test
    fun `recovers a rotation, which the burst aligner cannot represent`() {
        // This is the case that makes a sweep different from a burst.
        val a = scene()
        val angle = 0.06
        val cx = width / 2.0
        val cy = height / 2.0
        val truth = Homography(
            doubleArrayOf(
                cos(angle), -sin(angle), cx - cx * cos(angle) + cy * sin(angle),
                sin(angle), cos(angle), cy - cx * sin(angle) - cy * cos(angle),
                0.0, 0.0, 1.0,
            )
        )
        val b = warp(a, truth)

        val fit = FeatureMatcher.register(a, b)

        assertThat(fit).isNotNull()
        println("rotation: %d inliers, ratio %.2f".format(fit!!.inliers.size, fit.inlierRatio))
        for (point in listOf(60f to 45f, 180f to 135f, 120f to 90f)) {
            val expected = truth.apply(point.first, point.second)
            val actual = fit.homography.apply(point.first, point.second)
            assertThat(actual[0]).isWithin(2.5f).of(expected[0])
            assertThat(actual[1]).isWithin(2.5f).of(expected[1])
        }
    }

    @Test
    fun `matches survive an exposure change between frames`() {
        // Auto-exposure moves during a sweep. Normalised patches are why this
        // works: they compare shape, not brightness.
        val a = scene()
        val truth = Homography.translation(10.0, 6.0)
        val shifted = warp(a, truth)
        val brighter = Plane(width, height, ByteArray(width * height) { i ->
            val v = (shifted.data[i].toInt() and 0xFF)
            ((v * 0.75f) + 55f).toInt().coerceIn(0, 255).toByte()
        })

        val fit = FeatureMatcher.register(a, brighter)

        assertThat(fit).isNotNull()
        val moved = fit!!.homography.apply(120f, 90f)
        assertThat(moved[0]).isWithin(2f).of(130f)
        assertThat(moved[1]).isWithin(2f).of(96f)
    }

    // ------------------------------------------------------------------
    // Refusing.
    // ------------------------------------------------------------------

    @Test
    fun `unrelated frames are refused rather than stitched`() {
        // The failure that matters. A confident wrong transform tears the
        // mosaic; an honest refusal can be reported to the user.
        val a = scene(seed = 1)
        val b = scene(seed = 99)

        val fit = FeatureMatcher.register(a, b)

        assertThat(fit == null || !fit.isReliable).isTrue()
    }

    @Test
    fun `a repeating pattern does not produce confident nonsense`() {
        // Repeated structure is where matching goes wrong on real buildings:
        // every window looks like every other window. The ratio test exists
        // for this case.
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val on = (x / 8) % 2 == 0 && (y / 8) % 2 == 0
                data[y * width + x] = if (on) 220.toByte() else 30.toByte()
            }
        }
        val a = Plane(width, height, data)
        // Shifted by exactly one period, so every feature has a perfect but
        // wrong partner as well as a perfect right one.
        val b = warp(a, Homography.translation(16.0, 16.0))

        val fit = FeatureMatcher.register(a, b)

        // Either it refuses, or it finds the true shift. What it must not do
        // is return something else with confidence.
        if (fit != null && fit.isReliable) {
            val moved = fit.homography.apply(120f, 90f)
            val dx = moved[0] - 120f
            val dy = moved[1] - 90f
            println("repeating pattern resolved to (%.1f, %.1f)".format(dx, dy))
            assertThat(dx % 16f).isWithin(1.5f).of(0f)
            assertThat(dy % 16f).isWithin(1.5f).of(0f)
        }
    }
}
