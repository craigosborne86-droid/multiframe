package dev.multiframe.camera.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.imageio.ImageIO

/**
 * Content Credentials on the desk, with a JVM key standing in for the
 * Keystore's. `-Pmf.c2pa.out=<dir>` also writes the signed sample there, for
 * an independent validator (c2patool) to read.
 */
@RunWith(JUnit4::class)
class ContentCredentialsTest {

    private fun ecKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun sign(key: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(data) }.sign()

    /** A device root and a claim-signing leaf, built by [Der] exactly as the phone builds them. */
    private val signer: ContentCredentials.Signer by lazy {
        val root = ecKey()
        val leaf = ecKey()
        val now = Date()
        val later = Date(now.time + 20L * 365 * 24 * 3600 * 1000)
        val rootName = Der.name("Multiframe device root (test)", "Multiframe")
        val leafName = Der.name("Multiframe claim signer (test)", "Multiframe")
        val rootTbs = Der.tbsCertificate(BigInteger.valueOf(1), rootName, rootName, now, later, root.public, root.public, ca = true)
        val rootCert = Der.certificate(rootTbs, sign(root, rootTbs))
        val leafTbs = Der.tbsCertificate(BigInteger.valueOf(2), rootName, leafName, now, later, leaf.public, root.public, ca = false)
        val leafCert = Der.certificate(leafTbs, sign(root, leafTbs))
        object : ContentCredentials.Signer {
            override val certificateChain = listOf(leafCert, rootCert)
            override fun signDer(data: ByteArray) = sign(leaf, data)
        }
    }

    private fun sampleJpeg(): ByteArray {
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) for (x in 0 until 320) img.setRGB(x, y, (x * 255 / 320 shl 16) or (y * 255 / 240 shl 8) or 0x40)
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private val report = CaptureReport(
        kind = CaptureReport.KIND_NIGHT, framesRequested = 28, framesMerged = 26, meanContribution = 0.91f,
        spanMillis = 2_140, exposureTimeNs = 66_666_667L, iso = 1600, lens = "24 mm main",
        alignment = CaptureReport.ALIGN_TILES, localTone = true, hdr = false, plan = "HANDHELD · 1/15 · ISO 1600 · 28×",
    )

    @Test fun theCertificatesParseAsX509WithTheProfileAValidatorAsksFor() {
        val factory = CertificateFactory.getInstance("X.509")
        val (leaf, root) = signer.certificateChain.map {
            factory.generateCertificate(it.inputStream()) as X509Certificate
        }
        leaf.verify(root.publicKey)
        root.verify(root.publicKey)
        assertThat(leaf.basicConstraints).isEqualTo(-1)
        assertThat(root.basicConstraints).isAtLeast(0)
        assertThat(leaf.keyUsage[0]).isTrue() // digitalSignature
        assertThat(leaf.extendedKeyUsage).containsAtLeast("1.3.6.1.5.5.7.3.4", "1.3.6.1.5.5.7.3.36")
        assertThat(leaf.criticalExtensionOIDs).contains("2.5.29.15")
    }

