package com.radiozport.ninegfiles.ui.publisher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentPublisherToolBinding
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publisher Tool — encrypts arbitrary files for distribution to a specific device.
 *
 * ## Workflow
 * 1. The publisher pastes the **recipient device's** RSA-2048 public key (PEM), obtained
 *    from that device's Settings → "Share Public Key".
 * 2. The publisher picks one or more files.
 * 3. Each file is encrypted with [EncryptionUtils.encryptForDevice] (AES-256-GCM session key
 *    wrapped with RSA-OAEP/SHA-256). Output files carry the `.9genc` extension.
 * 4a. **Save to Downloads** — writes `.9genc` files to the Downloads folder.
 * 4b. **Send via Wi-Fi Direct** — encrypts to a private temp dir, then navigates to the
 *     Wi-Fi Direct screen with the file paths pre-loaded for automatic transfer on connect.
 *
 * ## This device's key
 * The top card shows this device's own public key fingerprint and exposes a Copy / Share
 * button so the user can send this device's PEM to a publisher on another machine or device.
 */
class PublisherToolFragment : Fragment() {

    // ── View-binding ──────────────────────────────────────────────────────────
    private var _binding: FragmentPublisherToolBinding? = null
    private val binding get() = _binding!!

    // ── State ─────────────────────────────────────────────────────────────────
    data class EncryptEntry(
        val displayName: String,
        val uri: Uri,
        val sizeBytes: Long,
        var state: State = State.PENDING,
        var outputPath: String? = null
    ) {
        enum class State { PENDING, WORKING, DONE, ERROR }
    }

    /** What to do after successful encryption. */
    private enum class SendMode { DOWNLOADS, WIFI_DIRECT }

    private val queue = mutableListOf<EncryptEntry>()

    // ── File picker ───────────────────────────────────────────────────────────
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            addUris(uris)
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPublisherToolBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupThisDeviceCard()
        setupKeyInput()
        setupFilePicker()
        setupButtons()
        refreshUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── This-device key card ──────────────────────────────────────────────────

