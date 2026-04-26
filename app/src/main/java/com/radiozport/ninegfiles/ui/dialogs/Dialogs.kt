package com.radiozport.ninegfiles.ui.dialogs

import android.os.Bundle
import android.text.InputFilter
import android.view.*
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.*

// ─── Create Item Dialog ───────────────────────────────────────────────────────

class CreateItemDialog(private val onConfirm: (type: Int, name: String) -> Unit) :
    BottomSheetDialogFragment() {

    companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_FILE = 1
    }

    private var selectedType = TYPE_FOLDER

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_create_item, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tilName = view.findViewById<TextInputLayout>(R.id.tilName)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val rgType = view.findViewById<RadioGroup>(R.id.rgType)
        val btnCreate = view.findViewById<MaterialButton>(R.id.btnCreate)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        // Filter illegal characters
        etName.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            if (source.any { it in "/\\:*?\"<>|" }) "" else null
        })

        rgType.setOnCheckedChangeListener { _, checkedId ->
            selectedType = if (checkedId == R.id.rbFolder) TYPE_FOLDER else TYPE_FILE
            tilName.hint = if (selectedType == TYPE_FOLDER) "Folder name" else "File name"
        }

        etName.addTextChangedListener {
            btnCreate.isEnabled = it?.isNotBlank() == true
        }

        btnCreate.setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: return@setOnClickListener
            if (name.isNotEmpty()) {
                onConfirm(selectedType, name)
                dismiss()
            }
        }

        btnCancel.setOnClickListener { dismiss() }

        // Auto-focus
        etName.requestFocus()
    }
}

// ─── Rename Dialog ────────────────────────────────────────────────────────────

class RenameDialog(
    private val item: FileItem,
    private val onConfirm: (String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_rename, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val btnRename = view.findViewById<MaterialButton>(R.id.btnRename)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        // Pre-fill name, select without extension for files
        etName.setText(item.name)
        if (!item.isDirectory && item.extension.isNotEmpty()) {
            val nameWithoutExt = item.name.substringBeforeLast(".")
            etName.setSelection(0, nameWithoutExt.length)
        } else {
            etName.selectAll()
        }

        etName.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
            if (source.any { it in "/\\:*?\"<>|" }) "" else null
        })

        etName.addTextChangedListener {
            btnRename.isEnabled = it?.isNotBlank() == true && it.toString() != item.name
        }
        btnRename.isEnabled = false

        btnRename.setOnClickListener {
            val newName = etName.text?.toString()?.trim() ?: return@setOnClickListener
            if (newName.isNotEmpty() && newName != item.name) {
                onConfirm(newName)
                dismiss()
            }
        }

        btnCancel.setOnClickListener { dismiss() }
    }
}

// ─── Compress Dialog ──────────────────────────────────────────────────────────

class CompressDialog(
    private val items: List<FileItem>,
    private val onConfirm: (outputName: String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_compress, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val etArchiveName = view.findViewById<TextInputEditText>(R.id.etArchiveName)
        val tvItemCount = view.findViewById<TextView>(R.id.tvItemCount)
        val btnCompress = view.findViewById<MaterialButton>(R.id.btnCompress)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        tvItemCount.text = "Compressing ${items.size} item(s)"
        val defaultName = if (items.size == 1) items[0].name.substringBeforeLast(".") else "archive"
        etArchiveName.setText(defaultName)
        etArchiveName.selectAll()

        btnCompress.setOnClickListener {
            val name = etArchiveName.text?.toString()?.trim() ?: return@setOnClickListener
            if (name.isNotEmpty()) {
                onConfirm(name)
                dismiss()
            }
        }
        btnCancel.setOnClickListener { dismiss() }
    }
}

// ─── File Details Dialog ──────────────────────────────────────────────────────

class FileDetailsDialog(
    private val item: FileItem,
    private val details: Map<String, String>
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_file_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<LinearLayout>(R.id.detailsContainer)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)

        tvTitle.text = item.name

        details.forEach { (key, value) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val tvKey = TextView(requireContext()).apply {
                text = key
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(resources.getColor(R.color.on_surface_medium, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvValue = TextView(requireContext()).apply {
                text = value
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                // Unix permissions string (e.g. "-rwxr-xr-x") must be monospace
                // so every character column lines up and is easy to read.
                if (key == "Permissions") {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(resources.getColor(
                        if (value.contains("w"))
                            com.google.android.material.R.color.design_default_color_primary
                        else
                            R.color.on_surface_medium,
                        null
                    ))
                    letterSpacing = 0.08f
                }
            }

            row.addView(tvKey)
            row.addView(tvValue)
            container.addView(row)

            // Divider
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(resources.getColor(R.color.divider, null))
            }
            container.addView(divider)
        }
    }
}

// ─── Sort Dialog ──────────────────────────────────────────────────────────────

class SortDialog(
    private val currentSort: SortOption,
    private val onSortSelected: (SortOption) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_sort, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sortOptions = mapOf(
            R.id.rbNameAsc to SortOption.NAME_ASC,
            R.id.rbNameDesc to SortOption.NAME_DESC,
            R.id.rbSizeAsc to SortOption.SIZE_ASC,
            R.id.rbSizeDesc to SortOption.SIZE_DESC,
            R.id.rbDateAsc to SortOption.DATE_ASC,
            R.id.rbDateDesc to SortOption.DATE_DESC,
            R.id.rbTypeAsc to SortOption.TYPE_ASC,
            R.id.rbTypeDesc to SortOption.TYPE_DESC,
            R.id.rbExtAsc to SortOption.EXTENSION_ASC,
            R.id.rbExtDesc to SortOption.EXTENSION_DESC
        )

        val rgSort = view.findViewById<RadioGroup>(R.id.rgSort)

        // Check current sort
        sortOptions.entries.find { it.value == currentSort }?.let { entry ->
            rgSort.check(entry.key)
        }

        rgSort.setOnCheckedChangeListener { _, checkedId ->
            sortOptions[checkedId]?.let { sort ->
                onSortSelected(sort)
                dismiss()
            }
        }
    }
}

// ─── Properties Dialog ────────────────────────────────────────────────────────

class FilePropertiesDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?) =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Properties")
            .setView(R.layout.dialog_file_details)
            .setPositiveButton("OK", null)
            .create()
}
