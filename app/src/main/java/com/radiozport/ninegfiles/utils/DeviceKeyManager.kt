package com.radiozport.ninegfiles.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Manages a device-unique RSA-2048 key pair stored in the Android Keystore
 * under the fixed alias [KEYSTORE_ALIAS].
 *
 * ## Design
 * The **private key** is hardware-backed and never leaves the Keystore — it
 * cannot be exported, copied, or recovered on any other device.
 *
 * The **public key** is safe to share openly.  A distributor uses it to
 * encrypt a one-time AES session key that only this device can unwrap.
 * The eBook itself is encrypted with that session key (hybrid encryption,
 * format `9GEK` — see [EncryptionUtils]).
 *
 * ## What is safe to display in Settings
 * Only the [getPublicKeyFingerprint] (a short SHA-256 digest) or the full
 * [getPublicKeyPem] (the public key in PEM format).  Neither of these
 * allows decryption by a third party.
 *
 * ## What must never be displayed
 * There is no secret key string to show.  The private key material is kept
 * entirely inside the Keystore secure element and is never exposed.
 */
object DeviceKeyManager {

    /** Fixed alias under which the RSA key pair lives in the Android Keystore. */
    const val KEYSTORE_ALIAS = "radiosport"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val RSA_KEY_SIZE      = 2048
    /**
     * Generic OAEP transform — the exact hash/MGF1 parameters are supplied
     * via [OAEPParameterSpec] at cipher-init time.  SHA-1/MGF1-SHA-1 is used
     * exclusively (see [decryptSessionKey] and [EncryptionUtils.encryptForDevice]).
     */
    private const val CIPHER_TRANSFORM  = "RSA/ECB/OAEPPadding"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the device's RSA public key as a standard PEM-encoded string.
     *
     * This string is **safe to share** — give it to whoever will encrypt
     * eBooks for this specific device.  It cannot be used to decrypt anything.
     *
     * May generate the key pair on first call (Keystore I/O); call from a
     * background coroutine.
     */
    fun getPublicKeyPem(): String {
        val encoded = getOrCreateKeyPair().public.encoded   // X.509 SubjectPublicKeyInfo DER
        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n" +
                b64.chunked(64).joinToString("\n") +
                "\n-----END PUBLIC KEY-----"
    }

