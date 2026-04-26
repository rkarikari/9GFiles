package com.radiozport.ninegfiles.ui.tags

import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.db.FileTagEntity
import com.radiozport.ninegfiles.databinding.FragmentTagManagerBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class FileTagManagerFragment : Fragment() {

    private var _binding: FragmentTagManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()

    companion object {
        // Pre-defined tag colors matching our palette
        val TAG_COLORS = listOf(
            0xFFE53935.toInt(), // Red
            0xFFE91E63.toInt(), // Pink
            0xFF9C27B0.toInt(), // Purple
            0xFF3F51B5.toInt(), // Indigo
            0xFF1565C0.toInt(), // Blue
            0xFF00838F.toInt(), // Teal
            0xFF2E7D32.toInt(), // Green
            0xFFF9A825.toInt(), // Amber
            0xFFE65100.toInt(), // Deep Orange
            0xFF4E342E.toInt()  // Brown
        )

        val TAG_COLOR_NAMES = listOf("Red", "Pink", "Purple", "Indigo", "Blue",
            "Teal", "Green", "Amber", "Orange", "Brown")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTagManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as NineGFilesApp

        // Load all distinct tags
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.fileTagDao().getAllTags().collectLatest { tags ->
                    binding.chipGroupTags.removeAllViews()

                    if (tags.isEmpty()) {
                        binding.emptyTagsView.isVisible = true
                        binding.rvTaggedFiles.isVisible = false
                    } else {
                        binding.emptyTagsView.isVisible = false
                        tags.forEach { tagName ->
                            addTagChip(tagName, app)
                        }
                    }
                }
            }
        }

        binding.btnAddTag.setOnClickListener {
            // Show add tag dialog for the currently selected file in ViewModel
            showAddTagPrompt(app)
        }
    }

    private fun addTagChip(tagName: String, app: NineGFilesApp) {
        val chip = Chip(requireContext()).apply {
            text = tagName
            isCheckable = true
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) showFilesForTag(tagName, app)
            }
        }
        binding.chipGroupTags.addView(chip)
    }

    private fun showFilesForTag(tag: String, app: NineGFilesApp) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.fileTagDao().getFilesByTag(tag).collectLatest { tagEntities ->
                val existingFiles = tagEntities.filter { File(it.filePath).exists() }
                binding.tvTaggedCount.text = "${existingFiles.size} file(s) tagged \"$tag\""
                binding.tvTaggedCount.isVisible = true
                // Navigate to explorer showing these files
                binding.btnBrowseTagged.isVisible = true
                binding.btnBrowseTagged.setOnClickListener {
                    // Navigate to search — ideally we'd filter by tag path
                    findNavController().navigate(R.id.searchFragment)
                }
            }
        }
    }

    private fun showAddTagPrompt(app: NineGFilesApp) {
        val items = viewModel.getSelectedFileItems()
        if (items.isEmpty()) {
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "Select files first in the Explorer", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
            return
        }

        val tagNames = TAG_COLOR_NAMES.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Tag to ${items.size} file(s)")
            .setItems(tagNames) { _, which ->
                val tagName = tagNames[which].lowercase()
                val color = TAG_COLORS[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    items.forEach { item ->
                        app.fileRepository.addTag(item.path, tagName, color)
                    }
                    com.google.android.material.snackbar.Snackbar
                        .make(binding.root, "Tagged ${items.size} file(s) as \"$tagName\"",
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                        .show()
                    viewModel.clearSelection()
                }
            }
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
