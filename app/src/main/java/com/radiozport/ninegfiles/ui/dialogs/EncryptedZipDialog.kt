package com.radiozport.ninegfiles.ui.dialogs

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.databinding.DialogEncryptedZipBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/**
 * Dialog to compress one or more files into an AES-256 encrypted ZIP.
 *
 * Usage:
 *   EncryptedZipDialog.show(childFragmentManager, selectedFiles) { outputPath ->
 *       // tell user where the zip landed
 *   }
 */
class EncryptedZipDialog : DialogFragment() {

    private var _binding: DialogEncryptedZipBinding? = null
    private val binding get() = _binding!!

    private lateinit var files: List<FileItem>
    private var onDone: ((String) -> Unit)? = null

    companion object {
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            files: List<FileItem>,
            onDone: (String) -> Unit
        ) = EncryptedZipDialog().apply {
            this.files = files
            this.onDone = onDone
        }.show(fm, "EncryptedZipDialog")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogEncryptedZipBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Default archive name from first file
        val firstName = files.firstOrNull()?.name?.substringBeforeLast('.') ?: "archive"
        binding.etArchiveName.setText(firstName)

        binding.tvFileCount.text = "Compressing ${files.size} item(s)"

        binding.switchEncrypt.setOnCheckedChangeListener { _, checked ->
            binding.layoutPassword.isVisible = checked
        }

        binding.btnCreate.setOnClickListener { createZip() }
        binding.btnCancel.setOnClickListener { dismiss() }
    }

    private fun createZip() {
        val name = binding.etArchiveName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) { binding.tilArchiveName.error = "Required"; return }

        val encrypt = binding.switchEncrypt.isChecked
        val password = binding.etPassword.text?.toString() ?: ""
        val confirm  = binding.etPasswordConfirm.text?.toString() ?: ""

        if (encrypt) {
            if (password.length < 4) { binding.tilPassword.error = "Min 4 characters"; return }
            if (password != confirm)  { binding.tilPasswordConfirm.error = "Passwords don't match"; return }
        }

        val destDir = files.firstOrNull()?.file?.parentFile
            ?: requireContext().getExternalFilesDir(null)
            ?: return
        val destFile = File(destDir, "${name}.zip")

        binding.progressBar.isVisible = true
        binding.btnCreate.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val zipFile = ZipFile(destFile)
                    val params = ZipParameters().apply {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel  = CompressionLevel.NORMAL
                        if (encrypt) {
                            isEncryptFiles   = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength   = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    if (encrypt) zipFile.setPassword(password.toCharArray())

                    files.forEach { item ->
                        if (item.file.isDirectory) zipFile.addFolder(item.file, params)
                        else zipFile.addFile(item.file, params)
                    }
                    destFile.absolutePath
                }
            }
            binding.progressBar.isVisible = false
            binding.btnCreate.isEnabled = true
            result.fold(
                onSuccess = { path ->
                    onDone?.invoke(path)
                    dismiss()
                },
                onFailure = { e ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Compression Failed")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            )
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
