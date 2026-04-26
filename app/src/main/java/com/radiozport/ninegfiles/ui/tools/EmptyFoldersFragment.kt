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
import com.radiozport.ninegfiles.databinding.FragmentEmptyFoldersBinding
import com.radiozport.ninegfiles.databinding.ItemEmptyFolderBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EmptyFoldersFragment : Fragment() {

    private var _binding: FragmentEmptyFoldersBinding? = null
    private val binding get() = _binding!!

    /** Root directory that the current scan covers. Defaults to internal storage. */
    private var scanRoot: java.io.File = Environment.getExternalStorageDirectory()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmptyFoldersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = EmptyFolderAdapter().also { it.scanRoot = scanRoot }
        binding.rvFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFolders.adapter = adapter

        binding.btnScan.setOnClickListener { scanForEmpty(adapter) }
        binding.btnDeleteAll.setOnClickListener {
            val items = adapter.currentList
            if (items.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete ${items.size} empty folder(s)?")
                .setMessage("This will permanently remove all empty directories listed.")
                .setPositiveButton("Delete All") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            items.count { it.delete() }
                        }
                        Snackbar.make(binding.root, "Deleted $deleted folder(s)", Snackbar.LENGTH_SHORT).show()
                        adapter.submitList(emptyList())
                        binding.tvStatus.text = "All empty folders deleted"
                        binding.btnDeleteAll.isEnabled = false
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        setupDriveSwitcher(adapter)
        scanForEmpty(adapter)
    }

    // ── Drive Switcher ──────────────────────────────────────────────────────
    private fun setupDriveSwitcher(adapter: EmptyFolderAdapter) {
        binding.btnSwitchDrive.setOnClickListener { anchor ->
            showDriveSwitcherMenu(anchor, adapter)
        }
    }

    private fun showDriveSwitcherMenu(anchor: View, adapter: EmptyFolderAdapter) {
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
            Snackbar.make(binding.root, "No drives found", Snackbar.LENGTH_SHORT).show()
            return
        }

        val labels = drives.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Switch Drive")
            .setItems(labels) { _, which ->
                scanRoot = drives[which].dir
                adapter.scanRoot = scanRoot
                scanForEmpty(adapter)
            }
            .show()
    }
    // ────────────────────────────────────────────────────────────────────────

    private fun scanForEmpty(adapter: EmptyFolderAdapter) {
        binding.progressBar.isVisible = true
        binding.btnScan.isEnabled = false
        binding.tvStatus.text = "Scanning…"
        binding.btnDeleteAll.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val emptyDirs = withContext(Dispatchers.IO) {
                findEmptyFolders(scanRoot)
            }
            if (_binding == null) return@launch

            binding.progressBar.isVisible = false
            binding.btnScan.isEnabled = true

            if (emptyDirs.isEmpty()) {
                binding.tvStatus.text = "No empty folders found ✓"
                binding.emptyView.isVisible = true
                binding.btnDeleteAll.isEnabled = false
            } else {
                binding.tvStatus.text = "${emptyDirs.size} empty folder(s) found"
                binding.emptyView.isVisible = false
                binding.btnDeleteAll.isEnabled = true
            }

            adapter.submitList(emptyDirs)
        }
    }

    private fun findEmptyFolders(root: File): List<File> {
        val empty = mutableListOf<File>()
        try {
            root.walkTopDown()
                .onEnter { it.canRead() }
                .filter { it.isDirectory && it != root }
                .forEach { dir ->
                    if (dir.listFiles()?.isEmpty() == true) {
                        empty.add(dir)
                    }
                }
        } catch (_: Exception) {}
        return empty.sortedBy { it.absolutePath }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class EmptyFolderAdapter : ListAdapter<File, EmptyFolderAdapter.VH>(FileDiff()) {

    /** Set by the fragment whenever the scan root changes, so relative paths display correctly. */
    var scanRoot: File = Environment.getExternalStorageDirectory()

    inner class VH(private val b: ItemEmptyFolderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(file: File) {
            b.tvPath.text = file.absolutePath
                .removePrefix(scanRoot.absolutePath)
                .ifEmpty { "/" }
            b.tvName.text = file.name
            b.btnDelete.setOnClickListener {
                file.delete()
                val newList = currentList.toMutableList().also { it.remove(file) }
                submitList(newList)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEmptyFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))

    class FileDiff : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(a: File, b: File) = a.absolutePath == b.absolutePath
        override fun areContentsTheSame(a: File, b: File) = a == b
    }
}
