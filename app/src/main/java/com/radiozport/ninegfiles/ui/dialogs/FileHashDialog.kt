package com.radiozport.ninegfiles.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.repository.ChecksumResult
import com.radiozport.ninegfiles.databinding.DialogFileHashBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileHashDialog(private val item: FileItem) : DialogFragment() {

    private var _binding: DialogFileHashBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogFileHashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header
        binding.ivFileIcon.setImageResource(item.fileType.iconRes)
        binding.tvFileName.text = item.name

        // Start computing
        binding.progressHash.isVisible = true
        binding.hashesGroup.isVisible  = false

        viewLifecycleOwner.lifecycleScope.launch {
            val (md5, sha1, sha256) = withContext(Dispatchers.IO) {
                val repo = (requireActivity().application as NineGFilesApp).fileRepository
                Triple(
                    repo.computeHash(item.file, "MD5"),
                    repo.computeHash(item.file, "SHA-1"),
                    repo.computeHash(item.file, "SHA-256")
                )
            }
            if (_binding == null) return@launch

            binding.progressHash.isVisible = false
            binding.hashesGroup.isVisible  = true
            binding.tvMd5.text    = md5
            binding.tvSha1.text   = sha1
            binding.tvSha256.text = sha256

            // Verify sidecar
            val verifyResult = withContext(Dispatchers.IO) {
                (requireActivity().application as NineGFilesApp)
                    .fileRepository.verifyChecksum(item.file)
            }
            if (_binding == null) return@launch

            when (verifyResult) {
                is ChecksumResult.NoSidecar -> { /* stay hidden */ }
                is ChecksumResult.Match -> {
                    binding.tvVerifyResult.text =
                        "✓ ${verifyResult.algorithm} verified — file is intact"
                    binding.tvVerifyResult.setTextColor(0xFF2E7D32.toInt())
                    binding.tvVerifyResult.isVisible = true
                }
                is ChecksumResult.Mismatch -> {
                    binding.tvVerifyResult.text =
                        "✗ ${verifyResult.algorithm} MISMATCH — file may be corrupted\n" +
                        "Expected: ${verifyResult.expected}\n" +
                        "Got:      ${verifyResult.actual}"
                    binding.tvVerifyResult.setTextColor(
                        requireContext().getColor(android.R.color.holo_red_dark))
                    binding.tvVerifyResult.isVisible = true
                }
            }
        }

        // Per-hash copy buttons
        binding.btnCopyMd5.setOnClickListener    { copyHash(binding.tvMd5.text.toString(),    "MD5") }
        binding.btnCopySha1.setOnClickListener   { copyHash(binding.tvSha1.text.toString(),   "SHA-1") }
        binding.btnCopySha256.setOnClickListener { copyHash(binding.tvSha256.text.toString(), "SHA-256") }

        binding.btnClose.setOnClickListener { dismiss() }
    }

    private fun copyHash(hash: String, label: String) {
        if (hash.isBlank()) return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, hash))
        Snackbar.make(binding.root, "$label copied", Snackbar.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
