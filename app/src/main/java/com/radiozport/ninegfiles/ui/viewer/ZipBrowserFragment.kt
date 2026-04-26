package com.radiozport.ninegfiles.ui.viewer

import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.databinding.FragmentZipBrowserBinding
import com.radiozport.ninegfiles.databinding.ItemZipEntryBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

data class ZipEntry(
    val name: String,
    val fullPath: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val extension: String = name.substringAfterLast('.', "").lowercase()
) {
    val fileType: FileType get() = if (isDirectory) FileType.FOLDER else FileType.fromExtension(extension)
    val formattedSize: String get() = if (isDirectory) "Folder" else FileUtils.formatSize(size)
    val compressionRatio: String get() = if (size > 0 && !isDirectory) {
        val ratio = (1.0 - compressedSize.toDouble() / size) * 100
        "%.0f%% saved".format(ratio)
    } else ""
}

private enum class ArchiveFormat { ZIP, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, SEVEN_Z, RAR, UNKNOWN }

class ZipBrowserFragment : Fragment() {

    private var _binding: FragmentZipBrowserBinding? = null
    private val binding get() = _binding!!

    private lateinit var archivePath: String
    private var currentPrefix = ""
    private val backStack = mutableListOf<String>()
    private lateinit var adapter: ZipEntryAdapter

