package com.radiozport.ninegfiles.ui.trash

import android.os.Bundle
import android.os.storage.StorageManager
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.db.TrashEntity
import com.radiozport.ninegfiles.databinding.FragmentRecycleBinBinding
import com.radiozport.ninegfiles.databinding.ItemTrashEntryBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class RecycleBinFragment : Fragment() {

    private var _binding: FragmentRecycleBinBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()
    private lateinit var trashAdapter: TrashAdapter

    // Local selection state (keyed on TrashEntity.originalPath)
    private val selectedTrashPaths = mutableSetOf<String>()
    private var isTrashSelectionMode = false

    /** null = show all drives; non-null = filter to items whose originalPath starts with this */
    private var driveFilter: String? = null
    private var driveFilterLabel: String = "All Drives"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecycleBinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupEmptyButton()
        setupDriveSwitcher()
        setupTrashSelectionToolbar()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        trashAdapter = TrashAdapter(
            onRestore = { item ->
                if (isTrashSelectionMode) {
                    toggleTrashSelection(item)
                } else {
                    viewModel.restoreFromTrash(item)
                    Snackbar.make(binding.root, "Restored: ${item.name}", Snackbar.LENGTH_SHORT).show()
                }
            },
            onDelete = { item ->
                if (isTrashSelectionMode) {
                    toggleTrashSelection(item)
                } else {
                    confirmPermanentDelete(item)
                }
            },
            onLongClick = { item ->
                toggleTrashSelection(item)
                true
            }
        )
        binding.rvTrash.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trashAdapter
        }
    }

    private fun toggleTrashSelection(item: TrashEntity) {
        if (item.originalPath in selectedTrashPaths) selectedTrashPaths.remove(item.originalPath)
        else selectedTrashPaths.add(item.originalPath)
        isTrashSelectionMode = selectedTrashPaths.isNotEmpty()
        trashAdapter.selectedPaths = selectedTrashPaths.toSet()
        updateTrashSelectionUI()
    }

    private fun clearTrashSelection() {
        selectedTrashPaths.clear()
        isTrashSelectionMode = false
        trashAdapter.selectedPaths = emptySet()
        updateTrashSelectionUI()
    }

    private fun getSelectedTrashItems(): List<TrashEntity> {
        val paths = selectedTrashPaths.toSet()
        return trashAdapter.currentList.filter { it.originalPath in paths }
    }

    private fun updateTrashSelectionUI() {
        binding.selectionToolbar.isVisible = isTrashSelectionMode
        binding.tvSelectionCount.text = "${selectedTrashPaths.size} selected"
    }

    private fun setupTrashSelectionToolbar() {
        binding.btnCancelSelection.setOnClickListener { clearTrashSelection() }

        binding.btnSelectionRestore.setOnClickListener {
            val items = getSelectedTrashItems()
            if (items.isEmpty()) return@setOnClickListener
            items.forEach { viewModel.restoreFromTrash(it) }
            Snackbar.make(binding.root, "Restored ${items.size} item(s)", Snackbar.LENGTH_SHORT).show()
            clearTrashSelection()
        }

        binding.btnSelectionDelete.setOnClickListener {
            val items = getSelectedTrashItems()
            if (items.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete ${items.size} item(s) permanently?")
                .setMessage("These files cannot be recovered.")
                .setPositiveButton("Delete") { _, _ ->
                    items.forEach { viewModel.deleteFromTrash(it) }
                    clearTrashSelection()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Drive Switcher ──────────────────────────────────────────────────────
    // Tapping the storage icon filters the trash list to items whose original
    // path was on the chosen volume.  "All Drives" resets the filter.

    private fun setupDriveSwitcher() {
        binding.btnSwitchDrive.setOnClickListener { anchor ->
            showDriveSwitcherMenu(anchor)
        }
    }

    private fun showDriveSwitcherMenu(anchor: View) {
        val sm = requireContext().getSystemService(StorageManager::class.java)
        val volumes = sm.storageVolumes

        data class DriveEntry(val label: String, val path: String?)
        val drives = mutableListOf(DriveEntry("All Drives", null))  // always first
        volumes.forEach { vol ->
            val dir = vol.directory ?: return@forEach
            val label = if (!vol.isRemovable) "Internal Storage"
                        else vol.getDescription(requireContext())
            drives.add(DriveEntry(label, dir.absolutePath))
        }

        val labels = drives.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Switch Drive")
            .setItems(labels) { _, which ->
                driveFilter      = drives[which].path
                driveFilterLabel = drives[which].label
                clearTrashSelection()
                applyDriveFilter(latestAllItems)
            }
            .show()
    }

    /** Cache of the most-recent full list from the ViewModel, used when re-applying the filter. */
    private var latestAllItems: List<TrashEntity> = emptyList()

    private fun applyDriveFilter(allItems: List<TrashEntity>) {
        latestAllItems = allItems
        val filtered = driveFilter?.let { root ->
            allItems.filter { it.originalPath.startsWith(root) }
        } ?: allItems

        trashAdapter.submitList(filtered)
        binding.emptyTrashView.isVisible = filtered.isEmpty()
        binding.btnEmptyTrash.isEnabled  = filtered.isNotEmpty()

        val filteredSize = filtered.sumOf { it.size }
        val prefix = if (driveFilter == null) "Trash" else "Trash · $driveFilterLabel"
        binding.tvTrashSize.text = "$prefix: ${formatBytes(filteredSize)}"

        // Scope the button label so the user knows what will be emptied
        binding.btnEmptyTrash.text = if (driveFilter == null) "Empty Trash"
                                     else "Empty $driveFilterLabel"
    }
    // ────────────────────────────────────────────────────────────────────────

    private fun setupEmptyButton() {
        binding.btnEmptyTrash.setOnClickListener {
            val scopeLabel  = if (driveFilter == null) "all trash"
                              else "trash on $driveFilterLabel"
            val actionLabel = if (driveFilter == null) "Empty Trash" else "Empty $driveFilterLabel"
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("$actionLabel?")
                .setMessage("All items in $scopeLabel will be permanently deleted. This cannot be undone.")
                .setPositiveButton(actionLabel) { _, _ ->
                    if (driveFilter == null) {
                        viewModel.emptyTrash()
                    } else {
                        // Delete only items belonging to the selected drive
                        latestAllItems
                            .filter { it.originalPath.startsWith(driveFilter!!) }
                            .forEach { viewModel.deleteFromTrash(it) }
                    }
                    Snackbar.make(binding.root, "$actionLabel complete", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.trashItems.collectLatest { items ->
                    applyDriveFilter(items)
                }
            }
        }
    }

    private fun confirmPermanentDelete(item: TrashEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Permanently?")
            .setMessage("\"${item.name}\" will be permanently deleted and cannot be recovered.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteFromTrash(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    override fun onDestroyView() {
        clearTrashSelection()
        super.onDestroyView()
        _binding = null
    }
}

class TrashAdapter(
    private val onRestore: (TrashEntity) -> Unit,
    private val onDelete: (TrashEntity) -> Unit,
    private val onLongClick: (TrashEntity) -> Boolean = { false }
) : ListAdapter<TrashEntity, TrashAdapter.TrashViewHolder>(TrashDiffCallback()) {

    var selectedPaths: Set<String> = emptySet()
        set(value) {
            val old = field
            field = value
            currentList.forEachIndexed { index, item ->
                if ((item.originalPath in old) != (item.originalPath in value))
                    notifyItemChanged(index)
            }
        }

    inner class TrashViewHolder(private val binding: ItemTrashEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TrashEntity) {
            val isSelected = item.originalPath in selectedPaths
            binding.tvName.text = item.name
            binding.tvOriginalPath.text = item.originalPath
            val df = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            binding.tvDeletedAt.text = "Deleted: ${df.format(Date(item.deletedAt))}"
            binding.tvSize.text = formatBytes(item.size)
            binding.ivIcon.setImageResource(
                if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file_generic
            )

            // Selection visual state
            binding.root.isChecked = isSelected
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivIcon.visibility = if (isSelected) View.GONE else View.VISIBLE

            binding.root.setOnLongClickListener { onLongClick(item) }
            binding.btnRestore.setOnClickListener { onRestore(item) }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder {
        val binding = ItemTrashEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrashViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrashViewHolder, position: Int) = holder.bind(getItem(position))
}

class TrashDiffCallback : DiffUtil.ItemCallback<TrashEntity>() {
    override fun areItemsTheSame(a: TrashEntity, b: TrashEntity) = a.originalPath == b.originalPath
    override fun areContentsTheSame(a: TrashEntity, b: TrashEntity) = a == b
}
