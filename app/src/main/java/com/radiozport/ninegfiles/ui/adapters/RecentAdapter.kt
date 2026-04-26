package com.radiozport.ninegfiles.ui.adapters

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.db.RecentFileEntity
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.databinding.ItemRecentFileBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RecentAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit = {}
) : ListAdapter<RecentFileEntity, RecentAdapter.ViewHolder>(RecentDiffCallback()) {

    inner class ViewHolder(private val binding: ItemRecentFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecentFileEntity) {
            binding.tvName.text = item.name

            val parentName = File(item.path).parentFile?.name ?: ""
            binding.tvPath.text = "/$parentName"

            binding.tvDate.text = formatDate(item.accessedAt)

            val (iconRes, colorRes) = iconAndColorForMime(item.mimeType)
            binding.ivIcon.setImageResource(iconRes)
            val color = ContextCompat.getColor(binding.root.context, colorRes)

            // Icon tint — always full color so file type stays recognisable
            binding.ivIcon.imageTintList = ColorStateList.valueOf(color)

            val isNight = (binding.root.context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            binding.iconContainer.backgroundTintList = if (isNight) {
                // Dark mode: solid near-black, no color tint
                ColorStateList.valueOf(Color.parseColor("#1E1E1E"))
            } else {
                // Light mode: keep original tinted look
                ColorStateList.valueOf(
                    Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
                )
            }

            binding.root.setOnClickListener { onClick(item.path) }
            binding.root.setOnLongClickListener { onLongClick(item.path); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemRecentFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class RecentDiffCallback : DiffUtil.ItemCallback<RecentFileEntity>() {
        override fun areItemsTheSame(a: RecentFileEntity, b: RecentFileEntity) = a.path == b.path
        override fun areContentsTheSame(a: RecentFileEntity, b: RecentFileEntity) = a == b
    }

    private fun formatDate(accessedAt: Long): String {
        val now = Calendar.getInstance()
        val accessed = Calendar.getInstance().apply { timeInMillis = accessedAt }
        val sameYear = now.get(Calendar.YEAR) == accessed.get(Calendar.YEAR)
        val hm = String.format("%02d:%02d",
            accessed.get(Calendar.HOUR_OF_DAY), accessed.get(Calendar.MINUTE))
        return when {
            sameYear && now.get(Calendar.DAY_OF_YEAR) == accessed.get(Calendar.DAY_OF_YEAR) ->
                "Today, $hm"
            sameYear && now.get(Calendar.DAY_OF_YEAR) - accessed.get(Calendar.DAY_OF_YEAR) == 1 ->
                "Yesterday"
            else -> {
                val day = SimpleDateFormat("EEE", Locale.getDefault()).format(accessed.time)
                "$day, $hm"
            }
        }
    }

    private fun iconAndColorForMime(mime: String): Pair<Int, Int> = when {
        mime.startsWith("image/")       -> R.drawable.ic_file_image    to R.color.category_image
        mime.startsWith("video/")       -> R.drawable.ic_file_video    to R.color.category_video
        mime.startsWith("audio/")       -> R.drawable.ic_file_audio    to R.color.category_audio
        mime == "application/pdf"       -> R.drawable.ic_file_pdf      to R.color.category_document
        mime.startsWith("text/")        -> R.drawable.ic_file_document to R.color.category_document
        mime.contains("zip") || mime.contains("archive") || mime.contains("compressed") ->
                                           R.drawable.ic_file_archive  to R.color.category_archive
        mime == "application/vnd.android.package-archive" ->
                                           R.drawable.ic_file_apk      to R.color.category_apk
        else                            -> R.drawable.ic_file_generic  to R.color.category_archive
    }
}
