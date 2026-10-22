package dev.multiframe.camera.pipeline

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The DER subset needed to write an X.509 v3 certificate (RFC 5280), and the
 * two certificates Content Credentials are signed under.
 *
 * Why write certificates at all: the Android Keystore's own certificate for a
 * generated key carries no extended key usage, and a C2PA validator rejects a
 * signing certificate without one. The key never leaves the Keystore; only the
 * to-be-signed certificate bytes are built here and signed by it.
 */
object Der {

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n <= 0xFF -> { out.write(0x81); out.write(n) }
            n <= 0xFFFF -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg items: ByteArray) = tlv(0x30, cat(*items))
    fun set(vararg items: ByteArray) = tlv(0x31, cat(*items))
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun int(v: Long) = int(BigInteger.valueOf(v))
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bits(b: ByteArray, unused: Int = 0) = tlv(0x03, byteArrayOf(unused.toByte()) + b)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun explicit(n: Int, content: ByteArray) = tlv(0xA0 or n, content)
    fun implicitPrimitive(n: Int, content: ByteArray) = tlv(0x80 or n, content)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) { stack.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
            stack.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime through 2049, GeneralizedTime after, as RFC 5280 §4.1.2.5 requires. */
    fun time(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = date }.get(java.util.Calendar.YEAR)
        return if (year < 2050) tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = utc }.format(date).toByteArray())
        else tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = utc }.format(date).toByteArray())
    }

    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    // --- certificates ------------------------------------------------------

    private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val CN = "2.5.4.3"
    private const val O = "2.5.4.10"

    fun name(commonName: String, organisation: String) = seq(
        set(seq(oid(CN), utf8(commonName))),
        set(seq(oid(O), utf8(organisation))),
    )

    private fun extension(oid: String, critical: Boolean, value: ByteArray) =
        if (critical) seq(oid(oid), bool(true), octets(value)) else seq(oid(oid), octets(value))

    /** Subject key identifier: SHA-1 of the subjectPublicKey BIT STRING's contents (RFC 5280 §4.2.1.2, method 1). */
    fun keyIdentifier(key: PublicKey): ByteArray {
        val spki = key.encoded
        // SubjectPublicKeyInfo ::= SEQUENCE { algorithm, subjectPublicKey BIT STRING }; the EC point is its tail.
        val point = spki.copyOfRange(spki.size - 65, spki.size)
        return MessageDigest.getInstance("SHA-1").digest(point)
    }

    /**
     * The to-be-signed part of a certificate. [ca] makes it the device's own
     * root (basicConstraints CA, keyCertSign); otherwise it is the claim-signing
     * leaf (digitalSignature, emailProtection + documentSigning EKUs, which the
     * C2PA certificate profile accepts).
     */
    fun tbsCertificate(
        serial: BigInteger,
        issuer: ByteArray,
        subject: ByteArray,
        notBefore: Date,
        notAfter: Date,
        subjectKey: PublicKey,
        issuerKey: PublicKey,
        ca: Boolean,
    ): ByteArray {
        val extensions = mutableListOf<ByteArray>()
        if (ca) {
            extensions += extension("2.5.29.19", true, seq(bool(true)))
            // keyCertSign (bit 5) and cRLSign (bit 6): 0b0000_0110, one unused bit.
            extensions += extension("2.5.29.15", true, bits(byteArrayOf(0x06), unused = 1))
        } else {
            extensions += extension("2.5.29.19", true, seq())
            // digitalSignature (bit 0): 0b1000_0000, seven unused bits.
            extensions += extension("2.5.29.15", true, bits(byteArrayOf(0x80.toByte()), unused = 7))
            extensions += extension("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.4"), oid("1.3.6.1.5.5.7.3.36")))
            extensions += extension("2.5.29.35", false, seq(implicitPrimitive(0, keyIdentifier(issuerKey))))
        }
        extensions += extension("2.5.29.14", false, octets(keyIdentifier(subjectKey)))
        return seq(
            explicit(0, int(2)),
            int(serial),
            seq(oid(ECDSA_SHA256)),
            issuer,
            seq(time(notBefore), time(notAfter)),
            subject,
            subjectKey.encoded,
            explicit(3, seq(*extensions.toTypedArray())),
        )
    }

    /** A certificate from its TBS bytes and the issuer's DER ECDSA signature over them. */
    fun certificate(tbs: ByteArray, signatureDer: ByteArray): ByteArray =
        seq(tbs, seq(oid(ECDSA_SHA256)), bits(signatureDer))
}
