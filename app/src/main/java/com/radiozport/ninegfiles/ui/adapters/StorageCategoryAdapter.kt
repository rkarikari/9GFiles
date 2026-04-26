package com.radiozport.ninegfiles.ui.adapters

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.databinding.ItemCategoryBinding
import com.radiozport.ninegfiles.ui.home.HomeFragment

class StorageCategoryAdapter(
    private val items: List<HomeFragment.CategoryItem>,
    private val onClick: (HomeFragment.CategoryItem) -> Unit
) : RecyclerView.Adapter<StorageCategoryAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeFragment.CategoryItem) {
            binding.ivIcon.setImageResource(item.iconRes)
            binding.tvLabel.text = item.label
            binding.tvCount.text = item.count

            val color = ContextCompat.getColor(binding.root.context, item.colorRes)

            // Icon tint — always full category color so icons stay identifiable
            binding.ivIcon.imageTintList = ColorStateList.valueOf(color)

            val isNight = (binding.root.context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            if (isNight) {
                // Dark mode: pure black shades — no color tint on backgrounds
                binding.iconContainer.backgroundTintList =
                    ColorStateList.valueOf(Color.parseColor("#1E1E1E"))
                binding.root.setCardBackgroundColor(Color.parseColor("#111111"))
                binding.root.strokeColor = Color.parseColor("#242424")
                binding.root.strokeWidth = 2
            } else {
                // Light mode: keep original tinted look
                binding.iconContainer.backgroundTintList = ColorStateList.valueOf(
                    Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
                )
                binding.root.setCardBackgroundColor(
                    Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
                )
                binding.root.strokeColor =
                    Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
                binding.root.strokeWidth = 2
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
