package com.radiozport.ninegfiles.ui.viewer

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.databinding.FragmentImageViewerBinding
import com.radiozport.ninegfiles.databinding.FragmentSingleImageBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.radiozport.ninegfiles.utils.CastMediaServer
import com.radiozport.ninegfiles.utils.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ImageViewerFragment : Fragment() {

    private var _binding: FragmentImageViewerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FileExplorerViewModel by activityViewModels()
    private lateinit var imageFiles: List<FileItem>
    private var startPosition = 0

    // ── Cast ──────────────────────────────────────────────────────────────────
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castSession = session
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            castSession = null
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    companion object {
        private const val TAG = "ImageViewerFragment"
        const val ARG_PATH = "path"
        fun newInstance(path: String) = ImageViewerFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImageViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val clickedPath = arguments?.getString(ARG_PATH) ?: return

        // Prefer images from the file browser's current directory (normal navigation).
        // Fall back to search results when arriving from a category/search view, where
        // viewModel.files may be empty or contain a different directory's contents.
        var candidates = viewModel.files.value.filter { it.fileType == FileType.IMAGE }
        if (candidates.none { it.path == clickedPath }) {
            candidates = viewModel.searchResults.value.filter { it.fileType == FileType.IMAGE }
        }
        // Last resort: wrap the single tapped file so the viewer always has something to show.
        if (candidates.none { it.path == clickedPath }) {
            val file = File(clickedPath)
            if (file.exists()) candidates = listOf(FileItem.fromFile(file))
        }
        imageFiles = candidates
        startPosition = imageFiles.indexOfFirst { it.path == clickedPath }.coerceAtLeast(0)

        if (imageFiles.isEmpty()) return

        // ── Cast init ─────────────────────────────────────────────────────────
        castContext = try {
            CastContext.getSharedInstance(requireContext())
        } catch (e: Exception) {
            Log.w(TAG, "Cast not available: ${e.message}")
            null
        }
        castSession = castContext?.sessionManager?.currentCastSession
        CastMediaServer.start()

        // Register ALL images in the queue upfront so each image gets its own
        // stable URL (/media/0, /media/1, …).  Using a per-index URL is what
        // makes the receiver reload content on swipe; when every image was served
        // at the same /media URL the receiver treated swipes as no-ops (same URL).
        CastMediaServer.serveQueue(imageFiles.map { item ->
            item.file to mimeForFile(item)
        })

        setupPager()
        setupOverlay()
        setupSystemUi()
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager
            ?.addSessionManagerListener(castSessionListener, CastSession::class.java)
        castSession = castContext?.sessionManager?.currentCastSession
    }

    override fun onPause() {
        super.onPause()
        castContext?.sessionManager
            ?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    private fun setupPager() {
        binding.viewPager.adapter = ImagePagerAdapter()
        binding.viewPager.setCurrentItem(startPosition, false)
        updateOverlayInfo(startPosition)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateOverlayInfo(position)
                // If a Cast session is active, push the newly visible image to the receiver
                val item = imageFiles.getOrNull(position) ?: return
                val session = castSession
                if (session != null && session.isConnected) {
                    castCurrentImage(item)
                }
            }
        })
    }

    private fun updateOverlayInfo(position: Int) {
        val item = imageFiles.getOrNull(position) ?: return
        binding.tvTitle.text = item.name
        binding.tvDetails.text = buildString {
            append(FileUtils.formatSize(item.size))
            append("  •  ")
            append(SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(item.lastModified)))
        }
        binding.tvCounter.text = "${position + 1} / ${imageFiles.size}"
    }

    private fun setupOverlay() {
        var overlayVisible = true
        binding.viewPager.setOnClickListener {
            overlayVisible = !overlayVisible
            binding.topOverlay.isVisible = overlayVisible
            binding.bottomOverlay.isVisible = overlayVisible
        }

        binding.btnClose.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.btnShare.setOnClickListener {
            val item = imageFiles.getOrNull(binding.viewPager.currentItem) ?: return@setOnClickListener
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", item.file
            )
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = item.mimeType
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share image"
            ))
        }

        binding.btnSaveAs.setOnClickListener {
            val item = imageFiles.getOrNull(binding.viewPager.currentItem) ?: return@setOnClickListener
            showSaveAsDialog(item)
        }

        binding.btnPrint.setOnClickListener {
            val item = imageFiles.getOrNull(binding.viewPager.currentItem) ?: return@setOnClickListener
            printImage(item)
        }

        binding.btnCast.setOnClickListener {
            val item = imageFiles.getOrNull(binding.viewPager.currentItem) ?: return@setOnClickListener
            castCurrentImage(item)
        }

        binding.btnDelete.setOnClickListener {
            val item = imageFiles.getOrNull(binding.viewPager.currentItem) ?: return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete \"${item.name}\"?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.delete(listOf(item))
                    imageFiles = imageFiles.toMutableList().also { it.remove(item) }
                    if (imageFiles.isEmpty()) {
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    } else {
                        (binding.viewPager.adapter as ImagePagerAdapter).notifyDataSetChanged()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Cast the image at [item] to a Chromecast receiver.
     *
     * Each image is served at a unique URL (/media/<index>) so the receiver
     * always fetches fresh content when the user swipes to a different image.
     * Previously every image was served at the same /media URL, which caused
     * the receiver to ignore swipes (same URL = no reload).
     */
    private fun castCurrentImage(item: FileItem) {
        val ctx = castContext
        if (ctx == null) {
            Toast.makeText(requireContext(),
                "Chromecast not available on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val session = castSession
        if (session == null || !session.isConnected) {
            Toast.makeText(requireContext(),
                "Tap the Cast button in the player to connect to a device first",
                Toast.LENGTH_LONG).show()
            try {
                val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                    .addControlCategory(
                        com.google.android.gms.cast.CastMediaControlIntent
                            .categoryForCast(
                                com.google.android.gms.cast.CastMediaControlIntent
                                    .DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                            )
                    )
                    .build()
                androidx.mediarouter.app.MediaRouteChooserDialog(requireContext()).apply {
                    routeSelector = selector
                    show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not show route chooser: ${e.message}")
            }
            return
        }

        val mime = mimeForFile(item)

        // Use the item's position in imageFiles as the queue index so the URL is
        // unique per image (/media/0, /media/1, …).
        val index = imageFiles.indexOf(item).coerceAtLeast(0)
        val url = CastMediaServer.getUrlForIndex(requireContext(), index)
        if (url == null) {
            Toast.makeText(requireContext(),
                "Wi-Fi connection required for casting", Toast.LENGTH_SHORT).show()
            return
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
            putString(MediaMetadata.KEY_TITLE, item.name)
        }

        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_NONE)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        session.remoteMediaClient?.load(request)?.setResultCallback { result ->
            if (result.status.isSuccess) {
                if (_binding != null) {
                    Toast.makeText(requireContext(),
                        "Casting \"${item.name}\"", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "Cast load failed: ${result.status.statusMessage}")
                if (_binding != null) {
                    Toast.makeText(requireContext(),
                        "Cast failed: ${result.status.statusMessage}",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Derive a Cast-compatible MIME type from the file extension. */
    private fun mimeForFile(item: FileItem): String = when (item.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "webp"        -> "image/webp"
        "gif"         -> "image/gif"
        "bmp"         -> "image/bmp"
        else          -> "image/jpeg"
    }

    private fun setupSystemUi() {
        requireActivity().window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onDestroyView() {
        // Restore system UI
        requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        CastMediaServer.stop()
        super.onDestroyView()
        _binding = null
    }

    private fun printImage(item: FileItem) {
        val bmp = try {
            android.graphics.BitmapFactory.decodeFile(item.path)
        } catch (_: Exception) { null }
        if (bmp == null) {
            android.widget.Toast.makeText(requireContext(),
                "Could not decode image for printing", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val helper = androidx.print.PrintHelper(requireContext())
        helper.scaleMode = androidx.print.PrintHelper.SCALE_MODE_FIT
        helper.printBitmap(item.name, bmp)
    }

    private fun showSaveAsDialog(item: FileItem) {
        val formats = arrayOf("JPEG (.jpg)", "PNG (.png)", "WebP (.webp)")
        val compressFormats = arrayOf(
            android.graphics.Bitmap.CompressFormat.JPEG,
            android.graphics.Bitmap.CompressFormat.PNG,
            android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
        )
        val extensions = arrayOf("jpg", "png", "webp")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save As\u2026")
            .setItems(formats) { _, which ->
                convertAndSave(item, compressFormats[which], extensions[which])
            }
            .show()
    }

    private fun convertAndSave(
        item: FileItem,
        format: android.graphics.Bitmap.CompressFormat,
        ext: String
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeFile(item.path)
                        ?: return@withContext "Cannot decode image"
                    val baseName = item.name.substringBeforeLast(".")
                    val outFile = java.io.File(item.file.parent, "$baseName.$ext")
                    outFile.outputStream().use { bmp.compress(format, 90, it) }
                    bmp.recycle()
                    "Saved as ${outFile.name}"
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, result, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
        }
    }

    inner class ImagePagerAdapter : FragmentStateAdapter(this) {
        override fun getItemCount() = imageFiles.size
        override fun createFragment(position: Int) =
            SingleImageFragment.newInstance(imageFiles[position].path)
    }
}

// ─── Single image page with pinch-to-zoom ─────────────────────────────────────

class SingleImageFragment : Fragment() {

    private var _binding: FragmentSingleImageBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance(path: String) = SingleImageFragment().apply {
            arguments = bundleOf(ImageViewerFragment.ARG_PATH to path)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSingleImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString(ImageViewerFragment.ARG_PATH) ?: return

        Glide.with(this)
            .load(File(path))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(binding.imageView)

        setupZoom()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoom() {
        val matrix = Matrix()
        val savedMatrix = Matrix()
        var mode = 0 // NONE
        val start = PointF()
        val mid = PointF()
        var oldDist = 1f
        var currentScale = 1f

        val NONE = 0; val DRAG = 1; val ZOOM = 2
        val MIN_SCALE = 1f; val MAX_SCALE = 5f

        binding.imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        binding.imageView.setOnTouchListener { v, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    start.set(event.x, event.y)
                    mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(mid, event)
                        mode = ZOOM
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                    // Snap back if over-zoomed or panned out of bounds
                    if (currentScale < MIN_SCALE) {
                        matrix.setScale(MIN_SCALE, MIN_SCALE)
                        currentScale = MIN_SCALE
                        binding.imageView.imageMatrix = matrix
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG && currentScale > MIN_SCALE) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - start.x, event.y - start.y)
                    } else if (mode == ZOOM) {
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            matrix.set(savedMatrix)
                            val scale = (newDist / oldDist).coerceIn(MIN_SCALE / currentScale, MAX_SCALE / currentScale)
                            currentScale *= scale
                            matrix.postScale(scale, scale, mid.x, mid.y)
                        }
                    }
                    binding.imageView.imageMatrix = matrix
                }
            }
            // Allow parent to handle horizontal swipes when not zoomed
            v.parent.requestDisallowInterceptTouchEvent(currentScale > MIN_SCALE)
            true
        }
    }

    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        point.set((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
