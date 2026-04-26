package com.radiozport.ninegfiles.ui.storage

import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.databinding.FragmentStorageAnalysisBinding
import com.radiozport.ninegfiles.databinding.ItemStorageBarBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StorageAnalysisFragment : Fragment() {

    private var _binding: FragmentStorageAnalysisBinding? = null
    private val binding get() = _binding!!

    /** Root directory that the current scan covers. Defaults to internal storage. */
    private var scanRoot: File = Environment.getExternalStorageDirectory()

    data class CategoryEntry(
        val label: String,
        val bytes: Long,
        val color: Int,
        val totalBytes: Long = 1L
    ) {
        val percent: Int get() = if (totalBytes > 0) (bytes * 100 / totalBytes).toInt() else 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStorageAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvCategories.layoutManager = LinearLayoutManager(requireContext())
        setupDriveSwitcher()
        analyzeStorage()
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
                analyzeStorage()
            }
            .show()
    }
    // ────────────────────────────────────────────────────────────────────────

    private fun analyzeStorage() {
        binding.scanningLayout.isVisible = true
        binding.contentGroup.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { buildCategoryEntries() }
            if (_binding == null) return@launch
            val total = raw.sumOf { it.bytes }.coerceAtLeast(1L)
            binding.tvTotalSize.text = FileUtils.formatSize(total)
            binding.rvCategories.adapter = CategoryAdapter(raw.map { it.copy(totalBytes = total) })
            binding.scanningLayout.isVisible = false
            binding.contentGroup.isVisible = true
        }
    }

    private fun buildCategoryEntries(): List<CategoryEntry> {
        val sizeMap = mutableMapOf<FileType, Long>()
        try {
            scanRoot
                .walkTopDown().onEnter { it.canRead() }
                .filter { it.isFile }
                .forEach { f -> sizeMap[FileType.fromExtension(f.extension)] = (sizeMap[FileType.fromExtension(f.extension)] ?: 0L) + f.length() }
        } catch (_: Exception) {}

        return listOf(
            CategoryEntry("Images",       sizeMap[FileType.IMAGE]        ?: 0L, R.color.category_image),
            CategoryEntry("Videos",       sizeMap[FileType.VIDEO]        ?: 0L, R.color.category_video),
            CategoryEntry("Audio",        sizeMap[FileType.AUDIO]        ?: 0L, R.color.category_audio),
            CategoryEntry("Documents",    (sizeMap[FileType.DOCUMENT] ?: 0L) + (sizeMap[FileType.PDF] ?: 0L), R.color.category_document),
            CategoryEntry("Archives",     sizeMap[FileType.ARCHIVE]      ?: 0L, R.color.category_archive),
            CategoryEntry("APKs",         sizeMap[FileType.APK]          ?: 0L, R.color.category_apk),
            CategoryEntry("Code",         sizeMap[FileType.CODE]         ?: 0L, R.color.md_theme_tertiary),
            CategoryEntry("Other",        sizeMap[FileType.UNKNOWN]      ?: 0L, R.color.md_theme_onSurfaceVariant)
        ).filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

class CategoryAdapter(private val items: List<StorageAnalysisFragment.CategoryEntry>) :
    RecyclerView.Adapter<CategoryAdapter.VH>() {

    inner class VH(private val b: ItemStorageBarBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: StorageAnalysisFragment.CategoryEntry) {
            b.label0.text = e.label
            b.size0.text = "${FileUtils.formatSize(e.bytes)}  (${e.percent}%)"
            b.bar0.progress = e.percent.coerceAtLeast(1)
            b.bar0.setIndicatorColor(androidx.core.content.ContextCompat.getColor(b.root.context, e.color))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemStorageBarBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
    override fun getItemCount() = items.size
}
