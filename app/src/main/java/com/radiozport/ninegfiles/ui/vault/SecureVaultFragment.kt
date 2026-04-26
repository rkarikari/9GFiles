package com.radiozport.ninegfiles.ui.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentSecureVaultBinding
import com.radiozport.ninegfiles.utils.AppLockManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SecureVaultFragment : Fragment() {

    companion object {
        /** Fragment result key broadcast after every successful vault import.
         *  FileExplorerFragment listens for this to auto-refresh its file list. */
        const val RESULT_IMPORT_COMPLETE = "vault_import_complete"
    }

    private var _binding: FragmentSecureVaultBinding? = null
    private val binding get() = _binding!!

    private var isUnlocked = false
    private val vaultDir by lazy {
        File(requireContext().filesDir, "secure_vault").also { it.mkdirs() }
    }

    private val prefs by lazy {
        (requireActivity().application as NineGFilesApp).preferences
    }

    // ── Import picker (SAF) ───────────────────────────────────────────────
    private val filePicker =
        registerForActivityResult(
            // Request write permission in addition to read so we can overwrite
            // the source bytes with zeros before deletion (secure wipe).
            object : ActivityResultContracts.OpenMultipleDocuments() {
                override fun createIntent(context: Context, input: Array<String>): Intent =
                    super.createIntent(context, input).apply {
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
            }
        ) { uris ->
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
        binding.btnImport.setOnClickListener { openFilePicker() }
        binding.btnExportSelected.setOnClickListener { exportSelected() }
        binding.btnDeleteSelected.setOnClickListener { deleteSelected() }

        handleIncomingSharedFiles()
    }

    // ── File picker entry point ────────────────────────────────────────────

    private fun openFilePicker() {
        filePicker.launch(arrayOf("*/*"))
    }

    // ── Share-sheet support ────────────────────────────────────────────────

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
        if (isUnlocked) promptImportSharedFiles(uris)
        else pendingSharedUris = uris
    }

    private var pendingSharedUris: List<Uri> = emptyList()

    // ── Origin sidecar helpers ─────────────────────────────────────────────
    // Each vault entry may have a companion "<vaultfile>.origin" text file
    // containing the content URI of the file that was originally imported.
    // This allows the decrypted file to be written back to the exact source on export.

    private fun originFile(vaultFile: File) =
        File(vaultFile.parent, "${vaultFile.name}.origin")

    private fun saveOriginUri(vaultFile: File, uri: Uri) =
        originFile(vaultFile).writeText(uri.toString())

    private fun readOriginUri(vaultFile: File): Uri? {
        val f = originFile(vaultFile)
        return if (f.exists()) runCatching { Uri.parse(f.readText()) }.getOrNull() else null
    }

    private fun deleteOriginFile(vaultFile: File) = originFile(vaultFile).delete()

    // ── Authentication ─────────────────────────────────────────────────────

    private fun authenticate() {
        AppLockManager.authenticateVault(requireActivity(), requireContext()) { success, error ->
            if (error == "SHOW_PIN_DIALOG") { showPinDialog(); return@authenticateVault }
            if (success) unlock()
            else error?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show() }
        }
    }

    private fun showPinDialog() {
        if (!AppLockManager.isVaultPinSet(requireContext())) { showSetPinDialog(); return }
        val dialogView = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        dialogView.findViewById<View>(R.id.layoutEncryptButtons)?.isVisible = false
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
        v.findViewById<View>(R.id.layoutEncryptButtons)?.isVisible = false
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
        if (pendingSharedUris.isNotEmpty()) {
            promptImportSharedFiles(pendingSharedUris)
            pendingSharedUris = emptyList()
        }
    }

    private fun refreshVaultList() {
        // Show only encrypted vault entries; .origin sidecar files are internal metadata.
        val files = vaultDir.listFiles()
            ?.filter { it.name.endsWith(EncryptionUtils.ENCRYPTED_EXT) }
            ?.sortedBy { it.name }
            ?: emptyList()
        binding.tvVaultEmpty.isVisible = files.isEmpty()
        binding.rvVaultFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVaultFiles.adapter = VaultAdapter(files)
    }

    // ── Import helpers ─────────────────────────────────────────────────────

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
        v.findViewById<View>(R.id.layoutEncryptButtons)?.isVisible = false
        v.findViewById<TextInputLayout>(R.id.tilPasswordConfirm)?.isVisible = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Encrypt & Import")
            .setMessage("Set an encryption password for \"$name\":")
            .setView(v)
            .setPositiveButton("Import") { _, _ ->
                val pw = v.findViewById<TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pw.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val deleteOriginal = prefs.vaultDeleteOriginal.first()
                    encryptAndStoreUri(uri, pw, deleteOriginal = deleteOriginal)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Multi-file import: one password covers all selected files. */
    private fun showPasswordDialogForUris(uris: List<Uri>) {
        val v = layoutInflater.inflate(R.layout.dialog_encrypt_file, null)
        v.findViewById<View>(R.id.layoutEncryptButtons)?.isVisible = false
        v.findViewById<TextInputLayout>(R.id.tilPasswordConfirm)?.isVisible = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Encrypt & Import ${uris.size} files")
            .setMessage("Set one encryption password for all selected files:")
            .setView(v)
            .setPositiveButton("Import All") { _, _ ->
                val pw = v.findViewById<TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pw.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val deleteOriginal = prefs.vaultDeleteOriginal.first()
                    binding.progressVault.isVisible = true
                    var ok = 0
                    for (uri in uris) {
                        val success = encryptAndStoreUri(
                            uri, pw, showProgress = false, deleteOriginal = deleteOriginal
                        )
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
     * Core import routine: URI → temp file → encrypt → vault.
     *
     * Saves a ".origin" sidecar alongside the vault entry so the file can be
     * restored to its original location on export.
     *
     * @param deleteOriginal  Remove the source file via the content resolver after a
     *                        successful import.  SAF does not guarantee a byte-level wipe;
     *                        this removes the file from the user-visible file system.
     */
    private suspend fun encryptAndStoreUri(
        uri: Uri,
        password: String,
        showProgress: Boolean = true,
        deleteOriginal: Boolean = true
    ): Boolean {
        if (showProgress) binding.progressVault.isVisible = true
        var tmp: File? = null
        return try {
            val originalName = displayNameOf(uri)
            tmp = withContext(Dispatchers.IO) { uriToTempFile(uri) }
            val result = EncryptionUtils.encryptFile(tmp, password)
            if (result is EncryptionUtils.EncryptResult.Success) {
                val vaultName = originalName + EncryptionUtils.ENCRYPTED_EXT
                val dest = File(vaultDir, vaultName)
                withContext(Dispatchers.IO) {
                    result.outputFile.copyTo(dest, overwrite = true)
                    result.outputFile.delete()
                    // Persist the origin URI so the file can be restored on export.
                    saveOriginUri(dest, uri)
                    // Securely erase the source file before unlinking it.
                    if (deleteOriginal) {
                        secureDeleteUri(uri)
                    }
                }
                if (showProgress) {
                    val msg = if (deleteOriginal) "Imported & original deleted: $originalName"
                              else "Imported: $originalName"
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    refreshVaultList()
                }
                // Notify the file explorer to refresh so deleted originals
                // disappear immediately without a manual pull-to-refresh.
                parentFragmentManager.setFragmentResult(RESULT_IMPORT_COMPLETE, Bundle.EMPTY)
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
            tmp?.delete()
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
        v.findViewById<View>(R.id.layoutEncryptButtons)?.isVisible = false
        v.findViewById<TextInputLayout>(R.id.tilPasswordConfirm)?.isVisible = false

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Decrypt & Export")
            .setView(v)
            .setPositiveButton("Export") { _, _ ->
                val pw = v.findViewById<TextInputEditText>(R.id.etPassword)?.text?.toString() ?: ""
                if (pw.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val restoreOnExport   = prefs.vaultRestoreOnExport.first()
                    val allHaveOrigins    = selected.all { readOriginUri(it) != null }
                    if (restoreOnExport && allHaveOrigins) {
                        decryptToOrigins(selected, pw)
                    } else {
                        pendingExportFiles    = selected
                        pendingExportPassword = pw
                        if (selected.size == 1) {
                            createDocLauncher.launch(
                                selected.first().name.removeSuffix(EncryptionUtils.ENCRYPTED_EXT)
                            )
                        } else {
                            openTreeLauncher.launch(null)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Restore-mode export: write each vault file back to its original content URI.
     *
     * If a particular origin URI is stale (the file was moved or deleted externally),
     * the affected files fall back to the standard picker flow rather than silently failing.
     */
    private suspend fun decryptToOrigins(files: List<File>, password: String) {
        binding.progressVault.isVisible = true
        var ok = 0
        val needsPicker = mutableListOf<File>()
        try {
            for (vaultFile in files) {
                val originUri = readOriginUri(vaultFile)
                if (originUri == null) { needsPicker += vaultFile; continue }

                val result = EncryptionUtils.decryptFile(vaultFile, password)
                if (result is EncryptionUtils.EncryptResult.Success) {
                    val written = withContext(Dispatchers.IO) {
                        runCatching {
                            // "wt" = write + truncate; overwrites the existing file in place.
                            requireContext().contentResolver
                                .openOutputStream(originUri, "wt")
                                ?.use { out -> result.outputFile.inputStream().use { it.copyTo(out) } }
                            result.outputFile.delete()
                            deleteOriginFile(vaultFile)   // origin consumed; remove sidecar
                        }.isSuccess
                    }
                    if (written) {
                        if (prefs.vaultDeleteAfterExport.first()) {
                            vaultFile.delete()
                        }
                        ok++
                    } else needsPicker += vaultFile
                } else {
                    val reason = (result as? EncryptionUtils.EncryptResult.Failure)?.reason
                        ?: "Decryption failed — check your password"
                    Snackbar.make(binding.root, reason, Snackbar.LENGTH_LONG).show()
                    binding.progressVault.isVisible = false
                    return
                }
            }
            if (ok > 0) {
                Snackbar.make(
                    binding.root,
                    "Restored $ok file${if (ok == 1) "" else "s"} to original location",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            refreshVaultList()
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Export failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        } finally {
            binding.progressVault.isVisible = false
        }

        // Fall back to the picker for any files whose origin URI was inaccessible.
        if (needsPicker.isNotEmpty()) {
            pendingExportFiles = needsPicker
            pendingExportPassword = password
            if (needsPicker.size == 1) {
                createDocLauncher.launch(
                    needsPicker.first().name.removeSuffix(EncryptionUtils.ENCRYPTED_EXT)
                )
            } else {
                openTreeLauncher.launch(null)
            }
        }
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
                if (prefs.vaultDeleteAfterExport.first()) {
                    vaultFile.delete()
                    deleteOriginFile(vaultFile)
                }
                refreshVaultList()
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
                    // Vault file deletion and list refresh run on the main thread so
                    // the UI always reflects the final state regardless of pref value.
                    if (prefs.vaultDeleteAfterExport.first()) {
                        vaultFile.delete()
                        deleteOriginFile(vaultFile)
                    }
                    ok++
                }
            }
            Snackbar.make(
                binding.root,
                "Exported $ok/${files.size} file${if (ok == 1) "" else "s"}",
                Snackbar.LENGTH_SHORT
            ).show()
            refreshVaultList()
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Export failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        } finally {
            binding.progressVault.isVisible = false
        }
    }

    /**
     * Secure delete via SAF:
     *  1. Query the file size.
     *  2. Overwrite every byte with zeros through the content resolver output stream.
     *     (Requires FLAG_GRANT_WRITE_URI_PERMISSION — requested in the file picker above.)
     *  3. Flush + close, then permanently unlink via [DocumentsContract.deleteDocument].
     *
     * Using DocumentsContract.deleteDocument() rather than ContentResolver.delete() is
     * required for SAF document URIs — it is the only API that actually removes the file
     * from the underlying provider (local storage, SD card, etc.).
     */
    private fun secureDeleteUri(uri: Uri) {
        runCatching {
            val cr = requireContext().contentResolver

            // ── Step 1: resolve file size ──────────────────────────────────────
            val fileSize: Long = cr.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (col != -1 && !cursor.isNull(col)) cursor.getLong(col) else -1L
                } else -1L
            } ?: -1L

            // ── Step 2: overwrite with zeros ───────────────────────────────────
            cr.openOutputStream(uri, "wt")?.use { out ->
                val buf = ByteArray(8192)   // 8 KB zero buffer
                if (fileSize > 0) {
                    var remaining = fileSize
                    while (remaining > 0) {
                        val chunk = minOf(remaining, buf.size.toLong()).toInt()
                        out.write(buf, 0, chunk)
                        remaining -= chunk
                    }
                } else {
                    // Size unknown — write a generous 4 MB of zeros as best-effort.
                    repeat(512) { out.write(buf) }
                }
                out.flush()
            }

            // ── Step 3: unlink ─────────────────────────────────────────────────
            // DocumentsContract.deleteDocument is the correct deletion API for
            // SAF URIs; ContentResolver.delete silently fails on most providers.
            if (DocumentsContract.isDocumentUri(requireContext(), uri)) {
                DocumentsContract.deleteDocument(cr, uri)
            } else {
                cr.delete(uri, null, null)
            }
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
                selected.forEach { vaultFile ->
                    vaultFile.delete()
                    deleteOriginFile(vaultFile)   // remove sidecar alongside the vault entry
                }
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
                isActivated = isSelected
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