    /**
     * Returns a human-readable 8-byte (64-bit) SHA-256 fingerprint of the
     * public key as colon-separated uppercase hex pairs.
     *
     * Example: `A3:7F:12:00:CC:44:BE:F1`
     *
     * Safe to display in Settings — uniquely identifies this device's key
     * without revealing anything secret.
     */
    fun getPublicKeyFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(getOrCreateKeyPair().public.encoded)
        return digest.take(8).joinToString(":") { "%02X".format(it) }
    }

    /**
     * Uses the Keystore-resident **private key** to RSA-OAEP-decrypt
     * [encryptedSessionKey], returning the raw AES session key bytes.
     *
     * Uses SHA-1 / MGF1-SHA-1 — the only OAEP parameter set universally
     * supported (and internally applied) by every Android Keystore
     * implementation, including OEM variants that silently ignore explicit
     * [OAEPParameterSpec] values and always use SHA-1 for MGF1.
     *
     * @throws GeneralSecurityException if the Keystore key is unavailable or
     *   the encrypted bytes were not produced by this device's public key.
     */
    fun decryptSessionKey(encryptedSessionKey: ByteArray): ByteArray {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = ks.getKey(KEYSTORE_ALIAS, null) as PrivateKey
        // SHA-1 / MGF1-SHA-1: matches every Android Keystore's actual behaviour
        // and matches encryptForDevice() in EncryptionUtils (v1.18+).
        val oaepParams = OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        )
        return Cipher.getInstance(CIPHER_TRANSFORM).run {
            init(Cipher.DECRYPT_MODE, privateKey, oaepParams)
            doFinal(encryptedSessionKey)
        }
    }


    /**
     * Returns `true` only if a valid RSA [KeyStore.PrivateKeyEntry] exists under
     * [KEYSTORE_ALIAS].  A stale [KeyStore.SecretKeyEntry] (from the previous
     * AES MasterKey implementation) returns `false` here.
     */
    fun hasKeyPair(): Boolean {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = runCatching { ks.getEntry(KEYSTORE_ALIAS, null) }.getOrNull()
        return entry is KeyStore.PrivateKeyEntry
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the existing RSA key pair from the Keystore, or generates a
     * new 2048-bit pair if none exists yet.
     *
     * [KeyGenParameterSpec] restricts the key to RSA-OAEP decryption only.
     * On devices with a TEE or StrongBox the private key is hardware-backed
     * and the private key bytes are never accessible to the OS or this app.
     *
     * ## Key migration (v1.18+)
     * Keys generated before v1.18 authorised only [KeyProperties.DIGEST_SHA256].
     * All encryption paths now use SHA-1/MGF1-SHA-1, the only OAEP parameter
     * set universally accepted by every Android Keystore variant (see
     * [CIPHER_TRANSFORM] comment).  A SHA-256-only key causes the Keystore to
     * throw [java.security.InvalidKeyException] when SHA-1 is requested, which
     * the caller's broad `catch` converts to a silent null — producing the
     * "encrypted on this device but unreadable on this device" failure seen on
     * Note 10 Lite and similar OEM devices.
     *
     * The fix: after locating an existing [KeyStore.PrivateKeyEntry], inspect
     * its [KeyInfo.digests] set.  If [KeyProperties.DIGEST_SHA1] is absent the
     * key cannot be used with the current cipher parameters and is replaced.
     * Regeneration invalidates any files that were encrypted with the old public
     * key; in the self-publish workflow the user simply re-exports the new key
     * and re-encrypts.
     */
    private fun getOrCreateKeyPair(): KeyPair {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // Only reuse the alias if it actually holds an RSA PrivateKeyEntry.
        // The previous app version stored an AES MasterKey (SecretKeyEntry) under
        // the same alias via EncryptedSharedPreferences; getCertificate() returns
        // null for that type, causing a NullPointerException.
        val existing = runCatching { ks.getEntry(KEYSTORE_ALIAS, null) }.getOrNull()
        if (existing is KeyStore.PrivateKeyEntry) {
            // Verify the key authorises SHA-1 OAEP decryption before reusing it.
            // Keys from app versions before v1.18 were generated with only
            // DIGEST_SHA256; they silently reject the SHA-1/MGF1-SHA-1 cipher
            // initialisation used by all current encrypt/decrypt paths.
            if (keySupportsOaepSha1(existing.privateKey)) {
                return KeyPair(existing.certificate.publicKey, existing.privateKey)
            }
            // Key exists but its authorised-digest set lacks SHA-1.
            // Delete it and fall through to generate a correctly-specced key.
            ks.deleteEntry(KEYSTORE_ALIAS)
        }

        // Stale entry present (e.g. old AES MasterKey, or just-deleted stale RSA key)
        // — remove it before generating the RSA pair so KeyPairGenerator can claim
        // the alias.
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            ks.deleteEntry(KEYSTORE_ALIAS)
        }

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(RSA_KEY_SIZE)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            // Authorize BOTH SHA-1 and SHA-256 as permitted digests.
            //
            // SHA-1 is the PRIMARY and universal hash.  Every encrypt path
            // (encryptForDevice in EncryptionUtils and the Web Crypto HTML
            // publisher tool) uses RSA-OAEP / SHA-1 / MGF1-SHA-1 — the only
            // OAEP parameter set that every Android Keystore variant (stock,
            // Samsung Knox, StrongBox) correctly honours.  A significant subset
            // of OEM Keystores (Samsung Exynos, MediaTek, certain Qualcomm
            // firmwares on Android 9–12) silently ignore any OAEPParameterSpec
            // and always use SHA-1 for MGF1 internally; specifying SHA-256
            // therefore produced a mismatch — the Keystore decrypted with SHA-1
            // MGF1, yielding garbage AES key bytes, failing the AES-GCM auth
            // tag with no RSA exception visible.
            //
            // SHA-1 is listed FIRST so that OEM Keystores that select the
            // first authorised digest when ignoring an explicit OAEPParameterSpec
            // default to SHA-1, matching the encryption side.
            //
            // SHA-256 is listed as a secondary authorised digest only so the
            // cipher-init probe in keySupportsOaepSha1() can distinguish this
            // freshly-generated key (SHA-1 + SHA-256) from an old SHA-256-only
            // key without needing a separate metadata flag.
            .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
            .build()

        return KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /**
     * Returns `true` if [privateKey] permits SHA-1/MGF1-SHA-1 RSA-OAEP decryption.
     *
     * ## Two-stage check
     *
     * **Stage 1 — [KeyInfo] inspection (fast path).**
     * Works for TEE-backed and software-backed keys on API 23+.
     * [KeyFactory.getKeySpec] returns a [KeyInfo] whose [KeyInfo.digests] set
     * is tested directly.
     *
     * **Stage 2 — cipher-init probe (StrongBox fallback).**
     * On devices with a StrongBox secure element (Samsung S22 Ultra, Pixel 6+,
     * etc.) [KeyFactory.getKeySpec] always throws because key metadata lives
     * inside the secure element and is inaccessible to the software
     * [KeyFactory].  The probe attempts [Cipher.init] with SHA-1/MGF1-SHA-1
     * OAEP parameters.  The Android Keystore evaluates the authorized-digest
     * set *at init time* and throws [java.security.InvalidKeyException]
     * immediately if SHA-1 is not permitted — no dummy ciphertext is needed.
     *
     * ## Why both stages matter
     *
     * Returning `false` blindly on any exception caused [getOrCreateKeyPair] to
     * delete and regenerate the key pair on **every call** on StrongBox devices,
     * silently rotating the key between "Copy My Key" on the S22 Ultra and the
     * subsequent encrypt step on Note 10 Lite — the cross-device failure in
     * scenario 2.
     *
     * Returning `true` blindly avoided churn but reused old SHA-256-only keys:
     * Note 10 Lite encrypted with the exported public key using SHA-1 OAEP;
     * the S22 Ultra then tried to decrypt with SHA-1 → Keystore rejected
     * (SHA-1 not in authorised digests of the old key) → fell back to SHA-256
     * → SHA-256 produced wrong bytes (file was SHA-1-encrypted) → both
     * attempts failed → scenario 2 still broken.
     *
     * The cipher-init probe correctly distinguishes a SHA-1-capable StrongBox
     * key from a SHA-256-only legacy one, replacing only keys that actually
     * need replacement.
     */
    private fun keySupportsOaepSha1(privateKey: PrivateKey): Boolean {
        // Stage 1: KeyInfo inspection — works for TEE/software-backed keys.
        try {
            val keyInfo = KeyFactory
                .getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
                .getKeySpec(privateKey, KeyInfo::class.java)
            return KeyProperties.DIGEST_SHA1 in keyInfo.digests
        } catch (_: Exception) {
            // getKeySpec throws on StrongBox keys — fall through to probe.
        }

        // Stage 2: cipher-init probe.
        // The Keystore checks digest authorisation at Cipher.init() time and
        // throws InvalidKeyException immediately if SHA-1 is not permitted.
        // No ciphertext is needed; the init call itself is the compatibility test.
        return try {
            Cipher.getInstance(CIPHER_TRANSFORM).init(
                Cipher.DECRYPT_MODE,
                privateKey,
                OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
            )
            true    // init accepted → key permits SHA-1 OAEP
        } catch (_: Exception) {
            false   // init rejected → key lacks SHA-1 → caller will regenerate
        }
    }
}
