package com.radiozport.ninegfiles.ui.tags

import android.os.Bundle
import android.view.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.db.FileTagEntity
import com.radiozport.ninegfiles.databinding.FragmentTagManagerBinding
import com.radiozport.ninegfiles.databinding.ItemTaggedFileBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class FileTagManagerFragment : Fragment() {

    private var _binding: FragmentTagManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()

    /** Currently selected tag name, drives the file list below the chips. */
    private var selectedTag: String? = null

    /** Active Flow collection for the tagged-file list — cancelled on tag change. */
    private var tagFileJob: Job? = null

    private lateinit var taggedFileAdapter: TaggedFileAdapter

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTagManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as NineGFilesApp

        taggedFileAdapter = TaggedFileAdapter(
            onOpen   = { entity -> openFileInExplorer(entity) },
            onRemove = { entity -> removeTagFromFile(entity, app) }
        )
        binding.rvTaggedFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTaggedFiles.adapter = taggedFileAdapter

        binding.btnAddTag.setOnClickListener { showAddTagPrompt(app) }

        observeAllTags(app)
    }

    // ─── Observe distinct tag list ──────────────────────────────────────────

    private fun observeAllTags(app: NineGFilesApp) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.fileTagDao().getAllTags().collectLatest { tags ->
                    buildTagChips(tags, app)
                    val isEmpty = tags.isEmpty()
                    binding.emptyTagsView.isVisible = isEmpty
                    binding.rvTaggedFiles.isVisible = !isEmpty
                    // If the previously selected tag was deleted, clear the file list.
                    if (selectedTag !in tags) {
                        selectedTag = null
                        taggedFileAdapter.submitList(emptyList())
                        binding.tvTaggedCount.isVisible = false
                        binding.btnBrowseTagged.isVisible = false
                    }
                }
            }
        }
    }

    // ─── Chip row ──────────────────────────────────────────────────────────

    private fun buildTagChips(tags: List<String>, app: NineGFilesApp) {
        binding.chipGroupTags.removeAllViews()
        tags.forEach { tagName ->
            viewLifecycleOwner.lifecycleScope.launch {
                // Fetch one representative entity to get this tag's colour.
                val sampleColor = app.database.fileTagDao()
                    .getFilesByTag(tagName).first()
                    .firstOrNull()?.color ?: TAG_COLORS[0]

                val chip = Chip(requireContext()).apply {
                    text = tagName
                    isCheckable = true
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                        ColorUtils.blendARGB(sampleColor, 0x00FFFFFF, 0.75f)
                    )
                    setTextColor(
                        if (ColorUtils.calculateLuminance(sampleColor) > 0.4) 0xFF000000.toInt()
                        else 0xFFFFFFFF.toInt()
                    )
                    isChecked = (tagName == selectedTag)

                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) showFilesForTag(tagName, app)
                    }

                    // Long-press: rename or delete tag
                    setOnLongClickListener { showTagContextMenu(tagName, app); true }
                }
                binding.chipGroupTags.addView(chip)
            }
        }
    }

    // ─── Tag context menu (long-press) ─────────────────────────────────────

    private fun showTagContextMenu(tagName: String, app: NineGFilesApp) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tag: \"$tagName\"")
            .setItems(arrayOf("Rename tag…", "Delete tag…")) { _, which ->
                when (which) {
                    0 -> showRenameTagDialog(tagName, app)
                    1 -> confirmDeleteTag(tagName, app)
                }
            }
            .show()
    }

    private fun showRenameTagDialog(oldName: String, app: NineGFilesApp) {
        val input = TextInputEditText(requireContext()).apply {
            setText(oldName)
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename tag")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text?.toString()?.trim()?.lowercase() ?: return@setPositiveButton
                if (newName.isEmpty() || newName == oldName) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val dao = app.database.fileTagDao()
                    // Fetch all entities carrying oldName, delete them, re-insert with newName.
                    val entities = dao.getFilesByTag(oldName).first()
                    entities.forEach { old ->
                        dao.removeTag(old)
                        dao.addTag(old.copy(tag = newName))
                    }
                    if (selectedTag == oldName) selectedTag = newName
                    Snackbar.make(binding.root, "Renamed \"$oldName\" → \"$newName\"",
                        Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTag(tagName: String, app: NineGFilesApp) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete tag \"$tagName\"?")
            .setMessage("This removes the tag from all files. Files themselves are untouched.")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val dao = app.database.fileTagDao()
                    dao.getFilesByTag(tagName).first().forEach { dao.removeTag(it) }
                    Snackbar.make(binding.root, "Tag \"$tagName\" deleted", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── File list for selected tag ─────────────────────────────────────────

    private fun showFilesForTag(tag: String, app: NineGFilesApp) {
        selectedTag = tag
        tagFileJob?.cancel()
        tagFileJob = viewLifecycleOwner.lifecycleScope.launch {
            app.database.fileTagDao().getFilesByTag(tag).collectLatest { entities ->
                // Drop entries whose paths no longer exist on disk.
                val live = entities.filter { File(it.filePath).exists() }
                val stale = entities.size - live.size

                taggedFileAdapter.submitList(live)

                binding.tvTaggedCount.text = buildString {
                    append("${live.size} file(s) tagged \"$tag\"")
                    if (stale > 0) append(" · $stale missing")
                }
                binding.tvTaggedCount.isVisible = true

                binding.btnBrowseTagged.isVisible = live.isNotEmpty()
                binding.btnBrowseTagged.setOnClickListener {
                    // Navigate to the first file's parent directory in the Explorer.
                    live.firstOrNull()?.let { entity ->
                        val dir = File(entity.filePath).parent ?: return@let
                        viewModel.navigate(dir)
                        findNavController().navigate(R.id.explorerFragment)
                    }
                }

                // Silently purge stale entries so the DB doesn't accumulate ghost rows.
                if (stale > 0) {
                    entities.filter { !File(it.filePath).exists() }
                        .forEach { app.database.fileTagDao().removeTag(it) }
                }
            }
        }
    }

    // ─── Actions ───────────────────────────────────────────────────────────

    private fun openFileInExplorer(entity: FileTagEntity) {
        val dir = File(entity.filePath).parent ?: return
        viewModel.navigate(dir)
        findNavController().navigate(R.id.explorerFragment)
    }

    private fun removeTagFromFile(entity: FileTagEntity, app: NineGFilesApp) {
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.fileTagDao().removeTag(entity)
            Snackbar.make(
                binding.root,
                "Removed tag \"${entity.tag}\" from ${File(entity.filePath).name}",
                Snackbar.LENGTH_LONG
            ).setAction("Undo") {
                viewLifecycleOwner.lifecycleScope.launch {
                    app.database.fileTagDao().addTag(entity)
                }
            }.show()
        }
    }

    // ─── Add-tag dialog ────────────────────────────────────────────────────

    private fun showAddTagPrompt(app: NineGFilesApp) {
        val items = viewModel.getSelectedFileItems()
        if (items.isEmpty()) {
            Snackbar.make(binding.root, "Select files in the Explorer first", Snackbar.LENGTH_SHORT).show()
            return
        }

        // Two-step: pick predefined tag name (with colour), or enter a custom one.
        val options = TAG_COLOR_NAMES + listOf("Custom…")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add tag to ${items.size} file(s)")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < TAG_COLOR_NAMES.size) {
                    val tagName = TAG_COLOR_NAMES[which].lowercase()
                    val color   = TAG_COLORS[which]
                    applyTag(tagName, color, app)
                } else {
                    showCustomTagDialog(app)
                }
            }
            .show()
    }

    private fun showCustomTagDialog(app: NineGFilesApp) {
        val input = TextInputEditText(requireContext()).apply { hint = "Tag name" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Custom tag")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text?.toString()?.trim()?.lowercase() ?: return@setPositiveButton
                if (name.isNotEmpty()) applyTag(name, TAG_COLORS.random(), app)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTag(tagName: String, color: Int, app: NineGFilesApp) {
        val items = viewModel.getSelectedFileItems()
        viewLifecycleOwner.lifecycleScope.launch {
            items.forEach { item ->
                app.fileRepository.addTag(item.path, tagName, color)
            }
            Snackbar.make(binding.root, "Tagged ${items.size} file(s) as \"$tagName\"",
                Snackbar.LENGTH_SHORT).show()
            viewModel.clearSelection()
        }
    }

    // ─── Destroy ───────────────────────────────────────────────────────────

    override fun onDestroyView() {
        tagFileJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    // ─── Constants ─────────────────────────────────────────────────────────

    companion object {
        val TAG_COLORS = listOf(
            0xFFE53935.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(),
            0xFF3F51B5.toInt(), 0xFF1565C0.toInt(), 0xFF00838F.toInt(),
            0xFF2E7D32.toInt(), 0xFFF9A825.toInt(), 0xFFE65100.toInt(),
            0xFF4E342E.toInt()
        )
        val TAG_COLOR_NAMES = listOf(
            "Red", "Pink", "Purple", "Indigo", "Blue",
            "Teal", "Green", "Amber", "Orange", "Brown"
        )
    }
}

// ─── Tagged-file RecyclerView adapter ─────────────────────────────────────────

class TaggedFileAdapter(
    private val onOpen:   (FileTagEntity) -> Unit,
    private val onRemove: (FileTagEntity) -> Unit
) : ListAdapter<FileTagEntity, TaggedFileAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FileTagEntity>() {
            override fun areItemsTheSame(a: FileTagEntity, b: FileTagEntity) =
                a.filePath == b.filePath && a.tag == b.tag
            override fun areContentsTheSame(a: FileTagEntity, b: FileTagEntity) = a == b
        }
    }

    inner class VH(private val b: ItemTaggedFileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entity: FileTagEntity) {
            val file = File(entity.filePath)
            b.tvFileName.text = file.name
            b.tvFilePath.text = file.parent ?: entity.filePath
            b.ivColor.setBackgroundColor(entity.color)
            b.root.setOnClickListener      { onOpen(entity) }
            b.btnRemoveTag.setOnClickListener { onRemove(entity) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTaggedFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))
}
