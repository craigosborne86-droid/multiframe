package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.ChromaSmooth
import dev.multiframe.camera.pipeline.DevelopParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in display-domain fixture exporter. No RAW, optics, photograph-quality or MTF claim. */
class ChromaColourDetailBench {
    @Test fun exportActualHighIsoChromaStages() {
        val input = System.getProperty("mf.burst.dir")?.let(::File)
        assumeTrue(input?.isDirectory == true && File(input, "fixtures.csv").isFile)
        val out = File(requireNotNull(System.getProperty("mf.burst.out"))).apply { mkdirs() }
        val adapted = DevelopParams().adaptedTo(6022).chroma
        File(out,"params.properties").writeText(
            "source=DevelopParams().adaptedTo(6022).chroma\nisoParameter=6022\n" +
                "adaptedRadius=${adapted.radius}\nlumaSigma=${adapted.lumaSigma}\namount=${adapted.amount}\n",
        )
        val hashes = StringBuilder("fixture,inputSha256\n")
        var exported = 0
        for (line in File(input,"fixtures.csv").readLines().drop(1)) {
            val row = line.split(',')
            val name = row[0]; val w = row[1].toInt(); val h = row[2].toInt()
            val bytes = File(input,"$name.rgb8").readBytes()
            assertEquals(w*h*3,bytes.size)
            hashes.appendLine("$name,"+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val original = IntArray(w*h) { i ->
                val k=i*3
                (0xff shl 24) or ((bytes[k].toInt() and 255) shl 16) or
                    ((bytes[k+1].toInt() and 255) shl 8) or (bytes[k+2].toInt() and 255)
            }
            for (radius in listOf(2,4)) for (amount in listOf(adapted.amount,0f)) {
                val pixels=original.copyOf()
                ChromaSmooth.apply(pixels,w,h,adapted.copy(radius=radius,amount=amount))
                val result=ByteArray(bytes.size) { k ->
                    val p=pixels[k/3]
                    (when(k%3) { 0 -> p shr 16; 1 -> p shr 8; else -> p } and 255).toByte()
                }
                if (amount==0f || name=="neutral") assertArrayEquals("$name radius=$radius amount=$amount null",bytes,result)
                File(out,"$name-r$radius-a${if(amount==0f) 0 else 1}.rgb8").writeBytes(result)
                exported++
            }
        }
        File(out,"input-sha256.csv").writeText(hashes.toString())
        println("Actual high-ISO params: radius=${adapted.radius} sigma=${adapted.lumaSigma} amount=${adapted.amount}; exported=$exported stages")
    }
}
