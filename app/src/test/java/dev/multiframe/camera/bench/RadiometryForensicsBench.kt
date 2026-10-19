package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Uses quarter-DN fixed-point inputs to measure the unmodified develop at sub-DN
 * pedestal errors. A/A scale equivalence is required before reporting any result.
 */
class RadiometryForensicsBench {
    @Test fun blackSensitivityAndHaloStageIsolation() {
        val root = System.getProperty("mf.burst.out")?.let(::File)
        val input = System.getProperty("mf.dng.dir")?.let(::File)
        assumeTrue(root != null && input != null)
        val dng = DngFile.read(input!!.listFiles()!!.first { it.name.startsWith("MF") && it.extension == "dng" })
        require(dng.sensor.whiteLevel * 4 < 65536)
        val out = File(root!!, "radiometry-synthetic").apply { mkdirs() }
        val w = 256; val h = 192
        val levels = doubleArrayOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0, 1024.0, 2048.0)
        val sensor = dng.sensor
        val raw = ShortArray(w * h) { i ->
            val x = i % w; val y = i / w; val site = (y % 2) * 2 + x % 2
            val c = sensor.cfaPattern[site]
            val gain = when (c) { 0 -> dng.color.gains[0]; 2 -> dng.color.gains[3]; else -> dng.color.gains[if (y % 2 == 0) 1 else 2] }
            (sensor.blackAt(x, y) + levels[y / 64 * 4 + x / 64] / gain).roundToInt().toShort()
        }
        val scaled = ShortArray(raw.size) { ((raw[it].toInt() and 65535) * 4).toShort() }
        val scaledSensor = SensorProfile(IntArray(4) { sensor.blackLevel[it] * 4 }, sensor.whiteLevel * 4, sensor.cfaPattern)
        val params = DevelopParams(localTone = DevelopParams.LOCAL_TONE_ON,
            shadowLift = DevelopParams.SHADOW_LIFT_ON, exposureGain = 1f)
        val aa = RawDeveloper.develop(BayerFrame(w, h, raw.copyOf()), sensor, dng.color, params)
        val ab = RawDeveloper.develop(BayerFrame(w, h, scaled.copyOf()), scaledSensor, dng.color, params)
        val maxError = aa.indices.maxOf { i -> (0..2).maxOf { c -> abs((aa[i] shr (8 * c) and 255) - (ab[i] shr (8 * c) and 255)) } }
        assertTrue("Fixed-point scale must preserve baseline develop: $maxError", maxError <= 1)
        File(out, "scale-null.txt").writeText("maxDisplayCodeError=$maxError\n")
        File(out, "black-sensitivity.csv").bufferedWriter().use { report ->
            report.appendLine("iso,errorSite,errorDn,patchSignalDn,stage,meanR,meanG,meanB,sdRminusG,sdBminusG")
            for (iso in listOf(24, 376, 6022)) for (site in -1..3) for (delta in listOf(-8, -4, -2, -1, 0, 1, 2, 4, 8)) {
                val s = scaledSensor.copy(blackLevel = IntArray(4) { scaledSensor.blackLevel[it] + if (site == -1 || site == it) delta else 0 })
                val collector = object : DevelopObserver {
                    val values = DevelopObserver.LinearStage.entries.associateWith { FloatArray(w * h * 3) }
                    val display = HashMap<DevelopObserver.DisplayStage, IntArray>()
                    override fun linear(stage: DevelopObserver.LinearStage, x: Int, y: Int, rgb: FloatArray) {
                        val at = (y * w + x) * 3
                        for (c in 0..2) values.getValue(stage)[at + c] = rgb[c]
                    }
                    override fun display(stage: DevelopObserver.DisplayStage, pixels: IntArray, width: Int, height: Int) {
                        display[stage] = pixels.copyOf()
                    }
                }
                RawDeveloper.develop(BayerFrame(w, h, scaled.copyOf()), s, dng.color, params.adaptedTo(iso), observer = collector)
                for (patch in 0 until 8) {
                    val x0 = patch % 4 * 64 + 16; val y0 = patch / 4 * 64 + 16
                    fun measure(stage: String, sample: (Int, Int) -> Double) {
                        val sum = DoubleArray(3); var cr = 0.0; var cb = 0.0; var cr2 = 0.0; var cb2 = 0.0
                        for (y in y0 until y0 + 32) for (x in x0 until x0 + 32) {
                            val at = y * w + x; val rgb = DoubleArray(3) { sample(at, it) }
                            for (c in 0..2) sum[c] += rgb[c]
                            val r = rgb[0] - rgb[1]; val b = rgb[2] - rgb[1]
                            cr += r; cb += b; cr2 += r * r; cb2 += b * b
                        }
                        report.appendLine("$iso,$site,${delta / 4.0},${levels[patch]},$stage,${sum.joinToString(",") { (it / 1024).toString() }},${sqrt(maxOf(0.0, cr2 / 1024 - cr * cr / (1024 * 1024)))},${sqrt(maxOf(0.0, cb2 / 1024 - cb * cb / (1024 * 1024)))}")
                    }
                    for ((stage, values) in collector.values) measure(stage.name) { at, c -> values[at * 3 + c].toDouble() }
                    for ((stage, values) in collector.display) measure(stage.name) { at, c -> (values[at] shr (16 - c * 8) and 255).toDouble() / 255 }
                }
            }
        }
        // Known optically smooth slanted step. Overshoot is measurable here;
        // this is synthetic edge response, not MTF from an arbitrary photograph.
        val ew = 512; val eh = 128
        val edge = ShortArray(ew * eh) { i ->
            val x = i % ew; val y = i / ew; val distance = x - (ew / 2.0 + 0.1 * (y - eh / 2.0))
            val linear = 0.01 + 0.49 * (0.5 + 0.5 * Math.tanh(distance / 1.2))
            val site = y % 2 * 2 + x % 2
            val c = sensor.cfaPattern[site]
            val g = when (c) { 0 -> dng.color.gains[0]; 2 -> dng.color.gains[3]; else -> dng.color.gains[if (y % 2 == 0) 1 else 2] }
            (sensor.blackAt(x, y) + linear * sensor.range / g).roundToInt().toShort()
        }
        val observer = DevelopStageDumps(File(out, "slanted-edge-stages"), ew, eh)
        observer.raw(BayerFrame(ew, eh, edge))
        RawDeveloper.develop(BayerFrame(ew, eh, edge), sensor, dng.color, params, observer = observer)
        observer.finish()
        File(out, "README.md").writeText("Quarter-DN black-level errors are represented exactly by multiplying RAW, white and black by four, then changing only black. The scale A/A null is recorded. Noise-free 64px neutral-camera patches; actual Pixel DNG calibration and WB; full real develop order with explicit fixed exposure. These are synthetic sensitivity/edge-response results, not live-camera quality or arbitrary-scene MTF.\n")
    }
}
