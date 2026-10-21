package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.DevelopParams
import dev.multiframe.camera.pipeline.DngWriter
import dev.multiframe.camera.pipeline.ProfileGainTableMap
import dev.multiframe.camera.pipeline.BilateralGrid
import dev.multiframe.camera.pipeline.RawDeveloper
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Sample DNGs for a converter to judge: each representative September
 * fixture rewritten by [DngWriter], with the gain table map the app would
 * now write for it and without, and the backlit tower once more with the
 * map's payload little-endian -- the specification gives the tag no byte
 * order, so a converter is asked which it reads.
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*DngWriterSamples*' \
 *         -Pmf.dng.dir=/path/to/september -Pmf.dngwriter.out=/path/to/out
 */
class DngWriterSamples {

    @Test
    fun writeSamples() {
        val dir = System.getProperty("mf.dng.dir")?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getProperty("mf.dngwriter.out")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("no -Pmf.dng.dir and -Pmf.dngwriter.out; no samples written", dir != null && dir.isDirectory && out != null)
        out!!.mkdirs()
        val log = StringBuilder()
        for ((name, scene) in SAMPLES) {
            val source = File(dir, name)
            if (!source.isFile) continue
            val theirs = TiffEntries.read(source.readBytes())
            val half = DngFile.read(source).decimated()
            val params = DevelopParams(
                localTone = DevelopParams.LOCAL_TONE_ON, shadowLift = DevelopParams.SHADOW_LIFT_ON,
                meteredGain = 2f.pow(half.baselineExposure),
            ).adaptedTo(half.iso)
            val (one, two) = ProfileGainTableMap.forCapture(half.frame, half.sensor, half.color, params, half.shading, half.baselineExposure)!!
            val resolved = params.withGain(RawDeveloper.resolveGain(half.frame, half.sensor, half.color, params))
            val grid = BilateralGrid.fromFrame(half.frame, half.sensor, half.color, resolved, half.shading)
            val record = theirs.record()
            val raw = ByteBuffer.allocateDirect(record.width * record.height * 2).order(ByteOrder.LITTLE_ENDIAN)
            raw.put(theirs.image()).clear()
            val stem = "${source.nameWithoutExtension}_$scene"
            fun write(suffix: String, r: dev.multiframe.camera.pipeline.DngRecord) {
                val f = File(out, "${stem}_$suffix.dng")
                java.io.FileOutputStream(f).channel.use { DngWriter.write(it, r, raw) }
                log.append("${f.name}  ${f.length()} bytes\n")
            }
            write("mf-pgtm", record.copy(profileGainTableMap = one, profileGainTableMap2 = two))
            write("mf-flat", record)
            if (scene == "tower") {
                val le = ProfileGainTableMap.encode(
                    ProfileGainTableMap.tables(grid, half.frame.width, half.frame.height, resolved, half.baselineExposure, ProfileGainTableMap.GAMMA),
                    version2 = true, order = ByteOrder.LITTLE_ENDIAN,
                )
                val leOne = ProfileGainTableMap.encode(
                    ProfileGainTableMap.tables(grid, half.frame.width, half.frame.height, resolved, half.baselineExposure, 1f),
                    version2 = false, order = ByteOrder.LITTLE_ENDIAN,
                )
                write("mf-pgtm-le", record.copy(profileGainTableMap = leOne, profileGainTableMap2 = le))
            }
            log.append("  $scene: BaselineExposure %.2f, develop gain %.3f, lift ceiling %.2f stops\n".format(
                half.baselineExposure, resolved.exposureGain, grid.ceiling(resolved.shadowLift, resolved.shadowLiftMax)))
        }
        File(out, "samples.txt").writeText(log.toString())
        println(log)
    }

    private companion object {
        val SAMPLES = listOf(
            "MF_20260917_145908_zsl_10f.dng" to "tower",
            "MF_20260917_145555_zsl_10f.dng" to "flower",
            "MF_20260917_104934_zsl_10f.dng" to "dog",
            "MF_20260917_145003_zsl_10f.dng" to "street",
            "MF_20260915_154430_sr2x_16f.dng" to "iso918",
        )
    }
}
