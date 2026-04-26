package com.radiozport.ninegfiles.ui.vault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentSecureVaultBinding
import com.radiozport.ninegfiles.utils.AppLockManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SecureVaultFragment : Fragment() {

    private var _binding: FragmentSecureVaultBinding? = null
    private val binding get() = _binding!!

    private var isUnlocked = false
    private val vaultDir by lazy {
        File(requireContext().filesDir, "secure_vault").also { it.mkdirs() }
    }

    // ── Import picker (SAF) ───────────────────────────────────────────────
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            if (uris.size == 1) showPasswordDialogForUri(uris.first())
            else showPasswordDialogForUris(uris)
        }

    // ── Export pickers (SAF) ──────────────────────────────────────────────
    // Pending state set before the picker opens, consumed in the callback.
    private var pendingExportFiles: List<File> = emptyList()
    private var pendingExportPassword: String = ""

    // Single-file export: system "Save As" dialog — user chooses folder + name.
    private val createDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            uri ?: return@registerForActivityResult
            val file = pendingExportFiles.firstOrNull() ?: return@registerForActivityResult
            lifecycleScope.launch { decryptToUri(file, pendingExportPassword, uri) }
        }

    // Multi-file export: user picks any visible folder (Downloads, Drive, etc.).
    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch { decryptToTree(pendingExportFiles, pendingExportPassword, uri) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecureVaultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar?.title = "Secure Vault"

        binding.layoutVaultLocked.isVisible = true
        binding.layoutVaultContent.isVisible = false

        binding.btnUnlock.setOnClickListener { authenticate() }

        // "Import" now launches the system file picker — no path needed
        binding.btnImport.setOnClickListener { openFilePicker() }

        binding.btnExportSelected.setOnClickListener { exportSelected() }
        binding.btnDeleteSelected.setOnClickListener { deleteSelected() }

        // Handle files shared into the vault via the Android share sheet
        handleIncomingSharedFiles()
    }

    // ── File picker entry point ────────────────────────────────────────────

    private fun openFilePicker() {
        // "*/*" lets the user pick any file type; pass specific MIME types
        // (e.g. arrayOf("image/*", "application/pdf")) to pre-filter.
        filePicker.launch(arrayOf("*/*"))
    }

    // ── Share-sheet support ────────────────────────────────────────────────
    // Allows users to long-press any file elsewhere on the device and choose
    // "Share → 9GFiles Vault" to import it directly.

    private fun handleIncomingSharedFiles() {
        val intent = requireActivity().intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { listOf(it) }
                    ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            else -> emptyList()
        }
        if (uris.isEmpty()) return

        // Wait until the vault is unlocked before prompting
        if (isUnlocked) {
            promptImportSharedFiles(uris)
        } else {
            // Store pending URIs; re-trigger after unlock
            pendingSharedUris = uris
        }
    }

    private var pendingSharedUris: List<Uri> = emptyList()

    // ── Authentication ─────────────────────────────────────────────────────

    private fun authenticate() {
        AppLockManager.authenticateVault(requireActivity(), requireContext()) { success, error ->
            if (error == "SHOW_PIN_DIALOG") {
                showPinDialog()
                return@authenticateVault
            }
            if (success) unlock() else {
                error?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showPinDialog() {
        if (!AppLockManager.isVaultPinSet(requireContext())) {
            showSetPinDialog()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Enter Vault PIN")
            .setView(dialogView)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
                    ?.text?.toString() ?: ""
                if (AppLockManager.verifyVaultPin(requireContext(), pin)) unlock()
                else Snackbar.make(binding.root, "Wrong PIN", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSetPinDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Vault PIN")
            .setMessage("Set a PIN to protect your vault when biometrics are unavailable.")
            .setView(v)
            .setPositiveButton("Set PIN") { _, _ ->
                val pin = v.findViewById<TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pin.length >= 4) {
                    AppLockManager.setVaultPin(requireContext(), pin)
                    unlock()
                } else Snackbar.make(binding.root, "PIN must be at least 4 digits", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unlock() {
        isUnlocked = true
        binding.layoutVaultLocked.isVisible = false
        binding.layoutVaultContent.isVisible = true
        refreshVaultList()

        // Import any files that were shared before unlock
        if (pendingSharedUris.isNotEmpty()) {
            promptImportSharedFiles(pendingSharedUris)
            pendingSharedUris = emptyList()
        }
    }

    private fun refreshVaultList() {
        val files = vaultDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        binding.tvVaultEmpty.isVisible = files.isEmpty()
        binding.rvVaultFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVaultFiles.adapter = VaultAdapter(files)
    }

    // ── Import helpers — URI-based (replaces the old path dialog) ─────────

    /** Resolve a display name from a content URI without reading its bytes. */
    private fun displayNameOf(uri: Uri): String {
        requireContext().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col != -1) return cursor.getString(col)
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /**
     * Copy a content URI to a temp file so [EncryptionUtils.encryptFile] can
     * operate on it with a real [File] handle.
     * The caller is responsible for deleting the temp file.
     */
    private fun uriToTempFile(uri: Uri): File {
        val name = displayNameOf(uri)
        val tmp = File(requireContext().cacheDir, "vault_import_$name")
        requireContext().contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        return tmp
    }

    /** Single-file import: ask for password then encrypt. */
    private fun showPasswordDialogForUri(uri: Uri) {
        val name = displayNameOf(uri)
        val v = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        v.findViewById<TextInputLayout>(R.id.tilPasswordConfirm)?.isVisible = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Encrypt & Import")
            .setMessage("Set an encryption password for \"$name\":")
            .setView(v)
            .setPositiveButton("Import") { _, _ ->
                val pw = v.findViewById<TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pw.isEmpty()) return@setPositiveButton
                lifecycleScope.launch { encryptAndStoreUri(uri, pw) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Multi-file import: one password covers all selected files. */
    private fun showPasswordDialogForUris(uris: List<Uri>) {
        val v = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        v.findViewById<TextInputLayout>(R.id.tilPasswordConfirm)?.isVisible = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Encrypt & Import ${uris.size} files")
            .setMessage("Set one encryption password for all selected files:")
            .setView(v)
            .setPositiveButton("Import All") { _, _ ->
                val pw = v.findViewById<TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pw.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    binding.progressVault.isVisible = true
                    var ok = 0
                    for (uri in uris) {
                        val success = encryptAndStoreUri(uri, pw, showProgress = false)
                        if (success) ok++
                    }
                    binding.progressVault.isVisible = false
                    Snackbar.make(
                        binding.root,
                        "Imported $ok/${uris.size} files into vault",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    refreshVaultList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Core import routine: copies URI → temp file → encrypts → stores in vault.
     * Returns true on success.
     */
    private suspend fun encryptAndStoreUri(
        uri: Uri,
        password: String,
        showProgress: Boolean = true
    ): Boolean {
        if (showProgress) binding.progressVault.isVisible = true
        var tmp: File? = null
        return try {
            val originalName = displayNameOf(uri)
            tmp = withContext(Dispatchers.IO) { uriToTempFile(uri) }
            val result = EncryptionUtils.encryptFile(tmp, password)
            if (result is EncryptionUtils.EncryptResult.Success) {
                // Use the original display name (not the temp-file prefix) for the vault entry.
                val vaultName = originalName + EncryptionUtils.ENCRYPTED_EXT
                val dest = File(vaultDir, vaultName)
                withContext(Dispatchers.IO) {
                    result.outputFile.copyTo(dest, overwrite = true)
                    result.outputFile.delete()
                }
                if (showProgress) {
                    Snackbar.make(binding.root, "Imported: ${dest.name}", Snackbar.LENGTH_SHORT).show()
                    refreshVaultList()
                }
                true
            } else {
                val reason = (result as? EncryptionUtils.EncryptResult.Failure)?.reason ?: "Unknown error"
                if (showProgress) Snackbar.make(binding.root, reason, Snackbar.LENGTH_LONG).show()
                false
            }
        } catch (e: Exception) {
            if (showProgress) Snackbar.make(binding.root, "Import failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            false
        } finally {
            tmp?.delete()          // always clean up the temp copy
            if (showProgress) binding.progressVault.isVisible = false
        }
    }

    /** Called when files arrive via the share sheet after vault is unlocked. */
    private fun promptImportSharedFiles(uris: List<Uri>) {
        val names = uris.take(3).joinToString("\n") { "• ${displayNameOf(it)}" } +
            if (uris.size > 3) "\n… and ${uris.size - 3} more" else ""
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add to Vault?")
            .setMessage("Encrypt and import these files?\n\n$names")
            .setPositiveButton("Import") { _, _ ->
                if (uris.size == 1) showPasswordDialogForUri(uris.first())
                else showPasswordDialogForUris(uris)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Export / Delete ────────────────────────────────────────────────────

    private fun exportSelected() {
        val adapter = binding.rvVaultFiles.adapter as? VaultAdapter ?: return
        val selected = adapter.getSelected()
        if (selected.isEmpty()) {
            Snackbar.make(binding.root, "Select files to export", Snackbar.LENGTH_SHORT).show()
            return
        }
        val v = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        v.findViewById<TextInputLayout>(R.id.tilPasswordConfirm)?.isVisible = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Decrypt & Export")
            .setView(v)
            .setPositiveButton("Choose destination") { _, _ ->
                val pw = v.findViewById<TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pw.isEmpty()) return@setPositiveButton
                pendingExportFiles = selected
                pendingExportPassword = pw
                if (selected.size == 1) {
                    // Suggest the original filename (strip the .9genc extension)
                    val suggestedName = selected.first().name
                        .removeSuffix(EncryptionUtils.ENCRYPTED_EXT)
                    createDocLauncher.launch(suggestedName)
                } else {
                    // Let the user pick any visible folder
                    openTreeLauncher.launch(null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Single-file export: decrypt and stream directly into the SAF URI the user chose. */
    private suspend fun decryptToUri(vaultFile: File, password: String, destUri: Uri) {
        binding.progressVault.isVisible = true
        try {
            val result = EncryptionUtils.decryptFile(vaultFile, password)
            if (result is EncryptionUtils.EncryptResult.Success) {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(destUri)?.use { out ->
                        result.outputFile.inputStream().use { it.copyTo(out) }
                    }
                    result.outputFile.delete()
                }
                Snackbar.make(binding.root, "File exported successfully", Snackbar.LENGTH_SHORT).show()
            } else {
                val reason = (result as? EncryptionUtils.EncryptResult.Failure)?.reason
                    ?: "Decryption failed — check your password"
                Snackbar.make(binding.root, reason, Snackbar.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Export failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        } finally {
            binding.progressVault.isVisible = false
        }
    }

    /** Multi-file export: decrypt each file and write it into the user-chosen folder. */
    private suspend fun decryptToTree(files: List<File>, password: String, treeUri: Uri) {
        binding.progressVault.isVisible = true
        var ok = 0
        try {
            val tree = DocumentFile.fromTreeUri(requireContext(), treeUri)
                ?: run {
                    Snackbar.make(binding.root, "Cannot access the selected folder", Snackbar.LENGTH_LONG).show()
                    return
                }
            for (vaultFile in files) {
                val result = EncryptionUtils.decryptFile(vaultFile, password)
                if (result is EncryptionUtils.EncryptResult.Success) {
                    val outName = result.outputFile.name
                    withContext(Dispatchers.IO) {
                        val docFile = tree.createFile(mimeTypeOf(outName), outName)
                        docFile?.uri?.let { uri ->
                            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                                result.outputFile.inputStream().use { it.copyTo(out) }
                            }
                        }
                        result.outputFile.delete()
                    }
                    ok++
                }
            }
            Snackbar.make(
                binding.root,
                "Exported $ok/${files.size} file${if (ok == 1) "" else "s"}",
                Snackbar.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Export failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        } finally {
            binding.progressVault.isVisible = false
        }
    }

    private fun mimeTypeOf(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun deleteSelected() {
        val adapter = binding.rvVaultFiles.adapter as? VaultAdapter ?: return
        val selected = adapter.getSelected()
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ${selected.size} vault file(s)?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                selected.forEach { it.delete() }
                refreshVaultList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Adapter ────────────────────────────────────────────────────────────

    inner class VaultAdapter(private val files: List<File>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val selected = mutableSetOf<File>()
        fun getSelected() = selected.toList()

        override fun getItemCount() = files.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_file_list, parent, false)
            ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val f = files[position]
            holder.itemView.apply {
                findViewById<TextView>(R.id.tvName).text = f.name
                findViewById<TextView>(R.id.tvSize)?.text = FileUtils.formatSize(f.length())
                // Vault items have no context-menu actions
                findViewById<android.widget.ImageButton>(R.id.btnMenu)?.visibility = View.GONE
                setOnClickListener {
                    if (f in selected) selected.remove(f) else selected.add(f)
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
            }
            updateSelection(holder, f)
        }

        /** Mirrors FileAdapter.ListViewHolder.updateSelection() exactly. */
        private fun updateSelection(holder: RecyclerView.ViewHolder, f: File) {
            val isSelected = f in selected
            holder.itemView.apply {
                // Triggers bg_selected_item.xml state_activated overlay
                isActivated = isSelected
                // Lock icon <-> checkmark swap (same FrameLayout stacking as FileAdapter)
                findViewById<ImageView>(R.id.ivIcon)?.apply {
                    setImageResource(R.drawable.ic_lock_silent_mode_off)
                    visibility = if (isSelected) View.GONE else View.VISIBLE
                }
                findViewById<ImageView>(R.id.ivCheck)?.visibility =
                    if (isSelected) View.VISIBLE else View.GONE
            }
        }
    }
}
