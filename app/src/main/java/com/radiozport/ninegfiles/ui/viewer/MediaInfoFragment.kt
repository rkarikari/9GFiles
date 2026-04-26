package com.radiozport.ninegfiles.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.databinding.FragmentMediaInfoBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaInfoFragment : Fragment() {

    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FileExplorerViewModel by activityViewModels()

    companion object {
        fun newInstance(path: String) = MediaInfoFragment().apply {
            arguments = bundleOf("mediaPath" to path)
        }
    }

    // All metadata extracted off the main thread
    data class MediaInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val genre: String?,
        val year: String?,
        val track: String?,
        val trackTotal: String?,
        val composer: String?,
        val duration: Long,       // ms
        val bitrate: Long?,       // bps
        val sampleRate: String?,
        val bitsPerSample: String?,
        val videoWidth: Int?,
        val videoHeight: Int?,
        val frameRate: String?,
        val rotation: String?,
        val mimeType: String?,
        val artBytes: ByteArray?,
        val isVideo: Boolean
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString("mediaPath") ?: run {
            binding.tvError.text = "No media path provided"
            binding.tvError.isVisible = true
            return
        }

        val file = File(path)
        binding.progressBar.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { extractMetadata(path) }
            if (_binding == null) return@launch

            binding.progressBar.isVisible = false

            if (info == null) {
                binding.tvError.text = "Could not read media metadata"
                binding.tvError.isVisible = true
                return@launch
            }

            bindInfo(info, file)
        }
    }

    private fun bindInfo(info: MediaInfo, file: File) {
        // ── Header ──────────────────────────────────────────────────────────

        // Album art / video thumbnail
        if (info.artBytes != null) {
            val bmp = BitmapFactory.decodeByteArray(info.artBytes, 0, info.artBytes.size)
            if (bmp != null) {
                binding.ivArtwork.setImageBitmap(bmp)
                binding.ivArtwork.isVisible = true
            }
        }

        binding.ivIcon.setImageResource(
            if (info.isVideo) R.drawable.ic_file_video else R.drawable.ic_file_audio
        )

        val displayTitle = info.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        binding.tvTitle.text = displayTitle

        if (info.isVideo) {
            val res = if (info.videoWidth != null && info.videoHeight != null)
                "${info.videoWidth} × ${info.videoHeight}" else null
            binding.tvArtist.text = res
            binding.tvArtist.isVisible = res != null
        } else {
            binding.tvArtist.text = info.artist
            binding.tvArtist.isVisible = !info.artist.isNullOrBlank()
        }

        binding.chipFormat.text = file.extension.uppercase()
        binding.chipDuration.text = formatDuration(info.duration)

        // ── Tags card ───────────────────────────────────────────────────────
        val isAudio = !info.isVideo
        binding.cardTags.isVisible = isAudio &&
            listOf(info.album, info.albumArtist, info.genre, info.year, info.track, info.composer)
                .any { !it.isNullOrBlank() }

        bindRow(binding.rowAlbum,       binding.tvAlbum,       info.album)
        bindRow(binding.rowAlbumArtist, binding.tvAlbumArtist, info.albumArtist)
        bindRow(binding.rowGenre,       binding.tvGenre,        info.genre)
        bindRow(binding.rowYear,        binding.tvYear,         info.year)
        bindRow(binding.rowTrack,       binding.tvTrack,
            buildTrackString(info.track, info.trackTotal))
        bindRow(binding.rowComposer,    binding.tvComposer,    info.composer)

        // ── Technical card ──────────────────────────────────────────────────
        binding.tvMimeType.text = info.mimeType ?: "—"
        binding.tvDuration.text = formatDuration(info.duration)
        binding.tvFileSize.text = FileUtils.formatSize(file.length())
        binding.tvFileName.text = file.name

        bindRow(binding.rowBitrate, binding.tvBitrate,
            info.bitrate?.let { "${it / 1000} kbps" })

        // Audio-only rows
        bindRow(binding.rowSampleRate,   binding.tvSampleRate,
            info.sampleRate?.let { "$it Hz" })
        bindRow(binding.rowBitsPerSample, binding.tvBitsPerSample,
            info.bitsPerSample?.let { "$it-bit" })
        binding.rowSampleRate.isVisible   = isAudio && !info.sampleRate.isNullOrBlank()
        binding.rowBitsPerSample.isVisible = isAudio && !info.bitsPerSample.isNullOrBlank()

        // Video-only rows
        bindRow(binding.rowResolution, binding.tvResolution,
            if (info.videoWidth != null && info.videoHeight != null)
                "${info.videoWidth} × ${info.videoHeight}" else null)
        bindRow(binding.rowFrameRate, binding.tvFrameRate,
            info.frameRate?.let { "$it fps" })
        bindRow(binding.rowRotation, binding.tvRotation,
            info.rotation?.let { "${it}°" })
        binding.rowResolution.isVisible = info.isVideo && info.videoWidth != null
        binding.rowFrameRate.isVisible  = info.isVideo && !info.frameRate.isNullOrBlank()
        binding.rowRotation.isVisible   = info.isVideo && !info.rotation.isNullOrBlank()

        // ── Actions ─────────────────────────────────────────────────────────
        // Replace btnPlay with inline player toggle
        var playerShown = false
        binding.btnPlay.setOnClickListener {
            if (!playerShown) {
                playerShown = true
                binding.btnPlay.text = "▼  Hide Player"
                // Collect sibling media files from the same directory so the
                // player can offer prev/next across the whole folder.
                val siblings: List<String> = run {
                    val mediaTypes = setOf(FileType.AUDIO, FileType.VIDEO)
                    // Prefer the explorer's current directory listing if available
                    var candidates = viewModel.files.value
                        .filter { it.fileType in mediaTypes }
                        .map { it.path }
                    // Fall back: files in the same directory on disk
                    if (candidates.isEmpty()) {
                        val dir = file.parentFile
                        if (dir != null && dir.canRead()) {
                            candidates = dir.listFiles()
                                ?.filter { f -> FileUtils.isMediaFile(f) }
                                ?.sortedBy { it.name.lowercase() }
                                ?.map { it.absolutePath }
                                ?: emptyList()
                        }
                    }
                    // If still nothing (e.g. direct search open), wrap just this file
                    candidates.ifEmpty { listOf(file.absolutePath) }
                }
                val startIdx = siblings.indexOfFirst { it == file.absolutePath }
                    .coerceAtLeast(0)
                val playerFrag = MediaPlayerFragment.newInstance(siblings, startIdx, info.isVideo)
                childFragmentManager.commit {
                    replace(com.radiozport.ninegfiles.R.id.playerContainer, playerFrag)
                    setReorderingAllowed(true)
                }
                val container = binding.root.findViewById<android.view.View>(com.radiozport.ninegfiles.R.id.playerContainer)
                container?.visibility = android.view.View.VISIBLE
            } else {
                playerShown = false
                binding.btnPlay.text = "▶  Play"
                childFragmentManager.commit {
                    val f = childFragmentManager.findFragmentById(com.radiozport.ninegfiles.R.id.playerContainer)
                    if (f != null) remove(f)
                }
                val container = binding.root.findViewById<android.view.View>(com.radiozport.ninegfiles.R.id.playerContainer)
                container?.visibility = android.view.View.GONE
            }
        }
        // Set initial label based on type
        binding.btnPlay.text = if (info.isVideo) "▶  Play Video" else "▶  Play Audio" 
        binding.btnShare.setOnClickListener { shareFile(file, info.mimeType) }

        // ID3 tag editor — visible only for MP3 files
        val isEditableMp3 = file.extension.lowercase() == "mp3" && file.canWrite()
        binding.btnEditTags.isVisible = isEditableMp3
        if (isEditableMp3) {
            binding.btnEditTags.setOnClickListener {
                val tags = mapOf(
                    "title"       to info.title,
                    "artist"      to info.artist,
                    "album"       to info.album,
                    "albumArtist" to info.albumArtist,
                    "year"        to info.year,
                    "track"       to info.track,
                    "genre"       to info.genre,
                    "composer"    to info.composer
                )
                com.radiozport.ninegfiles.ui.dialogs.Id3TagEditorDialog.show(
                    childFragmentManager, file.absolutePath, tags
                ) {
                    // Reload metadata after save
                    viewLifecycleOwner.lifecycleScope.launch {
                        val fresh = withContext(Dispatchers.IO) { extractMetadata(file.absolutePath) }
                        if (_binding != null && fresh != null) bindInfo(fresh, file)
                    }
                }
            }
        }
    }

    private fun bindRow(row: View, tv: android.widget.TextView, value: String?) {
        val show = !value.isNullOrBlank()
        row.isVisible = show
        if (show) tv.text = value
    }

    private fun buildTrackString(track: String?, total: String?): String? {
        if (track.isNullOrBlank()) return null
        return if (!total.isNullOrBlank()) "$track / $total" else track
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    // ── Metadata extraction ─────────────────────────────────────────────────

    private fun extractMetadata(path: String): MediaInfo? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)

            val mimeType = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val isVideo  = mimeType?.startsWith("video") == true ||
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"

            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val width  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()

            val frameRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.let { "%.2f".format(it).trimEnd('0').trimEnd('.') }
            } else null

            // Embedded picture: album art for audio, thumbnail for video
            val artBytes: ByteArray? = try {
                if (isVideo) {
                    // Get a frame as album-art substitute
                    val bmp = mmr.getFrameAtTime(durationMs * 1000 / 4) // 25% in
                    if (bmp != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                        stream.toByteArray()
                    } else null
                } else {
                    mmr.embeddedPicture
                }
            } catch (_: Exception) { null }

            MediaInfo(
                title        = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist       = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album        = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre        = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year         = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                track        = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                trackTotal   = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS),
                composer     = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                duration     = durationMs,
                bitrate      = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                sampleRate   = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE),
                bitsPerSample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE) else null,
                videoWidth   = width,
                videoHeight  = height,
                frameRate    = frameRate,
                rotation     = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                mimeType     = mimeType,
                artBytes     = artBytes,
                isVideo      = isVideo
            )
        } catch (_: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    // ── System actions ──────────────────────────────────────────────────────

    private fun openWithSystem(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(
                requireContext(), "No player app found", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareFile(file: File, mimeType: String?) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share ${file.name}"
        )
        startActivity(intent)
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp3"  -> "audio/mpeg"
            "flac" -> "audio/flac"
            "aac"  -> "audio/aac"
            "wav"  -> "audio/wav"
            "ogg"  -> "audio/ogg"
            "m4a"  -> "audio/mp4"
            "wma"  -> "audio/x-ms-wma"
            "opus" -> "audio/ogg"
            "aiff" -> "audio/aiff"
            "mp4"  -> "video/mp4"
            "mkv"  -> "video/x-matroska"
            "avi"  -> "video/avi"
            "mov"  -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp"  -> "video/3gpp"
            "ts"   -> "video/mp2t"
            else   -> "*/*"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
