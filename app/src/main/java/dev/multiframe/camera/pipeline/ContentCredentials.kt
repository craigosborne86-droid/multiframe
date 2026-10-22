package dev.multiframe.camera.pipeline

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/**
 * C2PA Content Credentials for a JPEG: a signed statement, inside the file,
 * that the picture is a camera capture -- a computational merge of measured
 * frames -- and not generated.
 *
 * ### What the manifest says
 *
 *  - `c2pa.actions.v2`: one action, `c2pa.created`, with the IPTC digital
 *    source type `computationalCapture` ("captured from the real world by a
 *    camera, processed by algorithms such as multi-frame merging"). Not
 *    `digitalCapture`, which claims a single unprocessed exposure, and never
 *    `trainedAlgorithmicMedia`, which is generative.
 *  - `dev.multiframe.capture`: the photograph's own report ([CaptureReport]):
 *    frames, span, exposure, lens, plan, and `generative_ai: false`.
 *  - `c2pa.hash.data`: SHA-256 of every byte of the file except the manifest's
 *    own APP11 segments, so any edit to the picture breaks the credential.
 *
 * ### Why the data hash is the hash of the file as it was
 *
 * The manifest is inserted as a run of APP11 segments at one offset, and the
 * hash assertion excludes exactly that run. The bytes it covers are therefore
 * the file before insertion, byte for byte: the hash is computed once, before
 * the manifest exists, and the exclusion's length is settled by rebuilding
 * until it stops changing (the CBOR integer width is the only thing that can
 * move it).
 *
 * ### Where it goes
 *
 * After SOI and any APP0/APP1 (JFIF, EXIF), before everything else -- in
 * particular before an Ultra HDR file's APP2 MPF segment, whose offsets to
 * the gain map are relative to the MPF header and so survive an insertion
 * ahead of it.
 *
 * ### Signing
 *
 * ES256 under a certificate chain the caller provides ([Signer]); on the
 * phone, an Android Keystore key and the device's own root
 * ([KeystoreCredentialSigner]). That root is on no trust list, so a verifier
 * reports a valid signature from an unknown signer; the claim itself and the
 * tamper evidence verify. See REFERENCE §3.11.
 */
object ContentCredentials {

    interface Signer {
        /** Leaf first, DER. */
        val certificateChain: List<ByteArray>
        /** SHA256withECDSA over [data], DER-encoded as Java returns it. */
        fun signDer(data: ByteArray): ByteArray
    }

    const val SOURCE_TYPE = "http://cv.iptc.org/newscodes/digitalsourcetype/computationalCapture"
    const val CAPTURE_ASSERTION = "dev.multiframe.capture"

    private const val APP11 = 0xEB
    private const val SEGMENT_MAX = 0xFFFF
    /** Lp(2) + CI "JP"(2) + En(2) + Z(4). */
    private const val SEGMENT_HEADER = 10

    /** Returns [jpeg] with a signed manifest inserted, or throws if it is not a JPEG. */
    fun embedJpeg(jpeg: ByteArray, signer: Signer, report: CaptureReport?, appVersion: String): ByteArray {
        require(jpeg.size > 4 && (jpeg[0].toInt() and 0xFF) == 0xFF && (jpeg[1].toInt() and 0xFF) == 0xD8) { "not a JPEG" }
        val at = insertionOffset(jpeg)
        val dataHash = sha256(jpeg)
        val label = "urn:c2pa:" + UUID.randomUUID()
        val instance = "xmp:iid:" + UUID.randomUUID()

        // Settle the exclusion length with a placeholder signature of the
        // final size, then sign once.
        var length = 0
        var segments: ByteArray
        var round = 0
        while (true) {
            val store = manifestStore(label, instance, at, length, dataHash, report, appVersion, signer, sign = false)
            segments = app11Segments(store)
            if (segments.size == length) break
            length = segments.size
            check(++round < 8) { "manifest size did not settle" }
        }
        val store = manifestStore(label, instance, at, length, dataHash, report, appVersion, signer, sign = true)
        segments = app11Segments(store)
        check(segments.size == length) { "signed manifest changed size" }

        return ByteArrayOutputStream(jpeg.size + segments.size).apply {
            write(jpeg, 0, at)
            write(segments)
            write(jpeg, at, jpeg.size - at)
        }.toByteArray()
    }

