package com.radiozport.ninegfiles.ui.tools

import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentDuplicateFinderBinding
import com.radiozport.ninegfiles.databinding.ItemDuplicateGroupBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class DuplicateFinderFragment : Fragment() {

    private var _binding: FragmentDuplicateFinderBinding? = null
    private val binding get() = _binding!!

    /** Root directory that the current scan covers. Defaults to internal storage. */
    private var scanRoot: java.io.File = Environment.getExternalStorageDirectory()

    data class DuplicateGroup(
        val hash: String,
        val size: Long,
        val files: List<File>
    ) {
        val wastedSpace: Long get() = size * (files.size - 1)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDuplicateFinderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = DuplicateGroupAdapter(
            onDeleteDuplicate = { file ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete duplicate?")
                    .setMessage("Delete: ${file.name}\n${file.parent}")
                    .setPositiveButton("Delete") { _, _ ->
                        file.delete()
                        binding.tvStatus.text = "Deleted: ${file.name}"
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.rvDuplicates.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDuplicates.adapter = adapter

        binding.btnScan.setOnClickListener {
            clearBulkSelection()
            startScan(adapter)
        }

        binding.btnSelectAll.setOnClickListener {
            // Collect all duplicates (index > 0 within each group — keep the first copy)
            val dupes = adapter.currentList.flatMap { group ->
                group.files.drop(1)  // first file is the "original"; rest are duplicates
            }
            if (dupes.isEmpty()) {
                binding.tvStatus.text = "No duplicates to select"
                return@setOnClickListener
            }
            showBulkDeleteBar(dupes)
        }

        setupDriveSwitcher(adapter)
        setupBulkDeleteBar()
    }

    // ── Drive Switcher ──────────────────────────────────────────────────────
    private fun setupDriveSwitcher(adapter: DuplicateGroupAdapter) {
        binding.btnSwitchDrive.setOnClickListener { anchor ->
            showDriveSwitcherMenu(anchor, adapter)
        }
    }

    private fun showDriveSwitcherMenu(anchor: View, adapter: DuplicateGroupAdapter) {
        val sm = requireContext().getSystemService(StorageManager::class.java)
        val volumes = sm.storageVolumes

        data class DriveEntry(val label: String, val dir: java.io.File)
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
                clearBulkSelection()
                startScan(adapter)
            }
            .show()
    }
    // ────────────────────────────────────────────────────────────────────────

    // Holds the list of duplicate files staged for bulk deletion
    private var stagedForDeletion: List<File> = emptyList()

    private fun showBulkDeleteBar(dupes: List<File>) {
        stagedForDeletion = dupes
        val totalSize = dupes.sumOf { it.length() }
        binding.tvBulkDeleteCount.text =
            "${dupes.size} duplicate file(s) selected  •  ${FileUtils.formatSize(totalSize)} recoverable"
        binding.bulkDeleteBar.isVisible = true
    }

    private fun clearBulkSelection() {
        stagedForDeletion = emptyList()
        binding.bulkDeleteBar.isVisible = false
    }

    private fun setupBulkDeleteBar() {
        binding.btnCancelBulk.setOnClickListener { clearBulkSelection() }

        binding.btnBulkDelete.setOnClickListener {
            val dupes = stagedForDeletion
            if (dupes.isEmpty()) return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete ${dupes.size} duplicate file(s)?")
                .setMessage(
                    "This will permanently delete ${dupes.size} file(s), " +
                    "freeing ${FileUtils.formatSize(dupes.sumOf { it.length() })}.\n\n" +
                    "One copy of each group will be kept."
                )
                .setPositiveButton("Delete") { _, _ ->
                    val deleted = dupes.count { it.delete() }
                    clearBulkSelection()
                    binding.tvStatus.text = "Deleted $deleted duplicate(s)"
                    binding.tvSummary.isVisible = false
                    // Re-run scan to refresh the list
                    startScan(binding.rvDuplicates.adapter as DuplicateGroupAdapter)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startScan(adapter: DuplicateGroupAdapter) {
        clearBulkSelection()
        binding.btnScan.isEnabled = false
        binding.progressBar.isVisible = true
        binding.tvStatus.text = "Scanning files…"
        binding.tvSummary.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { findDuplicates() }

            if (_binding == null) return@launch

            binding.btnScan.isEnabled = true
            binding.progressBar.isVisible = false

            if (groups.isEmpty()) {
                binding.tvStatus.text = "No duplicates found ✓"
                binding.tvSummary.isVisible = false
            } else {
                val totalFiles = groups.sumOf { it.files.size }
                val wastedBytes = groups.sumOf { it.wastedSpace }
                binding.tvStatus.text = "${groups.size} duplicate group(s) found"
                binding.tvSummary.text = "$totalFiles files • ${FileUtils.formatSize(wastedBytes)} wasted"
                binding.tvSummary.isVisible = true
            }

            adapter.submitList(groups)
        }
    }

    private fun findDuplicates(): List<DuplicateGroup> {
        val root = scanRoot

        // Step 1: Group files by size (fast, no hashing)
        val bySize = mutableMapOf<Long, MutableList<File>>()
        root.walkTopDown()
            .onEnter { it.canRead() }
            .filter { it.isFile && it.length() > 0 }
            .forEach { f -> bySize.getOrPut(f.length()) { mutableListOf() }.add(f) }

        // Step 2: For groups with same size, hash them
        val groups = mutableListOf<DuplicateGroup>()
        bySize.values
            .filter { it.size > 1 } // At least 2 files with same size
            .forEach { sameSize ->
                val byHash = mutableMapOf<String, MutableList<File>>()
                sameSize.forEach { f ->
                    val hash = md5Fast(f)
                    byHash.getOrPut(hash) { mutableListOf() }.add(f)
                }
                byHash.values
                    .filter { it.size > 1 }
                    .forEach { dupes ->
                        groups.add(DuplicateGroup(
                            hash = byHash.entries.first { it.value == dupes }.key,
                            size = dupes[0].length(),
                            files = dupes.sortedBy { it.absolutePath }
                        ))
                    }
            }

        return groups.sortedByDescending { it.wastedSpace }
    }

    /** Fast MD5 on first 64KB + file length as quick fingerprint */
    private fun md5Fast(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(65536)
            file.inputStream().use { stream ->
                val read = stream.read(buffer)
                if (read > 0) digest.update(buffer, 0, read)
            }
            // Include file size in hash to reduce false positives on partial reads
            digest.update(file.length().toString().toByteArray())
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { file.absolutePath }
    }

    override fun onDestroyView() {
        clearBulkSelection()
        super.onDestroyView()
        _binding = null
    }
}

class DuplicateGroupAdapter(
    private val onDeleteDuplicate: (File) -> Unit
) : ListAdapter<DuplicateFinderFragment.DuplicateGroup, DuplicateGroupAdapter.VH>(DiffCallback()) {

    inner class VH(private val b: ItemDuplicateGroupBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(group: DuplicateFinderFragment.DuplicateGroup) {
            b.tvGroupHeader.text = "${group.files.size} copies • ${FileUtils.formatSize(group.size)} each • ${FileUtils.formatSize(group.wastedSpace)} wasted"

            // Clear previous file rows
            b.filesContainer.removeAllViews()

            group.files.forEachIndexed { idx, file ->
                val row = android.widget.LinearLayout(b.root.context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }

                val tvPath = android.widget.TextView(b.root.context).apply {
                    text = if (idx == 0) "✓ ${file.name}\n   ${file.parent}" else "  ${file.name}\n   ${file.parent}"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }

                row.addView(tvPath)

                // Add delete button for duplicates (not the first/original)
                if (idx > 0) {
                    val btnDel = com.google.android.material.button.MaterialButton(
                        b.root.context,
                        null,
                        com.google.android.material.R.attr.materialIconButtonStyle
                    ).apply {
                        setIconResource(R.drawable.ic_delete)
                        iconTint = android.content.res.ColorStateList.valueOf(
                            resources.getColor(R.color.md_theme_error, null)
                        )
                        setOnClickListener { onDeleteDuplicate(file) }
                    }
                    row.addView(btnDel)
                }

                b.filesContainer.addView(row)

                // Divider between files (not after last)
                if (idx < group.files.size - 1) {
                    val div = android.view.View(b.root.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                        setBackgroundColor(resources.getColor(R.color.divider, null))
                    }
                    b.filesContainer.addView(div)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDuplicateGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))

    class DiffCallback : DiffUtil.ItemCallback<DuplicateFinderFragment.DuplicateGroup>() {
        override fun areItemsTheSame(a: DuplicateFinderFragment.DuplicateGroup, b: DuplicateFinderFragment.DuplicateGroup) = a.hash == b.hash
        override fun areContentsTheSame(a: DuplicateFinderFragment.DuplicateGroup, b: DuplicateFinderFragment.DuplicateGroup) = a == b
    }
}
