package dev.multiframe.camera.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Kelvin/tint conversion against the outputs of the implementation it replaced
 * (`white-balance-fixture.csv`, 2000–12000 K by 100, tint −100..100 by 5, recorded
 * before the replacement), and its isotemperature table against an independent
 * fit to the Planckian locus.
 */
class WhiteBalanceConversionTest {
    private data class Fixture(val kelvin: Int, val tint: Int, val x: Double, val y: Double)

    private val fixtures: List<Fixture> by lazy {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("white-balance-fixture.csv"))
        stream.bufferedReader().readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split(',')
            Fixture(f[0].toInt(), f[1].toInt(), f[2].toDouble(), f[3].toDouble())
        }
    }

    @Test fun agreesWithThePreviousConversionWithinHalfADeltaE00() {
        assertEquals(101 * 41, fixtures.size)
        var worst = 0.0
        for (f in fixtures) {
            val (x, y) = CalibratedWhiteBalance.chromaticity(f.kelvin, f.tint)
            val d = deltaE00(lab(f.x, f.y), lab(x, y))
            worst = maxOf(worst, d)
            // Away from the corrected 325-mired row (300–350 mired) the two agree exactly.
            if (f.kelvin < 2857 || f.kelvin > 3333) assertEquals("${f.kelvin} K tint ${f.tint}", 0.0, d, 1e-9)
        }
        println("worst ΔE00 against the previous conversion: $worst")
        assertTrue("worst ΔE00 $worst", worst < 0.5)
    }

    @Test fun everyIsothermSitsOnTheLocusAndIsNormalToIt() {
        for (row in Isotherms.rows.filter { it[0] >= 67.0 }) { // Krystek's fit holds to 15000 K
            val kelvin = 1e6 / row[0]
            val (u, v) = krystek(kelvin)
            assertEquals("u at ${row[0]} mired", u, row[1], 1.5e-4)
            assertEquals("v at ${row[0]} mired", v, row[2], 1.5e-4)
            val h = kelvin * 1e-4
            val (u1, v1) = krystek(kelvin + h); val (u0, v0) = krystek(kelvin - h)
            val normalSlope = -(u1 - u0) / (v1 - v0)
            val degrees = abs(atan(row[3]) - atan(normalSlope)) * 180 / PI
            assertTrue("isotherm at ${row[0]} mired is $degrees° off normal", degrees < 1.5)
        }
        // The reprinted 0.24702 at 325 mired is ten times further off than any row.
        assertTrue(abs(krystek(1e6 / 325).first - 0.24702) > 8e-4)
    }

    @Test fun tintIsADistanceAlongTheIsothermTowardsGreen() {
        for (kelvin in listOf(2000.0, 3077.0, 5000.0, 6504.0, 12000.0)) {
            val (u0, v0) = Isotherms.uv(kelvin, 0.0)
            val (u1, v1) = Isotherms.uv(kelvin, 100 / 3000.0)
            assertEquals(100 / 3000.0, hypot(u1 - u0, v1 - v0), 1e-12)
            assertTrue(v1 > v0)
        }
        val (x, y) = CalibratedWhiteBalance.chromaticity(6504, 0)
        assertEquals(0.3135, x, 1e-3); assertEquals(0.3237, y, 1e-3) // Planckian 6504 K, CIE 15
    }

    /** Krystek, J. Opt. Soc. Am. A 2(11), 1985: the Planckian locus in CIE 1960 uv, 1000–15000 K. */
    private fun krystek(t: Double): Pair<Double, Double> =
        (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t) to
            (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)

    /** CIELAB of chromaticity (x, y) at Y = 1 against D65: chroma differences are largest at full luminance. */
    private fun lab(x: Double, y: Double): DoubleArray {
        val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val white = doubleArrayOf(0.95047, 1.0, 1.08883)
        val f = DoubleArray(3) {
            val r = xyz[it] / white[it]
            if (r > 216.0 / 24389) cbrt(r) else (24389.0 / 27 * r + 16) / 116
        }
        return doubleArrayOf(116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2]))
    }

    /** CIEDE2000 (Sharma, Wu & Dalal, Color Res. Appl. 30(1), 2005), kL = kC = kH = 1. */
    private fun deltaE00(a: DoubleArray, b: DoubleArray): Double {
        fun deg(r: Double) = r * 180 / PI
        fun rad(d: Double) = d * PI / 180
        val c7 = ((hypot(a[1], a[2]) + hypot(b[1], b[2])) / 2).pow(7)
        val g = 0.5 * (1 - sqrt(c7 / (c7 + 25.0.pow(7))))
        val a1 = (1 + g) * a[1]; val a2 = (1 + g) * b[1]
        val c1 = hypot(a1, a[2]); val c2 = hypot(a2, b[2])
        fun hue(ap: Double, bp: Double) = if (ap == 0.0 && bp == 0.0) 0.0 else (deg(atan2(bp, ap)) + 360) % 360
        val h1 = hue(a1, a[2]); val h2 = hue(a2, b[2])
        val dL = b[0] - a[0]; val dC = c2 - c1
        val dh = when {
            c1 * c2 == 0.0 -> 0.0
            abs(h2 - h1) <= 180 -> h2 - h1
            h2 - h1 > 180 -> h2 - h1 - 360
            else -> h2 - h1 + 360
        }
        val dH = 2 * sqrt(c1 * c2) * sin(rad(dh / 2))
        val lMean = (a[0] + b[0]) / 2; val cMean = (c1 + c2) / 2
        val hMean = when {
            c1 * c2 == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(rad(hMean - 30)) + 0.24 * cos(rad(2 * hMean)) +
            0.32 * cos(rad(3 * hMean + 6)) - 0.20 * cos(rad(4 * hMean - 63))
        val sl = 1 + 0.015 * (lMean - 50).pow(2) / sqrt(20 + (lMean - 50).pow(2))
        val sc = 1 + 0.045 * cMean
        val sh = 1 + 0.015 * cMean * t
        val cm7 = cMean.pow(7)
        val rt = -2 * sqrt(cm7 / (cm7 + 25.0.pow(7))) * sin(rad(60 * exp(-((hMean - 275) / 25).pow(2))))
        return sqrt((dL / sl).pow(2) + (dC / sc).pow(2) + (dH / sh).pow(2) + rt * (dC / sc) * (dH / sh))
    }
}
