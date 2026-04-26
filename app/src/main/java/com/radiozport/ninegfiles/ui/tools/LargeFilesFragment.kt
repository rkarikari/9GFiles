package com.radiozport.ninegfiles.ui.tools

import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.databinding.FragmentLargeFilesBinding
import com.radiozport.ninegfiles.databinding.ItemFileListBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LargeFilesFragment : Fragment() {

    private var _binding: FragmentLargeFilesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()

    private var limitMb = 50L
    private var topN = 100
    private lateinit var adapter: LargeFileAdapter

    /** Root directory that the current scan covers. Defaults to internal storage. */
    private var scanRoot: File = Environment.getExternalStorageDirectory()

    // Local selection state
    private val selectedLargePaths = mutableSetOf<String>()
    private var isLargeSelectionMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLargeFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LargeFileAdapter(
            onClick = { item ->
                if (isLargeSelectionMode) {
                    toggleLargeSelection(item)
                } else {
                    viewModel.navigate(item.file.parent ?: item.path)
                    findNavController().navigate(R.id.explorerFragment)
                }
            },
            onDelete = { item ->
                if (isLargeSelectionMode) {
                    toggleLargeSelection(item)
                } else {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete \"${item.name}\"?")
                        .setMessage("Size: ${item.formattedSize}\nThis cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            viewModel.delete(listOf(item))
                            scan(adapter)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
            onLongClick = { item ->
                toggleLargeSelection(item)
                true
            }
        )

        binding.rvLargeFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLargeFiles.adapter = adapter
        adapter.scanRoot = scanRoot

        // Threshold chips
        binding.chip50mb.setOnCheckedChangeListener { _, checked -> if (checked) { limitMb = 50; scan(adapter) } }
        binding.chip100mb.setOnCheckedChangeListener { _, checked -> if (checked) { limitMb = 100; scan(adapter) } }
        binding.chip500mb.setOnCheckedChangeListener { _, checked -> if (checked) { limitMb = 500; scan(adapter) } }

        setupDriveSwitcher()
        setupLargeSelectionToolbar()
        scan(adapter)
    }

    // ── Drive Switcher ──────────────────────────────────────────────────────
    private fun setupDriveSwitcher() {
        binding.btnSwitchDrive.setOnClickListener { anchor ->
            showDriveSwitcherMenu(anchor)
        }
    }

    private fun showDriveSwitcherMenu(anchor: View) {
        val sm = requireContext().getSystemService(StorageManager::class.java)
        val volumes = sm.storageVolumes

        data class DriveEntry(val label: String, val dir: File)
        val drives = mutableListOf<DriveEntry>()
        volumes.forEach { vol ->
            val dir = vol.directory ?: return@forEach
            val label = if (!vol.isRemovable) "Internal Storage"
                        else vol.getDescription(requireContext())
            drives.add(DriveEntry(label, dir))
        }

        if (drives.isEmpty()) {
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "No drives found", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
            return
        }

        val labels = drives.map { it.label }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Switch Drive")
            .setItems(labels) { _, which ->
                scanRoot = drives[which].dir
                adapter.scanRoot = scanRoot
                scan(adapter)
            }
            .show()
    }
    // ────────────────────────────────────────────────────────────────────────

    private fun toggleLargeSelection(item: FileItem) {
        if (item.path in selectedLargePaths) selectedLargePaths.remove(item.path)
        else selectedLargePaths.add(item.path)
        isLargeSelectionMode = selectedLargePaths.isNotEmpty()
        adapter.selectedPaths = selectedLargePaths.toSet()
        updateLargeSelectionUI()
    }

    private fun clearLargeSelection() {
        selectedLargePaths.clear()
        isLargeSelectionMode = false
        adapter.selectedPaths = emptySet()
        updateLargeSelectionUI()
    }

    private fun getSelectedLargeItems(): List<FileItem> {
        val paths = selectedLargePaths.toSet()
        return adapter.currentList.filter { it.path in paths }
    }

    private fun updateLargeSelectionUI() {
        binding.selectionToolbar.isVisible = isLargeSelectionMode
        binding.tvSelectionCount.text = "${selectedLargePaths.size} selected"
    }

    private fun setupLargeSelectionToolbar() {
        binding.btnCancelSelection.setOnClickListener { clearLargeSelection() }

        binding.btnSelectionDelete.setOnClickListener {
            val items = getSelectedLargeItems()
            if (items.isEmpty()) return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Move ${items.size} file(s) to trash?")
                .setMessage("Total size: ${FileUtils.formatSize(items.sumOf { it.size })}")
                .setPositiveButton("Trash") { _, _ ->
                    viewModel.trash(items)
                    clearLargeSelection()
                    scan(adapter)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSelectionMore.setOnClickListener { anchor ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
            popup.menu.apply {
                add(0, 1, 0, "Select All")
                add(0, 2, 1, "Delete Permanently")
            }
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        adapter.currentList.forEach { selectedLargePaths.add(it.path) }
                        isLargeSelectionMode = true
                        adapter.selectedPaths = selectedLargePaths.toSet()
                        updateLargeSelectionUI()
                        true
                    }
                    2 -> {
                        val items = getSelectedLargeItems()
                        if (items.isEmpty()) return@setOnMenuItemClickListener false
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Delete ${items.size} file(s) permanently?")
                            .setMessage("This cannot be undone.")
                            .setPositiveButton("Delete") { _, _ ->
                                viewModel.delete(items)
                                clearLargeSelection()
                                scan(adapter)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun scan(adapter: LargeFileAdapter) {
        binding.progressBar.isVisible = true
        binding.tvStatus.text = "Scanning…"

        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { findLargeFiles() }
            if (_binding == null) return@launch

            binding.progressBar.isVisible = false

            if (files.isEmpty()) {
                binding.tvStatus.text = "No files larger than ${limitMb}MB found"
                binding.emptyView.isVisible = true
            } else {
                val totalSize = files.sumOf { it.size }
                binding.tvStatus.text = "${files.size} files • ${FileUtils.formatSize(totalSize)} total"
                binding.emptyView.isVisible = false
            }

            adapter.submitList(files)
        }
    }

    private fun findLargeFiles(): List<FileItem> {
        val threshold = limitMb * 1024 * 1024
        return try {
            scanRoot
                .walkTopDown()
                .onEnter { it.canRead() }
                .filter { it.isFile && it.length() >= threshold }
                .sortedByDescending { it.length() }
                .take(topN)
                .map { FileItem.fromFile(it) }
                .toList()
        } catch (_: Exception) { emptyList() }
    }

    override fun onDestroyView() {
        clearLargeSelection()
        super.onDestroyView()
        _binding = null
    }
}

class LargeFileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onDelete: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Boolean
) : ListAdapter<FileItem, LargeFileAdapter.VH>(LargeFileDiff()) {

    var selectedPaths: Set<String> = emptySet()
        set(value) {
            val old = field
            field = value
            currentList.forEachIndexed { index, item ->
                if ((item.path in old) != (item.path in value)) notifyItemChanged(index)
            }
        }

    /** Updated by the fragment whenever the scan root changes. */
    var scanRoot: File = Environment.getExternalStorageDirectory()

    inner class VH(private val b: ItemFileListBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: FileItem) {
            val isSelected = item.path in selectedPaths
            b.tvName.text = item.name
            b.tvSize.text = item.formattedSize
            b.tvDate.text = item.file.parent?.removePrefix(
                scanRoot.absolutePath
            ) ?: ""
            b.ivIcon.setImageResource(when (item.fileType) {
                com.radiozport.ninegfiles.data.model.FileType.VIDEO -> R.drawable.ic_file_video
                com.radiozport.ninegfiles.data.model.FileType.IMAGE -> R.drawable.ic_file_image
                com.radiozport.ninegfiles.data.model.FileType.AUDIO -> R.drawable.ic_file_audio
                com.radiozport.ninegfiles.data.model.FileType.ARCHIVE -> R.drawable.ic_file_archive
                com.radiozport.ninegfiles.data.model.FileType.APK -> R.drawable.ic_file_apk
                else -> R.drawable.ic_file_generic
            })
            b.root.isActivated = isSelected
            b.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            b.ivIcon.visibility = if (isSelected) View.GONE else View.VISIBLE
            b.ivBookmark.visibility = View.GONE
            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongClick(item) }
            b.btnMenu.setOnClickListener { onDelete(item) }
            b.btnMenu.setImageResource(R.drawable.ic_delete)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFileListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))

    class LargeFileDiff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(a: FileItem, b: FileItem) = a.path == b.path
        override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
    }
}
