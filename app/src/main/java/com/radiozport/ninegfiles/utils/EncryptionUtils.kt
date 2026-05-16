package com.radiozport.ninegfiles.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM file encryption with PBKDF2WithHmacSHA256 key derivation.
 *
 * Encrypted file format (all values big-endian):
 *   [4 bytes magic "9GEF"] [1 byte version=1] [16 bytes salt] [12 bytes IV]
 *   [encrypted payload + 16-byte GCM authentication tag]
 *
 * The GCM tag is appended by the Cipher automatically.
 */
object EncryptionUtils {

    private const val MAGIC = "9GEF"
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12          // 96-bit IV recommended for GCM
    private const val TAG_LEN = 128        // bits
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LEN = 256        // bits
    private const val BUFFER_SIZE = 64 * 1024
    const val ENCRYPTED_EXT = ".9genc"

    sealed class EncryptResult {
        data class Success(val outputFile: File) : EncryptResult()
        data class Failure(val reason: String) : EncryptResult()
        data class Progress(val bytesProcessed: Long, val totalBytes: Long) : EncryptResult()
    }

    // ─── Encrypt ──────────────────────────────────────────────────────────

    suspend fun encryptFile(
        source: File,
        password: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): EncryptResult = withContext(Dispatchers.IO) {
        try {
            val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
            val iv   = ByteArray(IV_LEN).also  { SecureRandom().nextBytes(it) }
            val key  = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
            }

            val destName = source.name + ENCRYPTED_EXT
            val dest = File(source.parent, destName)
            val total = source.length()

            FileOutputStream(dest).use { fos ->
                // Header
                fos.write(MAGIC.toByteArray(Charsets.US_ASCII))
                fos.write(VERSION.toInt())
                fos.write(salt)
                fos.write(iv)

                // Payload
                var processed = 0L
                val buf = ByteArray(BUFFER_SIZE)
                FileInputStream(source).use { fis ->
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        fos.write(cipher.update(buf, 0, read))
                        processed += read
                        onProgress?.invoke(processed, total)
                    }
                }
                fos.write(cipher.doFinal())
            }
            EncryptResult.Success(dest)
        } catch (e: Exception) {
            EncryptResult.Failure("Encryption failed: ${e.message}")
        }
    }

    // ─── Decrypt ──────────────────────────────────────────────────────────

    suspend fun decryptFile(
        source: File,
        password: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): EncryptResult = withContext(Dispatchers.IO) {
        try {
            // Wrap in DataInputStream so readFully() guarantees every header field is
            // read completely — InputStream.read(byte[]) is only required to return
            // *at least* one byte, not the full buffer.  On certain Android kernel /
            // file-system combinations the raw read() call returned a short count for
            // the salt, IV, or (in the 9GEK path) the 256-byte RSA-wrapped session key,
            // silently corrupting every field that followed and making decryption fail
            // on those devices while succeeding on others.
            java.io.DataInputStream(FileInputStream(source)).use { dis ->
                // Validate magic
                val magic = ByteArray(4).also { dis.readFully(it) }
                if (String(magic, Charsets.US_ASCII) != MAGIC)
                    return@withContext EncryptResult.Failure("Not a 9GFiles encrypted file")

                val version = dis.readByte()
                if (version != VERSION)
                    return@withContext EncryptResult.Failure("Unsupported encryption version: $version")

                val salt = ByteArray(SALT_LEN).also { dis.readFully(it) }
                val iv   = ByteArray(IV_LEN).also  { dis.readFully(it) }
                val key  = deriveKey(password, salt)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
                }

                // Destination: strip .9genc if present
                val destName = if (source.name.endsWith(ENCRYPTED_EXT))
                    source.name.dropLast(ENCRYPTED_EXT.length) else source.name + ".decrypted"
                val dest = File(source.parent, destName)
                val total = source.length()
                var processed = 0L

                FileOutputStream(dest).use { fos ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var read: Int
                    // Payload reads use the standard read() loop — partial reads are
                    // handled correctly here because we pass the actual byte count to
                    // cipher.update() and loop until EOF.
                    while (dis.read(buf).also { read = it } != -1) {
                        fos.write(cipher.update(buf, 0, read))
                        processed += read
                        onProgress?.invoke(processed, total)
                    }
                    // doFinal verifies GCM tag — throws AEADBadTagException if wrong password
                    fos.write(cipher.doFinal())
                }
                EncryptResult.Success(dest)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            EncryptResult.Failure("Wrong password — decryption failed")
        } catch (e: Exception) {
            EncryptResult.Failure("Decryption failed: ${e.message}")
        }
    }

    // ─── In-memory decrypt ────────────────────────────────────────────────

    /**
     * Decrypts [source] entirely into a [ByteArray] held in process memory.
     * No intermediate file is written; the caller is responsible for clearing
     * the returned array when it is no longer needed.
     *
     * Returns `null` if the file is not a valid 9GEF container, the password
     * is wrong, or any I/O error occurs.
     */
    suspend fun decryptToBytes(
        source: File,
        password: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            java.io.DataInputStream(FileInputStream(source)).use { dis ->
                // Validate header — readFully() guarantees each field is read in
                // its entirety; the raw read(byte[]) overload may return a short
                // count on certain Android kernel / FS configurations.
                val magic = ByteArray(4).also { dis.readFully(it) }
                if (String(magic, Charsets.US_ASCII) != MAGIC) return@withContext null

                val version = dis.readByte()
                if (version != VERSION) return@withContext null

                val salt = ByteArray(SALT_LEN).also { dis.readFully(it) }
                val iv   = ByteArray(IV_LEN).also  { dis.readFully(it) }
                val key  = deriveKey(password, salt)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
                }

                val bos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var read: Int
                while (dis.read(buf).also { read = it } != -1) {
                    bos.write(cipher.update(buf, 0, read))
                }
                // doFinal verifies the GCM authentication tag; throws on wrong password
                bos.write(cipher.doFinal())
                bos.toByteArray()
            }
        } catch (_: javax.crypto.AEADBadTagException) { null }
          catch (_: Exception)                         { null }
    }

    // ─── Device-key hybrid format (9GEK) ──────────────────────────────────

    /**
     * Magic bytes and version for the device-key hybrid format.
     *
     * Format layout (big-endian):
     * ```
     *   [4B  magic  "9GEK"]
     *   [1B  version = 1  ]
     *   [4B  enc_key_len  ]          ← byte-length of the RSA-encrypted AES key
     *   [enc_key_len bytes]          ← RSA-OAEP(device public key) wrapping a 256-bit AES key
     *   [12B AES-GCM IV   ]
     *   [ciphertext + 16B GCM tag]   ← AES-256-GCM encrypted ePub bytes
     * ```
     *
     * The AES session key is random per file; only the device whose RSA
     * private key is in the Keystore can unwrap it.
     */
    private const val MAGIC_DEVICE         = "9GEK"
    private const val VERSION_DEVICE: Byte = 1
    // Generic OAEP transform — explicit OAEPParameterSpec is supplied at
    // cipher-init time so the hash algorithms are unambiguous.
    //
    // OAEP parameter choice — SHA-1 / MGF1-SHA-1:
    //   The Android Keystore on a significant subset of devices (Samsung
    //   Exynos, MediaTek, Qualcomm running Android 9–12) silently ignores
    //   any OAEPParameterSpec and internally uses SHA-1 for MGF1 regardless
    //   of what is requested.  Specifying SHA-256 for MGF1 therefore produced
    //   a hash mismatch between the Java-side encryption (SHA-256 MGF1) and
    //   the Keystore-side decryption (SHA-1 MGF1), yielding garbage AES key
    //   bytes that failed the AES-GCM auth tag — no exception from the RSA
    //   step, just a silent wrong result.  SHA-1/MGF1-SHA-1 is the only set
    //   universally accepted and applied by every Android Keystore variant.
    //   Security note: SHA-1 for OAEP padding is acceptable for RSA key-
    //   wrapping (NIST SP 800-131A Rev 2 §6); real confidentiality comes from
    //   the 256-bit AES-GCM layer.
    private const val RSA_CIPHER           = "RSA/ECB/OAEPPadding"

    /**
     * Encrypts [source] for a specific device identified by [recipientPublicKeyPem].
     *
     * A fresh 256-bit AES session key is generated for every file.  That key
     * is wrapped with the device's RSA-2048 public key (RSA-OAEP / SHA-256)
     * and prepended as a header.  The ePub content is then encrypted with
     * AES-256-GCM.  Only the device holding the matching private key in its
     * Keystore can decrypt the result.
     *
     * The output file is written to [dest] (parent directory must be writable).
     *
     * @param recipientPublicKeyPem  PEM string obtained from the target device's
     *                               Settings → "Share Public Key".
     */
    suspend fun encryptForDevice(
        source: File,
        dest: File,
        recipientPublicKeyPem: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): EncryptResult = withContext(Dispatchers.IO) {
        try {
            // 1. Parse the PEM public key
            val pemBody = recipientPublicKeyPem
                .lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
            val keyBytes = android.util.Base64.decode(pemBody, android.util.Base64.DEFAULT)
            val publicKey = java.security.KeyFactory
                .getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))

            // 2. Generate a random AES-256 session key
            val sessionKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val sessionKey = javax.crypto.spec.SecretKeySpec(sessionKeyBytes, "AES")

            // 3. Wrap the session key with the device's RSA public key.
            //    SHA-1 / MGF1-SHA-1: the only OAEP parameter set reliably
            //    supported by every Android Keystore implementation (see the
            //    RSA_CIPHER constant comment above for full rationale).
            val oaepParams = OAEPParameterSpec(
                "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
            )
            val encryptedSessionKey = javax.crypto.Cipher.getInstance(RSA_CIPHER).run {
                init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey, oaepParams)
                doFinal(sessionKeyBytes)
            }

            // 4. AES-256-GCM encrypt the ePub
            val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
            val aesCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(TAG_LEN, iv))
            }

            val total = source.length()
            FileOutputStream(dest).use { fos ->
                // Header
                fos.write(MAGIC_DEVICE.toByteArray(Charsets.US_ASCII))
                fos.write(VERSION_DEVICE.toInt())
                // Encrypted session key (length-prefixed)
                val encKeyLen = encryptedSessionKey.size
                fos.write((encKeyLen shr 24) and 0xFF)
                fos.write((encKeyLen shr 16) and 0xFF)
                fos.write((encKeyLen shr  8) and 0xFF)
                fos.write( encKeyLen         and 0xFF)
                fos.write(encryptedSessionKey)
                // IV
                fos.write(iv)
                // Encrypted payload
                var processed = 0L
                val buf = ByteArray(BUFFER_SIZE)
                FileInputStream(source).use { fis ->
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        fos.write(aesCipher.update(buf, 0, read))
                        processed += read
                        onProgress?.invoke(processed, total)
                    }
                }
                fos.write(aesCipher.doFinal())
            }

            // Clear session key bytes from memory
            sessionKeyBytes.fill(0)
            EncryptResult.Success(dest)
        } catch (e: Exception) {
            EncryptResult.Failure("Device encryption failed: ${e.message}")
        }
    }

    /**
     * Single decryption attempt with the given [sessionKeyDecryptor].
     * Returns the decrypted ePub bytes on success, or `null` on any failure.
     */
    private suspend fun attemptDecryptDevice(
        source: File,
        sessionKeyDecryptor: (ByteArray) -> ByteArray
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            java.io.DataInputStream(FileInputStream(source)).use { dis ->
                // ── Header ─────────────────────────────────────────────────────────
                // readFully() guarantees every header field is fully read even on
                // kernels that return short counts from FileInputStream.read(byte[]).
                val magic = ByteArray(4).also { dis.readFully(it) }
                if (String(magic, Charsets.US_ASCII) != MAGIC_DEVICE) return@withContext null
                val version = dis.readByte()
                if (version != VERSION_DEVICE) return@withContext null

                // readInt() reads exactly 4 bytes big-endian — matches the four
                // fos.write() calls in encryptForDevice().
                val encKeyLen     = dis.readInt()
                val encSessionKey = ByteArray(encKeyLen).also { dis.readFully(it) }

                // Unwrap session key using the device private key (Keystore operation)
                val sessionKeyBytes = sessionKeyDecryptor(encSessionKey)
                val sessionKey = javax.crypto.spec.SecretKeySpec(sessionKeyBytes, "AES")

                // AES-256-GCM decrypt
                val iv = ByteArray(IV_LEN).also { dis.readFully(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(TAG_LEN, iv))
                }

                val bos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var read: Int
                while (dis.read(buf).also { read = it } != -1) {
                    bos.write(cipher.update(buf, 0, read))
                }
                // doFinal verifies GCM auth tag — throws if the session key is wrong
                bos.write(cipher.doFinal())

                sessionKeyBytes.fill(0)
                bos.toByteArray()
            }
        } catch (_: javax.crypto.AEADBadTagException) { null }
          catch (_: Exception)                         { null }
    }

    /**
     * Decrypts a `9GEK` device-encrypted file entirely into a [ByteArray] in
     * process memory.  No intermediate plaintext file is written to disk.
     *
     * The session key is unwrapped with SHA-1/MGF1-SHA-1 OAEP via
     * [sessionKeyDecryptor] — the only parameter set universally applied by
     * every Android Keystore variant.  SHA-256 is not used; it is not
     * reliably supported across all OEM Keystore implementations.
     *
     * Returns `null` if the file is not a valid `9GEK` container, the
     * decryptor fails, or any I/O or authentication error occurs.
     */
    suspend fun decryptDeviceToBytes(
        source: File,
        sessionKeyDecryptor: (ByteArray) -> ByteArray
    ): ByteArray? = withContext(Dispatchers.IO) {
        attemptDecryptDevice(source, sessionKeyDecryptor)
    }

    /**
     * Detects the encryption format of [file] by reading its 4-byte magic header.
     * Returns [EncryptionFormat.PASSWORD_BASED] (`9GEF`),
     * [EncryptionFormat.DEVICE_KEY] (`9GEK`), or `null` for unknown/plain files.
     */
    fun detectFormat(file: File): EncryptionFormat? = try {
        val magic = ByteArray(4)
        // readFully() — not read() — to ensure all 4 magic bytes are read even if
        // the underlying OS returns a short count on the first call.
        java.io.DataInputStream(java.io.FileInputStream(file)).use { it.readFully(magic) }
        when (String(magic, Charsets.US_ASCII)) {
            MAGIC        -> EncryptionFormat.PASSWORD_BASED
            MAGIC_DEVICE -> EncryptionFormat.DEVICE_KEY
            else         -> null
        }
    } catch (_: Exception) { null }

    enum class EncryptionFormat { PASSWORD_BASED, DEVICE_KEY }

    // ─── Helpers ──────────────────────────────────────────────────────────

    fun isEncrypted(file: File): Boolean = file.name.endsWith(ENCRYPTED_EXT)

    /**
     * Returns the inner extension of a `.9genc` file — the extension that
     * identifies the decrypted content type.
     *
     * Examples:
     *   "report.pdf.9genc"   → "pdf"
     *   "novel.epub.9genc"   → "epub"
     *   "notes.txt.9genc"    → "txt"
     *   "plain.9genc"        → ""   (no inner extension)
     *   "document.pdf"       → ""   (not encrypted)
     */
    fun innerExtension(file: File): String {
        if (!file.name.endsWith(ENCRYPTED_EXT, ignoreCase = true)) return ""
        val inner = file.name.dropLast(ENCRYPTED_EXT.length)  // e.g. "report.pdf"
        return inner.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    }

    /**
     * Decrypts a `9GEK` device-key file to a temporary file on disk.
     *
     * This is required for readers that need a real file descriptor rather than
     * in-memory bytes (e.g. [android.graphics.pdf.PdfRenderer]).
     *
     * The caller is responsible for deleting [tempFile] when finished.
     *
     * @param source             The `.9genc` encrypted file.
     * @param tempFile           Destination temp file path; created/overwritten.
     * @param sessionKeyDecryptor Callback that unwraps the RSA-encrypted session key
     *                           via the Android Keystore.
     * @return `true` on success; `false` if the file is not a valid `9GEK` container
     *         or decryption fails.
     */
    suspend fun decryptDeviceToTempFile(
        source: File,
        tempFile: File,
        sessionKeyDecryptor: (ByteArray) -> ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            java.io.DataInputStream(java.io.FileInputStream(source)).use { dis ->
                val magic = ByteArray(4).also { dis.readFully(it) }
                if (String(magic, Charsets.US_ASCII) != MAGIC_DEVICE) return@withContext false
                val version = dis.readByte()
                if (version != VERSION_DEVICE) return@withContext false

                val encKeyLen = dis.readInt()
                val encSessionKey = ByteArray(encKeyLen).also { dis.readFully(it) }
                val sessionKeyBytes = sessionKeyDecryptor(encSessionKey)
                val sessionKey = javax.crypto.spec.SecretKeySpec(sessionKeyBytes, "AES")

                val iv = ByteArray(IV_LEN).also { dis.readFully(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(TAG_LEN, iv))
                }

                java.io.FileOutputStream(tempFile).use { fos ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (dis.read(buf).also { read = it } != -1) {
                        fos.write(cipher.update(buf, 0, read))
                    }
                    fos.write(cipher.doFinal())
                }
                sessionKeyBytes.fill(0)
                true
            }
        } catch (_: javax.crypto.AEADBadTagException) { tempFile.delete(); false }
          catch (_: Exception)                         { tempFile.delete(); false }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN)
        val raw  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}
