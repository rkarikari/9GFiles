package com.radiozport.ninegfiles.ui.tools

import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.UnlockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Secret unlock-code **generator** screen.
 *
 * Accessible only via the 9-tap Easter egg on the version/copyright block in Settings.
 * It is never reachable from any menu, drawer, or bottom-nav entry.
 *
 * ## Two panels
 *
 * **This Device**
 * Displays the unlock code derived from this device's Keystore RSA public key,
 * along with its short fingerprint for verification.  A "Copy" button puts the
 * code on the clipboard.
 *
 * **Generate for Another Device**
 * Accepts a PEM public key (pasted from another device's "Share Public Key" share-sheet)
 * and derives that device's unlock code — allowing an operator to unlock remote
 * devices without physical access.
 *
 * The "Enter Unlock Code" entry lives in Settings (always visible) and is
 * intentionally NOT duplicated here.
 */
class UnlockToolsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_unlock_tools, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── This device panel ─────────────────────────────────────────────────
        val tvThisCode        = view.findViewById<TextView>(R.id.tvGeneratedUnlockCode)
        val tvFingerprint     = view.findViewById<TextView>(R.id.tvUnlockDeviceFingerprint)
        val btnCopyThisCode   = view.findViewById<Button>(R.id.btnCopyUnlockCode)

        tvThisCode.text    = "Generating…"
        tvFingerprint?.text = "…"

        viewLifecycleOwner.lifecycleScope.launch {
            val code        = withContext(Dispatchers.IO) { UnlockManager.generateUnlockCode() }
            val fingerprint = withContext(Dispatchers.IO) { DeviceKeyManager.getPublicKeyFingerprint() }

            tvThisCode.text     = code ?: "Error"
            tvFingerprint?.text = "Key fingerprint: $fingerprint"

            btnCopyThisCode?.setOnClickListener {
                copyToClipboard(code ?: return@setOnClickListener, "Unlock Code")
                Snackbar.make(view, "Code copied to clipboard", Snackbar.LENGTH_SHORT).show()
            }
        }

        // ── Generate for another device panel ─────────────────────────────────
        val tilPem            = view.findViewById<TextInputLayout>(R.id.tilOtherDevicePem)
        val etPem             = view.findViewById<TextInputEditText>(R.id.etOtherDevicePem)
        val btnGenerateOther  = view.findViewById<Button>(R.id.btnGenerateOtherCode)
        val tvOtherCode       = view.findViewById<TextView>(R.id.tvOtherDeviceCode)
        val btnCopyOtherCode  = view.findViewById<Button>(R.id.btnCopyOtherCode)

        btnGenerateOther?.setOnClickListener {
            val pem = etPem?.text?.toString()?.trim() ?: ""
            if (pem.isEmpty()) {
                tilPem?.error = "Paste the other device's public key here"
                return@setOnClickListener
            }
            tilPem?.error = null
            tvOtherCode?.text = "Generating…"
            btnCopyOtherCode?.visibility = View.GONE

            viewLifecycleOwner.lifecycleScope.launch {
                val code = withContext(Dispatchers.IO) { UnlockManager.generateUnlockCodeFromPem(pem) }
                if (code != null) {
                    tvOtherCode?.text = code
                    btnCopyOtherCode?.visibility = View.VISIBLE
                    btnCopyOtherCode?.setOnClickListener {
                        copyToClipboard(code, "Unlock Code")
                        Snackbar.make(view, "Code copied to clipboard", Snackbar.LENGTH_SHORT).show()
                    }
                } else {
                    tvOtherCode?.text = "Invalid public key — paste the full PEM block"
                    tilPem?.error = "Could not parse this PEM"
                    btnCopyOtherCode?.visibility = View.GONE
                }
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }
}
