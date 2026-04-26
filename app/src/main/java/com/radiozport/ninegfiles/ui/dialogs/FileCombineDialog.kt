package com.radiozport.ninegfiles.ui.dialogs

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.databinding.DialogFileCombineBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.launch
import java.io.File

class FileCombineDialog : BottomSheetDialogFragment() {

    private var _binding: DialogFileCombineBinding? = null
    private val binding get() = _binding!!

    private lateinit var triggerItem: FileItem
    private var onComplete: ((success: Boolean, message: String) -> Unit)? = null

    companion object {
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            triggerItem: FileItem,
            onComplete: (success: Boolean, message: String) -> Unit
        ) = FileCombineDialog().apply {
            this.triggerItem = triggerItem
            this.onComplete  = onComplete
        }.show(fm, "FileCombine")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogFileCombineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo = (requireActivity().application as NineGFilesApp).fileRepository

        // Auto-detect sibling parts: same base name, .partXXX extension
        val baseName = triggerItem.name.replace(Regex("\\.part\\d+$"), "")
        val parentDir = triggerItem.file.parentFile ?: run { dismiss(); return }
        val parts = parentDir.listFiles()
            ?.filter { it.name.matches(Regex("${Regex.escape(baseName)}\\.part\\d+$")) }
            ?.sortedBy { it.name }
            ?: emptyList()

        binding.tvCombineTitle.text = "Combine: $baseName"
        binding.tvCombineParts.text = "${parts.size} parts found (${FileUtils.formatSize(parts.sumOf { it.length() })} total)"

        if (parts.isEmpty()) {
            binding.tvCombineError.isVisible = true
            binding.tvCombineError.text = "No .partXXX siblings found for \"$baseName\""
            binding.btnCombine.isEnabled = false
        }

        val outputFile = File(parentDir, baseName)
        binding.tvCombineOutput.text = "Output: ${outputFile.name}"

        binding.btnCombine.setOnClickListener {
            binding.btnCombine.isEnabled = false
            binding.progressCombine.isVisible = true

            val partItems = parts.map { FileItem.fromFile(it) }
            lifecycleScope.launch {
                val result = repo.combineFileParts(partItems, outputFile.absolutePath) { progress ->
                    if (progress is com.radiozport.ninegfiles.data.model.OperationResult.Progress) {
                        binding.tvCombineProgress.post {
                            binding.tvCombineProgress.text =
                                "Writing part ${progress.current}/${progress.total}…"
                        }
                    }
                }
                when (result) {
                    is com.radiozport.ninegfiles.data.model.OperationResult.Success -> {
                        onComplete?.invoke(true, result.message)
                        dismiss()
                    }
                    is com.radiozport.ninegfiles.data.model.OperationResult.Failure -> {
                        binding.progressCombine.isVisible = false
                        binding.btnCombine.isEnabled = true
                        binding.tvCombineError.isVisible = true
                        binding.tvCombineError.text = result.error
                    }
                    else -> Unit
                }
            }
        }

        binding.btnCombineCancel.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
