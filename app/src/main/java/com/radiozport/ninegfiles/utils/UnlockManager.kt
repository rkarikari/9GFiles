package com.radiozport.ninegfiles.utils

import android.util.Base64
import java.security.MessageDigest

/**
 * Manages the Security & Privacy section unlock mechanism.
 *
 * ## How it works
 *
 * An unlock code is derived deterministically from a device's RSA public key DER bytes,
 * making it:
 *  - **Device-unique** — only the public key of a specific device produces its code.
 *  - **Non-reversible** — the code cannot be used to recover any key material.
 *  - **Cross-device capable** — an operator can paste any device's PEM public key into
 *    the secret generator screen to produce that device's unlock code without physical
 *    access to the device.
 *
 * ## Code format
 * 19-character uppercase hex string in four groups: `A3F1-BC92-00EE-D74A`.
 * SHA-256(pubKeyDER ‖ SALT), first 8 bytes, hex-encoded.
 *
 * ## Unlock entry
 * Visible in Settings (always). The user enters the code they received; it is
 * validated against THIS device's derived code and, if correct, persists the
 * unlock state via AppPreferences.
 *
 * ## Secret generator screen
 * Accessible only via the 9-tap Easter egg on the version/copyright block in Settings.
 * Shows this device's code AND lets an operator generate a code for any other device
 * by pasting its PEM public key.
 */
object UnlockManager {

    /** Salt baked into the hash so codes cannot be brute-forced from a public key alone. */
    private const val SALT = "9GFiles_SecurityUnlock_v1"

    // ── Code generation ───────────────────────────────────────────────────────

    /**
     * Derives the unlock code for THIS device from its Keystore RSA public key.
     * Must be called from a background coroutine (Keystore I/O).
     *
     * @return 19-character string like `A3F1-BC92-00EE-D74A`
     */
    fun generateUnlockCode(): String {
        val pem = DeviceKeyManager.getPublicKeyPem()
        // getPublicKeyPem() always returns a well-formed PEM from the Keystore,
        // so generateUnlockCodeFromPem() will never return null here.
        return generateUnlockCodeFromPem(pem)
            ?: error("Failed to derive unlock code from device public key")
    }

    /**
     * Derives the unlock code for ANY device given its PEM public key string.
     *
     * The PEM can be the output of "Share Public Key" from any device's Settings screen.
     * This allows an operator to generate unlock codes for remote devices without
     * physical access — they just need the device's exported public key.
     *
     * This function is pure (no Keystore I/O) and can be called on any thread.
     *
     * @param pem  PEM-encoded RSA public key (with or without `-----BEGIN PUBLIC KEY-----` headers)
     * @return 19-character unlock code for the device that owns [pem], or `null` if [pem] is invalid.
     */
    fun generateUnlockCodeFromPem(pem: String): String? {
        return try {
            val b64Body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()
            if (b64Body.isEmpty()) return null
            val pubKeyDer = Base64.decode(b64Body, Base64.NO_WRAP)
            deriveCode(pubKeyDer)
        } catch (_: Exception) {
            null
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Validates a user-supplied [code] against THIS device's public key.
     *
     * Case-insensitive; hyphens are ignored, so `a3f1bc9200eed74a` and
     * `A3F1-BC92-00EE-D74A` are both accepted.
     *
     * Must be called from a background coroutine (calls [generateUnlockCode]).
     *
     * @return `true` if [code] matches the code derived from this device's public key.
     */
    fun validateCode(code: String): Boolean {
        val expected = generateUnlockCode() ?: return false
        val normalise: (String) -> String = { it.replace("-", "").uppercase().trim() }
        return normalise(code) == normalise(expected)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun deriveCode(pubKeyDer: ByteArray): String {
        val saltBytes = SALT.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(pubKeyDer + saltBytes)
        val hex = digest.take(8).joinToString("") { "%02X".format(it) }
        return "${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}"
    }
}
