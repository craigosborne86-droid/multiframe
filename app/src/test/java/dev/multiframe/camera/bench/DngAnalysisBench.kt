package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.BilateralGrid
import dev.multiframe.camera.pipeline.Demosaic
import dev.multiframe.camera.pipeline.DevelopParams
import dev.multiframe.camera.pipeline.RawDeveloper
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * What the develop sees before it renders: the gain it chose, the scene's
 * luminance in stops, and how much of the picture the colour matrix pushes
 * out of the sRGB gamut. Skipped unless run like [DngDevelopBench]; reads the
 * same `mf.dng.dir`.
 */
class DngAnalysisBench {
    @Test
    fun whatTheDevelopSees() {
        val dir = System.getProperty("mf.dng.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Pmf.dng.dir; the analysis is not run", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.extension.equals("dng", ignoreCase = true) }!!.sortedBy { it.name }
        for (file in files) {
            val dng = DngFile.read(file).decimated()
            val params = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON)
            val metered = 2.0.pow(dng.baselineExposure.toDouble()).toFloat()
            val auto = RawDeveloper.autoExposureGain(dng.frame, dng.sensor, dng.color, params)
            val gain = RawDeveloper.boundedGain(auto, metered)
            val m = dng.color.matrix
            println("== ${dng.name}: baseline %+.2f (metered gain %.2f), auto %.2f, bounded %.2f, gains %s".format(
                dng.baselineExposure, metered, auto, gain, dng.color.gains.joinToString(" ") { "%.3f".format(it) }))
            println("   matrix [%s]".format(m.joinToString(" ") { "%+.3f".format(it) }))

            val rgb = FloatArray(3)
            val stops = IntArray(20) // -14 .. +5 stops relative to nominal white after gain
            var negG = 0; var negB = 0; var negAny = 0; var rOver = 0; var n = 0
            var satHi = 0
            val purityHist = IntArray(10) // by 0.1 of purity, for pixels with max > 0.03
            val purityByHue = Array(6) { IntArray(3) } // hue sextant x (purity >0.7, >0.8, >0.9)
            val w = dng.frame.width; val h = dng.frame.height
            val resolved = params.withGain(gain)
            val grid = BilateralGrid.fromFrame(dng.frame, dng.sensor, dng.color, resolved, dng.shading)
            var liftSum = 0.0; var liftN = 0; var maxLift = 0f; var minLift = 9f
            var y = 1
            while (y < h - 1) {
                var x = 1
                while (x < w - 1) {
                    Demosaic.pixel(dng.frame, dng.sensor, dng.color, x, y, rgb, dng.shading)
                    val r = (m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2]) * gain
                    val g = (m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2]) * gain
                    val b = (m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2]) * gain
                    n++
                    if (g < 0f) negG++
                    if (b < 0f) negB++
                    if (r < 0f || g < 0f || b < 0f) negAny++
                    if (r > 1f) rOver++
                    val mx = max(r, max(g, b)); val mn = minOf(r, g, b)
                    if (mx > 0.05f && (mx - mn) / mx > 0.85f) satHi++
                    if (mx > 0.03f) {
                        val pur = ((mx - mn) / mx).coerceIn(0f, 0.999f)
                        purityHist[(pur * 10).toInt()]++
                        if (pur > 0.7f) {
                            // Hue sextant from which channel peaks and which is second.
                            val sext = when {
                                r >= g && g >= b -> 0; r >= b && b > g -> 5; g > r && r >= b -> 1
                                g >= b && b > r -> 2; b > g && g > r -> 3; else -> 4
                            }
                            purityByHue[sext][0]++
                            if (pur > 0.8f) purityByHue[sext][1]++
                            if (pur > 0.9f) purityByHue[sext][2]++
                        }
                    }
                    val lum = BilateralGrid.luminance(max(r, 0f), max(g, 0f), max(b, 0f))
                    val s = (ln(max(lum, 1e-6f).toDouble()) / ln(2.0)).toInt() + 14
                    stops[s.coerceIn(0, 19)]++
                    val k = grid.gain(x, y, lum, resolved.localTone)
                    val lk = (ln(k.toDouble()) / ln(2.0)).toFloat()
                    liftSum += lk; liftN++; if (lk > maxLift) maxLift = lk; if (lk < minLift) minLift = lk
                    x += 3
                }
                y += 3
            }
            println("   luminance after gain, % of pixels per stop from -14 to +5: " +
                stops.mapIndexed { i, c -> "${i - 14}:%.1f".format(100.0 * c / n) }.joinToString(" "))
            println("   out of gamut: G<0 %.1f%%, B<0 %.1f%%, any<0 %.1f%%, R>1 %.1f%%, saturation>0.85 %.1f%%".format(
                100.0 * negG / n, 100.0 * negB / n, 100.0 * negAny / n, 100.0 * rOver / n, 100.0 * satHi / n))
            println("   purity histogram (0.0-1.0 by tenths, % of lit pixels): " + purityHist.joinToString(" ") { "%.1f".format(100.0 * it / n) })
            println("   purity >0.7/>0.8/>0.9 by hue [R-Y, Y-G, G-C, C-B, B-M, M-R] (% of pixels): " +
                purityByHue.joinToString(" | ") { h -> h.joinToString("/") { "%.1f".format(100.0 * it / n) } })
            println("   local tone at %.2f: pivot %.2f stops, gain mean %+.2f, range %+.2f .. %+.2f stops".format(
                resolved.localTone, grid.pivot, liftSum / liftN, minLift, maxLift))
            println("   grid: cell %d, mean %.2f, median %.2f, bright end (p92) %.2f, p10 %.2f, p5 %.2f, p25 %.2f, p75 %.2f; range p92-p10 %.2f, p92-mean %.2f".format(
                grid.cell, grid.pivot, grid.percentile(0.5f), grid.brightEnd, grid.percentile(0.10f), grid.percentile(0.05f), grid.percentile(0.25f), grid.percentile(0.75f),
                grid.brightEnd - grid.percentile(0.10f), grid.brightEnd - grid.pivot))
            println("   shadow lift earned at 1.0/3.5: %.2f stops".format(grid.ceiling(1f, 3.5f)))
        }
    }
}
