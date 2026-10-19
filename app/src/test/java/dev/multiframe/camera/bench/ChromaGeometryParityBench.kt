package dev.multiframe.camera.bench

import dev.multiframe.camera.pipeline.ChromaSmooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.abs

/** Opt-in parity against a selected frozen native source version. No fixed kernel or IQ claim. */
class ChromaGeometryParityBench {
    @Test fun compareWithVersionedNativeGeometryReference() {
        val input = System.getProperty("mf.burst.dir")?.let(::File)
        val manifest = input?.let { File(it, "geometry-parity.properties") }
        assumeTrue("requires frozen native geometry outputs", manifest?.isFile == true)
        val properties = Properties().apply { requireNotNull(manifest).inputStream().use { load(it) } }
        val output = File(requireNotNull(System.getProperty("mf.burst.out"))).apply { mkdirs() }
        val count = properties.getProperty("cases").toInt()
        val nativeVersion = properties.getProperty("nativeVersion", "unlabelled frozen reference")
        assertEquals("four geometries x two radii x two sigmas x three amounts", 48, count)
        val csv = StringBuilder("case,width,height,radius,sigma,amount,maxCodes,differentChannels,borderMaxCodes,borderDifferentChannels,seamMaxCodes,seamDifferentChannels,amountZeroExact,alphaUnchanged,kotlinSha256,nativeSha256\n")
        val failures = ArrayList<String>()
        for (case in 0 until count) {
            fun property(name: String) = requireNotNull(properties.getProperty("case.$case.$name"))
            val id = property("id")
            val width = property("width").toInt()
            val height = property("height").toInt()
            val radius = property("radius").toInt()
            val sigma = property("sigma").toFloat()
            val amount = property("amount").toFloat()
            val original = File(input!!, property("input")).readBytes()
            val native = File(input, property("native")).readBytes()
            assertEquals("$id RGB input size", width * height * 3, original.size)
            assertEquals("$id native output size", original.size, native.size)
            assertEquals("$id frozen input", property("inputSha256"), sha256(original))
            assertEquals("$id frozen native output", property("nativeSha256"), sha256(native))
            val pixels = IntArray(width * height) { i ->
                val k = i * 3
                (0xff shl 24) or ((original[k].toInt() and 255) shl 16) or
                    ((original[k + 1].toInt() and 255) shl 8) or (original[k + 2].toInt() and 255)
            }
            ChromaSmooth.apply(pixels, width, height, ChromaSmooth.Params(radius, sigma, amount))
            val kotlin = ByteArray(original.size) { k ->
                val pixel = pixels[k / 3]
                (when (k % 3) { 0 -> pixel shr 16; 1 -> pixel shr 8; else -> pixel } and 255).toByte()
            }
            File(output, "$id.rgb8").writeBytes(kotlin)
            var max = 0
            var different = 0
            var borderMax = 0
            var borderDifferent = 0
            var seamMax = 0
            var seamDifferent = 0
            for (k in kotlin.indices) {
                val delta = abs((kotlin[k].toInt() and 255) - (native[k].toInt() and 255))
                val x = (k / 3) % width
                val y = (k / 3) / width
                max = maxOf(max, delta)
                if (delta != 0) different++
                if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) {
                    borderMax = maxOf(borderMax, delta)
                    if (delta != 0) borderDifferent++
                }
                if ((height > 512 && y in 500 until 524) || (height > 1024 && y in 1012 until 1036)) {
                    seamMax = maxOf(seamMax, delta)
                    if (delta != 0) seamDifferent++
                }
            }
            val amountZeroExact = amount != 0f || (kotlin.contentEquals(original) && native.contentEquals(original))
            val alphaUnchanged = pixels.all { it ushr 24 == 255 }
            csv.appendLine("$id,$width,$height,$radius,$sigma,$amount,$max,$different,$borderMax,$borderDifferent,$seamMax,$seamDifferent,$amountZeroExact,$alphaUnchanged,${sha256(kotlin)},${sha256(native)}")
            if (max > 2) failures.add("$id maximum $max codes (border $borderMax, seam $seamMax)")
            if (!amountZeroExact) failures.add("$id amount-zero identity changed")
            if (!alphaUnchanged) failures.add("$id packed alpha changed")
        }
        File(output, "parity.csv").writeText(csv.toString())
        File(output, "summary.txt").writeText("nativeVersion=$nativeVersion\ncases=$count\nfailures=${failures.size}\n" + failures.joinToString("\n", postfix = "\n"))
        println("Chroma versioned geometry parity ($nativeVersion): $count combinations; failures=${failures.size}; CSV=${File(output, "parity.csv")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
