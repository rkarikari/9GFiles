package com.radiozport.ninegfiles.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.data.db.RecentFolderEntity
import com.radiozport.ninegfiles.databinding.ItemRecentFolderBinding

class RecentFolderAdapter(
    private val onClick: (String) -> Unit
) : ListAdapter<RecentFolderEntity, RecentFolderAdapter.ViewHolder>(DiffCb()) {

    inner class ViewHolder(private val b: ItemRecentFolderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: RecentFolderEntity) {
            b.tvFolderName.text = item.name
            b.root.setOnClickListener { onClick(item.path) }
            b.root.contentDescription = item.path
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemRecentFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class DiffCb : DiffUtil.ItemCallback<RecentFolderEntity>() {
        override fun areItemsTheSame(a: RecentFolderEntity, b: RecentFolderEntity) = a.path == b.path
        override fun areContentsTheSame(a: RecentFolderEntity, b: RecentFolderEntity) = a == b
    }
}
