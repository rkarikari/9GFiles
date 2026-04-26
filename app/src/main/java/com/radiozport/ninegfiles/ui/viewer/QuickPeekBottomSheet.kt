package com.radiozport.ninegfiles.ui.viewer

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.databinding.BottomSheetQuickPeekBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Long-press preview sheet: shows image thumbnail, first lines of text,
 * or media metadata without fully opening the file.
 */
class QuickPeekBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetQuickPeekBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_PATH = "path"
        fun newInstance(item: FileItem) = QuickPeekBottomSheet().apply {
            arguments = bundleOf(ARG_PATH to item.path)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetQuickPeekBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString(ARG_PATH) ?: run { dismiss(); return }
        val file = File(path)
        val fileType = FileType.fromExtension(file.extension.lowercase())

        binding.tvFileName.text = file.name
        binding.tvFileSize.text = FileUtils.formatSize(file.length())
        binding.tvFilePath.text = file.parent

        // Show content based on type
        when (fileType) {
            FileType.IMAGE -> showImagePreview(file)
            FileType.AUDIO, FileType.VIDEO -> showMediaPreview(file, fileType)
            FileType.DOCUMENT, FileType.CODE, FileType.PDF -> showTextPreview(file)
            FileType.ARCHIVE -> showArchivePreview(file)
            else -> showGenericInfo(file)
        }
    }

    private fun showImagePreview(file: File) {
        binding.imagePreview.isVisible = true
        Glide.with(this)
            .load(file)
            .centerInside()
            .into(binding.imagePreview)
    }

    private fun showMediaPreview(file: File, type: FileType) {
        binding.imagePreview.isVisible = true
        // Show video thumbnail or audio art via Glide
        Glide.with(this).load(file)
            .placeholder(if (type == FileType.VIDEO) com.radiozport.ninegfiles.R.drawable.ic_file_video
                         else com.radiozport.ninegfiles.R.drawable.ic_file_audio)
            .into(binding.imagePreview)

        // Show duration via MediaMetadataRetriever
        CoroutineScope(Dispatchers.Main).launch {
            val meta = withContext(Dispatchers.IO) {
                try {
                    android.media.MediaMetadataRetriever().run {
                        setDataSource(file.absolutePath)
                        val dur = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        val title = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                        val artist = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        release()
                        Triple(dur, title, artist)
                    }
                } catch (_: Exception) { Triple(null, null, null) }
            }
            if (_binding == null) return@launch
            val (dur, title, artist) = meta
            val lines = mutableListOf<String>()
            title?.let { lines.add("Title: $it") }
            artist?.let { lines.add("Artist: $it") }
            dur?.let {
                val s = it / 1000
                lines.add("Duration: ${"%d:%02d".format(s / 60, s % 60)}")
            }
            if (lines.isNotEmpty()) {
                binding.tvTextPreview.isVisible = true
                binding.tvTextPreview.text = lines.joinToString("\n")
            }
        }
    }

    private fun showTextPreview(file: File) {
        binding.tvTextPreview.isVisible = true
        CoroutineScope(Dispatchers.Main).launch {
            val preview = withContext(Dispatchers.IO) {
                try {
                    file.bufferedReader().use { br ->
                        val sb = StringBuilder()
                        repeat(20) {
                            val line = br.readLine() ?: return@repeat
                            sb.appendLine(line)
                        }
                        sb.toString().trim()
                    }
                } catch (_: Exception) { "Could not read file" }
            }
            if (_binding != null) binding.tvTextPreview.text = preview.ifEmpty { "(empty)" }
        }
    }

    private fun showArchivePreview(file: File) {
        binding.tvTextPreview.isVisible = true
        CoroutineScope(Dispatchers.Main).launch {
            val entries = withContext(Dispatchers.IO) {
                try {
                    org.apache.commons.compress.archivers.zip.ZipFile(file).use { zip ->
                        zip.entries.toList().take(15).joinToString("\n") { "  ${it.name}" }
                    }
                } catch (_: Exception) { "Cannot read archive" }
            }
            if (_binding != null) binding.tvTextPreview.text = "Archive contents:\n$entries"
        }
    }

    private fun showGenericInfo(file: File) {
        binding.tvTextPreview.isVisible = true
        val modified = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(file.lastModified()))
        binding.tvTextPreview.text = "Type: ${file.extension.uppercase().ifEmpty { "Unknown" }}\nModified: $modified"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
