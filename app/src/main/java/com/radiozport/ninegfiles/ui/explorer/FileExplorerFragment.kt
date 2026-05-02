package com.radiozport.ninegfiles.ui.explorer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.*
import com.radiozport.ninegfiles.ui.dialogs.FileActionsBottomSheet
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.*
import com.radiozport.ninegfiles.databinding.FragmentFileExplorerBinding
import com.radiozport.ninegfiles.ui.adapters.FileAdapter
import com.radiozport.ninegfiles.ui.dialogs.*
import com.radiozport.ninegfiles.ui.viewer.QuickPeekBottomSheet
import com.radiozport.ninegfiles.ui.vault.SecureVaultFragment
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class FileExplorerFragment : Fragment() {

    private var _binding: FragmentFileExplorerBinding? = null
    private val binding get() = _binding!!
    /**
     * In normal single-pane mode the ViewModel is activity-scoped so HomeFragment,
     * ToolsFragment, and SearchFragment can share navigation state with the explorer.
     *
     * In dual-pane mode each pane gets its OWN fragment-scoped ViewModel so the two
     * panes browse completely independently.
     */
    private val viewModel: FileExplorerViewModel by lazy {
        val isDualPane = arguments?.getBoolean("isDualPane", false) == true
        val factory = FileExplorerViewModelFactory(requireActivity().application)
        if (isDualPane) {
            // Scope to the PARENT (DualPaneFragment) rather than to this child fragment.
            // DualPaneFragment stays alive on the back stack while a viewer (media, image,
            // etc.) is open, so its ViewModelStore survives. A child-fragment-scoped ViewModel
            // would be destroyed whenever the child is recreated after returning from a viewer,
            // resetting _currentPath to root.  Using distinct keys gives each pane its own
            // independent ViewModel while still benefiting from the parent's longer lifetime.
            val paneKey = if (arguments?.getBoolean("isLeftPane", true) == true)
                "vm_pane_left" else "vm_pane_right"
            ViewModelProvider(requireParentFragment(), factory)[paneKey, FileExplorerViewModel::class.java]
        } else {
            ViewModelProvider(requireActivity(), factory)[FileExplorerViewModel::class.java]
        }
    }

    /**
     * When hosted inside DualPaneFragment, findNavController() returns the NavController
     * scoped to dualPaneFragment, which has no viewer actions. Use the root NavController
     * (hosted by MainActivity's nav_host_fragment) so all destinations are reachable.
     */
    private val rootNavController get() =
        if (arguments?.getBoolean("isDualPane", false) == true)
            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment)
        else
            findNavController()

    private lateinit var fileAdapter: FileAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFileExplorerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        setupBreadcrumbs()
        setupNavHistoryButtons()
        setupDriveSwitcher()
        observeViewModel()
        setupPasteBar()
        setupSelectionToolbar()
        setupBackNavigation()

        // Refresh automatically when files are deleted by a vault import so the
        // folder reflects its new state without requiring a manual pull-to-refresh.
        parentFragmentManager.setFragmentResultListener(
            SecureVaultFragment.RESULT_IMPORT_COMPLETE, viewLifecycleOwner
        ) { _, _ -> viewModel.refresh() }
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(
            onItemClick = ::handleItemClick,
            onItemLongClick = ::handleItemLongClick,
            onItemMenuClick = ::showFileContextMenu
        )
        binding.rvFiles.apply {
            adapter = fileAdapter
            setHasFixedSize(true)
            itemAnimator = null
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    private fun setupFab() {
        binding.fabCreate.setOnClickListener { showCreateDialog() }
        binding.fabCreate.setOnLongClickListener { showSortDialog(); true }
    }

    private fun setupBreadcrumbs() {}  // chips are added dynamically in observeViewModel

    private fun setupNavHistoryButtons() {
        binding.btnNavBack?.setOnClickListener { viewModel.navigateBack() }
        binding.btnNavForward?.setOnClickListener { viewModel.navigateForward() }
    }

    // ── Drive Switcher ────────────────────────────────────────────────────
    // Tapping the storage icon in the breadcrumb bar pops up a menu that
    // lists every currently-mounted volume (Internal + any removable drives).
    // Selecting an entry navigates the explorer to that volume's root.

    private fun setupDriveSwitcher() {
        binding.btnSwitchDrive?.setOnClickListener { anchor ->
            showDriveSwitcherMenu(anchor)
        }
    }

    private fun showDriveSwitcherMenu(anchor: View) {
        val sm = requireContext().getSystemService(StorageManager::class.java)
        val volumes = sm.storageVolumes

        // Build label + path pairs for every accessible volume
        data class DriveEntry(val label: String, val path: String)
        val drives = mutableListOf<DriveEntry>()

        volumes.forEach { vol ->
            val dir = vol.directory ?: return@forEach
            val label = if (!vol.isRemovable) "Internal Storage"
                        else vol.getDescription(requireContext())
            drives.add(DriveEntry(label, dir.absolutePath))
        }

        if (drives.isEmpty()) {
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "No drives found", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
            return
        }

        val labels = drives.map { it.label }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Switch Drive")
            .setItems(labels) { _, which ->
                viewModel.navigate(drives[which].path)
            }
            .show()
    }

    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        viewModel.isInSelectionMode.value -> viewModel.clearSelection()
                        viewModel.navigateUpFolder() -> {}
                        else -> {
                            isEnabled = false
                            rootNavController.navigate(R.id.homeFragment)
                            isEnabled = true
                        }
                    }
                }
            }
        )
    }

    private fun setupPasteBar() {
        binding.btnPaste.setOnClickListener { viewModel.paste() }
        binding.btnClearClipboard.setOnClickListener { viewModel.clearClipboard() }
    }

    private fun setupSelectionToolbar() {
        binding.btnCancelSelection.setOnClickListener { viewModel.clearSelection() }

        binding.btnSelectionCopy.setOnClickListener {
            val items = viewModel.getSelectedFileItems()
            if (items.isNotEmpty()) viewModel.copy(items)
        }
        binding.btnSelectionCut.setOnClickListener {
            val items = viewModel.getSelectedFileItems()
            if (items.isNotEmpty()) viewModel.cut(items)
        }
        binding.btnSelectionDelete.setOnClickListener {
            val items = viewModel.getSelectedFileItems()
            if (items.isNotEmpty()) viewModel.trash(items)
        }
        binding.btnSelectionMore.setOnClickListener { showSelectionOverflowMenu(it) }
    }

    private fun showSelectionOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_selection, popup.menu)
        // Copy / Cut / Delete are already exposed as icon buttons — hide them from overflow
        popup.menu.findItem(R.id.action_copy)?.isVisible = false
        popup.menu.findItem(R.id.action_cut)?.isVisible = false
        popup.menu.findItem(R.id.action_delete)?.isVisible = false
        popup.setOnMenuItemClickListener { menuItem ->
            val items = viewModel.getSelectedFileItems()
            when (menuItem.itemId) {
                R.id.action_select_all -> { viewModel.selectAll(); true }
                R.id.action_share -> { shareFiles(items); true }
                R.id.action_wifi_direct_send -> {
                    val bundle = android.os.Bundle().apply {
                        putStringArrayList("pendingFilePaths", ArrayList(items.map { it.path }))
                    }
                    findNavController().navigate(R.id.action_explorer_to_wifi_direct, bundle)
                    true
                }
                R.id.action_compress -> { if (items.isNotEmpty()) showCompressDialog(items); true }
                R.id.action_batch_rename -> { if (items.isNotEmpty()) showBatchRenameDialog(items); true }
                R.id.action_shred -> { if (items.isNotEmpty()) confirmShred(items); true }
                R.id.action_delete_permanently -> { if (items.isNotEmpty()) confirmPermanentDelete(items); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareFiles(items: List<FileItem>) {
        if (items.isEmpty()) return
        if (items.size == 1) { shareFile(items.first()); return }
        val uris = ArrayList(items.map { item ->
            FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", item.file)
        })
        try {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share ${items.size} files"
            ))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, "No app found to share these files", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.files.collectLatest { files ->
                        fileAdapter.submitList(files)
                        binding.emptyView.isVisible = files.isEmpty() && !viewModel.isLoading.value
                        binding.tvEmptyMessage.text = if (files.isEmpty()) "This folder is empty" else ""
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        binding.progressBar.isVisible = loading && fileAdapter.currentList.isEmpty()
                    }
                }
                launch {
                    viewModel.error.collectLatest { error ->
                        error?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                    }
                }
                launch {
                    viewModel.viewMode.collectLatest { mode ->
                        fileAdapter.viewMode = mode
                        updateLayoutManager(mode)
                    }
                }
                launch {
                    viewModel.gridSpanCount.collectLatest { span ->
                        if (viewModel.viewMode.value == ViewMode.GRID) {
                            (binding.rvFiles.layoutManager as? GridLayoutManager)?.spanCount = span
                            binding.rvFiles.requestLayout()
                        }
                    }
                }
                launch {
                    viewModel.selectedItems.collectLatest { selected ->
                        fileAdapter.selectedPaths = selected
                    }
                }
                launch {
                    viewModel.isInSelectionMode.collectLatest { inSelectionMode ->
                        binding.selectionToolbar.isVisible = inSelectionMode
                        binding.fabCreate.isVisible = !inSelectionMode
                    }
                }
                launch {
                    viewModel.selectionCount.collectLatest { count ->
                        binding.tvSelectionCount.text = "$count selected"
                    }
                }
                launch {
                    viewModel.breadcrumbs.collectLatest { crumbs -> updateBreadcrumbs(crumbs) }
                }
                launch {
                    viewModel.clipboard.collectLatest { clipboard ->
                        binding.pasteBar.isVisible = clipboard != null
                        clipboard?.let { (items, isCut) ->
                            binding.tvPasteInfo.text = "${if (isCut) "Move" else "Copy"} ${items.size} item(s) here"
                        }
                        val pasteBarHeightPx = 56 * resources.displayMetrics.density
                        binding.fabCreate.animate()
                            .translationY(if (clipboard != null) -pasteBarHeightPx else 0f)
                            .setDuration(200).start()
                    }
                }
                launch {
                    viewModel.operationProgress.collectLatest { progress ->
                        binding.cardTransferProgress.isVisible = progress != null
                        if (progress == null) return@collectLatest

                        // ── Filename line ─────────────────────────────────
                        binding.tvTransferFileName.text = progress.currentFile

                        // ── File counter ──────────────────────────────────
                        binding.tvTransferCount.text = "${progress.current} of ${progress.total} item(s)"

                        // ── Progress bar + percent ────────────────────────
                        val pct = if (progress.totalBytes > 0L)
                            (progress.bytesTransferred * 100 / progress.totalBytes).toInt()
                        else
                            ((progress.current.toFloat() / progress.total.coerceAtLeast(1)) * 100).toInt()
                        binding.operationProgressBar.progress = pct
                        binding.tvTransferPercent.text = "$pct%"

                        // ── Bytes line ────────────────────────────────────
                        if (progress.totalBytes > 0L) {
                            binding.tvTransferBytes.text =
                                "${formatTransferBytes(progress.bytesTransferred)} / ${formatTransferBytes(progress.totalBytes)}"
                            binding.tvTransferBytes.isVisible = true
                        } else {
                            binding.tvTransferBytes.isVisible = false
                        }

                        // ── Speed · ETA ───────────────────────────────────
                        val speedStr = if (progress.speedBytesPerSec > 0L)
                            "${formatTransferBytes(progress.speedBytesPerSec)}/s" else ""
                        val etaStr = if (progress.estimatedSecondsRemaining >= 0L)
                            " · ETA ${formatTransferEta(progress.estimatedSecondsRemaining)}" else ""
                        binding.tvTransferSpeed.text = speedStr + etaStr
                    }
                }
                binding.btnCancelTransfer.setOnClickListener {
                    viewModel.cancelCurrentOperation()
                }
                launch {
                    viewModel.operationResult.collectLatest { result ->
                        val msg = when (result) {
                            is OperationResult.Success -> result.message
                            is OperationResult.Failure -> "Error: ${result.error}"
                            else -> null
                        }
                        msg?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show() }
                    }
                }
                launch {
                    viewModel.canGoBack.collectLatest { canGo ->
                        binding.btnNavBack?.isEnabled = canGo
                        binding.btnNavBack?.alpha = if (canGo) 1.0f else 0.38f
                    }
                }
                launch {
                    viewModel.canGoForward.collectLatest { canGo ->
                        binding.btnNavForward?.isEnabled = canGo
                        binding.btnNavForward?.alpha = if (canGo) 1.0f else 0.38f
                    }
                }
                launch {
                    viewModel.listDensity.collectLatest { density ->
                        fileAdapter.listDensity = density
                    }
                }
                launch {
                    viewModel.showFileInfo.collectLatest { show ->
                        fileAdapter.showFileInfo = show
                    }
                }
                launch {
                    viewModel.showExtensions.collectLatest { show ->
                        fileAdapter.showExtensions = show
                    }
                }
                launch {
                    viewModel.showThumbnails.collectLatest { show ->
                        fileAdapter.showThumbnails = show
                        fileAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun updateLayoutManager(mode: ViewMode) {
        binding.rvFiles.layoutManager = when (mode) {
            ViewMode.GRID -> GridLayoutManager(requireContext(), viewModel.gridSpanCount.value)
            ViewMode.LIST, ViewMode.COMPACT -> LinearLayoutManager(requireContext())
        }
        fileAdapter.lastAnimatedPosition = -1
    }

    private fun updateBreadcrumbs(crumbs: List<Pair<String, String>>) {
        binding.chipGroupBreadcrumbs.removeAllViews()
        crumbs.forEachIndexed { index, (name, path) ->
            val chip = Chip(requireContext()).apply {
                text = name; isClickable = true
                chipEndPadding = if (index == crumbs.lastIndex) 8f else 0f
                chipBackgroundColor = if (index == crumbs.lastIndex)
                    requireContext().getColorStateList(com.google.android.material.R.color.material_on_surface_emphasis_medium) else null
                setOnClickListener { viewModel.navigateToBreadcrumb(path) }
            }
            binding.chipGroupBreadcrumbs.addView(chip)
        }
        binding.breadcrumbScrollView.post { binding.breadcrumbScrollView.fullScroll(View.FOCUS_RIGHT) }
    }

    // ─── Item Click Handlers ──────────────────────────────────────────────

    private fun handleItemClick(item: FileItem) {
        if (viewModel.isInSelectionMode.value) { viewModel.toggleSelection(item); return }
        if (item.isDirectory) viewModel.navigate(item.path)
        else { viewModel.recordAccess(item); openFile(item) }
    }

    private fun handleItemLongClick(item: FileItem): Boolean {
        // Always toggle selection — first long-press enters selection mode by selecting the item;
        // subsequent long-presses on other items add/remove them from the selection set.
        viewModel.toggleSelection(item)
        return true
    }

    // ─── Context Menu ────────────────────────────────────────────────────

    private fun showFileContextMenu(item: FileItem, anchor: View) {
        FileActionsBottomSheet.show(childFragmentManager, item) { actionId ->
            handleContextMenuAction(actionId, item)
        }
    }

    private fun handleContextMenuAction(actionId: Int, item: FileItem) {
        when (actionId) {
            R.id.action_quick_peek -> QuickPeekBottomSheet.newInstance(item).show(childFragmentManager, "QuickPeek")
            R.id.action_open -> openFile(item)
            R.id.action_open_with -> openFileWith(item)
            R.id.action_copy -> viewModel.copy(listOf(item))
            R.id.action_cut -> viewModel.cut(listOf(item))
            R.id.action_delete -> viewModel.trash(listOf(item))
            R.id.action_delete_permanently -> confirmPermanentDelete(listOf(item))
            R.id.action_shred -> confirmShred(listOf(item))
            R.id.action_rename -> showRenameDialog(item)
            R.id.action_batch_rename -> showBatchRenameDialog(listOf(item))
            R.id.action_share -> shareFile(item)
            R.id.action_bookmark -> viewModel.toggleBookmark(item)
            R.id.action_compress -> showCompressDialog(listOf(item))
            R.id.action_compress_encrypted -> {
                com.radiozport.ninegfiles.ui.dialogs.EncryptedZipDialog.show(
                    childFragmentManager, listOf(item)
                ) { path ->
                    Snackbar.make(binding.root, "Saved: $path", Snackbar.LENGTH_LONG).show()
                    viewModel.refresh()
                }
            }
            R.id.action_wifi_direct_send -> {
                val bundle = android.os.Bundle().apply {
                    putStringArrayList("pendingFilePaths", arrayListOf(item.path))
                }
                findNavController().navigate(R.id.action_explorer_to_wifi_direct, bundle)
            }
            R.id.action_extract -> viewModel.extract(item)
            R.id.action_split -> showSplitDialog(item)
            R.id.action_details -> showDetailsDialog(item)
            R.id.action_timestamp -> showTimestampDialog(item)
            R.id.action_checksums -> FileHashDialog(item).show(childFragmentManager, "FileHashDialog")
            R.id.action_copy_path -> {
                com.radiozport.ninegfiles.utils.FileUtils.copyPathToClipboard(requireContext(), item.path)
                Snackbar.make(binding.root, "Path copied", Snackbar.LENGTH_SHORT).show()
            }
            R.id.action_home_shortcut -> pinFolderToHomeScreen(item)
            R.id.action_note -> showNoteDialog(item)
            R.id.action_encrypt -> com.radiozport.ninegfiles.ui.dialogs.EncryptFileDialog.show(childFragmentManager, item) { success, msg ->
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                if (success) viewModel.refresh()
            }
            R.id.action_exif -> rootNavController.navigate(
                R.id.exifViewerFragment,
                android.os.Bundle().apply { putString("filePath", item.path) }
            )
            R.id.action_combine -> com.radiozport.ninegfiles.ui.dialogs.FileCombineDialog.show(childFragmentManager, item) { success, msg ->
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                if (success) viewModel.refresh()
            }
            R.id.action_print -> printFile(item)
            R.id.action_paste_here -> { viewModel.navigate(item.path); viewModel.paste() }
        }
    }

    private fun printFile(item: FileItem) {
        val printManager = requireContext()
            .getSystemService(android.content.Context.PRINT_SERVICE)
            as? android.print.PrintManager
            ?: run {
                Snackbar.make(binding.root, "Print not available on this device",
                    Snackbar.LENGTH_SHORT).show()
                return
            }
        val ext = item.extension.lowercase()
        when {
            ext == "pdf" -> {
                val file = item.file
                val jobName = item.name
                val totalPages = try {
                    val pfd = android.os.ParcelFileDescriptor.open(
                        file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = android.graphics.pdf.PdfRenderer(pfd)
                    val count = r.pageCount
                    r.close(); pfd.close(); count
                } catch (_: Exception) { android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN }

                printManager.print(jobName,
                    object : android.print.PrintDocumentAdapter() {
                        override fun onLayout(o: android.print.PrintAttributes?,
                            n: android.print.PrintAttributes,
                            cs: android.os.CancellationSignal?,
                            cb: LayoutResultCallback, e: android.os.Bundle?) {
                            if (cs?.isCanceled == true) { cb.onLayoutCancelled(); return }
                            cb.onLayoutFinished(
                                android.print.PrintDocumentInfo.Builder(jobName)
                                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                    .setPageCount(totalPages).build(), n != o)
                        }
                        override fun onWrite(pages: Array<out android.print.PageRange>?,
                            dest: android.os.ParcelFileDescriptor,
                            cs: android.os.CancellationSignal?,
                            cb: WriteResultCallback) {
                            if (cs?.isCanceled == true) { cb.onWriteCancelled(); return }
                            try {
                                file.inputStream().use { i ->
                                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(dest)
                                        .use { o -> i.copyTo(o) }
                                }
                                cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                            } catch (e: Exception) { cb.onWriteFailed(e.message) }
                        }
                    },
                    android.print.PrintAttributes.Builder().build())
            }
            ext in setOf("jpg","jpeg","png","webp","heic","heif","tiff","tif","gif","bmp") -> {
                val bmp = try { android.graphics.BitmapFactory.decodeFile(item.path) }
                    catch (_: Exception) { null }
                if (bmp == null) {
                    Snackbar.make(binding.root, "Could not decode image for printing",
                        Snackbar.LENGTH_SHORT).show()
                    return
                }
                val helper = androidx.print.PrintHelper(requireContext())
                helper.scaleMode = androidx.print.PrintHelper.SCALE_MODE_FIT
                helper.printBitmap(item.name, bmp)
            }
            ext == "epub" || com.radiozport.ninegfiles.utils.FileUtils.isTextFile(item.file) -> {
                // For text/epub files, render through a temporary WebView so Android
                // handles pagination automatically.
                val jobName = item.name
                val content = try { item.file.readText(Charsets.UTF_8) }
                    catch (_: Exception) {
                        Snackbar.make(binding.root, "Cannot read file for printing",
                            Snackbar.LENGTH_SHORT).show()
                        return
                    }
                val escaped = android.text.Html.escapeHtml(content)
                val html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                    "<style>body{font-family:monospace;font-size:11pt;" +
                    "white-space:pre-wrap;margin:16pt;color:#000}</style></head>" +
                    "<body>$escaped</body></html>"
                val wv = android.webkit.WebView(requireContext())
                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        printManager.print(jobName, view.createPrintDocumentAdapter(jobName),
                            android.print.PrintAttributes.Builder().build())
                    }
                }
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            else -> Snackbar.make(binding.root, "Cannot print ${item.name}",
                Snackbar.LENGTH_SHORT).show()
        }
    }

    // ─── Dialogs ─────────────────────────────────────────────────────────

    private fun showCreateDialog() {
        CreateItemDialog { type, name ->
            if (type == CreateItemDialog.TYPE_FOLDER) viewModel.createFolder(name)
            else viewModel.createFile(name)
        }.show(childFragmentManager, "CreateItemDialog")
    }

    private fun showRenameDialog(item: FileItem) {
        RenameDialog(item) { newName -> viewModel.rename(item, newName) }
            .show(childFragmentManager, "RenameDialog")
    }

    private fun showBatchRenameDialog(items: List<FileItem>) {
        BatchRenameDialog(items) { template -> viewModel.batchRename(items, template) }
            .show(childFragmentManager, "BatchRenameDialog")
    }

    private fun showCompressDialog(items: List<FileItem>) {
        CompressDialog(items) { outputName -> viewModel.compress(items, outputName) }
            .show(childFragmentManager, "CompressDialog")
    }

    private fun showSplitDialog(item: FileItem) {
        val sizes = arrayOf("1 MB", "5 MB", "10 MB", "50 MB", "100 MB")
        val sizeMbs = intArrayOf(1, 5, 10, 50, 100)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Split File: ${item.name}")
            .setItems(sizes) { _, which ->
                viewModel.splitFile(item, sizeMbs[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetailsDialog(item: FileItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val details = viewModel.getFileDetails2(item)
            FileDetailsDialog(item, details).show(childFragmentManager, "FileDetailsDialog")
        }
    }

    private fun showSortDialog() {
        SortDialog(viewModel.getEffectiveSortOption()) { sortOption ->
            viewModel.setFolderSortOption(sortOption)
        }.show(childFragmentManager, "SortDialog")
    }

    private fun showTimestampDialog(item: FileItem) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = item.lastModified }
        android.app.DatePickerDialog(requireContext(), { _, year, month, day ->
            android.app.TimePickerDialog(requireContext(), { _, hour, minute ->
                cal.set(year, month, day, hour, minute, 0)
                viewModel.changeTimestamp(item, cal.timeInMillis)
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun confirmPermanentDelete(items: List<FileItem>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete permanently?")
            .setMessage("${items.size} item(s) will be deleted forever.")
            .setPositiveButton("Delete") { _, _ -> viewModel.delete(items) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmShred(items: List<FileItem>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Securely Shred?")
            .setMessage("${items.size} item(s) will be overwritten 3 times then deleted. This cannot be undone.")
            .setPositiveButton("Shred") { _, _ -> viewModel.shred(items) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Home Screen Shortcut ─────────────────────────────────────────────

    private fun pinFolderToHomeScreen(item: FileItem) {
        if (!item.isDirectory) {
            Snackbar.make(binding.root, "Shortcuts are for folders only", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = requireContext().getSystemService<ShortcutManager>() ?: return
            if (!sm.isRequestPinShortcutSupported) {
                Snackbar.make(binding.root, "Launcher doesn't support pinned shortcuts", Snackbar.LENGTH_SHORT).show()
                return
            }
            val shortcutIntent = requireActivity().packageManager
                .getLaunchIntentForPackage(requireContext().packageName)!!
                .apply {
                    action = "com.radiozport.ninegfiles.ACTION_OPEN_PATH"
                    putExtra("open_path", item.path)
                }

            val shortcut = ShortcutInfo.Builder(requireContext(), "folder_${item.path.hashCode()}")
                .setShortLabel(item.name)
                .setLongLabel(item.path)
                .setIcon(Icon.createWithResource(requireContext(), R.drawable.ic_folder))
                .setIntent(shortcutIntent)
                .build()

            sm.requestPinShortcut(shortcut, null)
            Snackbar.make(binding.root, "Shortcut added to home screen", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "Requires Android 8+", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showNoteDialog(item: FileItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val existing = try {
                viewModel.repo.getNoteForFile(item.path).first()?.note ?: ""
            } catch (e: Exception) { "" }

            val editText = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                setText(existing)
                hint = "Write a note about this file…"
                minLines = 3
                maxLines = 8
                setSingleLine(false)
                setPadding(48, 24, 48, 8)
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
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
                .setNeutralButton("Delete Note") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.repo.deleteNote(item.path)
                        Snackbar.make(binding.root, "Note removed", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─── File Opening ─────────────────────────────────────────────────────

    private fun openFile(item: FileItem) {
        when (item.fileType) {
            FileType.IMAGE -> rootNavController.navigate(R.id.imageViewerFragment,
                android.os.Bundle().apply { putString("path", item.path) })
            FileType.AUDIO, FileType.VIDEO -> rootNavController.navigate(R.id.mediaInfoFragment,
                android.os.Bundle().apply { putString("mediaPath", item.path) })
            FileType.PDF -> rootNavController.navigate(R.id.pdfViewerFragment,
                android.os.Bundle().apply { putString("pdfPath", item.path) })
            FileType.ARCHIVE -> {
                val supported = item.extension in setOf("zip","tar","gz","bz2","xz","7z","rar","tgz","tbz2","txz")
                if (supported) rootNavController.navigate(R.id.zipBrowserFragment,
                    android.os.Bundle().apply { putString("archivePath", item.path) })
                else openWithSystem(item)
            }
            FileType.APK -> rootNavController.navigate(R.id.apkInfoFragment,
                android.os.Bundle().apply { putString("apkPath", item.path) })
            FileType.CODE, FileType.DOCUMENT -> {
                if (item.extension.lowercase() == "epub")
                    rootNavController.navigate(R.id.epubReaderFragment,
                        android.os.Bundle().apply { putString("epubPath", item.path) })
                else if (com.radiozport.ninegfiles.utils.FileUtils.isTextFile(item.file))
                    rootNavController.navigate(R.id.textEditorFragment,
                        android.os.Bundle().apply { putString("filePath", item.path) })
                else openWithSystem(item)
            }
            else -> openWithSystem(item)
        }
    }

    private fun openWithSystem(item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", item.file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) { openFileWith(item) }
    }

    private fun openFileWith(item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", item.file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Open with…"))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, "No app found to open this file", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(item: FileItem) {
        // Offer standard share AND QR code share
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Share \"${item.name}\"")
            .setItems(arrayOf("Send via app…", "QR code (path)")) { _, which ->
                when (which) {
                    0 -> {
                        val uri = FileProvider.getUriForFile(requireContext(),
                            "${requireContext().packageName}.fileprovider", item.file)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = item.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share \"${item.name}\""))
                    }
                    1 -> com.radiozport.ninegfiles.ui.dialogs.QrShareDialog.show(
                        childFragmentManager,
                        content = item.path,
                        label   = item.name
                    )
                }
            }
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    /** Returns the currently browsed path — used by DualPaneFragment for pane swap. */
    fun currentPath(): String = viewModel.currentPath.value ?: ""

    /** Navigates the pane to the given absolute path. */
    fun navigateTo(path: String) {
        if (path.isNotEmpty()) viewModel.navigate(path)
    }

    /**
     * Returns the currently selected [FileItem]s in this pane.
     * Used by DualPaneFragment to identify what to copy into the other pane.
     */
    fun getSelectedItems(): List<com.radiozport.ninegfiles.data.model.FileItem> =
        viewModel.getSelectedFileItems()

    /**
     * Forces a re-scan of the current directory.
     * Called by DualPaneFragment after a cross-pane copy completes so the
     * destination pane reflects the new files without the user having to
     * navigate away and back.
     */
    fun refresh() = viewModel.refresh()

    // ─── Transfer progress helpers ────────────────────────────────────────

    private fun formatTransferBytes(bytes: Long): String = when {
        bytes < 1_024L                 -> "$bytes B"
        bytes < 1_024L * 1_024        -> "%.1f KB".format(bytes / 1_024.0)
        bytes < 1_024L * 1_024 * 1_024 -> "%.1f MB".format(bytes / (1_024.0 * 1_024))
        else                            -> "%.2f GB".format(bytes / (1_024.0 * 1_024 * 1_024))
    }

    private fun formatTransferEta(secs: Long): String = when {
        secs < 60   -> "${secs}s"
        secs < 3600 -> "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
        else        -> "${secs / 3600}h ${(secs % 3600) / 60}m"
    }
}
