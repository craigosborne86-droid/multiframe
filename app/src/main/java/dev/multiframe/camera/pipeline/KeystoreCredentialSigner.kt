package dev.multiframe.camera.pipeline

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date

private const val TAG = "Multiframe"

/**
 * Signs Content Credentials with keys that never leave the Android Keystore.
 *
 * Two keys, generated on first use: a device root and a claim-signing leaf,
 * both EC P-256, in StrongBox where the phone has one. Their certificates are
 * written by [Der] -- the leaf issued by the root, with the key usage and
 * extended key usage the C2PA certificate profile asks for -- and signed by
 * the Keystore. They are kept beside the app's files; if a key is gone (a
 * reinstall, a factory reset) both are made again.
 *
 * The root is the device's own, on no trust list: verifiers show a valid
 * signature from an unknown signer. A trust-listed signer would need a
 * certificate from a C2PA-recognised authority; that is a decision outside
 * the app (REFERENCE §3.11).
 */
class KeystoreCredentialSigner private constructor(
    private val leaf: PrivateKey,
    override val certificateChain: List<ByteArray>,
) : ContentCredentials.Signer {

    override fun signDer(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(leaf); update(data) }.sign()

    companion object {
        private const val ROOT = "multiframe-c2pa-root"
        private const val LEAF = "multiframe-c2pa-leaf"
        private const val DIR = "c2pa"
        @Volatile private var cached: KeystoreCredentialSigner? = null

        /** The signer, creating its keys and certificates on first use; null where the Keystore refuses. */
        @Synchronized
        fun obtain(context: Context): KeystoreCredentialSigner? = cached ?: runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val rootFile = File(dir, "root.der")
            val leafFile = File(dir, "leaf.der")
            val haveKeys = store.containsAlias(ROOT) && store.containsAlias(LEAF)
            if (!haveKeys || !rootFile.exists() || !leafFile.exists()) {
                store.deleteEntry(ROOT); store.deleteEntry(LEAF)
                val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                val root = generate(ROOT, strongBox)
                val leafKey = generate(LEAF, strongBox)
                val now = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
                val until = Date(now.time + 20L * 365 * 24 * 3600 * 1000)
                val rootName = Der.name("Multiframe device root", "Multiframe")
                val leafName = Der.name("Multiframe on-device claim signer", "Multiframe")
                val rootTbs = Der.tbsCertificate(serial(), rootName, rootName, now, until, root, root, ca = true)
                rootFile.writeBytes(Der.certificate(rootTbs, signWith(store.getKey(ROOT, null) as PrivateKey, rootTbs)))
                val leafTbs = Der.tbsCertificate(serial(), rootName, leafName, now, until, leafKey, root, ca = false)
                leafFile.writeBytes(Der.certificate(leafTbs, signWith(store.getKey(ROOT, null) as PrivateKey, leafTbs)))
                Log.i(TAG, "content credentials: new device signer (StrongBox=$strongBox)")
            }
            KeystoreCredentialSigner(
                store.getKey(LEAF, null) as PrivateKey,
                listOf(leafFile.readBytes(), rootFile.readBytes()),
            ).also { cached = it }
        }.onFailure { Log.w(TAG, "content credentials: no signer", it) }.getOrNull()

        private fun generate(alias: String, strongBox: Boolean): PublicKey {
            fun spec(sb: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(sb)
                .build()
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            return try {
                gen.initialize(spec(strongBox)); gen.generateKeyPair().public
            } catch (e: StrongBoxUnavailableException) {
                gen.initialize(spec(false)); gen.generateKeyPair().public
            }
        }

        private fun signWith(key: PrivateKey, data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()

        private fun serial(): BigInteger = BigInteger(127, SecureRandom()).add(BigInteger.ONE)
    }
}
