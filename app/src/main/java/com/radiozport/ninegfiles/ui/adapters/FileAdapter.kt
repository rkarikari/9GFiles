package com.radiozport.ninegfiles.ui.adapters

import android.annotation.SuppressLint
import android.content.Context
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
            // Efficiently notify only changed items
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
            binding.tvName.text = item.name
            binding.tvSize.text = item.formattedSize
            binding.tvDate.text = formatDate(item.lastModified)
            loadThumbnail(binding.root.context, item, binding.ivIcon)
            updateSelection(isSelected)
            binding.ivBookmark.visibility = if (item.isBookmarked) View.VISIBLE else View.GONE

            // Apply density: adjust the ConstraintLayout min-height inside the CardView
            val density = binding.root.context.resources.displayMetrics.density
            val minHeightPx = (rowMinHeightDp() * density).toInt()
            // The ConstraintLayout is the direct child of the CardView
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
            binding.tvName.text = item.name
            binding.tvSize.text = item.formattedSize
            loadThumbnail(binding.root.context, item, binding.ivThumbnail)
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
            binding.tvName.text = item.name
            loadThumbnail(binding.root.context, item, binding.ivIcon)
            updateSelection(isSelected)
        }

        fun updateSelection(isSelected: Boolean) {
            binding.root.isActivated = isSelected
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    // ─── Thumbnail Loading ────────────────────────────────────────────────

    private fun loadThumbnail(context: Context, item: FileItem, imageView: android.widget.ImageView) {
        when (item.fileType) {
            FileType.IMAGE -> {
                Glide.with(context)
                    .load(item.file)
                    .apply(
                        RequestOptions()
                            .placeholder(R.drawable.ic_file_image)
                            .error(R.drawable.ic_file_image)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(300, 300)
                            .centerCrop()
                    )
                    .into(imageView)
            }
            FileType.VIDEO -> {
                Glide.with(context)
                    .load(item.file)
                    .apply(
                        RequestOptions()
                            .placeholder(R.drawable.ic_file_video)
                            .error(R.drawable.ic_file_video)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(300, 300)
                            .centerCrop()
                            .frame(1_000_000L) // 1 second frame
                    )
                    .into(imageView)
            }
            FileType.APK -> {
                Glide.with(context)
                    .load(item.file)
                    .apply(RequestOptions().placeholder(R.drawable.ic_file_apk).error(R.drawable.ic_file_apk))
                    .into(imageView)
            }
            else -> {
                imageView.setImageResource(getIconForType(item.fileType))
                Glide.with(context).clear(imageView)
            }
        }
    }

    private fun getIconForType(type: FileType): Int = when (type) {
        FileType.FOLDER -> R.drawable.ic_folder
        FileType.IMAGE -> R.drawable.ic_file_image
        FileType.VIDEO -> R.drawable.ic_file_video
        FileType.AUDIO -> R.drawable.ic_file_audio
        FileType.DOCUMENT -> R.drawable.ic_file_document
        FileType.PDF -> R.drawable.ic_file_pdf
        FileType.ARCHIVE -> R.drawable.ic_file_archive
        FileType.APK -> R.drawable.ic_file_apk
        FileType.CODE -> R.drawable.ic_file_code
        FileType.SPREADSHEET -> R.drawable.ic_file_spreadsheet
        FileType.PRESENTATION -> R.drawable.ic_file_presentation
        FileType.UNKNOWN -> R.drawable.ic_file_generic
    }

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private fun formatDate(timestamp: Long) = dateFormat.format(Date(timestamp))
}

// ─── DiffUtil Callback ────────────────────────────────────────────────────────

class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
    override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem.path == newItem.path
    override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem) = oldItem == newItem
}