    companion object {
        fun newInstance(path: String) = ZipBrowserFragment().apply {
            arguments = bundleOf("archivePath" to path, "zipPath" to path)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentZipBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        archivePath = arguments?.getString("archivePath")
            ?: arguments?.getString("zipPath")
            ?: run {
                binding.tvError.text = "No archive path provided"
                binding.tvError.isVisible = true
                return
            }

        adapter = ZipEntryAdapter(onFolderClick = { entry ->
            backStack.add(currentPrefix)
            currentPrefix = entry.fullPath
            loadEntries()
        })

        binding.rvEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.rvEntries.adapter = adapter
        binding.tvArchiveName.text = File(archivePath).name
        binding.btnUp.setOnClickListener { navigateUp() }

        loadEntries()
    }

    private fun loadEntries() {
        binding.progressBar.isVisible = true
        binding.rvEntries.isVisible = false
        binding.tvError.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readArchiveEntries() }
            if (_binding == null) return@launch

            binding.progressBar.isVisible = false
            binding.rvEntries.isVisible = true

            result.fold(
                onSuccess = { entries ->
                    binding.tvEmpty.isVisible = entries.isEmpty()
                    binding.tvEmpty.text = if (currentPrefix.isEmpty()) "Archive is empty" else "Folder is empty"
                    binding.tvCurrentPath.text = if (currentPrefix.isEmpty()) "/" else "/$currentPrefix"
                    binding.btnUp.isVisible = backStack.isNotEmpty()
                    adapter.submitList(entries)
                },
                onFailure = { e ->
                    binding.tvError.text = "Failed to read archive: ${e.message}"
                    binding.tvError.isVisible = true
                }
            )
        }
    }

    // ── Format detection ──────────────────────────────────────────────────────

    private fun detectFormat(path: String): ArchiveFormat {
        val name = File(path).name.lowercase()
        return when {
            name.endsWith(".tar.gz")  || name.endsWith(".tgz")  -> ArchiveFormat.TAR_GZ
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> ArchiveFormat.TAR_BZ2
            name.endsWith(".tar.xz")  || name.endsWith(".txz")  -> ArchiveFormat.TAR_XZ
            name.endsWith(".tar")                               -> ArchiveFormat.TAR
            name.endsWith(".zip")                               -> ArchiveFormat.ZIP
            name.endsWith(".7z")                                -> ArchiveFormat.SEVEN_Z
            name.endsWith(".rar")                               -> ArchiveFormat.RAR
            else                                                -> ArchiveFormat.UNKNOWN
        }
    }

    // ── Entry reading ─────────────────────────────────────────────────────────

    private fun readArchiveEntries(): Result<List<ZipEntry>> = runCatching {
        when (detectFormat(archivePath)) {
            ArchiveFormat.ZIP    -> readZip()
            ArchiveFormat.TAR    -> readTar(null)
            ArchiveFormat.TAR_GZ -> readTar("gz")
            ArchiveFormat.TAR_BZ2 -> readTar("bz2")
            ArchiveFormat.TAR_XZ -> readTar("xz")
            ArchiveFormat.SEVEN_Z -> readSevenZ()
            ArchiveFormat.RAR    -> readRar()
            ArchiveFormat.UNKNOWN -> emptyList()
        }
    }

    private fun readZip(): List<ZipEntry> = ZipFile(File(archivePath)).use { zip ->
        buildEntryList(zip.entries.toList().map { ze ->
            RawEntry(ze.name.trimEnd('/'), ze.size.coerceAtLeast(0L),
                ze.compressedSize.coerceAtLeast(0L), ze.isDirectory)
        })
    }

    private fun readTar(compression: String?): List<ZipEntry> {
        val buffered = BufferedInputStream(FileInputStream(File(archivePath)))
        val decompressed = when (compression) {
            "gz"  -> GzipCompressorInputStream(buffered)
            "bz2" -> BZip2CompressorInputStream(buffered)
            "xz"  -> XZCompressorInputStream(buffered)
            else  -> buffered
        }
        return TarArchiveInputStream(decompressed).use { tar ->
            val raw = mutableListOf<RawEntry>()
            var e = tar.nextTarEntry
            while (e != null) {
                raw.add(RawEntry(e.name.trimEnd('/'), e.size.coerceAtLeast(0L), 0L, e.isDirectory))
                e = tar.nextTarEntry
            }
            buildEntryList(raw)
        }
    }

    private fun readSevenZ(): List<ZipEntry> = SevenZFile(File(archivePath)).use { sz ->
        buildEntryList(sz.entries.map { e ->
            // SevenZArchiveEntry.compressedSize is private in commons-compress 1.25 — use 0L
            RawEntry(e.name.trimEnd('/').replace('\\', '/'),
                e.size.coerceAtLeast(0L), 0L, e.isDirectory)
        })
    }

    private fun readRar(): List<ZipEntry> {
        // commons-compress 1.25 does not expose a usable public RarArchiveInputStream
        // constructor — surface a clear message so the UI can fall back to a system handler
        throw Exception("RAR archives are not supported. Use a dedicated app to open this file.")
    }

    // ── Common entry-list builder ─────────────────────────────────────────────

    private data class RawEntry(val path: String, val size: Long, val compressedSize: Long, val isDir: Boolean)

    private fun buildEntryList(rawEntries: List<RawEntry>): List<ZipEntry> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ZipEntry>()

        for (raw in rawEntries) {
            if (!raw.path.startsWith(currentPrefix)) continue
            val relative = raw.path.removePrefix(currentPrefix).trimStart('/')
            if (relative.isEmpty()) continue
            val firstSegment = relative.substringBefore('/')
            val isInSubDir   = relative.contains('/')

            if (isInSubDir) {
                if (seen.add(firstSegment))
                    results.add(ZipEntry(firstSegment, "$currentPrefix$firstSegment/", 0L, 0L, true))
            } else {
                if (seen.add(firstSegment)) {
                    if (raw.isDir)
                        results.add(ZipEntry(firstSegment, "$currentPrefix$firstSegment/", 0L, 0L, true))
                    else
                        results.add(ZipEntry(firstSegment, "$currentPrefix$firstSegment",
                            raw.size, raw.compressedSize, false))
                }
            }
        }

        return results.sortedWith(compareByDescending<ZipEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun navigateUp(): Boolean {
        if (backStack.isEmpty()) return false
        currentPrefix = backStack.removeLastOrNull() ?: ""
        loadEntries()
        return true
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── ZipEntryAdapter ──────────────────────────────────────────────────────────

class ZipEntryAdapter(
    private val onFolderClick: (ZipEntry) -> Unit
) : ListAdapter<ZipEntry, ZipEntryAdapter.VH>(ZipDiffCallback()) {

    inner class VH(private val b: ItemZipEntryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: ZipEntry) {
            b.tvName.text = if (entry.isDirectory) "${entry.name}/" else entry.name
            b.ivIcon.setImageResource(getIcon(entry.fileType))
            b.tvSize.text = entry.formattedSize
            if (entry.isDirectory) {
                b.tvCompression.text = ""
                b.tvCompressedSize.text = ""
            } else {
                b.tvCompression.text = entry.compressionRatio
                b.tvCompressedSize.text = if (entry.compressedSize > 0)
                    "${FileUtils.formatSize(entry.compressedSize)} compressed" else ""
            }
            b.root.setOnClickListener { if (entry.isDirectory) onFolderClick(entry) }
            b.root.isClickable = entry.isDirectory
            b.root.alpha = if (entry.isDirectory) 1f else 0.92f
        }

        private fun getIcon(type: FileType) = when (type) {
            FileType.FOLDER       -> R.drawable.ic_folder
            FileType.IMAGE        -> R.drawable.ic_file_image
            FileType.VIDEO        -> R.drawable.ic_file_video
            FileType.AUDIO        -> R.drawable.ic_file_audio
            FileType.DOCUMENT     -> R.drawable.ic_file_document
            FileType.PDF          -> R.drawable.ic_file_pdf
            FileType.ARCHIVE      -> R.drawable.ic_file_archive
            FileType.APK          -> R.drawable.ic_file_apk
            FileType.CODE         -> R.drawable.ic_file_code
            FileType.SPREADSHEET  -> R.drawable.ic_file_spreadsheet
            FileType.PRESENTATION -> R.drawable.ic_file_presentation
            else                  -> R.drawable.ic_file_generic
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemZipEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))

    class ZipDiffCallback : DiffUtil.ItemCallback<ZipEntry>() {
        override fun areItemsTheSame(a: ZipEntry, b: ZipEntry) = a.fullPath == b.fullPath
        override fun areContentsTheSame(a: ZipEntry, b: ZipEntry) = a == b
    }
}