    private fun setupThisDeviceCard() {
        viewLifecycleOwner.lifecycleScope.launch {
            val fp = withContext(Dispatchers.IO) { DeviceKeyManager.getPublicKeyFingerprint() }
            binding.tvDeviceFingerprint.text = fp
        }

        binding.btnCopyMyKey.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val pem = withContext(Dispatchers.IO) { DeviceKeyManager.getPublicKeyPem() }
                val cm = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("9GFiles Public Key", pem))
                Snackbar.make(binding.root, "Public key copied to clipboard", Snackbar.LENGTH_SHORT)
                    .show()
            }
        }

        binding.btnShareMyKey.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val pem = withContext(Dispatchers.IO) { DeviceKeyManager.getPublicKeyPem() }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "9GFiles Device Public Key")
                    putExtra(Intent.EXTRA_TEXT, pem)
                }
                startActivity(Intent.createChooser(intent, "Share public key"))
            }
        }
    }

    // ── Recipient key input ───────────────────────────────────────────────────

    private fun setupKeyInput() {
        binding.etRecipientKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateKeyStatus(s?.toString()?.trim() ?: "")
                refreshUI()
            }
        })
    }

    private fun updateKeyStatus(pem: String) {
        if (pem.isEmpty()) {
            binding.tvKeyStatus.text = "No key entered"
            binding.tvKeyStatus.setTextColor(
                requireContext().getColor(com.google.android.material.R.color.material_on_surface_disabled))
            binding.ivKeyStatusIcon.setImageResource(R.drawable.ic_lock_silent_mode_off)
            binding.ivKeyStatusIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(com.google.android.material.R.color.material_on_surface_disabled))
            return
        }
        val looksValid = pem.contains("-----BEGIN PUBLIC KEY-----") &&
                pem.contains("-----END PUBLIC KEY-----") && pem.length > 200
        if (looksValid) {
            binding.tvKeyStatus.text = "Key looks valid — ready to encrypt"
            binding.tvKeyStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
            binding.ivKeyStatusIcon.setImageResource(R.drawable.ic_check)
            binding.ivKeyStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvKeyStatus.text = "Paste a full RSA-2048 PEM public key"
            binding.tvKeyStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light))
            binding.ivKeyStatusIcon.setImageResource(R.drawable.ic_close)
            binding.ivKeyStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(android.R.color.holo_red_light))
        }
    }

    // ── File picker ───────────────────────────────────────────────────────────

    private fun setupFilePicker() {
        binding.btnAddFiles.setOnClickListener { filePicker.launch(arrayOf("*/*")) }
    }

    private fun addUris(uris: List<Uri>) {
        val resolver = requireContext().contentResolver
        for (uri in uris) {
            val (name, size) = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                val n = if (ni >= 0) cursor.getString(ni) ?: uri.lastPathSegment ?: "file"
                        else uri.lastPathSegment ?: "file"
                val s = if (si >= 0) cursor.getLong(si) else 0L
                Pair(n, s)
            } ?: Pair(uri.lastPathSegment ?: "file", 0L)

            if (queue.none { it.displayName == name && it.sizeBytes == size }) {
                queue.add(EncryptEntry(name, uri, size))
            }
        }
        renderQueue()
        refreshUI()
    }

    // ── Queue rendering ───────────────────────────────────────────────────────

    private fun renderQueue() {
        val container = binding.llFileQueue
        container.removeAllViews()

        if (queue.isEmpty()) { binding.tvEmptyQueue.isVisible = true; return }
        binding.tvEmptyQueue.isVisible = false

        val inflater = LayoutInflater.from(requireContext())
        queue.forEachIndexed { idx, entry ->
            val row = inflater.inflate(R.layout.item_publisher_file_row, container, false)
            row.findViewById<TextView>(R.id.tvFileName).text = entry.displayName
            row.findViewById<TextView>(R.id.tvFileSize).text = formatSize(entry.sizeBytes)

            val tvState = row.findViewById<TextView>(R.id.tvFileState)
            when (entry.state) {
                EncryptEntry.State.PENDING -> {
                    tvState.text = "PENDING"
                    tvState.setTextColor(requireContext().getColor(
                        com.google.android.material.R.color.material_on_surface_emphasis_medium))
                }
                EncryptEntry.State.WORKING -> {
                    tvState.text = "ENCRYPTING…"
                    tvState.setTextColor(requireContext().getColor(android.R.color.holo_blue_dark))
                }
                EncryptEntry.State.DONE -> {
                    tvState.text = "✓ DONE"
                    tvState.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                }
                EncryptEntry.State.ERROR -> {
                    tvState.text = "✕ FAILED"
                    tvState.setTextColor(requireContext().getColor(android.R.color.holo_red_light))
                }
            }

            val btnRemove = row.findViewById<ImageButton>(R.id.btnRemoveFile)
            btnRemove.isVisible = entry.state == EncryptEntry.State.PENDING
            btnRemove.setOnClickListener { queue.removeAt(idx); renderQueue(); refreshUI() }

            container.addView(row)
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnEncryptAll.setOnClickListener       { startEncryption(SendMode.DOWNLOADS)    }
        binding.btnEncryptAndSend.setOnClickListener   { startEncryption(SendMode.WIFI_DIRECT)  }
        binding.btnClearAll.setOnClickListener {
            queue.clear(); renderQueue(); refreshUI()
        }
    }

    // ── Shared encryption engine ──────────────────────────────────────────────

    /**
     * Encrypts all PENDING queue entries.
     *
     * @param mode  [SendMode.DOWNLOADS]   → saves `.9genc` files to the public Downloads folder.
     *              [SendMode.WIFI_DIRECT] → writes encrypted files to the app's private cache,
     *                then navigates to Wi-Fi Direct with those paths pre-loaded for auto-send.
     */
    private fun startEncryption(mode: SendMode) {
        val pem = binding.etRecipientKey.text?.toString()?.trim() ?: return
        val pending = queue.filter { it.state == EncryptEntry.State.PENDING }
        if (pending.isEmpty()) return

        // Confirm Wi-Fi Direct mode so the user knows where files are going
        if (mode == SendMode.WIFI_DIRECT) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Encrypt & Send via Wi-Fi Direct")
                .setMessage(
                    "Files will be encrypted and then automatically sent to the first " +
                    "peer you connect to in the Wi-Fi Direct screen.\n\n" +
                    "Make sure the recipient device has 9GFiles open and is visible as a peer."
                )
                .setPositiveButton("Continue") { _, _ -> doEncrypt(pem, pending, mode) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        doEncrypt(pem, pending, mode)
    }

    private fun doEncrypt(pem: String, pending: List<EncryptEntry>, mode: SendMode) {
        lockUI(true)
        binding.progressBar.max = pending.size
        binding.progressBar.progress = 0

        viewLifecycleOwner.lifecycleScope.launch {
            // Destination directory
            val destDir = withContext(Dispatchers.IO) {
                when (mode) {
                    SendMode.DOWNLOADS   ->
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            .also { it.mkdirs() }
                    SendMode.WIFI_DIRECT ->
                        // Private cache — cleaned up when Wi-Fi Direct screen dismisses it,
                        // or on next app launch via NineGFilesApp cache sweeper.
                        File(requireContext().cacheDir, "pub_wfd_${System.currentTimeMillis()}")
                            .also { it.mkdirs() }
                }
            }

            val encryptedPaths = mutableListOf<String>()
            var done = 0

            for (entry in pending) {
                entry.state = EncryptEntry.State.WORKING
                renderQueue()

                val (encResult, outPath) = withContext(Dispatchers.IO) {
                    try {
                        val tmp = File(requireContext().cacheDir,
                            "pub_tmp_${System.currentTimeMillis()}_${entry.displayName}")
                        requireContext().contentResolver.openInputStream(entry.uri)
                            ?.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }

                        val dest     = File(destDir, entry.displayName + EncryptionUtils.ENCRYPTED_EXT)
                        val result   = EncryptionUtils.encryptForDevice(
                            source               = tmp,
                            dest                 = dest,
                            recipientPublicKeyPem = pem
                        )
                        tmp.delete()
                        result to dest.absolutePath
                    } catch (e: Exception) {
                        (EncryptionUtils.EncryptResult.Failure("${e.message}")
                                as EncryptionUtils.EncryptResult) to ""
                    }
                }

                when (encResult) {
                    is EncryptionUtils.EncryptResult.Success -> {
                        entry.state = EncryptEntry.State.DONE
                        entry.outputPath = outPath
                        if (outPath.isNotEmpty()) encryptedPaths.add(outPath)
                    }
                    else -> entry.state = EncryptEntry.State.ERROR
                }

                done++
                binding.progressBar.progress = done
                renderQueue()
            }

            lockUI(false)
            refreshUI()

            val doneCount = queue.count { it.state == EncryptEntry.State.DONE }
            val failCount = queue.count { it.state == EncryptEntry.State.ERROR }

            when {
                // ── Wi-Fi Direct: navigate with encrypted file paths ──────────
                mode == SendMode.WIFI_DIRECT && encryptedPaths.isNotEmpty() -> {
                    val snackMsg = buildString {
                        append("$doneCount file(s) encrypted")
                        if (failCount > 0) append(" · $failCount failed")
                        append(" — opening Wi-Fi Direct…")
                    }
                    Snackbar.make(binding.root, snackMsg, Snackbar.LENGTH_SHORT).show()

                    val bundle = bundleOf(
                        "pendingFilePaths" to encryptedPaths.toTypedArray()
                    )
                    findNavController().navigate(
                        R.id.action_publisher_to_wifi_direct, bundle)
                }

                mode == SendMode.WIFI_DIRECT && encryptedPaths.isEmpty() -> {
                    Snackbar.make(binding.root, "Encryption failed — nothing to send",
                        Snackbar.LENGTH_LONG).show()
                }

                // ── Downloads: show summary ───────────────────────────────────
                else -> {
                    val msg = buildString {
                        if (doneCount > 0) append("$doneCount file(s) saved to Downloads")
                        if (failCount > 0) {
                            if (doneCount > 0) append(" · ")
                            append("$failCount failed")
                        }
                    }
                    if (msg.isNotEmpty())
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    private fun lockUI(locked: Boolean) {
        binding.btnEncryptAll.isEnabled     = !locked
        binding.btnEncryptAndSend.isEnabled = !locked
        binding.btnAddFiles.isEnabled       = !locked
        binding.progressBar.isVisible       = locked
    }

    private fun refreshUI() {
        val pem = binding.etRecipientKey.text?.toString()?.trim() ?: ""
        val keyOk = pem.contains("-----BEGIN PUBLIC KEY-----") &&
                pem.contains("-----END PUBLIC KEY-----") && pem.length > 200
        val hasPending = queue.any { it.state == EncryptEntry.State.PENDING }

        binding.btnEncryptAll.isEnabled     = keyOk && hasPending
        binding.btnEncryptAndSend.isEnabled = keyOk && hasPending
        binding.btnClearAll.isEnabled       = queue.isNotEmpty()

        binding.tvStatusHint.text = when {
            !keyOk && queue.isEmpty() ->
                "Paste the recipient device's public key, then add files"
            !keyOk ->
                "Paste a valid RSA-2048 public key to enable encryption"
            !hasPending ->
                if (queue.any { it.state == EncryptEntry.State.DONE })
                    "All files encrypted — save to Downloads or send via Wi-Fi Direct"
                else "Add files to encrypt"
            else ->
                "${queue.count { it.state == EncryptEntry.State.PENDING }} file(s) ready"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        else                -> "%.2f MB".format(bytes / (1024f * 1024f))
    }
}
