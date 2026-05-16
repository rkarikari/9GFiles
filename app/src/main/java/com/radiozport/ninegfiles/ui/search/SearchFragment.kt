package com.radiozport.ninegfiles.ui.search

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.radiozport.ninegfiles.utils.FileUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.radiozport.ninegfiles.ui.dialogs.BatchRenameDialog
import com.radiozport.ninegfiles.ui.dialogs.CompressDialog
import com.radiozport.ninegfiles.ui.dialogs.FileActionsBottomSheet
import com.radiozport.ninegfiles.ui.dialogs.FileDetailsDialog
import com.radiozport.ninegfiles.ui.dialogs.FileHashDialog
import com.radiozport.ninegfiles.ui.dialogs.FileCombineDialog
import com.radiozport.ninegfiles.ui.dialogs.EncryptedZipDialog
import com.radiozport.ninegfiles.ui.dialogs.EncryptFileDialog
import com.radiozport.ninegfiles.ui.dialogs.RenameDialog
import com.radiozport.ninegfiles.ui.viewer.QuickPeekBottomSheet
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.*
import com.radiozport.ninegfiles.databinding.FragmentSearchBinding
import com.radiozport.ninegfiles.databinding.ItemSearchHistoryBinding
import com.radiozport.ninegfiles.ui.adapters.FileAdapter
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()
    private lateinit var searchAdapter: FileAdapter
    private lateinit var historyAdapter: SearchHistoryAdapter

    // Use the root NavController (same host as the explorer) so we can navigate
    // to internal viewer/editor fragments exactly the same way the explorer does.
    private val rootNavController get() =
        try { Navigation.findNavController(requireActivity(), R.id.nav_host_fragment) }
        catch (_: Exception) { findNavController() }

    private val activeFilters = mutableSetOf<FileType>()
    private val chipMap = mutableMapOf<FileType, Chip>()
    private var searchJob: Job? = null
    private var isCategoryMode = false
    private var updatingChipsProgrammatically = false

    // Local selection state — search results are ephemeral so we track selection here,
    // not in the shared ViewModel, to avoid polluting the explorer's selection.
    private val selectedSearchPaths = mutableSetOf<String>()
    private var isSearchSelectionMode = false

    // Advanced filter state
    private var minDateMs: Long = 0L
    private var maxDateMs: Long = Long.MAX_VALUE
    private var regexEnabled = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSearchView()
        setupFilterChips()
        setupResultsList()
        setupHistoryList()
        setupAdvancedFilters()
        setupSearchSelectionToolbar()
        observeViewModel()

        val filterTypesArg = arguments?.getString("filterTypes") ?: arguments?.getString("filterType")
        filterTypesArg?.let { raw ->
            raw.split(",").forEach { typeName ->
                try { activeFilters.add(FileType.valueOf(typeName.trim())) } catch (_: Exception) {}
            }
            if (activeFilters.isNotEmpty()) {
                isCategoryMode = true
                activeFilters.forEach { updateFilterChipState(it, true) }
                performSearch(binding.searchView.query?.toString() ?: "")
            }
        }
    }

    private fun setupSearchView() {
        binding.searchView.apply {
            isIconified = false
            requestFocusFromTouch()
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    performSearch(query ?: "")
                    return true
                }
                override fun onQueryTextChange(newText: String?): Boolean {
                    searchJob?.cancel()
                    if (newText.isNullOrBlank() && activeFilters.isEmpty()) {
                        viewModel.clearSearch()
                        showRecentSearches()
                    } else {
                        // Live suggestions
                        searchJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(250)
                            val suggestions = viewModel.getSearchSuggestions(newText ?: "")
                            historyAdapter.submitList(suggestions.map { it.query })
                            binding.rvHistory.isVisible = suggestions.isNotEmpty() && binding.rvResults.isVisible.not()

                            delay(150) // extra debounce before full search
                            performSearch(newText ?: "")
                        }
                    }
                    return true
                }
            })
        }
    }

    private fun setupFilterChips() {
        val filterTypes = listOf(
            "Images" to FileType.IMAGE, "Videos" to FileType.VIDEO, "Audio" to FileType.AUDIO,
            "Docs" to FileType.DOCUMENT, "Archives" to FileType.ARCHIVE,
            "APKs" to FileType.APK, "Code" to FileType.CODE
        )
        filterTypes.forEach { (label, type) ->
            val chip = Chip(requireContext()).apply {
                text = label; isCheckable = true
                setOnCheckedChangeListener { _, isChecked ->
                    if (updatingChipsProgrammatically) return@setOnCheckedChangeListener
                    if (isChecked) activeFilters.add(type) else activeFilters.remove(type)
                    isCategoryMode = false
                    performSearch(binding.searchView.query?.toString() ?: "")
                }
            }
            chipMap[type] = chip
            binding.chipGroupFilters.addView(chip)
        }

        // Regex toggle chip
        val regexChip = Chip(requireContext()).apply {
            text = "Regex"; isCheckable = true
            setOnCheckedChangeListener { _, isChecked ->
                regexEnabled = isChecked
                performSearch(binding.searchView.query?.toString() ?: "")
            }
        }
        binding.chipGroupFilters.addView(regexChip)
    }

    private fun updateFilterChipState(type: FileType, checked: Boolean) {
        updatingChipsProgrammatically = true
        chipMap[type]?.isChecked = checked
        updatingChipsProgrammatically = false
    }

    private fun setupResultsList() {
        searchAdapter = FileAdapter(
            onItemClick = { item ->
                if (isSearchSelectionMode) {
                    toggleSearchSelection(item)
                } else {
                    viewModel.recordAccess(item)
                    if (item.isDirectory) {
                        viewModel.navigate(item.path)
                        findNavController().navigate(R.id.explorerFragment)
                    } else openFile(item)
                }
            },
            onItemLongClick = { item ->
                toggleSearchSelection(item)
                true
            },
            onItemMenuClick = ::showFileContextMenu
        )
        binding.rvResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }
    }

    private fun toggleSearchSelection(item: com.radiozport.ninegfiles.data.model.FileItem) {
        if (item.path in selectedSearchPaths) selectedSearchPaths.remove(item.path)
        else selectedSearchPaths.add(item.path)
        isSearchSelectionMode = selectedSearchPaths.isNotEmpty()
        searchAdapter.selectedPaths = selectedSearchPaths.toSet()
        updateSearchSelectionUI()
    }

    private fun clearSearchSelection() {
        selectedSearchPaths.clear()
        isSearchSelectionMode = false
        searchAdapter.selectedPaths = emptySet()
        updateSearchSelectionUI()
    }

    private fun getSelectedSearchItems(): List<com.radiozport.ninegfiles.data.model.FileItem> {
        val paths = selectedSearchPaths.toSet()
        return searchAdapter.currentList.filter { it.path in paths }
    }

    private fun updateSearchSelectionUI() {
        binding.selectionToolbar.isVisible = isSearchSelectionMode
        binding.searchView.isVisible = !isSearchSelectionMode
        binding.tvSelectionCount.text = "${selectedSearchPaths.size} selected"
    }

    private fun setupSearchSelectionToolbar() {
        binding.btnCancelSelection.setOnClickListener { clearSearchSelection() }

        binding.btnSelectionCopy.setOnClickListener {
            val items = getSelectedSearchItems()
            if (items.isNotEmpty()) { viewModel.copy(items); clearSearchSelection() }
        }
        binding.btnSelectionCut.setOnClickListener {
            val items = getSelectedSearchItems()
            if (items.isNotEmpty()) { viewModel.cut(items); clearSearchSelection() }
        }
        binding.btnSelectionDelete.setOnClickListener {
            val items = getSelectedSearchItems()
            if (items.isNotEmpty()) viewModel.trash(items)
            clearSearchSelection()
        }
        binding.btnSelectionMore.setOnClickListener { anchor ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.menu_selection, popup.menu)
            popup.menu.findItem(R.id.action_copy)?.isVisible = false
            popup.menu.findItem(R.id.action_cut)?.isVisible = false
            popup.menu.findItem(R.id.action_delete)?.isVisible = false
            popup.setOnMenuItemClickListener { menuItem ->
                val items = getSelectedSearchItems()
                when (menuItem.itemId) {
                    R.id.action_select_all -> {
                        searchAdapter.currentList.forEach { selectedSearchPaths.add(it.path) }
                        isSearchSelectionMode = true
                        searchAdapter.selectedPaths = selectedSearchPaths.toSet()
                        updateSearchSelectionUI()
                        true
                    }
                    R.id.action_share -> { shareSearchFiles(items); true }
                    R.id.action_wifi_direct_send -> {
                        if (items.isNotEmpty()) {
                            val bundle = android.os.Bundle().apply {
                                putStringArray("pendingFilePaths", items.map { it.path }.toTypedArray())
                            }
                            rootNavController.navigate(R.id.wifiDirectFragment, bundle)
                            clearSearchSelection()
                        }
                        true
                    }
                    R.id.action_compress -> {
                        if (items.isNotEmpty()) {
                            com.radiozport.ninegfiles.ui.dialogs.CompressDialog(items) { outputName ->
                                viewModel.compress(items, outputName)
                            }.show(childFragmentManager, "CompressDialog")
                        }
                        true
                    }
                    R.id.action_compress_encrypted -> {
                        if (items.isNotEmpty()) {
                            EncryptedZipDialog.show(childFragmentManager, items) { path ->
                                Snackbar.make(binding.root, "Saved: $path", Snackbar.LENGTH_LONG).show()
                            }
                            clearSearchSelection()
                        }
                        true
                    }
                    R.id.action_batch_rename -> {
                        if (items.isNotEmpty()) {
                            BatchRenameDialog(items) { template ->
                                viewModel.batchRename(items, template)
                            }.show(childFragmentManager, "BatchRenameDialog")
                        }
                        true
                    }
                    R.id.action_shred -> {
                        if (items.isNotEmpty()) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Securely Shred?")
                                .setMessage("${items.size} item(s) will be overwritten 3 times then deleted. This cannot be undone.")
                                .setPositiveButton("Shred") { _, _ -> viewModel.shred(items) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        clearSearchSelection(); true
                    }
                    R.id.action_delete_permanently -> {
                        if (items.isNotEmpty()) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Delete permanently?")
                                .setMessage("${items.size} item(s) will be deleted forever.")
                                .setPositiveButton("Delete") { _, _ -> viewModel.delete(items) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        clearSearchSelection(); true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun shareSearchFiles(items: List<com.radiozport.ninegfiles.data.model.FileItem>) {
        if (items.isEmpty()) return
        val uris = ArrayList(items.map { item ->
            androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", item.file
            )
        })
        try {
            startActivity(Intent.createChooser(
                Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                    else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share ${items.size} file${if (items.size == 1) "" else "s"}"
            ))
        } catch (_: ActivityNotFoundException) { /* no-op */ }
    }

    private fun setupHistoryList() {
        historyAdapter = SearchHistoryAdapter(
            onQueryClick = { query ->
                binding.searchView.setQuery(query, true)
            },
            onDeleteClick = { query ->
                viewModel.deleteSearchHistory(query)
            }
        )
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }
    }

    private fun setupAdvancedFilters() {
        binding.btnAdvancedFilters.setOnClickListener {
            val visible = binding.advancedFiltersPanel.isVisible
            binding.advancedFiltersPanel.isVisible = !visible
            binding.btnAdvancedFilters.text = if (visible) "Advanced Filters ▸" else "Advanced Filters ▾"
        }

        // Date range pickers
        binding.btnDateFrom.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, y, m, d ->
                cal.set(y, m, d, 0, 0, 0)
                minDateMs = cal.timeInMillis
                binding.btnDateFrom.text = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnDateTo.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, y, m, d ->
                cal.set(y, m, d, 23, 59, 59)
                maxDateMs = cal.timeInMillis
                binding.btnDateTo.text = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        binding.btnClearDates.setOnClickListener {
            minDateMs = 0L; maxDateMs = Long.MAX_VALUE
            binding.btnDateFrom.text = "From date"
            binding.btnDateTo.text = "To date"
        }

        binding.chipAnySize.setOnCheckedChangeListener { _, checked -> if (checked) applyFilters() }
        binding.chipSmall.setOnCheckedChangeListener { _, checked -> if (checked) applyFilters() }
        binding.chipMedium.setOnCheckedChangeListener { _, checked -> if (checked) applyFilters() }
        binding.chipLarge.setOnCheckedChangeListener { _, checked -> if (checked) applyFilters() }

        binding.switchHidden.setOnCheckedChangeListener { _, _ -> applyFilters() }
        binding.switchSubfolders.setOnCheckedChangeListener { _, _ -> applyFilters() }

        binding.btnApplyFilters.setOnClickListener { applyFilters() }

        binding.btnClearHistory.setOnClickListener {
            viewModel.clearSearchHistory()
            binding.rvHistory.isVisible = false
        }
    }

    private fun applyFilters() = performSearch(binding.searchView.query?.toString() ?: "")

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.searchResults.collectLatest { results ->
                        searchAdapter.submitList(results)
                        val hasQuery = binding.searchView.query?.isNotBlank() == true
                        val isActive = hasQuery || activeFilters.isNotEmpty()
                        binding.rvResults.isVisible = results.isNotEmpty()
                        binding.rvHistory.isVisible = !isActive
                        binding.emptySearchView.isVisible = results.isEmpty() && isActive
                        binding.tvResultCount.isVisible = results.isNotEmpty()
                        binding.tvResultCount.text = "${results.size} result${if (results.size == 1) "" else "s"}"
                    }
                }
                launch {
                    viewModel.isSearching.collectLatest { searching ->
                        binding.searchProgressBar.isVisible = searching
                    }
                }
                launch {
                    viewModel.sortOption.collectLatest {
                        // Re-run the current search with the new sort order if there is an active query
                        val query = binding.searchView.query?.toString() ?: ""
                        if (query.isNotBlank() || activeFilters.isNotEmpty()) {
                            applyFilters()
                        }
                    }
                }
            }
        }
    }

    private fun performSearch(query: String) {
        val sizeRange = when {
            binding.chipSmall.isChecked  -> 0L to (1024L * 1024L)
            binding.chipMedium.isChecked -> (1024L * 1024L) to (100L * 1024L * 1024L)
            binding.chipLarge.isChecked  -> (100L * 1024L * 1024L) to Long.MAX_VALUE
            else -> 0L to Long.MAX_VALUE
        }
        val filter = SearchFilter(
            query = query,
            fileTypes = activeFilters.toSet(),
            minSize = sizeRange.first,
            maxSize = sizeRange.second,
            minDate = minDateMs,
            maxDate = maxDateMs,
            includeHidden = binding.switchHidden.isChecked,
            searchInSubFolders = binding.switchSubfolders.isChecked,
            regexSearch = regexEnabled,
            searchInContent = binding.switchSearchInContent.isChecked
        )
        viewModel.searchAll(filter)
    }

    private fun showRecentSearches() {
        binding.rvResults.isVisible = false
        binding.rvHistory.isVisible = true
        binding.emptySearchView.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getSearchHistory().collectLatest { history ->
                historyAdapter.submitList(history.map { it.query })
                binding.rvHistory.isVisible = history.isNotEmpty()
            }
        }
    }

    // ─── File context menu (mirrors FileExplorerFragment) ────────────────────

    private fun showFileContextMenu(item: FileItem, anchor: View) {
        FileActionsBottomSheet.show(childFragmentManager, item) { actionId ->
            handleContextMenuAction(actionId, item)
        }
    }

    private fun handleContextMenuAction(actionId: Int, item: FileItem) {
        when (actionId) {
            R.id.action_quick_peek  -> QuickPeekBottomSheet.newInstance(item).show(childFragmentManager, "QuickPeek")
            R.id.action_open        -> openFile(item)
            R.id.action_open_with   -> openWithSystem(item)
            R.id.action_copy        -> viewModel.copy(listOf(item))
            R.id.action_cut         -> viewModel.cut(listOf(item))
            R.id.action_delete      -> viewModel.trash(listOf(item))
            R.id.action_delete_permanently -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete permanently?")
                    .setMessage("This item will be deleted forever.")
                    .setPositiveButton("Delete") { _, _ -> viewModel.delete(listOf(item)) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            R.id.action_shred       -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Securely Shred?")
                    .setMessage("This item will be overwritten 3 times then deleted. This cannot be undone.")
                    .setPositiveButton("Shred") { _, _ -> viewModel.shred(listOf(item)) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            R.id.action_rename      -> {
                RenameDialog(item) { newName ->
                    viewModel.rename(item, newName)
                }.show(childFragmentManager, "RenameDialog")
            }
            R.id.action_share       -> shareSearchFiles(listOf(item))
            R.id.action_bookmark    -> viewModel.toggleBookmark(item)
            R.id.action_compress    -> {
                CompressDialog(listOf(item)) { outputName ->
                    viewModel.compress(listOf(item), outputName)
                }.show(childFragmentManager, "CompressDialog")
            }
            R.id.action_compress_encrypted -> {
                EncryptedZipDialog.show(childFragmentManager, listOf(item)) { path ->
                    Snackbar.make(binding.root, "Saved: $path", Snackbar.LENGTH_LONG).show()
                }
            }
            R.id.action_wifi_direct_send -> {
                val bundle = android.os.Bundle().apply {
                    putStringArray("pendingFilePaths", arrayOf(item.path))
                }
                rootNavController.navigate(R.id.wifiDirectFragment, bundle)
            }
            R.id.action_extract     -> viewModel.extract(item)
            R.id.action_details     -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    val details = viewModel.getFileDetails2(item)
                    FileDetailsDialog(item, details).show(childFragmentManager, "FileDetailsDialog")
                }
            }
            R.id.action_checksums   -> FileHashDialog(item).show(childFragmentManager, "FileHashDialog")
            R.id.action_copy_path   -> {
                FileUtils.copyPathToClipboard(requireContext(), item.path)
                Snackbar.make(binding.root, "Path copied", Snackbar.LENGTH_SHORT).show()
            }
            R.id.action_encrypt     -> EncryptFileDialog.show(childFragmentManager, item) { success, msg ->
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
            R.id.action_exif        -> rootNavController.navigate(
                R.id.exifViewerFragment,
                android.os.Bundle().apply { putString("filePath", item.path) }
            )
            R.id.action_combine     -> FileCombineDialog.show(childFragmentManager, item) { success, msg ->
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
            R.id.action_note        -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    val existing = try {
                        viewModel.repo.getNoteForFile(item.path).first()?.note ?: ""
                    } catch (_: Exception) { "" }
                    val editText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                        setText(existing)
                        hint = "Write a note about this file…"
                        minLines = 3; maxLines = 8
                        isSingleLine = false
                        setPadding(48, 24, 48, 8)
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Note: ${item.name}")
                        .setView(editText)
                        .setPositiveButton("Save") { _, _ ->
                            val text = editText.text?.toString()?.trim() ?: ""
                            viewLifecycleOwner.lifecycleScope.launch {
                                if (text.isEmpty()) viewModel.repo.deleteNote(item.path)
                                else viewModel.repo.upsertNote(item.path, text)
                                Snackbar.make(binding.root,
                                    if (text.isEmpty()) "Note removed" else "Note saved",
                                    Snackbar.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun openFile(item: FileItem) {
        com.radiozport.ninegfiles.utils.FileOpener.open(
            fragment      = this,
            navController = rootNavController,
            item          = item,
            snackbarRoot  = binding.root,
        )
    }

    private fun openWithSystem(item: FileItem) {
        com.radiozport.ninegfiles.utils.FileOpener.openWithSystem(this, item, binding.root)
    }

    override fun onDestroyView() {
        clearSearchSelection()
        super.onDestroyView()
        _binding = null
    }
}

// ─── Search History Adapter ───────────────────────────────────────────────────

class SearchHistoryAdapter(
    private val onQueryClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : ListAdapter<String, SearchHistoryAdapter.HistoryViewHolder>(object : DiffUtil.ItemCallback<String>() {
    override fun areItemsTheSame(a: String, b: String) = a == b
    override fun areContentsTheSame(a: String, b: String) = a == b
}) {
    inner class HistoryViewHolder(private val binding: ItemSearchHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(query: String) {
            binding.tvQuery.text = query
            binding.root.setOnClickListener { onQueryClick(query) }
            binding.btnDelete.setOnClickListener { onDeleteClick(query) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        HistoryViewHolder(ItemSearchHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) = holder.bind(getItem(position))
}
