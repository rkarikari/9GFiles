package com.radiozport.ninegfiles.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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
            FileInputStream(source).use { fis ->
                // Validate magic
                val magic = ByteArray(4).also { fis.read(it) }
                if (String(magic, Charsets.US_ASCII) != MAGIC)
                    return@withContext EncryptResult.Failure("Not a 9GFiles encrypted file")

                val version = fis.read().toByte()
                if (version != VERSION)
                    return@withContext EncryptResult.Failure("Unsupported encryption version: $version")

                val salt = ByteArray(SALT_LEN).also { fis.read(it) }
                val iv   = ByteArray(IV_LEN).also  { fis.read(it) }
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
                    while (fis.read(buf).also { read = it } != -1) {
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

    // ─── Helpers ──────────────────────────────────────────────────────────

    fun isEncrypted(file: File): Boolean = file.name.endsWith(ENCRYPTED_EXT)

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN)
        val raw  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}
