package com.radiozport.ninegfiles.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Central app-lock controller.
 *
 * Two independent lock vectors are supported:
 *   1. Biometric / device credential  — unlocks the whole app
 *   2. PIN / biometric                 — unlocks the Secure Vault specifically
 *
 * Usage from any Fragment:
 *   AppLockManager.authenticate(activity, reason = "Unlock 9GFiles") { success ->
 *       if (success) { /* proceed */ }
 *   }
 */
object AppLockManager {

    private const val PREFS_NAME = "app_lock_secure_prefs"
    private const val KEY_APP_LOCK_ENABLED  = "app_lock_enabled"
    private const val KEY_VAULT_PIN_HASH    = "vault_pin_hash"
    private const val KEY_REQUIRE_BIOMETRIC = "require_biometric"

    // ─── Availability ────────────────────────────────────────────────────

    fun isBiometricAvailable(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    // ─── Prefs (encrypted) ───────────────────────────────────────────────

    private fun getSecurePrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun isAppLockEnabled(context: Context): Boolean =
        getSecurePrefs(context).getBoolean(KEY_APP_LOCK_ENABLED, false)

    fun setAppLockEnabled(context: Context, enabled: Boolean) =
        getSecurePrefs(context).edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()

    fun isVaultPinSet(context: Context): Boolean =
        getSecurePrefs(context).getString(KEY_VAULT_PIN_HASH, null) != null

    fun setVaultPin(context: Context, pin: String) {
        val hash = hashPin(pin)
        getSecurePrefs(context).edit().putString(KEY_VAULT_PIN_HASH, hash).apply()
    }

    fun verifyVaultPin(context: Context, pin: String): Boolean {
        val stored = getSecurePrefs(context).getString(KEY_VAULT_PIN_HASH, null) ?: return false
        return stored == hashPin(pin)
    }

    fun clearVaultPin(context: Context) =
        getSecurePrefs(context).edit().remove(KEY_VAULT_PIN_HASH).apply()

    private fun hashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ─── BiometricPrompt ─────────────────────────────────────────────────

    /**
     * Show a biometric (or device-credential fallback) prompt.
     *
     * @param activity   The calling FragmentActivity
     * @param title      Dialog title shown to the user
     * @param subtitle   Optional subtitle
     * @param onResult   Called on main thread with `true` on success, `false` on failure/cancel
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Biometric Authentication",
        subtitle: String = "Use your fingerprint or device lock to continue",
        onResult: (success: Boolean, errorMsg: String?) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true, null)
            }
            override fun onAuthenticationFailed() {
                // Don't close — let user retry; BiometricPrompt handles retries internally
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false, errString.toString())
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    /**
     * Vault-specific auth: prefer biometric, PIN fallback when biometric unavailable.
     */
    fun authenticateVault(
        activity: FragmentActivity,
        context: Context,
        onResult: (success: Boolean, errorMsg: String?) -> Unit
    ) {
        if (isBiometricAvailable(context)) {
            authenticate(
                activity,
                title = "Unlock Secure Vault",
                subtitle = "Authenticate to access your encrypted vault",
                onResult = onResult
            )
        } else {
            // Fall back to PIN dialog — caller must show PinDialog
            onResult(false, "SHOW_PIN_DIALOG")
        }
    }
}
