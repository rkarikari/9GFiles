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
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.data.model.ViewMode
import com.radiozport.ninegfiles.databinding.ItemFileGridBinding
import com.radiozport.ninegfiles.databinding.ItemFileListBinding
import com.radiozport.ninegfiles.databinding.ItemFileCompactBinding
import java.text.SimpleDateFormat
import java.util.*

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
            binding.tvSize.visibility = if (showFileInfo && !item.isDirectory) View.VISIBLE else View.GONE
            binding.tvDate.visibility = if (showFileInfo) View.VISIBLE else View.GONE
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
        // RecyclerView recycles ViewHolders, so a holder that previously showed
        // a zero-padded thumbnail would carry that state into the next bind
        // (e.g. a folder icon), making icons render at inconsistent sizes.
        // Resetting here guarantees every bind starts from the same state.
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
                    val albumArtUri = resolveAudioAlbumArt(context, item.path)
                    if (albumArtUri != null) {
                        imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.imageTintList = null
                        Glide.with(context)
                            .load(albumArtUri)
                            .apply(
                                RequestOptions()
                                    .placeholder(R.drawable.ic_file_audio)
                                    .error(R.drawable.ic_file_audio)
                                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                    .override(300, 300)
                                    .centerCrop()
                            )
                            .into(imageView)
                        return
                    }
                    // fall through to static icon if no album art found
                }
                FileType.APK -> {
                    val apkDrawable = extractApkIcon(context, item.path)
                    if (apkDrawable != null) {
                        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        imageView.setPadding(0, 0, 0, 0)
                        imageView.imageTintList = null
                        Glide.with(context).clear(imageView)
                        imageView.setImageDrawable(apkDrawable)
                        return
                    }
                    // fall through to static icon if extraction fails
                }
                else -> { /* fall through */ }
            }
        }

        // ── Static / fallback icon ────────────────────────────────────────
        // Do NOT call setPadding here — the XML layout already defines the
        // correct padding for each view type (6 dp for list, proportional for
        // grid). Overriding it programmatically was shrinking icons to nothing.
        Glide.with(context).clear(imageView)
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
     * Queries MediaStore for the album-art URI of the given audio file path.
     * Returns null when not indexed or permissions are absent.
     */
    private fun resolveAudioAlbumArt(context: Context, path: String): Uri? = try {
        val projection = arrayOf(MediaStore.Audio.Media.ALBUM_ID)
        val selection  = "${MediaStore.Audio.Media.DATA} = ?"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf(path), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val albumId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                )
                android.content.ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
            } else null
        }
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
        FileType.CODE         -> R.drawable.ic_file_code
        FileType.SPREADSHEET  -> R.drawable.ic_file_spreadsheet
        FileType.PRESENTATION -> R.drawable.ic_file_presentation
        FileType.UNKNOWN      -> R.drawable.ic_file_generic
    }

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private fun formatDate(timestamp: Long) = dateFormat.format(Date(timestamp))
}

// ─── DiffUtil Callback ────────────────────────────────────────────────────────

class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
    override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem.path == newItem.path
    override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem == newItem
}
