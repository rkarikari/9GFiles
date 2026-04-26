package com.radiozport.ninegfiles.ui.dialogs

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.utils.EncryptionUtils
import kotlinx.coroutines.launch

/**
 * Bottom-sheet dialog for file encryption / decryption.
 *
 * Encrypting:  shows two password fields + strength indicator
 * Decrypting:  shows single password field
 */
class EncryptFileDialog : BottomSheetDialogFragment() {

    private lateinit var item: FileItem
    private var onComplete: ((success: Boolean, message: String) -> Unit)? = null

    companion object {
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            item: FileItem,
            onComplete: (success: Boolean, message: String) -> Unit
        ) = EncryptFileDialog().apply {
            this.item = item
            this.onComplete = onComplete
        }.show(fm, "EncryptFile")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_encrypt_file, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isDecrypt = EncryptionUtils.isEncrypted(item.file)

        view.findViewById<TextView>(R.id.tvEncryptTitle).text =
            if (isDecrypt) "Decrypt File" else "Encrypt File"
        view.findViewById<TextView>(R.id.tvEncryptSubtitle).text =
            if (isDecrypt) "Enter password to decrypt \"${item.name}\""
            else "Encrypt \"${item.name}\" with AES-256-GCM"

        val confirmLayout = view.findViewById<TextInputLayout>(R.id.tilPasswordConfirm)
        val strengthBar   = view.findViewById<ProgressBar>(R.id.strengthBar)
        val tvStrength    = view.findViewById<TextView>(R.id.tvPasswordStrength)

        // Hide confirm + strength meter for decrypt
        confirmLayout.isVisible = !isDecrypt
        strengthBar.isVisible   = !isDecrypt
        tvStrength.isVisible    = !isDecrypt

        val etPassword = view.findViewById<TextInputEditText>(R.id.etPassword)
        val etConfirm  = view.findViewById<TextInputEditText>(R.id.etPasswordConfirm)
        val btnAction  = view.findViewById<MaterialButton>(R.id.btnEncryptAction)
        val progress   = view.findViewById<LinearLayout>(R.id.layoutProgress)
        val tvProgress = view.findViewById<TextView>(R.id.tvProgressLabel)

        btnAction.text = if (isDecrypt) "Decrypt" else "Encrypt"

        // Live password-strength feedback
        if (!isDecrypt) {
            etPassword.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val score = passwordStrength(s?.toString() ?: "")
                    strengthBar.progress = score
                    tvStrength.text = when {
                        score < 25 -> "Weak"
                        score < 50 -> "Fair"
                        score < 75 -> "Good"
                        else       -> "Strong"
                    }
                    tvStrength.setTextColor(
                        requireContext().getColor(
                            when {
                                score < 25 -> android.R.color.holo_red_light
                                score < 50 -> android.R.color.holo_orange_light
                                else       -> android.R.color.holo_green_light
                            }
                        )
                    )
                }
            })
        }

        btnAction.setOnClickListener {
            val pw = etPassword.text?.toString() ?: ""
            if (pw.isEmpty()) {
                view.findViewById<TextInputLayout>(R.id.tilPassword).error = "Password required"
                return@setOnClickListener
            }
            if (!isDecrypt) {
                val confirm = etConfirm.text?.toString() ?: ""
                if (pw != confirm) {
                    confirmLayout.error = "Passwords do not match"
                    return@setOnClickListener
                }
                if (pw.length < 6) {
                    view.findViewById<TextInputLayout>(R.id.tilPassword).error = "Minimum 6 characters"
                    return@setOnClickListener
                }
            }

            btnAction.isEnabled = false
            progress.isVisible = true

            lifecycleScope.launch {
                val result = if (isDecrypt)
                    EncryptionUtils.decryptFile(item.file, pw) { done, total ->
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        tvProgress.post { tvProgress.text = "Decrypting… $pct%" }
                    }
                else
                    EncryptionUtils.encryptFile(item.file, pw) { done, total ->
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        tvProgress.post { tvProgress.text = "Encrypting… $pct%" }
                    }

                when (result) {
                    is EncryptionUtils.EncryptResult.Success -> {
                        onComplete?.invoke(true, "Done → ${result.outputFile.name}")
                        dismiss()
                    }
                    is EncryptionUtils.EncryptResult.Failure -> {
                        progress.isVisible = false
                        btnAction.isEnabled = true
                        view.findViewById<TextInputLayout>(R.id.tilPassword).error = result.reason
                    }
                    else -> Unit
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.btnEncryptCancel).setOnClickListener { dismiss() }
    }

    /** Rough 0-100 strength score */
    private fun passwordStrength(pw: String): Int {
        if (pw.isEmpty()) return 0
        var score = (pw.length * 4).coerceAtMost(40)
        if (pw.any { it.isUpperCase() })   score += 15
        if (pw.any { it.isLowerCase() })   score += 10
        if (pw.any { it.isDigit() })       score += 15
        if (pw.any { !it.isLetterOrDigit() }) score += 20
        return score.coerceIn(0, 100)
    }
}
