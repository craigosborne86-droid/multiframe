package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.Demosaic
import dev.multiframe.camera.pipeline.DevelopParams
import dev.multiframe.camera.pipeline.RawDeveloper
import dev.multiframe.camera.pipeline.ToneCurve
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** What a saturated red is at each stage of the chain: camera space, after the matrix, after the tone. */
class DngPetalProbe {
    @Test
    fun theRedAtEachStage() {
        val dir = System.getProperty("mf.dng.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (file in dir!!.listFiles { f -> f.extension.equals("dng", ignoreCase = true) }!!.sortedBy { it.name }) {
            val dng = DngFile.read(file).decimated()
            val params = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON)
            val metered = 2.0.pow(dng.baselineExposure.toDouble()).toFloat()
            val gain = RawDeveloper.boundedGain(RawDeveloper.autoExposureGain(dng.frame, dng.sensor, dng.color, params), metered)
            val resolved = params.withGain(gain)
            val m = dng.color.matrix
            val rgb = FloatArray(3)
            // Bands of post-matrix red level, so the gradation is visible.
            val bands = arrayOf(0.15f to 0.3f, 0.3f to 0.5f, 0.5f to 0.7f, 0.7f to 1.0f, 1.0f to 1.5f, 1.5f to 9f)
            val cam = Array(bands.size) { DoubleArray(3) }; val mat = Array(bands.size) { DoubleArray(3) }
            val tone = Array(bands.size) { DoubleArray(3) }; val enc = Array(bands.size) { DoubleArray(3) }; val encNoS = Array(bands.size) { DoubleArray(3) }
            val n = IntArray(bands.size)
            var y = 1
            while (y < dng.frame.height - 1) {
                var x = 1
                while (x < dng.frame.width - 1) {
                    Demosaic.pixel(dng.frame, dng.sensor, dng.color, x, y, rgb, dng.shading)
                    val r = (m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2]) * gain
                    val g = (m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2]) * gain
                    val b = (m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2]) * gain
                    if (r > 0.15f && g < 0.35f * r && b < 0.35f * r) {
                        val band = bands.indexOfFirst { r >= it.first && r < it.second }
                        if (band >= 0) {
                            n[band]++
                            cam[band][0] += rgb[0] * gain; cam[band][1] += rgb[1] * gain; cam[band][2] += rgb[2] * gain
                            mat[band][0] += r; mat[band][1] += g; mat[band][2] += b
                            val t = floatArrayOf(r, g, b); ToneCurve.renderLinear(t, resolved)
                            tone[band][0] += t[0]; tone[band][1] += t[1]; tone[band][2] += t[2]
                            for (c in 0..2) {
                                val v = t[c].coerceIn(0f, 1f)
                                val e = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
                                encNoS[band][c] += ToneCurve.renderDisplay(e, resolved.copy(contrast = 0f)) * 255
                                enc[band][c] += ToneCurve.renderDisplay(e, resolved) * 255
                            }
                        }
                    }
                    x += 2
                }
                y += 2
            }
            println("== ${dng.name}: gain %.2f, knee %.2f, white %.2f".format(gain, resolved.shoulderKnee, resolved.shoulderWhite))
            println("   band(R)     n     camera RGB (wb, gained)   after matrix        after shoulder      sRGB no S-curve   sRGB shipped")
            for (i in bands.indices) if (n[i] > 0) {
                fun f(a: DoubleArray) = "%.3f %.3f %.3f".format(a[0] / n[i], a[1] / n[i], a[2] / n[i])
                fun e(a: DoubleArray) = "%3.0f %3.0f %3.0f".format(a[0] / n[i], a[1] / n[i], a[2] / n[i])
                println("   %.2f-%.2f %6d  %s   %s   %s   %s   %s".format(bands[i].first, bands[i].second, n[i], f(cam[i]), f(mat[i]), f(tone[i]), e(encNoS[i]), e(enc[i])))
            }
        }
    }
}
