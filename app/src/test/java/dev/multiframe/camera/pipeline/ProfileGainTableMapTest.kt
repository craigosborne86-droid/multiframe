package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.multiframe.camera.bench.DngFile
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The gain table map against the operator it was built from.
 *
 * [Evaluator] is a reader of the tag written from the specification's text
 * (DNG 1.6 and 1.7.1, "ProfileGainTableMap" / "ProfileGainTableMap2") and
 * nothing of ours: it parses the bytes, places the map in half-pixel relative
 * coordinates, interpolates the four tables bilinearly, indexes by
 * `clamp(weights . (R, G, B, min, max)) ^ gamma * N` and returns the gain. It
 * is fed what a converter has -- the pixel's linear RIMM colour after
 * BaselineExposure -- and compared with what the develop's operator gives the
 * same pixel ([BilateralGrid.gain] on its post-purity luminance).
 *
 * The tolerance, measured on the September fixtures and stated in
 * REFERENCE §3.9, over the pixels a converter can index (RIMM luminance
 * under one), for the 1.7 tag a converter applies: the encoding -- the
 * operator asked at the tag's own luminance -- within [ENCODING_MEDIAN_STOPS]
 * at the median and [ENCODING_P99_STOPS] at the 99th percentile; the total
 * -- the operator as the develop asks it, after purity compression and on
 * sRGB luminance -- within [TOTAL_MEDIAN_STOPS] and [TOTAL_P99_STOPS]. The
 * 1.6 tag is reported, not held: without a gamma its first sample is
 * seven stops under white, and the tower's shadows are under that.
 */
class ProfileGainTableMapTest {

    /** A reader of the tag from the specification alone. */
    class Evaluator(bytes: ByteArray, version2: Boolean, order: ByteOrder = ByteOrder.BIG_ENDIAN) {
        val pointsV: Int; val pointsH: Int; val pointsN: Int
        private val spacingV: Double; private val spacingH: Double
        private val originV: Double; private val originH: Double
        private val weights = FloatArray(5)
        val gamma: Float
        private val gains: FloatArray

        init {
            val b = ByteBuffer.wrap(bytes).order(order)
            pointsV = b.getInt(); pointsH = b.getInt()
            spacingV = b.getDouble(); spacingH = b.getDouble()
            originV = b.getDouble(); originH = b.getDouble()
            pointsN = b.getInt()
            for (i in 0 until 5) weights[i] = b.getFloat()
            if (version2) {
                check(b.getInt() == 3) { "float gains only" }
                gamma = b.getFloat(); b.getFloat(); b.getFloat()
            } else {
                gamma = 1f
            }
            gains = FloatArray(pointsV * pointsH * pointsN) { b.getFloat() }
            check(!b.hasRemaining()) { "the tag's size is 64 or 80 plus its gains" }
            check(gains.all { it >= 0f }) { "gains are non-negative" }
        }

        /** The gain at pixel (x, y) of a [width] by [height] image, for linear RIMM [rgb]. */
        fun gain(x: Int, y: Int, width: Int, height: Int, rgb: FloatArray): Float {
            val v = ((y + 0.5) / height - originV) / spacingV
            val h = ((x + 0.5) / width - originH) / spacingH
            val fv = v.coerceIn(0.0, (pointsV - 1).toDouble()); val fh = h.coerceIn(0.0, (pointsH - 1).toDouble())
            val v0 = fv.toInt(); val h0 = fh.toInt()
            val v1 = min(v0 + 1, pointsV - 1); val h1 = min(h0 + 1, pointsH - 1)
            val tv = (fv - v0).toFloat(); val th = (fh - h0).toFloat()
            val input = (weights[0] * rgb[0] + weights[1] * rgb[1] + weights[2] * rgb[2] +
                weights[3] * minOf(rgb[0], rgb[1], rgb[2]) + weights[4] * maxOf(rgb[0], rgb[1], rgb[2])).coerceIn(0f, 1f)
            val index = (input.pow(gamma) * pointsN).coerceIn(0f, (pointsN - 1).toFloat())
            val n0 = index.toInt(); val n1 = min(n0 + 1, pointsN - 1); val tn = index - n0
            fun table(pv: Int, ph: Int): Float {
                val base = (pv * pointsH + ph) * pointsN
                return gains[base + n0] * (1 - tn) + gains[base + n1] * tn
            }
            return (table(v0, h0) * (1 - th) + table(v0, h1) * th) * (1 - tv) +
                (table(v1, h0) * (1 - th) + table(v1, h1) * th) * tv
        }
    }