    /** After SOI and any leading APP0/APP1 segments. */
    internal fun insertionOffset(jpeg: ByteArray): Int {
        var p = 2
        while (p + 4 <= jpeg.size && (jpeg[p].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[p + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val len = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            p += 2 + len
        }
        return p
    }

    private fun manifestStore(
        label: String, instance: String, at: Int, length: Int, dataHash: ByteArray,
        report: CaptureReport?, appVersion: String, signer: Signer, sign: Boolean,
    ): ByteArray {
        val agent = mapOf("name" to "Multiframe", "version" to appVersion)
        val actions = Jumbf.cborSuperbox(Jumbf.CBOR_CONTENT, "c2pa.actions.v2", Cbor.encode(mapOf(
            "actions" to listOf(mapOf(
                "action" to "c2pa.created",
                "digitalSourceType" to SOURCE_TYPE,
                "softwareAgent" to agent,
            )),
        )))
        val capture = Jumbf.cborSuperbox(Jumbf.CBOR_CONTENT, CAPTURE_ASSERTION, Cbor.encode(captureFacts(report)))
        val hashData = Jumbf.cborSuperbox(Jumbf.CBOR_CONTENT, "c2pa.hash.data", Cbor.encode(mapOf(
            "exclusions" to listOf(mapOf("start" to at, "length" to length)),
            "name" to "jumbf manifest",
            "alg" to "sha256",
            "hash" to dataHash,
            "pad" to ByteArray(0),
        )))
        val assertionStore = Jumbf.superbox(Jumbf.ASSERTION_STORE, "c2pa.assertions", actions, capture, hashData)

        fun hashed(name: String, box: ByteArray) = mapOf(
            "url" to "self#jumbf=c2pa.assertions/$name",
            "hash" to sha256(Jumbf.hashedContents(box)),
        )
        val claim = Cbor.encode(mapOf(
            "instanceID" to instance,
            "claim_generator_info" to agent,
            "signature" to "self#jumbf=/c2pa/$label/c2pa.signature",
            "created_assertions" to listOf(
                hashed("c2pa.actions.v2", actions),
                hashed(CAPTURE_ASSERTION, capture),
                hashed("c2pa.hash.data", hashData),
            ),
            "alg" to "sha256",
        ))
        val claimBox = Jumbf.cborSuperbox(Jumbf.CLAIM, "c2pa.claim.v2", claim)

        val chain = signer.certificateChain
        val protectedHeader = Cbor.encode(mapOf(
            1 to -7, // alg: ES256
            33 to if (chain.size == 1) chain[0] else chain, // x5chain
        ))
        val toBeSigned = Cbor.encode(listOf("Signature1", protectedHeader, ByteArray(0), claim))
        val signature = if (sign) rawSignature(signer.signDer(toBeSigned)) else ByteArray(64)
        val cose = Cbor.encode(Cbor.Tagged(18, listOf(protectedHeader, emptyMap<String, Any?>(), null, signature)))
        val signatureBox = Jumbf.cborSuperbox(Jumbf.SIGNATURE, "c2pa.signature", cose)

        val manifest = Jumbf.superbox(Jumbf.MANIFEST, label, assertionStore, claimBox, signatureBox)
        return Jumbf.superbox(Jumbf.MANIFEST_STORE, "c2pa", manifest)
    }

    internal fun captureFacts(report: CaptureReport?): Map<String, Any?> = buildMap {
        put("generative_ai", false)
        if (report == null) return@buildMap
        put("kind", report.kind)
        put("frames_requested", report.framesRequested)
        put("frames_merged", report.framesMerged)
        put("contribution_permille", (report.meanContribution * 1000).toInt())
        put("span_ms", report.spanMillis)
        report.exposureTimeNs?.let { put("exposure_ns", it) }
        report.iso?.let { put("iso", it) }
        report.lens?.let { put("lens", it) }
        put("alignment", report.alignment)
        report.plan?.let { put("plan", it) }
        report.mlAnalysis?.let {
            put("ml_analysis", it)
            put("ml_analysis_role", "segmentation mask selecting among measured samples; no pixel values generated")
        }
    }

    /**
     * The JUMBF manifest store as JPEG-XT APP11 segments: each carries the
     * common identifier "JP", box instance 1 and a sequence number from 1;
     * every segment after the first repeats the box's LBox/TBox.
     */
    internal fun app11Segments(store: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val header = store.copyOfRange(0, 8)
        var offset = 0
        var sequence = 1
        while (offset < store.size) {
            val repeat = if (sequence == 1) ByteArray(0) else header
            val room = SEGMENT_MAX - SEGMENT_HEADER - repeat.size
            val chunk = minOf(room, store.size - offset)
            val lp = SEGMENT_HEADER + repeat.size + chunk
            out.write(0xFF); out.write(APP11)
            out.write(lp shr 8); out.write(lp and 0xFF)
            out.write('J'.code); out.write('P'.code)
            out.write(0); out.write(1)
            for (s in intArrayOf(24, 16, 8, 0)) out.write((sequence shr s) and 0xFF)
            out.write(repeat)
            out.write(store, offset, chunk)
            offset += chunk
            sequence++
        }
        return out.toByteArray()
    }

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` to COSE's fixed-width r‖s (RFC 8152 §8.1). */
    internal fun rawSignature(der: ByteArray, width: Int = 32): ByteArray {
        require(der[0].toInt() == 0x30) { "not a DER signature" }
        var p = 2
        if ((der[1].toInt() and 0x80) != 0) p = 2 + (der[1].toInt() and 0x7F)
        fun integer(): ByteArray {
            require(der[p].toInt() == 0x02)
            val len = der[p + 1].toInt() and 0xFF
            val v = BigInteger(1, der.copyOfRange(p + 2, p + 2 + len))
            p += 2 + len
            val bytes = v.toByteArray().let { if (it.size > width) it.copyOfRange(it.size - width, it.size) else it }
            return ByteArray(width - bytes.size) + bytes
        }
        return integer() + integer()
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
