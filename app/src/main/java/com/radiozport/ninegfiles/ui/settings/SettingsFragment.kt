package com.radiozport.ninegfiles.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.preferences.AppPreferences
import com.radiozport.ninegfiles.databinding.FragmentSettingsBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.UnlockManager
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()
    private lateinit var prefs: AppPreferences

    private val accentPalette = linkedMapOf(
        "Purple (Default)" to 0xFF6750A4.toInt(),
        "Blue"             to 0xFF1565C0.toInt(),
        "Green"            to 0xFF2E7D32.toInt(),
        "Teal"             to 0xFF00695C.toInt(),
        "Orange"           to 0xFFE65100.toInt(),
        "Red"              to 0xFFC62828.toInt(),
        "Pink"             to 0xFFAD1457.toInt(),
        "Indigo"           to 0xFF283593.toInt()
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = (requireActivity().application as NineGFilesApp).preferences
        loadCurrentSettings()
        setupListeners()
        setupSecuritySettings()
        setupDeviceKeySection()
        setupUnlockEntry()
    }

    /**
     * Populates the eBook encryption card in Settings.
     *
     * Displays the **public key fingerprint** (a short, safe-to-share identifier)
     * and provides a "Share Public Key" button that opens the Android share sheet
     * with the full PEM public key.  The recipient uses this PEM to encrypt eBooks
     * that can only be decrypted on this device.
     *
     * The private key is never exposed here — it lives exclusively in the
     * Android Keystore and is not accessible to this code.
     */
    private fun setupDeviceKeySection() {
        val tvFingerprint  = binding.root.findViewById<android.widget.TextView>(R.id.tvDeviceKey)
            ?: return
        val btnShare       = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyDeviceKey)
            ?: return
        val tvKeystoreAlias = binding.root.findViewById<android.widget.TextView>(R.id.tvKeystoreAlias)

        tvKeystoreAlias?.text = "Keystore alias: ${DeviceKeyManager.KEYSTORE_ALIAS}"
        tvFingerprint.text = "Generating…"

        viewLifecycleOwner.lifecycleScope.launch {
            // Key pair generation / Keystore I/O must run off the main thread.
            val fingerprint = withContext(Dispatchers.IO) {
                DeviceKeyManager.getPublicKeyFingerprint()
            }
            tvFingerprint.text = fingerprint

            btnShare.setOnClickListener {
                // Fetch the PEM on IO then open the share sheet on main.
                viewLifecycleOwner.lifecycleScope.launch {
                    val pem = withContext(Dispatchers.IO) { DeviceKeyManager.getPublicKeyPem() }
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "9GFiles Device Public Key")
                        putExtra(android.content.Intent.EXTRA_TEXT, pem)
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Share Device Public Key"))
                }
            }
        }
    }

    private fun loadCurrentSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.switchHidden.isChecked        = prefs.showHidden.first()
            binding.switchExtensions.isChecked    = prefs.showExtensions.first()
            binding.switchConfirmDelete.isChecked = prefs.confirmDelete.first()
            binding.switchFoldersFirst.isChecked  = prefs.foldersFirst.first()
            binding.switchVibrate.isChecked       = prefs.vibrateOnAction.first()
            binding.switchDoubleTapBack.isChecked = prefs.doubleTapBack.first()
            binding.switchKeepPasteBar.isChecked  = prefs.keepPasteBar.first()
            binding.switchKeepScreenOn.isChecked  = prefs.keepScreenOn.first()
            binding.switchShowFileInfo.isChecked  = prefs.showFileInfo.first()
            binding.switchVaultDeleteOriginal.isChecked    = prefs.vaultDeleteOriginal.first()
            binding.switchVaultRestoreOnExport.isChecked   = prefs.vaultRestoreOnExport.first()
            binding.switchVaultDeleteAfterExport.isChecked = prefs.vaultDeleteAfterExport.first()

            val quality = prefs.thumbnailQuality.first()
            binding.sliderThumbnailQuality.value = quality.toFloat()
            binding.tvThumbnailQuality.text = "$quality%"

            val gridSpan = prefs.gridSpanCount.first()
            binding.sliderGridColumns.value = gridSpan.toFloat()
            binding.tvGridColumns.text = "$gridSpan columns"

            binding.tvCurrentTheme.text =
                prefs.themeMode.first().replaceFirstChar { it.uppercase() }

            val accentColor = prefs.accentColor.first()
            val accentName = accentPalette.entries.firstOrNull { it.value == accentColor }?.key
                ?: "Custom"
            binding.tvCurrentAccent.text = accentName

            val density = prefs.listDensity.first()
            binding.tvCurrentDensity.text = density.replaceFirstChar { it.uppercase() }

            // ── New preferences ───────────────────────────────────────────────
            binding.switchRememberLastPath.isChecked = prefs.rememberLastPath.first()
            binding.switchShowThumbnails.isChecked   = prefs.showThumbnails.first()
            binding.switchShowFileTypeIcons.isChecked = prefs.showFileTypeIcons.first()

            val trashDays = prefs.trashAutoCleanDays.first()
            binding.tvTrashAutoClean.text = if (trashDays == 0) "Never" else "$trashDays days"

            val dateFormatPref = prefs.dateFormat.first()
            binding.tvDateFormat.text = when (dateFormatPref) {
                "short"  -> "Short (4/30/26)"
                "iso"    -> "ISO 8601 (2026-04-30)"
                else     -> "Medium (Apr 30, 2026)"
            }
        }
    }

    private fun setupListeners() {
        binding.switchHidden.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setShowHidden(c) }
        }
        binding.switchExtensions.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setShowExtensions(c) }
        }
        binding.switchConfirmDelete.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setConfirmDelete(c) }
        }
        binding.switchFoldersFirst.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setFoldersFirst(c) }
        }
        binding.switchVibrate.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setVibrateOnAction(c) }
        }
        binding.switchDoubleTapBack.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setDoubleTapBack(c) }
        }
        binding.switchKeepPasteBar.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setKeepPasteBar(c) }
        }
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, keep ->
            lifecycleScope.launch { prefs.setKeepScreenOn(keep) }
            // Apply FLAG_KEEP_SCREEN_ON to the current window immediately.
            if (keep) requireActivity().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else      requireActivity().window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding.switchShowFileInfo.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setShowFileInfo(c) }
        }
        binding.switchVaultDeleteOriginal.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setVaultDeleteOriginal(c) }
        }
        binding.switchVaultRestoreOnExport.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setVaultRestoreOnExport(c) }
        }
        binding.switchVaultDeleteAfterExport.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setVaultDeleteAfterExport(c) }
        }
        binding.sliderThumbnailQuality.addOnChangeListener { _, value, _ ->
            val q = value.toInt()
            binding.tvThumbnailQuality.text = "$q%"
            lifecycleScope.launch { prefs.setThumbnailQuality(q) }
        }
        binding.sliderGridColumns.addOnChangeListener { _, value, _ ->
            val span = value.toInt()
            binding.tvGridColumns.text = "$span columns"
            lifecycleScope.launch { prefs.setGridSpanCount(span) }
        }
        binding.btnTheme.setOnClickListener { showThemeDialog() }
        binding.btnAccentColor.setOnClickListener { showAccentColorDialog() }
        binding.btnDensity.setOnClickListener { showDensityDialog() }

        binding.btnClearRecent.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Recent Files?")
                .setMessage("This will clear all recent file history.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        (requireActivity().application as NineGFilesApp)
                            .database.recentFileDao().clearAll()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.tvVersion.text = "Version ${
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }"
        binding.tvCopyright.text = getString(R.string.app_copyright)

        // ── 9-tap Easter egg → hidden "Tools" (unlock code generator) ─────────
        // Tapping the version/copyright info block 9 times in quick succession
        // navigates to the secret UnlockToolsFragment.  No visual hint is given.
        var tapCount = 0
        var lastTapTime = 0L
        val tapResetMs = 2000L   // reset counter after 2 s of inactivity

        val easterEggListener = android.view.View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > tapResetMs) tapCount = 0
            lastTapTime = now
            tapCount++
            if (tapCount >= 9) {
                tapCount = 0
                try {
                    findNavController()
                        .navigate(R.id.action_settings_to_unlock_tools)
                } catch (_: Exception) { /* nav state race — ignore */ }
            }
        }
        binding.tvVersion.setOnClickListener(easterEggListener)
        binding.tvCopyright.setOnClickListener(easterEggListener)

        // ── New settings ─────────────────────────────────────────────────────
        binding.switchRememberLastPath.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setRememberLastPath(c) }
        }
        binding.switchShowThumbnails.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setShowThumbnails(c) }
        }
        binding.switchShowFileTypeIcons.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setShowFileTypeIcons(c) }
        }
        binding.btnTrashAutoClean.setOnClickListener { showTrashAutoCleanDialog() }
        binding.btnDateFormat.setOnClickListener { showDateFormatDialog() }
    }

    private fun showDensityDialog() {
        val labels = arrayOf("Compact  (48 dp)", "Normal  (64 dp)", "Comfortable  (80 dp)")
        val values = arrayOf("compact", "normal", "comfortable")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("List Density")
            .setItems(labels) { _, which ->
                val selected = values[which]
                lifecycleScope.launch { prefs.setListDensity(selected) }
                binding.tvCurrentDensity.text = selected.replaceFirstChar { it.uppercase() }
            }
            .show()
    }

    private fun showThemeDialog() {
        val options = arrayOf("System Default", "Light", "Dark")
        val values  = arrayOf("system", "light", "dark")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Choose Theme")
            .setItems(options) { _, which ->
                val selected = values[which]
                lifecycleScope.launch { prefs.setThemeMode(selected) }
                AppCompatDelegate.setDefaultNightMode(when (selected) {
                    "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                })
                binding.tvCurrentTheme.text = options[which]
            }
            .show()
    }

    private fun showAccentColorDialog() {
        val names  = accentPalette.keys.toTypedArray()
        val colors = accentPalette.values.toIntArray()

        // We need a reference to dismiss from inside swatch click
        var dialog: androidx.appcompat.app.AlertDialog? = null

        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        var currentRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        names.forEachIndexed { idx, name ->
            // Start a new row every 4 swatches
            if (idx > 0 && idx % 4 == 0) {
                contentLayout.addView(currentRow)
                currentRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8 }
                }
            }

            val sizePx = (52 * resources.displayMetrics.density).toInt()
            val swatch = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(8, 4, 8, 4)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(colors[idx])
                }
                isClickable = true
                isFocusable = true
                contentDescription = name
                setOnClickListener {
                    applyAccentColor(colors[idx], name)
                    dialog?.dismiss()
                }
            }
            currentRow.addView(swatch)
        }
        contentLayout.addView(currentRow)

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Accent Color")
            .setView(contentLayout)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun applyAccentColor(color: Int, name: String) {
        lifecycleScope.launch { prefs.setAccentColor(color) }
        binding.tvCurrentAccent.text = name
        Snackbar.make(binding.root, "Accent: $name — restart to apply", Snackbar.LENGTH_LONG).show()
    }

    private fun setupSecuritySettings() {
        val appLockSwitch = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAppLock) ?: return
        appLockSwitch.isChecked = com.radiozport.ninegfiles.utils.AppLockManager.isAppLockEnabled(requireContext())
        appLockSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled && !com.radiozport.ninegfiles.utils.AppLockManager.isBiometricAvailable(requireContext())) {
                appLockSwitch.isChecked = false
                Snackbar.make(binding.root, "No biometric / device lock set up — configure in device Settings first", Snackbar.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            com.radiozport.ninegfiles.utils.AppLockManager.setAppLockEnabled(requireContext(), enabled)
            Snackbar.make(binding.root, if (enabled) "App Lock enabled" else "App Lock disabled", Snackbar.LENGTH_SHORT).show()
        }

        binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSetVaultPin)
            ?.setOnClickListener { showSetVaultPinDialog() }
    }

    private fun showSetVaultPinDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPasswordConfirm)?.visibility = android.view.View.GONE
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Vault PIN")
            .setMessage("This PIN unlocks the Secure Vault when biometrics are unavailable.")
            .setView(v)
            .setPositiveButton("Set PIN") { _, _ ->
                val pin = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pin.length >= 4) {
                    com.radiozport.ninegfiles.utils.AppLockManager.setVaultPin(requireContext(), pin)
                    Snackbar.make(binding.root, "Vault PIN set", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, "PIN must be at least 4 digits", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTrashAutoCleanDialog() {
        val options = arrayOf("Never", "7 days", "15 days", "30 days", "60 days", "90 days")
        val days    = intArrayOf(0, 7, 15, 30, 60, 90)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Auto-clean Trash After")
            .setItems(options) { _, which ->
                val d = days[which]
                lifecycleScope.launch { prefs.setTrashAutoCleanDays(d) }
                binding.tvTrashAutoClean.text = if (d == 0) "Never" else "$d days"
            }
            .show()
    }

    private fun showDateFormatDialog() {
        val options = arrayOf("Short  (4/30/26)", "Medium  (Apr 30, 2026)", "ISO 8601  (2026-04-30)")
        val values  = arrayOf("short", "medium", "iso")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Date Format")
            .setItems(options) { _, which ->
                val v = values[which]
                lifecycleScope.launch { prefs.setDateFormat(v) }
                binding.tvDateFormat.text = options[which].substringBefore("  (")
                    .trim() + " — " + options[which].substringAfter("  (").trimEnd(')')
            }
            .show()
    }

    private fun setupUnlockEntry() {
        val tilCode    = binding.root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSettingsUnlockCode) ?: return
        val etCode     = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSettingsUnlockCode) ?: return
        val btnApply   = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSettingsApplyUnlock) ?: return
        val tvStatus   = binding.root.findViewById<android.widget.TextView>(R.id.tvSettingsUnlockStatus)

        // Reflect current unlock state immediately
        viewLifecycleOwner.lifecycleScope.launch {
            prefs.securitySectionUnlocked.collect { unlocked ->
                tvStatus?.text = if (unlocked) "✓ Section unlocked" else ""
                btnApply.isEnabled = !unlocked
                if (unlocked) {
                    etCode.setText("")
                    tilCode.error = null
                }
            }
        }

        // Auto-format as XXXX-XXXX-XXXX-XXXX while typing
        var isFormatting = false
        etCode.addTextChangedListener { editable ->
            if (isFormatting) return@addTextChangedListener
            isFormatting = true
            val raw = editable.toString().replace("-", "").uppercase().take(16)
            val formatted = raw.chunked(4).joinToString("-")
            etCode.setText(formatted)
            etCode.setSelection(formatted.length)
            isFormatting = false
        }

        btnApply.setOnClickListener {
            val entered = etCode.text?.toString()?.trim() ?: ""
            if (entered.replace("-", "").length < 16) {
                tilCode.error = "Enter the full 16-character code"
                return@setOnClickListener
            }
            tilCode.error = null
            viewLifecycleOwner.lifecycleScope.launch {
                val valid = withContext(kotlinx.coroutines.Dispatchers.IO) { UnlockManager.validateCode(entered) }
                if (valid) {
                    prefs.setSecuritySectionUnlocked(true)
                    Snackbar.make(binding.root, "✓ Security & Privacy section unlocked!", Snackbar.LENGTH_LONG).show()
                } else {
                    tilCode.error = "Invalid code — this code is not for this device"
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