    @Test fun theManifestSitsAfterTheHeaderAndTheHashCoversTheFileAsItWas() {
        val original = sampleJpeg()
        val signed = ContentCredentials.embedJpeg(original, signer, report, "0.1.0-test")
        val at = ContentCredentials.insertionOffset(original)
        assertThat(signed[at].toInt() and 0xFF).isEqualTo(0xFF)
        assertThat(signed[at + 1].toInt() and 0xFF).isEqualTo(0xEB)
        // Removing the inserted run gives back the original bytes exactly:
        // that is the region the hash assertion excludes.
        val inserted = signed.size - original.size
        val stripped = signed.copyOfRange(0, at) + signed.copyOfRange(at + inserted, signed.size)
        assertThat(stripped).isEqualTo(original)
        // And the hash assertion carries that file's SHA-256.
        val digest = MessageDigest.getInstance("SHA-256").digest(original)
        assertThat(String(signed, Charsets.ISO_8859_1)).contains(String(digest, Charsets.ISO_8859_1))
        assertThat(String(signed, Charsets.ISO_8859_1)).contains("computationalCapture")
        // Still a decodable JPEG.
        assertThat(ImageIO.read(signed.inputStream()).width).isEqualTo(320)

        System.getProperty("mf.c2pa.out")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "unsigned.jpg").writeBytes(original)
            File(dir, "signed.jpg").writeBytes(signed)
            File(dir, "tampered.jpg").writeBytes(signed.copyOf().also { it[it.size - 200] = (it[it.size - 200] + 1).toByte() })
            File(dir, "root.der").writeBytes(signer.certificateChain.last())
        }
    }

    @Test fun theManifestNamesEachModelAndSaysTheBlurIsAnEdit() {
        val plain = String(ContentCredentials.embedJpeg(sampleJpeg(), signer, report, "0.1.0-test"), Charsets.ISO_8859_1)
        assertThat(plain).doesNotContain("c2pa.filtered")
        val blurred = report.copy(analysis = listOf(
            AnalysisUse(AnalysisTask.PERSON, "Multiframe person segmenter 5e6f7a8b (on-device)", AnalysisUse.PORTRAIT_ROLE)))
        val facts = ContentCredentials.captureFacts(blurred)
        @Suppress("UNCHECKED_CAST")
        val models = facts["ml_analysis"] as List<Map<String, Any?>>
        assertThat(models.single()["task"]).isEqualTo("person")
        assertThat(facts["simulated_background_blur"]).isEqualTo(true)
        assertThat(facts["generative_ai"]).isEqualTo(false)
        val signed = String(ContentCredentials.embedJpeg(sampleJpeg(), signer, blurred, "0.1.0-test"), Charsets.ISO_8859_1)
        assertThat(signed).contains("c2pa.filtered")
        assertThat(signed).contains("Simulated background blur")
    }

    @Test fun aDerSignatureBecomesSixtyFourRawBytes() {
        val key = ecKey()
        repeat(20) {
            val raw = ContentCredentials.rawSignature(sign(key, byteArrayOf(it.toByte())))
            assertThat(raw.size).isEqualTo(64)
        }
    }

    @Test fun cborWritesTheShortestHeads() {
        assertThat(Cbor.encode(23)).isEqualTo(byteArrayOf(0x17))
        assertThat(Cbor.encode(24)).isEqualTo(byteArrayOf(0x18, 0x18))
        assertThat(Cbor.encode(-7)).isEqualTo(byteArrayOf(0x26))
        assertThat(Cbor.encode(1000)).isEqualTo(byteArrayOf(0x19, 0x03, 0xE8.toByte()))
        assertThat(Cbor.encode(mapOf("a" to 1))).isEqualTo(byteArrayOf(0xA1.toByte(), 0x61, 0x61, 0x01))
        assertThat(Cbor.encode(Cbor.Tagged(18, null))).isEqualTo(byteArrayOf(0xD2.toByte(), 0xF6.toByte()))
    }

    @Test fun aLargeManifestSpansSegmentsThatEachRepeatTheBoxHeader() {
        val store = Jumbf.superbox(Jumbf.MANIFEST_STORE, "c2pa", ByteArray(150_000))
        val segments = ContentCredentials.app11Segments(store)
        var p = 0; var count = 0
        while (p < segments.size) {
            assertThat(segments[p + 1].toInt() and 0xFF).isEqualTo(0xEB)
            val lp = ((segments[p + 2].toInt() and 0xFF) shl 8) or (segments[p + 3].toInt() and 0xFF)
            if (count > 0) assertThat(segments.copyOfRange(p + 12, p + 20)).isEqualTo(store.copyOfRange(0, 8))
            p += 2 + lp; count++
        }
        assertThat(p).isEqualTo(segments.size)
        assertThat(count).isEqualTo(3)
    }
}
