package com.radiozport.ninegfiles.ui.bookmarks

import android.os.Bundle
import android.view.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.db.BookmarkEntity
import com.radiozport.ninegfiles.databinding.FragmentBookmarksBinding
import com.radiozport.ninegfiles.databinding.ItemBookmarkBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BookmarksFragment : Fragment() {

    private var _binding: FragmentBookmarksBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FileExplorerViewModel by activityViewModels()
    private lateinit var bookmarkAdapter: BookmarkAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBookmarksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeToDelete()
        observeBookmarks()
    }

    private fun setupRecyclerView() {
        bookmarkAdapter = BookmarkAdapter { bookmark ->
            if (bookmark.path.startsWith("content://")) {
                // Cloud / SAF bookmark — open in the network/cloud browser, not the
                // local file explorer. Pass the stored tree URI as the treeUri argument.
                findNavController().navigate(
                    R.id.action_bookmarks_to_cloud,
                    android.os.Bundle().apply { putString("treeUri", bookmark.path) }
                )
            } else {
                viewModel.navigate(bookmark.path)
                findNavController().navigate(R.id.action_bookmarks_to_explorer)
            }
        }

        binding.rvBookmarks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = bookmarkAdapter
        }
    }

    private fun setupSwipeToDelete() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val item = bookmarkAdapter.currentList[position]
                viewLifecycleOwner.lifecycleScope.launch {
                    val repo = (requireActivity().application as NineGFilesApp).fileRepository
                    repo.removeBookmark(item.path)
                    Snackbar.make(binding.root, "Bookmark removed", Snackbar.LENGTH_LONG)
                        .setAction("Undo") {
                            // Re-insert the exact entity that was removed so all
                            // fields (emoji, addedAt, isDirectory) are preserved.
                            viewLifecycleOwner.lifecycleScope.launch {
                                repo.restoreBookmark(item)
                            }
                        }
                        .setAnchorView(R.id.bottomNav)
                        .show()
                }
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvBookmarks)
    }

    private fun observeBookmarks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val app = requireActivity().application as NineGFilesApp
                app.database.bookmarkDao().getAllBookmarks().collectLatest { bookmarks ->
                    bookmarkAdapter.submitList(bookmarks)
                    binding.emptyView.isVisible = bookmarks.isEmpty()
                    binding.tvEmptyBookmarks.isVisible = bookmarks.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class BookmarkAdapter(private val onClick: (BookmarkEntity) -> Unit) :
    androidx.recyclerview.widget.ListAdapter<BookmarkEntity, BookmarkAdapter.ViewHolder>(BookmarkDiffCallback()) {

    inner class ViewHolder(private val binding: ItemBookmarkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BookmarkEntity) {
            binding.tvName.text = "${item.emoji} ${item.name}"
            binding.tvPath.text = item.path
            binding.ivIcon.setImageResource(
                if (item.isDirectory) R.drawable.ic_folder else R.drawable.ic_file_generic
            )
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class BookmarkDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<BookmarkEntity>() {
        override fun areItemsTheSame(a: BookmarkEntity, b: BookmarkEntity) = a.path == b.path
        override fun areContentsTheSame(a: BookmarkEntity, b: BookmarkEntity) = a == b
    }
}