    @Test
    fun `at a grid node the tag reads back the operator's gain at each table sample`() {
        // 408 rows: a cell of 17, odd, so every node sits on a pixel.
        val w = 612; val h = 408
        val data = ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            // A bright window in a dim room, with texture: a scene with range to earn a lift.
            val window = x in 350..520 && y in 60..220
            (64 + (if (window) 900 else 12 + (x * 7 + y * 3) % 20)).toShort()
        }
        val frame = BayerFrame(w, h, data)
        val sensor = SensorProfile(intArrayOf(64, 64, 64, 64), 1023, intArrayOf(0, 1, 1, 2))
        val params = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON).withGain(2f)
        val grid = BilateralGrid.fromFrame(frame, sensor, ColorProfile.NEUTRAL, params)
        assertThat(grid.ceiling(params.shadowLift, params.shadowLiftMax)).isGreaterThan(0f)
        val baseline = 0.5f
        val table = ProfileGainTableMap.tables(grid, w, h, params, baseline, gamma = ProfileGainTableMap.GAMMA)
        val tag = Evaluator(ProfileGainTableMap.encode(table, version2 = true), version2 = true)
        assertThat(tag.pointsH).isEqualTo(grid.cellsX)
        assertThat(tag.pointsV).isEqualTo(grid.cellsY)
        val toDevelop = params.exposureGain / 2f.pow(baseline)
        for (cy in 0 until grid.cellsY) for (cx in 0 until grid.cellsX) {
            // Node (cx, cy) is at pixel (i + 0.5) * cell - 0.5, an integer when the cell is odd.
            val px = cx * grid.cell + grid.cell / 2
            val py = cy * grid.cell + grid.cell / 2
            if (px >= w || py >= h) continue
            for (n in intArrayOf(8, 40, 90, 127)) {
                val u = (n.toFloat() / ProfileGainTableMap.POINTS).pow(1f / ProfileGainTableMap.GAMMA)
                val rimm = floatArrayOf(u, u, u) // a neutral: its RIMM luminance is u
                val expected = grid.gain(px, py, u * toDevelop, params.localTone, params.shadowLift, params.shadowLiftMax)
                assertThat(tag.gain(px, py, w, h, rimm)).isWithin(2e-3f * expected).of(expected)
            }
        }
    }

    @Test
    fun `the two tags are the sizes the specification gives`() {
        val grid = BilateralGrid.forImage(320, 240).apply { splat(1, 1, 0.5f); finish() }
        val p = DevelopParams(localTone = 0.15f).withGain(1f)
        val one = ProfileGainTableMap.encode(ProfileGainTableMap.tables(grid, 320, 240, p, 0f, 1f), version2 = false)
        val two = ProfileGainTableMap.encode(ProfileGainTableMap.tables(grid, 320, 240, p, 0f, ProfileGainTableMap.GAMMA), version2 = true)
        val cells = grid.cellsX * grid.cellsY * ProfileGainTableMap.POINTS
        assertThat(one.size).isEqualTo(64 + 4 * cells)
        assertThat(two.size).isEqualTo(80 + 4 * cells)
    }

    /**
     * The desk proof on DngDevelopBench's inputs: the September fixtures,
     * half scale, the shipped rendering, the develop's gain bounded by the
     * meter the DNG's BaselineExposure records.
     */
    @Test
    fun `on the September fixtures a reader of the tag reproduces the operator's lift`() {
        val dir = System.getProperty("mf.dng.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Pmf.dng.dir; the desk proof is not run", dir != null && dir.isDirectory)
        val names = REPRESENTATIVE.filter { File(dir, it).isFile }
        assumeTrue("none of the representative fixtures in $dir", names.isNotEmpty())
        val report = StringBuilder("fixture, tag, pixels, encoding median, encoding p99, encoding max, total median, total p99, total max, ceiling stops\n")
        val failures = ArrayList<String>()
        for (name in names) {
            val dng = DngFile.read(File(dir, name)).decimated()
            val params = DevelopParams(
                localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON,
                meteredGain = 2f.pow(dng.baselineExposure),
            ).adaptedTo(dng.iso)
            val (one, two) = ProfileGainTableMap.forCapture(dng.frame, dng.sensor, dng.color, params, dng.shading, dng.baselineExposure)!!
            val resolved = params.withGain(RawDeveloper.resolveGain(dng.frame, dng.sensor, dng.color, params))
            val grid = BilateralGrid.fromFrame(dng.frame, dng.sensor, dng.color, resolved, dng.shading)
            val toDevelop = resolved.exposureGain / 2f.pow(dng.baselineExposure)
            for ((label, tag) in listOf("1.6" to Evaluator(one, false), "1.7" to Evaluator(two, true))) {
                val (encoding, total) = errors(dng, resolved, grid, toDevelop, tag)
                encoding.sort(); total.sort()
                fun q(a: FloatArray, f: Double) = a[min(a.size - 1, (a.size * f).toInt())]
                report.append("%s, %s, %d, %.4f, %.4f, %.4f, %.4f, %.4f, %.4f, %.2f\n".format(name, label, total.size,
                    q(encoding, 0.5), q(encoding, 0.99), encoding.last(), q(total, 0.5), q(total, 0.99), total.last(),
                    grid.ceiling(resolved.shadowLift, resolved.shadowLiftMax)))
                if (label == "1.7") {
                    if (q(encoding, 0.5) >= ENCODING_MEDIAN_STOPS) failures += "$name encoding median ${q(encoding, 0.5)}"
                    if (q(encoding, 0.99) >= ENCODING_P99_STOPS) failures += "$name encoding p99 ${q(encoding, 0.99)}"
                    if (q(total, 0.5) >= TOTAL_MEDIAN_STOPS) failures += "$name total median ${q(total, 0.5)}"
                    if (q(total, 0.99) >= TOTAL_P99_STOPS) failures += "$name total p99 ${q(total, 0.99)}"
                }
            }
        }
        println(report)
        System.getProperty("mf.dngwriter.out")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() }.resolve("pgtm-tolerance.csv").writeText(report.toString()) }
        assertWithMessage(failures.joinToString("; ")).that(failures).isEmpty()
    }

    /**
     * |log2(tag / operator)| at every third pixel a converter can index, two
     * ways. Encoding: the operator asked at the luminance the tag is indexed
     * by, so only the map's sampling separates them -- what the tag can
     * represent. Total: the operator as the develop asks it, after purity
     * compression and on sRGB luminance -- what a converter will differ by.
     */
    private fun errors(dng: DngFile, params: DevelopParams, grid: BilateralGrid, toDevelop: Float, tag: Evaluator): Pair<FloatArray, FloatArray> {
        val w = dng.frame.width; val h = dng.frame.height
        val m = dng.color.matrix
        val rgb = FloatArray(3)
        val encoding = ArrayList<Float>(w * h / 9)
        val total = ArrayList<Float>(w * h / 9)
        val ln2 = ln(2.0).toFloat()
        fun stops(a: Float, b: Float) = abs(ln(max(a, 1e-6f) / b) / ln2)
        var y = 1
        while (y < h) {
            var x = 1
            while (x < w) {
                Demosaic.pixel(dng.frame, dng.sensor, dng.color, x, y, rgb, dng.shading)
                val r = (m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2]) * params.exposureGain
                val g = (m[3] * rgb[0] + m[4] * rgb[1] + m[5] * rgb[2]) * params.exposureGain
                val b = (m[6] * rgb[0] + m[7] * rgb[1] + m[8] * rgb[2]) * params.exposureGain
                // What a converter holds: the linear colour at BaselineExposure, in RIMM.
                val rimm = srgbToRimm(r / toDevelop, g / toDevelop, b / toDevelop)
                val yRimm = ProfileGainTableMap.RIMM_LUMINANCE.let { wt -> wt[0] * rimm[0] + wt[1] * rimm[1] + wt[2] * rimm[2] }
                if (yRimm in 1e-4f..1f) {
                    val theirs = tag.gain(x, y, w, h, rimm)
                    encoding.add(stops(theirs, grid.gain(x, y, yRimm * toDevelop, params.localTone, params.shadowLift, params.shadowLiftMax)))
                    // What the develop does: purity compression, then the operator on that luminance.
                    val dev = floatArrayOf(r, g, b)
                    ToneCurve.compressPurity(dev, params.purityThreshold, params.purityCeiling)
                    total.add(stops(theirs, grid.gain(x, y, BilateralGrid.luminance(dev[0], dev[1], dev[2]), params.localTone, params.shadowLift, params.shadowLiftMax)))
                }
                x += 3
            }
            y += 3
        }
        return encoding.toFloatArray() to total.toFloatArray()
    }

    private companion object {
        val REPRESENTATIVE = listOf(
            "MF_20260917_145908_zsl_10f.dng", // backlit tower
            "MF_20260917_145555_zsl_10f.dng", // flower
            "MF_20260917_104934_zsl_10f.dng", // dog
            "MF_20260917_145003_zsl_10f.dng", // street
            "MF_20260915_154430_sr2x_16f.dng", // ISO 918
        )
        /** What the 1.7 tag can represent: measured at most 0.0008 and 0.021 stop. */
        const val ENCODING_MEDIAN_STOPS = 0.002f
        const val ENCODING_P99_STOPS = 0.03f
        /** What a converter differs by, the colour path included: measured at most 0.0011 and 0.12 stop. */
        const val TOTAL_MEDIAN_STOPS = 0.005f
        const val TOTAL_P99_STOPS = 0.15f

        /** Linear sRGB (D65) to linear ROMM RGB (D50): sRGB to XYZ, Bradford to D50, XYZ to ROMM. */
        private val SRGB_TO_RIMM: FloatArray = run {
            val srgbToXyz = doubleArrayOf(0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750, 0.0193339, 0.1191920, 0.9503041)
            val bradford = doubleArrayOf(1.0478112, 0.0228866, -0.0501270, 0.0295424, 0.9904844, -0.0170491, -0.0092345, 0.0150436, 0.7521316)
            val rommToXyz = doubleArrayOf(0.7976749, 0.1351917, 0.0313534, 0.2880402, 0.7118741, 0.0000857, 0.0, 0.0, 0.8252100)
            fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> (0..2).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }
            fun inv(a: DoubleArray): DoubleArray {
                val p = a[0]; val q = a[1]; val r = a[2]; val s = a[3]; val t = a[4]; val u = a[5]; val v = a[6]; val w = a[7]; val z = a[8]
                val det = p * (t * z - u * w) - q * (s * z - u * v) + r * (s * w - t * v)
                return doubleArrayOf(t * z - u * w, r * w - q * z, q * u - r * t, u * v - s * z, p * z - r * v, r * s - p * u, s * w - t * v, q * v - p * w, p * t - q * s).map { it / det }.toDoubleArray()
            }
            mul(inv(rommToXyz), mul(bradford, srgbToXyz)).map { it.toFloat() }.toFloatArray()
        }

        fun srgbToRimm(r: Float, g: Float, b: Float): FloatArray {
            val m = SRGB_TO_RIMM
            return floatArrayOf(m[0] * r + m[1] * g + m[2] * b, m[3] * r + m[4] * g + m[5] * b, m[6] * r + m[7] * g + m[8] * b)
        }
    }
}
