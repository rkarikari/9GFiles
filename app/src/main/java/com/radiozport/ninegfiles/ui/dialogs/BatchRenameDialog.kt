package com.radiozport.ninegfiles.ui.dialogs

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.repository.BatchRenameTemplate
import com.radiozport.ninegfiles.databinding.DialogBatchRenameBinding

class BatchRenameDialog(
    private val items: List<FileItem>,
    private val onConfirm: (BatchRenameTemplate) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogBatchRenameBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogBatchRenameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvItemCount.text = "Renaming ${items.size} item(s)"

        // Update preview on any input change
        val watcher = { _: Any? -> updatePreview() }
        binding.etPrefix.addTextChangedListener { updatePreview() }
        binding.etSuffix.addTextChangedListener { updatePreview() }
        binding.etRegexFrom.addTextChangedListener { updatePreview() }
        binding.etRegexTo.addTextChangedListener { updatePreview() }
        binding.switchCounter.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.switchKeepExt.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.etCounterStart.addTextChangedListener { updatePreview() }
        binding.etCounterPad.addTextChangedListener { updatePreview() }

        updatePreview()

        binding.btnApply.setOnClickListener {
            onConfirm(buildTemplate())
            dismiss()
        }
        binding.btnCancel.setOnClickListener { dismiss() }
    }

    private fun buildTemplate() = BatchRenameTemplate(
        prefix = binding.etPrefix.text?.toString() ?: "",
        suffix = binding.etSuffix.text?.toString() ?: "",
        keepExtension = binding.switchKeepExt.isChecked,
        useCounter = binding.switchCounter.isChecked,
        counterStart = binding.etCounterStart.text?.toString()?.toIntOrNull() ?: 1,
        counterPad = binding.etCounterPad.text?.toString()?.toIntOrNull() ?: 2,
        regexFrom = binding.etRegexFrom.text?.toString() ?: "",
        regexTo = binding.etRegexTo.text?.toString() ?: ""
    )

    private fun updatePreview() {
        val template = buildTemplate()
        val previews = items.take(3).mapIndexed { idx, item ->
            val newName = try { template.apply(item.name, idx + 1) } catch (_: Exception) { "Error" }
            "${item.name}  →  $newName"
        }
        binding.tvPreview.text = previews.joinToString("\n")
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
