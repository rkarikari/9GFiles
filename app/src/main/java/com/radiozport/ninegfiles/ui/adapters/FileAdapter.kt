package com.radiozport.ninegfiles.ui.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.data.model.ViewMode
import com.radiozport.ninegfiles.databinding.ItemFileGridBinding
import com.radiozport.ninegfiles.databinding.ItemFileListBinding
import com.radiozport.ninegfiles.databinding.ItemFileCompactBinding
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

class FileAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Boolean,
    private val onItemMenuClick: (FileItem, View) -> Unit
) : ListAdapter<FileItem, RecyclerView.ViewHolder>(FileDiffCallback()) {

    var viewMode: ViewMode = ViewMode.LIST
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /**
     * Controls the vertical row height for LIST view.
     *  "compact"     → 48 dp  (power user, dense info)
     *  "normal"      → 64 dp  (default)
     *  "comfortable" → 80 dp  (accessibility, touch-friendly)
     */
    /**
     * When false, size and date lines are hidden in LIST and GRID view.
     * Matches the "Show File Info in List" preference in Settings.
     */
    var showFileInfo: Boolean = true
    var showThumbnails: Boolean = true
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    /**
     * When true, file type icons are displayed with category-colour tinting
     * and album-art / APK icons are loaded for audio and APK files.
     * Matches the "File Type Icons" preference in Settings.
     */
    var showFileTypeIcons: Boolean = true
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    /**
     * When false, file extensions are stripped from display names.
     * Matches the "Show Extensions" preference in Settings.
     */
    var showExtensions: Boolean = true
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    /** Returns the display name for [item] honouring the [showExtensions] flag. */
    private fun displayName(item: FileItem): String {
        if (showExtensions || item.isDirectory) return item.name
        return item.name.substringBeforeLast('.').ifEmpty { item.name }
    }

    var listDensity: String = "normal"
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private fun rowMinHeightDp(): Int = when (listDensity) {
        "compact"     -> 48
        "comfortable" -> 80
        else          -> 64
    }

    var selectedPaths: Set<String> = emptySet()
        set(value) {
            val old = field
            field = value
            currentList.forEachIndexed { index, item ->
                val wasSelected = item.path in old
                val isSelected = item.path in value
                if (wasSelected != isSelected) notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }

    var lastAnimatedPosition = -1

    companion object {
        private const val VIEW_TYPE_LIST = 0
        private const val VIEW_TYPE_GRID = 1
        private const val VIEW_TYPE_COMPACT = 2
        const val PAYLOAD_SELECTION = "selection"
    }

    override fun getItemViewType(position: Int): Int = when (viewMode) {
        ViewMode.LIST -> VIEW_TYPE_LIST
        ViewMode.GRID -> VIEW_TYPE_GRID
        ViewMode.COMPACT -> VIEW_TYPE_COMPACT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_GRID -> GridViewHolder(ItemFileGridBinding.inflate(inflater, parent, false))
            VIEW_TYPE_COMPACT -> CompactViewHolder(ItemFileCompactBinding.inflate(inflater, parent, false))
            else -> ListViewHolder(ItemFileListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val isSelected = item.path in selectedPaths
        setAnimation(holder.itemView, position)
        when (holder) {
            is ListViewHolder -> holder.bind(item, isSelected)
            is GridViewHolder -> holder.bind(item, isSelected)
            is CompactViewHolder -> holder.bind(item, isSelected)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            val item = getItem(position)
            val isSelected = item.path in selectedPaths
            when (holder) {
                is ListViewHolder -> holder.updateSelection(isSelected)
                is GridViewHolder -> holder.updateSelection(isSelected)
                is CompactViewHolder -> holder.updateSelection(isSelected)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun setAnimation(view: View, position: Int) {
        if (position > lastAnimatedPosition) {
            val anim = AnimationUtils.loadAnimation(view.context, R.anim.item_appear)
            view.startAnimation(anim)
            lastAnimatedPosition = position
        }
    }

    // ─── List ViewHolder ─────────────────────────────────────────────────

    inner class ListViewHolder(private val binding: ItemFileListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener { onItemClick(getItem(adapterPosition)) }
            binding.root.setOnLongClickListener { onItemLongClick(getItem(adapterPosition)) }
            binding.btnMenu.setOnClickListener { onItemMenuClick(getItem(adapterPosition), it) }
        }

        fun bind(item: FileItem, isSelected: Boolean) {
            binding.tvName.text = displayName(item)
            binding.tvSize.text = item.formattedSize
            binding.tvDate.text = formatDate(item.lastModified)
            binding.tvTime.text = formatTime(item.lastModified)
            binding.tvSize.visibility = if (showFileInfo && !item.isDirectory) View.VISIBLE else View.GONE
            binding.tvDate.visibility = if (showFileInfo) View.VISIBLE else View.GONE
            binding.tvTime.visibility = if (showFileInfo) View.VISIBLE else View.GONE
            loadFileIcon(binding.root.context, item, binding.ivIcon, binding.iconContainer)
            updateSelection(isSelected)
            binding.ivBookmark.visibility = if (item.isBookmarked) View.VISIBLE else View.GONE
            binding.noteDot?.visibility = if (item.hasNote) View.VISIBLE else View.GONE

            val density = binding.root.context.resources.displayMetrics.density
            val minHeightPx = (rowMinHeightDp() * density).toInt()
            (binding.root.getChildAt(0) as? android.view.View)?.minimumHeight = minHeightPx
        }

        fun updateSelection(isSelected: Boolean) {
            binding.root.isActivated = isSelected
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivIcon.visibility = if (isSelected) View.GONE else View.VISIBLE
        }
    }

    // ─── Grid ViewHolder ─────────────────────────────────────────────────

    inner class GridViewHolder(private val binding: ItemFileGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener { onItemClick(getItem(adapterPosition)) }
            binding.root.setOnLongClickListener { onItemLongClick(getItem(adapterPosition)) }
            binding.btnMenu.setOnClickListener { onItemMenuClick(getItem(adapterPosition), it) }
        }

        fun bind(item: FileItem, isSelected: Boolean) {
            binding.tvName.text = displayName(item)
            binding.tvSize.text = item.formattedSize
            binding.tvSize.visibility = if (showFileInfo && !item.isDirectory) View.VISIBLE else View.GONE
            loadFileIcon(binding.root.context, item, binding.ivThumbnail, binding.thumbnailContainer)
            updateSelection(isSelected)
        }

        fun updateSelection(isSelected: Boolean) {
            binding.root.isActivated = isSelected
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    // ─── Compact ViewHolder ───────────────────────────────────────────────

    inner class CompactViewHolder(private val binding: ItemFileCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener { onItemClick(getItem(adapterPosition)) }
            binding.root.setOnLongClickListener { onItemLongClick(getItem(adapterPosition)) }
        }

        fun bind(item: FileItem, isSelected: Boolean) {
            binding.tvName.text = displayName(item)
            loadFileIcon(binding.root.context, item, binding.ivIcon, null)
            updateSelection(isSelected)
        }

        fun updateSelection(isSelected: Boolean) {
            binding.root.isActivated = isSelected
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    // ─── Icon & Thumbnail Loading ─────────────────────────────────────────

    /**
     * Central icon/thumbnail loader.
     *
     * Priority:
     *   1. If [showThumbnails] is true: load a real preview (image, video frame,
     *      audio album art, or APK package icon).
     *   2. Otherwise fall back to the static vector icon for the file type.
     *
     * If [showFileTypeIcons] is true the vector icon is tinted with the
     * category colour and the [container] (when provided) gets a matching
     * low-alpha rounded background — giving every file type a distinctive look.
     */
    private fun loadFileIcon(
        context: Context,
        item: FileItem,
        imageView: android.widget.ImageView,
        container: View?
    ) {
        val type = item.fileType

        // ── Always reset to a clean baseline first ────────────────────────
        // (1) Cancel any in-flight Glide request on this ImageView BEFORE
        //     doing anything else. Without this, a recycled ViewHolder whose
        //     previous album-art / thumbnail load is still async-pending will
        //     stamp that stale image onto whichever new item reuses the holder.
        Glide.with(context).clear(imageView)
        // (2) Reset padding and scaleType so recycled state never leaks into
        //     the next bind (thumbnail→icon or vice-versa).
        val defaultPadPx = (8 * context.resources.displayMetrics.density).toInt()
        imageView.setPadding(defaultPadPx, defaultPadPx, defaultPadPx, defaultPadPx)
        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        imageView.imageTintList = null

        // ── Container background (type-colour pill) ───────────────────────
        if (container != null) {
            if (showFileTypeIcons) {
                val categoryColor = ContextCompat.getColor(context, type.colorRes)
                container.background = buildColoredBackground(categoryColor, context)
            } else {
                container.setBackgroundResource(R.drawable.bg_icon_rounded)
            }
        }

        // ── Try to load a real preview when thumbnails are enabled ────────
        if (showThumbnails) {
            when (type) {
                FileType.FOLDER -> {
                    // A folder's lastModified() updates whenever files inside change.
                    // Using it as the Glide signature means the cached thumbnail is
                    // automatically invalidated on any add/remove/rename activity.
                    val firstImage = findFirstImageInFolder(item.file)
                    if (firstImage != null) {
                        imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.imageTintList = null
                        Glide.with(context)
                            .load(firstImage)
                            .apply(
                                RequestOptions()
                                    .placeholder(R.drawable.ic_folder)
                                    .error(R.drawable.ic_folder)
                                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                    .signature(com.bumptech.glide.signature.ObjectKey(item.file.lastModified()))
                                    .override(300, 300)
                                    .centerCrop()
                            )
                            .into(imageView)
                        return
                    }
                    // No image found — fall through to static folder icon
                }
                FileType.IMAGE -> {
                    imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    imageView.setPadding(0, 0, 0, 0)
                    imageView.imageTintList = null
                    Glide.with(context)
                        .load(item.file)
                        .apply(
                            RequestOptions()
                                .placeholder(R.drawable.ic_file_image)
                                .error(R.drawable.ic_file_image)
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .signature(com.bumptech.glide.signature.ObjectKey(item.file.lastModified()))
                                .override(300, 300)
                                .centerCrop()
                        )
                        .into(imageView)
                    return
                }
                FileType.VIDEO -> {
                    imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    imageView.setPadding(0, 0, 0, 0)
                    imageView.imageTintList = null
                    Glide.with(context)
                        .load(item.file)
                        .apply(
                            RequestOptions()
                                .placeholder(R.drawable.ic_file_video)
                                .error(R.drawable.ic_file_video)
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .signature(com.bumptech.glide.signature.ObjectKey(item.file.lastModified()))
                                .override(300, 300)
                                .centerCrop()
                                .frame(1_000_000L)
                        )
                        .into(imageView)
                    return
                }
                FileType.AUDIO -> {
                    // Tag the view with the file path so we can detect if the
                    // ViewHolder has been recycled before the IO work completes.
                    val expectedPath = item.path
                    imageView.tag = expectedPath
                    // MediaMetadataRetriever is blocking I/O — run off main thread.
                    CoroutineScope(Dispatchers.IO).launch {
                        val bitmap = extractEmbeddedAudioArt(expectedPath)
                        withContext(Dispatchers.Main) {
                            // Bail if this ViewHolder was recycled to a different item.
                            if (imageView.tag != expectedPath) return@withContext
                            if (bitmap != null) {
                                imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                imageView.setPadding(0, 0, 0, 0)
                                imageView.imageTintList = null
                                Glide.with(context)
                                    .load(bitmap)
                                    .apply(
                                        RequestOptions()
                                            .placeholder(R.drawable.ic_file_audio)
                                            .error(R.drawable.ic_file_audio)
                                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                            .signature(com.bumptech.glide.signature.ObjectKey(item.file.lastModified()))
                                            .override(300, 300)
                                            .centerCrop()
                                    )
                                    .into(imageView)
                            }
                            // If bitmap == null, the static icon was already set
                            // at the top of loadFileIcon — nothing more to do.
                        }
                    }
                    // Static icon is shown immediately as placeholder while IO runs.
                    // Fall through to the static icon block below.
                }
                FileType.APK -> {
                    val apkDrawable = extractApkIcon(context, item.path)
                    if (apkDrawable != null) {
                        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.imageTintList = null
                        imageView.setImageDrawable(apkDrawable)
                        return
                    }
                    // fall through to static icon if extraction fails
                }
                FileType.EBOOK -> {
                    // Extract the ePub cover on a background thread, write it to a
                    // persistent cache file keyed by path+lastModified, then load the
                    // file into Glide — the same pipeline Glide uses for IMAGE/VIDEO.
                    // Loading a raw Bitmap via Glide.load(bitmap) is unreliable because
                    // Glide keys on object identity, defeating disk-caching and causing
                    // re-extraction on every scroll.
                    val expectedPath = item.path
                    imageView.tag = expectedPath
                    CoroutineScope(Dispatchers.IO).launch {
                        val coverFile = getOrCreateEpubCoverCache(context, item)
                        withContext(Dispatchers.Main) {
                            if (imageView.tag != expectedPath) return@withContext
                            if (coverFile != null) {
                                imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                imageView.setPadding(0, 0, 0, 0)
                                imageView.imageTintList = null
                                Glide.with(context)
                                    .load(coverFile)
                                    .apply(
                                        RequestOptions()
                                            .placeholder(R.drawable.ic_file_epub)
                                            .error(R.drawable.ic_file_epub)
                                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                            .signature(com.bumptech.glide.signature.ObjectKey(item.file.lastModified()))
                                            .override(300, 300)
                                            .centerCrop()
                                    )
                                    .into(imageView)
                            }
                        }
                    }
                    // Static icon shown immediately while IO runs; fall through below.
                }
                else -> { /* fall through */ }
            }
        }

        // ── Static / fallback icon ────────────────────────────────────────
        // Do NOT call setPadding here — the XML layout already defines the
        // correct padding for each view type (6 dp for list, proportional for
        // grid). Overriding it programmatically was shrinking icons to nothing.
        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        imageView.setImageResource(getIconForType(type))

        // Apply category colour tint to the vector icon when showFileTypeIcons=true
        if (showFileTypeIcons) {
            val color = ContextCompat.getColor(context, type.colorRes)
            imageView.imageTintList = ColorStateList.valueOf(color)
        } else {
            imageView.imageTintList = null
        }
    }

    /**
     * Builds a rounded rectangle drawable tinted with [color] at ~15 % opacity —
     * used as the icon container background when file-type icons are enabled.
     */
    private fun buildColoredBackground(color: Int, context: Context): GradientDrawable {
        val alphaBg = (color and 0x00FFFFFF) or 0x26000000   // 15 % alpha
        val radiusPx = 10f * context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(alphaBg)
            cornerRadius = radiusPx
        }
    }

    /**
     * Returns the first image file found directly inside [folder] (non-recursive,
     * top-level only, sorted by name for a stable result).
     * Returns null if the folder is empty or contains no images.
     */
    private fun findFirstImageInFolder(folder: java.io.File): java.io.File? {
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        return folder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in imageExts }
            ?.minByOrNull { it.name.lowercase() }
    }

    /**
     * Returns a File containing the ePub's cover image, creating it from the ePub
     * the first time and re-using the cached copy on subsequent calls.
     *
     * Cache key = hashCode(path) + "_" + lastModified, so the file is automatically
     * invalidated if the ePub is replaced.  Cache lives in the app's cacheDir and
     * is cleared by the system when storage runs low.
     *
     * Returns null when no cover is found or extraction fails.
     */
    private suspend fun getOrCreateEpubCoverCache(context: Context, item: FileItem): java.io.File? {
        val cacheKey = "${item.path.hashCode()}_${item.file.lastModified()}"
        // Prefix "v3" — v2 files could contain raw SVG bytes written as .jpg (Career
        // Technology pattern) and must never be reused; bumping the prefix forces a clean
        // re-extraction for every ePub on first launch after this update.
        val cacheFile = java.io.File(context.cacheDir, "epub_cvr3_$cacheKey.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile

        // ── Encrypted ePub (.9genc) path ─────────────────────────────────────
        // .9genc files are AES-256-GCM encrypted and cannot be opened as a plain
        // ZipFile. For device-key (9GEK) files the session key is stored in the
        // Android Keystore and can be unwrapped without a user-supplied password,
        // so we decrypt in-memory and extract the cover from the resulting bytes.
        // Password-based (9GEF) files have no automatic decryption path; we return
        // null so the caller falls back to the static ePub icon.
        if (item.file.extension.equals(EncryptionUtils.ENCRYPTED_EXT.trimStart('.'), ignoreCase = true)) {
            val epubBytes: ByteArray = when (EncryptionUtils.detectFormat(item.file)) {
                EncryptionUtils.EncryptionFormat.DEVICE_KEY -> {
                    EncryptionUtils.decryptDeviceToBytes(
                        source              = item.file,
                        sessionKeyDecryptor = { DeviceKeyManager.decryptSessionKey(it) }
                    ) ?: return null
                }
                // Password-based format: cannot decrypt without user input at thumbnail time
                EncryptionUtils.EncryptionFormat.PASSWORD_BASED -> return null
                null -> return null
            }
            val entries = loadZipEntriesFromBytes(epubBytes)
            if (entries.isEmpty()) return null

            // Path 1 (encrypted): extract cover image bytes from the in-memory ZIP entries
            val coverBytes = extractEpubCoverBytesFromEntries(entries)
            if (coverBytes != null) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, opts)
                if (opts.outWidth > 0) {
                    return try { cacheFile.writeBytes(coverBytes); cacheFile } catch (_: Exception) { null }
                }
                val svgBitmap = renderBytesAsSvg(coverBytes)
                if (svgBitmap != null) {
                    return try {
                        cacheFile.outputStream().use { out ->
                            svgBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                        }
                        svgBitmap.recycle()
                        cacheFile
                    } catch (_: Exception) { null }
                }
            }

            // Path 2 (encrypted): XHTML cover page fallback
            val bitmap = renderEpubXhtmlSvgCoverFromEntries(entries) ?: return null
            return try {
                cacheFile.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                }
                bitmap.recycle()
                cacheFile
            } catch (_: Exception) { null }
        }

        // ── Plain ePub path ───────────────────────────────────────────────────
        // Path 1: ePub contains a cover image.
        // Strategy: extract raw bytes, then verify they are a decodable raster image.
        // If BitmapFactory cannot decode them (e.g. the cover is a standalone SVG file),
        // attempt to render via AndroidSVG before falling through to the XHTML cover path.
        // This handles all image types uniformly without needing per-format special-casing.
        val bytes = extractEpubCoverBytes(item.path)
        if (bytes != null) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0) {
                // Valid raster image — write directly so Glide can decode it
                return try { cacheFile.writeBytes(bytes); cacheFile } catch (_: Exception) { null }
            }
            // Not a raster (e.g. standalone SVG) — try rendering with AndroidSVG
            val svgBitmap = renderBytesAsSvg(bytes)
            if (svgBitmap != null) {
                return try {
                    cacheFile.outputStream().use { out ->
                        svgBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    svgBitmap.recycle()
                    cacheFile
                } catch (_: Exception) { null }
            }
            // SVG render also failed — fall through to the XHTML cover page path below
        }

        // Path 2: ePub uses an XHTML cover page (inline SVG or <img> reference).
        // Render to a Bitmap and save as JPEG so Glide can load it normally.
        val bitmap = renderEpubXhtmlSvgCover(item.path) ?: return null
        return try {
            cacheFile.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            cacheFile
        } catch (_: Exception) { null }
    }

    /**
     * Reads all non-directory entries from a ZIP held entirely in [bytes] into a
     * [Map] keyed by entry name.  Mirrors the logic in EpubReaderFragment so that
     * both the reader and the thumbnail extractor share the same in-memory strategy.
     */
    private fun loadZipEntriesFromBytes(bytes: ByteArray): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) map[entry.name] = zis.readBytes()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) { /* return whatever was read so far */ }
        return map
    }

    /**
     * Extracts the raw cover image bytes from an in-memory ePub entry map.
     *
     * Mirrors [extractEpubCoverBytes] but reads from [entries] (pre-decrypted
     * ZIP contents) rather than from a [java.util.zip.ZipFile] — used for
     * `.9genc` encrypted ePubs whose bytes are decrypted in-memory first.
     *
     * The same four strategies (A–D) are applied in the same priority order.
     */
    private fun extractEpubCoverBytesFromEntries(entries: Map<String, ByteArray>): ByteArray? {
        return try {
            // ── Step 1: OPF path ─────────────────────────────────────────────────
            val containerXml = entries["META-INF/container.xml"]
                ?.toString(Charsets.UTF_8) ?: return null
            val opfPath = Regex("""full-path=["']([^"']+)["']""")
                .find(containerXml)?.groupValues?.get(1) ?: return null
            val opfDir = opfPath.substringBeforeLast("/", "")

            // ── Step 2: cover href from OPF ──────────────────────────────────────
            val opfXml = entries[opfPath]?.toString(Charsets.UTF_8) ?: return null

            fun attr(tag: String, name: String): String? =
                Regex("""\b${Regex.escape(name)}=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)

            val manifestXml = Regex(
                """<manifest\b[^>]*>(.*?)</manifest>""", RegexOption.DOT_MATCHES_ALL
            ).find(opfXml)?.groupValues?.get(1) ?: opfXml

            val items = Regex("""<item\b[^>]+/?>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(manifestXml).toList()

            // Strategy A: epub3 — properties="cover-image"
            var coverHref = items.firstOrNull { item ->
                attr(item.value, "properties")?.contains("cover-image") == true
            }?.let { attr(it.value, "href") }

            // Strategy B: epub2 — <meta name="cover" content="id"/>
            if (coverHref == null) {
                val metaId =
                    Regex("""<meta\b[^>]*\bname="cover"[^>]*\bcontent="([^"]+)"""")
                        .find(opfXml)?.groupValues?.get(1)
                    ?: Regex("""<meta\b[^>]*\bcontent="([^"]+)"[^>]*\bname="cover"""")
                        .find(opfXml)?.groupValues?.get(1)
                if (metaId != null) {
                    val targetItem = items.firstOrNull { attr(it.value, "id") == metaId }
                    val mime = targetItem?.let { attr(it.value, "media-type") } ?: ""
                    if (mime.startsWith("image/"))
                        coverHref = targetItem?.let { attr(it.value, "href") }
                }
            }

            // Strategy C: any image item whose filename starts with "cover" or "front"
            if (coverHref == null)
                coverHref = items.firstOrNull { item ->
                    val mime = attr(item.value, "media-type") ?: return@firstOrNull false
                    if (!mime.startsWith("image/")) return@firstOrNull false
                    val fn = (attr(item.value, "href") ?: "").substringAfterLast("/").lowercase()
                    fn.startsWith("cover") || fn.startsWith("front")
                }?.let { attr(it.value, "href") }

            // Strategy D: heuristic — scan all entries for an image with "cover" in the path
            if (coverHref == null) {
                val imgExts = setOf("jpg", "jpeg", "png", "webp")
                val entryKey = entries.keys.firstOrNull { name ->
                    !name.endsWith('/') && name.lowercase().let { n ->
                        n.contains("cover") && n.substringAfterLast('.') in imgExts
                    }
                }
                if (entryKey != null) return entries[entryKey]
            }

            if (coverHref == null) return null

            // ── Step 3: read image bytes ──────────────────────────────────────────
            val fullHref = resolveEpubHref(opfDir, coverHref)
            entries[fullHref] ?: entries[coverHref]
        } catch (_: Exception) { null }
    }

    /**
     * Handles encrypted ePubs whose OPF meta-cover points to an XHTML page rather
     * than a raster image — mirrors [renderEpubXhtmlSvgCover] but reads from an
     * in-memory [entries] map instead of a [java.util.zip.ZipFile].
     */
    private fun renderEpubXhtmlSvgCoverFromEntries(entries: Map<String, ByteArray>): android.graphics.Bitmap? {
        return try {
            // ── Locate OPF ───────────────────────────────────────────────────────
            val containerXml = entries["META-INF/container.xml"]
                ?.toString(Charsets.UTF_8) ?: return null
            val opfPath = Regex("""full-path=["']([^"']+)["']""")
                .find(containerXml)?.groupValues?.get(1) ?: return null
            val opfDir  = opfPath.substringBeforeLast("/", "")
            val opfXml  = entries[opfPath]?.toString(Charsets.UTF_8) ?: return null

            fun attr(tag: String, name: String): String? =
                Regex("""\b${Regex.escape(name)}=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)

            val items = Regex("""<item\b[^>]+/?>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(opfXml).toList()

            // ── Find the XHTML cover item ─────────────────────────────────────────
            val metaId = Regex("""<meta\b[^>]*\bname=["']cover["'][^>]*\bcontent=["']([^"']+)["']""")
                .find(opfXml)?.groupValues?.get(1)
                ?: Regex("""<meta\b[^>]*\bcontent=["']([^"']+)["'][^>]*\bname=["']cover["']""")
                    .find(opfXml)?.groupValues?.get(1)

            val xhtmlHref: String = run {
                var href: String? = null
                if (metaId != null) {
                    val target = items.firstOrNull { attr(it.value, "id") == metaId }
                    val mime   = target?.let { attr(it.value, "media-type") } ?: ""
                    if (mime.contains("xhtml") || mime.contains("html"))
                        href = target?.let { attr(it.value, "href") }
                }
                if (href == null) {
                    href = items.firstOrNull { item ->
                        val mime = attr(item.value, "media-type") ?: ""
                        val h    = attr(item.value, "href") ?: ""
                        (mime.contains("xhtml") || mime.contains("html")) &&
                            h.substringAfterLast("/").lowercase().startsWith("cover")
                    }?.let { attr(it.value, "href") }
                }
                href
            } ?: return null

            val fullXhtmlHref = resolveEpubHref(opfDir, xhtmlHref)
            val xhtmlContent  = (entries[fullXhtmlHref] ?: entries[xhtmlHref])
                ?.toString(Charsets.UTF_8) ?: return null

            // ── Sub-case A: inline SVG ────────────────────────────────────────────
            val svgBlock = Regex("""<svg\b.*?</svg>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(xhtmlContent)?.value

            if (svgBlock != null) {
                return try {
                    val svg    = com.caverock.androidsvg.SVG.getFromString(svgBlock)
                    val vb     = svg.documentViewBox
                    val aspect = if (vb != null && vb.height() > 0) vb.width() / vb.height() else 2f / 3f
                    val bmpH   = 900
                    val bmpW   = (bmpH * aspect).toInt().coerceAtLeast(1)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    svg.renderToCanvas(canvas)
                    bitmap
                } catch (_: Exception) { null }
            }

            // ── Sub-case B: <img src="…"> reference ──────────────────────────────
            val imgSrc  = Regex("""<img\b[^>]*\bsrc="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(xhtmlContent)?.groupValues?.get(1) ?: return null
            val imgHref = resolveEpubHref(opfDir, imgSrc)
            val imgBytes = entries[imgHref] ?: entries[imgSrc] ?: return null
            android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                ?: renderBytesAsSvg(imgBytes)
        } catch (_: Exception) { null }
    }

    /**
     * Extracts the raw cover image bytes from an ePub ZIP.
     *
     * Root cause of the previous "null bitmap" bug: BitmapFactory.decodeStream()
     * calls available() on the underlying InflaterInputStream (ZipFile entry), which
     * returns 0.  BitmapFactory then sets its internal mark limit to 0, the read
     * overflows the mark, and decodeStream() silently returns null.
     * Fix: read all bytes first with readBytes(), then use decodeByteArray() in the
     * caller — this function now returns the raw bytes so the caller can do that.
     *
     * Four strategies are tried in priority order:
     *   A. epub3  — <item properties="cover-image" href="..."/>
     *   B. epub2  — <meta name="cover" content="id"/> + item by id
     *   C. name   — any image item whose filename starts with "cover" or "front"
     *   D. scan   — first ZIP entry whose path contains "cover" and is an image
     */
    private fun extractEpubCoverBytes(path: String): ByteArray? = try {
        java.util.zip.ZipFile(path).use { zip ->

            // ── Step 1: OPF path ─────────────────────────────────────────
            val containerXml = zip.getEntry("META-INF/container.xml")
                ?.let { zip.getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
                ?: return null
            // Accept both double-quoted (full-path="…") and single-quoted (full-path='…')
            // attributes — both are valid XML and both appear in real-world EPUBs.
            val opfPath = Regex("""full-path=["']([^"']+)["']""")
                .find(containerXml)?.groupValues?.get(1) ?: return null
            val opfDir = opfPath.substringBeforeLast("/", "")

            // ── Step 2: cover href from OPF ──────────────────────────────
            val opfXml = zip.getEntry(opfPath)
                ?.let { zip.getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
                ?: return null

            /** Extract one named attribute value from an already-isolated tag string.
             *  Accepts both single-quoted and double-quoted attribute values. */
            fun attr(tag: String, name: String): String? =
                Regex("""\b${Regex.escape(name)}=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)

            val manifestXml = Regex(
                """<manifest\b[^>]*>(.*?)</manifest>""", RegexOption.DOT_MATCHES_ALL
            ).find(opfXml)?.groupValues?.get(1) ?: opfXml

            val items = Regex("""<item\b[^>]+/?>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(manifestXml).toList()

            // Strategy A: epub3 — properties="cover-image" (any attribute order)
            var coverHref = items.firstOrNull { item ->
                attr(item.value, "properties")?.contains("cover-image") == true
            }?.let { attr(it.value, "href") }

            // Strategy B: epub2 — <meta name="cover" content="id"/> (both attribute orders)
            // IMPORTANT: only accept the item if its media-type is image/*. Many EPUBs
            // (including those produced by ebooklib) incorrectly point the meta-cover at
            // an application/xhtml+xml cover-page rather than the image itself. Returning
            // XHTML bytes here causes Glide to silently fail — Strategy E handles those.
            if (coverHref == null) {
                val metaId =
                    Regex("""<meta\b[^>]*\bname="cover"[^>]*\bcontent="([^"]+)"""")
                        .find(opfXml)?.groupValues?.get(1)
                    ?: Regex("""<meta\b[^>]*\bcontent="([^"]+)"[^>]*\bname="cover"""")
                        .find(opfXml)?.groupValues?.get(1)
                if (metaId != null) {
                    val targetItem = items.firstOrNull { attr(it.value, "id") == metaId }
                    val mime = targetItem?.let { attr(it.value, "media-type") } ?: ""
                    if (mime.startsWith("image/"))
                        coverHref = targetItem?.let { attr(it.value, "href") }
                    // else: points to XHTML cover page — handled by renderEpubXhtmlSvgCover()
                }
            }

            // Strategy C: any image item whose filename starts with "cover" or "front"
            if (coverHref == null)
                coverHref = items.firstOrNull { item ->
                    val mime = attr(item.value, "media-type") ?: return@firstOrNull false
                    if (!mime.startsWith("image/")) return@firstOrNull false
                    val fn = (attr(item.value, "href") ?: "").substringAfterLast("/").lowercase()
                    fn.startsWith("cover") || fn.startsWith("front")
                }?.let { attr(it.value, "href") }

            // Strategy D: heuristic — scan all ZIP entries for an image with "cover" in the path
            if (coverHref == null) {
                val imgExts = setOf("jpg", "jpeg", "png", "webp")
                val entry = zip.entries().asSequence().firstOrNull { e ->
                    !e.isDirectory && e.name.lowercase().let { n ->
                        n.contains("cover") && n.substringAfterLast('.') in imgExts
                    }
                }
                if (entry != null) return zip.getInputStream(entry).use { it.readBytes() }
            }

            coverHref ?: return null

            // ── Step 3: read image bytes ──────────────────────────────────
            val fullHref = resolveEpubHref(opfDir, coverHref)
            val entry = zip.getEntry(fullHref) ?: zip.getEntry(coverHref) ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    } catch (_: Exception) { null }

    /**
     * Renders raw SVG bytes to a [Bitmap] using AndroidSVG.
     *
     * Called whenever a cover image extracted from an EPUB cannot be decoded as a
     * raster image by [android.graphics.BitmapFactory] — most commonly because the
     * EPUB stores its cover as a standalone SVG file rather than JPEG/PNG/WebP.
     * By centralising SVG-to-bitmap conversion here both [getOrCreateEpubCoverCache]
     * and [renderEpubXhtmlSvgCover] (sub-case B) can delegate to it without
     * duplicating the AndroidSVG boilerplate.
     *
     * Returns null if [bytes] cannot be parsed as valid SVG or if rendering fails.
     */
    private fun renderBytesAsSvg(bytes: ByteArray): android.graphics.Bitmap? = try {
        val svg    = com.caverock.androidsvg.SVG.getFromString(bytes.toString(Charsets.UTF_8))
        val vb     = svg.documentViewBox
        val aspect = if (vb != null && vb.height() > 0) vb.width() / vb.height() else 2f / 3f
        val bmpH   = 900
        val bmpW   = (bmpH * aspect).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(android.graphics.Canvas(bitmap))
        bitmap
    } catch (_: Exception) { null }

    /**
     * Resolves [href] relative to [baseDir] (the OPF's containing directory),
     * collapsing any ".." path segments so the result can be passed directly
     * to ZipFile.getEntry().
     */
    private fun resolveEpubHref(baseDir: String, href: String): String {
        if (href.startsWith("/")) return href.trimStart('/')
        if (baseDir.isEmpty()) return href
        val parts = "$baseDir/$href".split("/")
        val resolved = mutableListOf<String>()
        for (part in parts) when (part) {
            "", "." -> {}
            ".."    -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            else    -> resolved.add(part)
        }
        return resolved.joinToString("/")
    }

    /**
     * Handles EPUBs whose OPF meta-cover points to an XHTML page rather than a raster
     * image — a pattern used by ebooklib and several other generators.
     *
     * Two sub-cases are supported:
     *
     *   A. Inline SVG — the XHTML body contains a <svg …>…</svg> block.
     *      The SVG is extracted, rendered to a 600 × 900 Bitmap via AndroidSVG,
     *      and returned. The cover's declared viewBox is preserved; if none is
     *      present the canvas is left at the default coordinate system.
     *
     *   B. <img> reference — the XHTML contains an <img src="…"> that points to
     *      a raster image inside the ZIP. The image bytes are decoded to a Bitmap
     *      and returned directly (no SVG library needed).
     *
     * Returns null if the EPUB has no XHTML cover page, or if neither SVG nor
     * <img> content can be found / decoded.
     */
    private fun renderEpubXhtmlSvgCover(path: String): android.graphics.Bitmap? = try {
        java.util.zip.ZipFile(path).use { zip ->

            // ── Locate OPF ──────────────────────────────────────────────────
            val containerXml = zip.getEntry("META-INF/container.xml")
                ?.let { zip.getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
                ?: return null
            val opfPath = Regex("""full-path=["']([^"']+)["']""")
                .find(containerXml)?.groupValues?.get(1) ?: return null
            val opfDir  = opfPath.substringBeforeLast("/", "")
            val opfXml  = zip.getEntry(opfPath)
                ?.let { zip.getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
                ?: return null

            fun attr(tag: String, name: String): String? =
                Regex("""\b${Regex.escape(name)}=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)

            val items = Regex("""<item\b[^>]+/?>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(opfXml).toList()

            // ── Find the XHTML cover item ────────────────────────────────────
            // Try epub2 meta-cover first, then epub3 properties="cover-image" on XHTML
            val metaId = Regex("""<meta\b[^>]*\bname=["']cover["'][^>]*\bcontent=["']([^"']+)["']""")
                .find(opfXml)?.groupValues?.get(1)
                ?: Regex("""<meta\b[^>]*\bcontent=["']([^"']+)["'][^>]*\bname=["']cover["']""")
                    .find(opfXml)?.groupValues?.get(1)

            // Resolve the XHTML cover href using two strategies in priority order:
            //   1. epub2: follow the meta-cover id to its manifest item (if it's XHTML)
            //   2. epub3 heuristic: any XHTML manifest item whose filename starts with "cover"
            //
            // Strategy 2 always runs as fallback — even when metaId is set but resolves to
            // nothing (e.g. the meta content="cover-image" but no item has id="cover-image").
            val xhtmlHref: String = run {
                var href: String? = null
                if (metaId != null) {
                    val target = items.firstOrNull { attr(it.value, "id") == metaId }
                    val mime   = target?.let { attr(it.value, "media-type") } ?: ""
                    if (mime.contains("xhtml") || mime.contains("html"))
                        href = target?.let { attr(it.value, "href") }
                }
                if (href == null) {
                    href = items.firstOrNull { item ->
                        val mime = attr(item.value, "media-type") ?: ""
                        val h    = attr(item.value, "href") ?: ""
                        (mime.contains("xhtml") || mime.contains("html")) &&
                            h.substringAfterLast("/").lowercase().startsWith("cover")
                    }?.let { attr(it.value, "href") }
                }
                href
            } ?: return null

            val fullXhtmlHref = resolveEpubHref(opfDir, xhtmlHref)
            val xhtmlEntry    = zip.getEntry(fullXhtmlHref) ?: zip.getEntry(xhtmlHref) ?: return null
            val xhtmlContent  = zip.getInputStream(xhtmlEntry).use { it.readBytes().toString(Charsets.UTF_8) }

            // ── Sub-case A: inline SVG ───────────────────────────────────────
            val svgBlock = Regex("""<svg\b.*?</svg>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(xhtmlContent)?.value

            if (svgBlock != null) {
                return try {
                    val svg    = com.caverock.androidsvg.SVG.getFromString(svgBlock)
                    val vb     = svg.documentViewBox
                    val aspect = if (vb != null && vb.height() > 0) vb.width() / vb.height() else 2f / 3f
                    val bmpH   = 900
                    val bmpW   = (bmpH * aspect).toInt().coerceAtLeast(1)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    svg.renderToCanvas(canvas)
                    bitmap
                } catch (_: Exception) { null }
            }

            // ── Sub-case B: <img src="…"> reference ─────────────────────────
            val imgSrc = Regex("""<img\b[^>]*\bsrc="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(xhtmlContent)?.groupValues?.get(1) ?: return null
            val imgHref  = resolveEpubHref(opfDir, imgSrc)
            val imgEntry = zip.getEntry(imgHref) ?: zip.getEntry(imgSrc) ?: return null
            val imgBytes = zip.getInputStream(imgEntry).use { it.readBytes() }
            // Try raster decode first; if the referenced image is an SVG (not a raster),
            // fall back to AndroidSVG so any cover format is handled uniformly.
            android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                ?: renderBytesAsSvg(imgBytes)
        }
    } catch (_: Exception) { null }

    /**
     * Extracts the embedded album-art bitmap directly from the audio file's
     * ID3 / Vorbis tags using [MediaMetadataRetriever].
     *
     * Unlike the MediaStore approach (which groups files by album ID and therefore
     * returns the same art for every track in the same album), this reads the raw
     * picture bytes embedded in each individual file, so every track gets its own
     * unique artwork.
     *
     * Returns null if the file has no embedded art or if extraction fails.
     */
    private fun extractEmbeddedAudioArt(path: String): android.graphics.Bitmap? = try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(path)
        val bytes = retriever.embeddedPicture
        retriever.release()
        if (bytes != null && bytes.isNotEmpty())
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        else null
    } catch (e: Exception) { null }

    /**
     * Uses [android.content.pm.PackageManager] to extract the launcher icon from
     * an uninstalled APK at [apkPath].  Returns null on failure so the caller
     * can fall back to the static APK vector.
     */
    private fun extractApkIcon(context: Context, apkPath: String): android.graphics.drawable.Drawable? = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apkPath, 0)
        info?.applicationInfo?.let { appInfo ->
            appInfo.sourceDir       = apkPath
            appInfo.publicSourceDir = apkPath
            appInfo.loadIcon(pm)
        }
    } catch (e: Exception) { null }

    private fun getIconForType(type: FileType): Int = when (type) {
        FileType.FOLDER       -> R.drawable.ic_folder
        FileType.IMAGE        -> R.drawable.ic_file_image
        FileType.VIDEO        -> R.drawable.ic_file_video
        FileType.AUDIO        -> R.drawable.ic_file_audio
        FileType.DOCUMENT     -> R.drawable.ic_file_document
        FileType.PDF          -> R.drawable.ic_file_pdf
        FileType.ARCHIVE      -> R.drawable.ic_file_archive
        FileType.APK          -> R.drawable.ic_file_apk
        FileType.EBOOK        -> R.drawable.ic_file_epub
        FileType.CODE         -> R.drawable.ic_file_code
        FileType.SPREADSHEET  -> R.drawable.ic_file_spreadsheet
        FileType.PRESENTATION -> R.drawable.ic_file_presentation
        FileType.UNKNOWN      -> R.drawable.ic_file_generic
    }

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun formatDate(timestamp: Long) = dateFormat.format(Date(timestamp))
    private fun formatTime(timestamp: Long) = timeFormat.format(Date(timestamp))
}

// ─── DiffUtil Callback ────────────────────────────────────────────────────────

class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
    override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem.path == newItem.path
    override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem == newItem
}
