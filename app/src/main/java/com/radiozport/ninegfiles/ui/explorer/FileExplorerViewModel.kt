package com.radiozport.ninegfiles.ui.explorer

import android.app.Application
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.lifecycle.*
import java.io.File
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.data.db.TrashEntity
import com.radiozport.ninegfiles.data.model.*
import com.radiozport.ninegfiles.data.preferences.AppPreferences
import com.radiozport.ninegfiles.data.repository.*
import com.radiozport.ninegfiles.data.repository.BatchRenameTemplate
import com.radiozport.ninegfiles.utils.FileClipboardManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FileExplorerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NineGFilesApp
    val repo: FileRepository = app.fileRepository
    private val prefs: AppPreferences = app.preferences

    // ─── Navigation State ──────────────────────────────────────────────────

    private val _currentPath = MutableStateFlow(Environment.getExternalStorageDirectory().absolutePath)
    val currentPath: StateFlow<String> = _currentPath

    // Separate forward/back stacks for toolbar nav history buttons
    private val _backStack = MutableStateFlow(mutableListOf<String>())
    private val _forwardStack = MutableStateFlow(mutableListOf<String>())

    val canGoBack: StateFlow<Boolean> = _backStack.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val canGoForward: StateFlow<Boolean> = _forwardStack.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ─── Multi-volume breadcrumbs ──────────────────────────────────────────
    // Detects which storage volume the current path belongs to, labels
    // the root chip with the volume name (e.g. "Internal", "SD Card",
    // "USB Drive"), and builds relative crumbs from there.

    val breadcrumbs: StateFlow<List<Pair<String, String>>> = _currentPath.map { path ->
        val (volumeRoot, volumeLabel) = resolveVolumeRoot(path)
        val relative = path.removePrefix(volumeRoot).trimStart('/')
        val parts = if (relative.isEmpty()) emptyList()
                    else relative.split("/").filter { it.isNotEmpty() }
        val crumbs = mutableListOf(Pair(volumeLabel, volumeRoot))
        var accumulated = volumeRoot
        parts.forEach { part ->
            accumulated = "$accumulated/$part"
            crumbs.add(Pair(part, accumulated))
        }
        crumbs
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ─── Per-folder sort/view memory ──────────────────────────────────────

    private val perFolderSort = mutableMapOf<String, SortOption>()
    private val perFolderView = mutableMapOf<String, ViewMode>()

    // ─── Files ────────────────────────────────────────────────────────────

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // ─── Settings ─────────────────────────────────────────────────────────

    val sortOption = prefs.sortOption.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), SortOption.NAME_ASC)
    val viewMode = prefs.viewMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ViewMode.LIST)
    val showHidden = prefs.showHidden.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    val gridSpanCount = prefs.gridSpanCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 3)
    val foldersFirst = prefs.foldersFirst.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val listDensity    = prefs.listDensity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "normal")
    val showFileInfo   = prefs.showFileInfo.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val showExtensions = prefs.showExtensions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val showThumbnails = prefs.showThumbnails.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
    val keepPasteBar  = prefs.keepPasteBar.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // ─── Selection ────────────────────────────────────────────────────────

    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems
    val selectionCount: StateFlow<Int> = _selectedItems.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)
    val isInSelectionMode: StateFlow<Boolean> = _selectedItems.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    // ─── Clipboard ────────────────────────────────────────────────────────

    val clipboard: StateFlow<Pair<List<FileItem>, Boolean>?> =
        FileClipboardManager.state
            .map { state -> if (state.isEmpty) null else Pair(state.files, state.isCut) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ─── Operation Results ────────────────────────────────────────────────

    private val _operationResult = MutableSharedFlow<OperationResult>()
    val operationResult: SharedFlow<OperationResult> = _operationResult
    private val _operationProgress = MutableStateFlow<OperationResult.Progress?>(null)
    val operationProgress: StateFlow<OperationResult.Progress?> = _operationProgress

    /** Tracks the currently running file-operation job so the UI can cancel it. */
    private var currentOperationJob: kotlinx.coroutines.Job? = null

    fun cancelCurrentOperation() {
        currentOperationJob?.cancel()
        currentOperationJob = null
        _operationProgress.value = null
    }

    /** Replaces any in-flight operation with a new coroutine, cancelling the old one. */
    private fun launchOperation(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        currentOperationJob?.cancel()
        currentOperationJob = viewModelScope.launch(block = block)
    }

    // ─── Search ───────────────────────────────────────────────────────────

    private val _searchResults = MutableStateFlow<List<FileItem>>(emptyList())
    val searchResults: StateFlow<List<FileItem>> = _searchResults
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    // ─── Trash ───────────────────────────────────────────────────────────

    val trashItems = repo.getTrashItems()
    val trashCount = app.database.trashDao().getTrashCount()
    val trashSize = app.database.trashDao().getTrashSize()

    init {
        viewModelScope.launch {
            combine(sortOption, showHidden, foldersFirst) { _, _, _ -> Unit }
                .collect { refresh() }
        }
    }

    // ─── Volume helpers ───────────────────────────────────────────────────

    /**
     * Returns the root path and human-readable label for the storage volume
     * that contains [path]. Falls back gracefully to internal storage.
     *
     * Priority order:
     *   1. Exact StorageVolume directory match (API 24+)
     *   2. Longest-prefix match among all mounted volume directories
     *   3. Internal storage fallback
     */
    private fun resolveVolumeRoot(path: String): Pair<String, String> {
        val sm = app.getSystemService(android.content.Context.STORAGE_SERVICE) as StorageManager
        val internalPath = Environment.getExternalStorageDirectory().absolutePath

        // API 24+ — iterate StorageVolumes for exact matches
        val volumes: List<StorageVolume> = sm.storageVolumes
        var bestRoot  = internalPath
        var bestLabel = "Internal"
        var bestLen   = 0

        for (vol in volumes) {
            val dir = vol.directory ?: continue
            val volPath = dir.absolutePath
            if (path.startsWith(volPath) && volPath.length > bestLen) {
                bestLen   = volPath.length
                bestRoot  = volPath
                bestLabel = when {
                    !vol.isRemovable -> "Internal"
                    else -> vol.getDescription(app).takeIf { it.isNotBlank() }
                               ?: if (path.startsWith(internalPath)) "Internal" else "External"
                }
            }
        }
        return Pair(bestRoot, bestLabel)
    }

    /**
     * Returns the root path of the storage volume that [path] lives on.
     * Used by [navigateUpFolder] to stop "up" navigation at the volume root
     * rather than allowing traversal above it.
     */
    private fun volumeRootFor(path: String): String = resolveVolumeRoot(path).first

    // ─── Navigation ───────────────────────────────────────────────────────

    fun navigate(path: String) {
        _backStack.value = (_backStack.value + _currentPath.value).toMutableList()
        _forwardStack.value = mutableListOf()
        _currentPath.value = path
        clearSelection()
        loadFiles(path)
        viewModelScope.launch {
            repo.addRecentFolder(path, File(path).name)
            // Persist last-visited path only when the preference is enabled
            if (prefs.rememberLastPath.first()) {
                prefs.setLastPath(path)
            }
        }
    }

    fun navigateBack(): Boolean {
        if (_backStack.value.isEmpty()) return false
        val previous = _backStack.value.last()
        _forwardStack.value = (_forwardStack.value + _currentPath.value).toMutableList()
        _backStack.value = _backStack.value.dropLast(1).toMutableList()
        _currentPath.value = previous
        clearSelection()
        loadFiles(previous)
        return true
    }

    fun navigateForward(): Boolean {
        if (_forwardStack.value.isEmpty()) return false
        val next = _forwardStack.value.last()
        _backStack.value = (_backStack.value + _currentPath.value).toMutableList()
        _forwardStack.value = _forwardStack.value.dropLast(1).toMutableList()
        _currentPath.value = next
        clearSelection()
        loadFiles(next)
        return true
    }

    fun navigateUp(): Boolean {
        if (_backStack.value.isEmpty()) return false
        val previous = _backStack.value.last()
        _backStack.value = _backStack.value.dropLast(1).toMutableList()
        _currentPath.value = previous
        clearSelection()
        loadFiles(previous)
        return true
    }

    fun canNavigateUp() = _backStack.value.isNotEmpty()

    /**
     * Navigate one directory up, stopping at the root of the current
     * storage volume (internal or any external/OTG/USB/SD volume).
     *
     * Returns false when already at the volume root so that
     * FileExplorerFragment can decide to pop back to HomeFragment.
     */
    fun navigateUpFolder(): Boolean {
        val current = _currentPath.value
        val volumeRoot = volumeRootFor(current)

        // Already at the root of this volume — let the fragment handle it
        if (current == volumeRoot) return false

        val parent = File(current).parent ?: return false

        // Do not navigate above the volume root (e.g. into /storage or /)
        if (!parent.startsWith(volumeRoot)) return false

        _backStack.value = (_backStack.value + current).toMutableList()
        _forwardStack.value = mutableListOf()
        _currentPath.value = parent
        clearSelection()
        loadFiles(parent)
        return true
    }

    fun navigateToBreadcrumb(path: String) {
        if (path == _currentPath.value) return
        val stack = _backStack.value.toMutableList()
        val idx = stack.lastIndexOf(path)
        _backStack.value = if (idx >= 0) stack.subList(0, idx).toMutableList()
            else (stack + _currentPath.value).toMutableList()
        _forwardStack.value = mutableListOf()
        _currentPath.value = path
        clearSelection()
        loadFiles(path)
    }

    // ─── File Loading ─────────────────────────────────────────────────────

    fun loadFiles(path: String = _currentPath.value) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val sort = perFolderSort[path] ?: sortOption.value
                val hidden = showHidden.value
                val folders = foldersFirst.value
                _files.value = repo.listFiles(path, sort, hidden, folders)
            } catch (e: Exception) {
                _error.value = "Failed to load files: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() = loadFiles()

    // ─── Per-folder sort/view ─────────────────────────────────────────────

    fun setFolderSortOption(option: SortOption) {
        perFolderSort[_currentPath.value] = option
        viewModelScope.launch { prefs.setSortOption(option) }
        loadFiles()
    }

    fun setFolderViewMode(mode: ViewMode) {
        perFolderView[_currentPath.value] = mode
        viewModelScope.launch { prefs.setViewMode(mode) }
    }

    fun getEffectiveSortOption() = perFolderSort[_currentPath.value] ?: sortOption.value

    // ─── Selection ────────────────────────────────────────────────────────

    fun toggleSelection(item: FileItem) {
        val current = _selectedItems.value.toMutableSet()
        if (item.path in current) current.remove(item.path) else current.add(item.path)
        _selectedItems.value = current
    }

    fun selectAll() { _selectedItems.value = _files.value.map { it.path }.toSet() }
    fun clearSelection() { _selectedItems.value = emptySet() }
    fun getSelectedFileItems(): List<FileItem> {
        val paths = _selectedItems.value
        return _files.value.filter { it.path in paths }
    }

    // ─── Sort & View ──────────────────────────────────────────────────────

    fun setSortOption(option: SortOption) = viewModelScope.launch { prefs.setSortOption(option) }
    fun setViewMode(mode: ViewMode) = viewModelScope.launch { prefs.setViewMode(mode) }
    fun setShowHidden(show: Boolean) = viewModelScope.launch { prefs.setShowHidden(show) }
    fun setGridSpanCount(count: Int) = viewModelScope.launch { prefs.setGridSpanCount(count) }
    fun setFoldersFirst(first: Boolean) = viewModelScope.launch { prefs.setFoldersFirst(first) }

    // ─── File Operations ──────────────────────────────────────────────────

    fun copy(items: List<FileItem>) { FileClipboardManager.copy(items); clearSelection() }
    fun cut(items: List<FileItem>) { FileClipboardManager.cut(items); clearSelection() }

    fun paste() {
        val state = FileClipboardManager.state.value
        if (state.isEmpty) return
        val items = state.files.toList()   // snapshot before any potential clear
        val isCut = state.isCut
        val dest = _currentPath.value

        // Default behaviour: dismiss the paste bar the moment the user taps Paste.
        // If the "Keep paste bar" setting is on, leave it visible (legacy behaviour).
        if (!keepPasteBar.value) FileClipboardManager.clear()

        launchOperation {
            if (isCut) {
                val result = repo.moveFiles(items, dest) { _operationProgress.value = it as? OperationResult.Progress }
                _operationResult.emit(result)
                // For a move, also clear when keepPasteBar is on (move is destructive — no re-paste)
                if (keepPasteBar.value) FileClipboardManager.clear()
            } else {
                val result = repo.copyFiles(items, dest) { _operationProgress.value = it as? OperationResult.Progress }
                _operationResult.emit(result)
            }
            _operationProgress.value = null
            refresh()
        }
    }

    fun clearClipboard() { FileClipboardManager.clear() }

    fun delete(items: List<FileItem>) {
        launchOperation {
            val result = repo.deleteFiles(items) { _operationProgress.value = it as? OperationResult.Progress }
            _operationResult.emit(result)
            _operationProgress.value = null
            clearSelection()
            refresh()
        }
    }

    fun trash(items: List<FileItem>) {
        launchOperation {
            val result = repo.trashFiles(items) { _operationProgress.value = it as? OperationResult.Progress }
            _operationResult.emit(result)
            _operationProgress.value = null
            clearSelection()
            refresh()
        }
    }

    fun restoreFromTrash(item: TrashEntity) {
        viewModelScope.launch {
            val result = repo.restoreFromTrash(item)
            _operationResult.emit(result)
            refresh()
        }
    }

    fun deleteFromTrash(item: TrashEntity) {
        viewModelScope.launch {
            val result = repo.deleteFromTrash(item)
            _operationResult.emit(result)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val result = repo.emptyTrash()
            _operationResult.emit(result)
        }
    }

    fun shred(items: List<FileItem>) {
        launchOperation {
            val result = repo.shredFiles(items) { _operationProgress.value = it as? OperationResult.Progress }
            _operationResult.emit(result)
            _operationProgress.value = null
            clearSelection()
            refresh()
        }
    }

    fun rename(item: FileItem, newName: String) {
        viewModelScope.launch {
            val result = repo.renameFile(item, newName)
            _operationResult.emit(result)
            refresh()
        }
    }

    fun batchRename(items: List<FileItem>, template: BatchRenameTemplate) {
        launchOperation {
            val result = repo.batchRename(items, template) { _operationProgress.value = it as? OperationResult.Progress }
            _operationResult.emit(result)
            _operationProgress.value = null
            clearSelection()
            refresh()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val result = repo.createFolder(_currentPath.value, name)
            _operationResult.emit(result)
            refresh()
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            val result = repo.createFile(_currentPath.value, name)
            _operationResult.emit(result)
            refresh()
        }
    }

    fun compress(items: List<FileItem>, outputName: String) {
        val outputPath = "${_currentPath.value}/$outputName.zip"
        launchOperation {
            val result = repo.compressFiles(items, outputPath) { _operationProgress.value = it as? OperationResult.Progress }
            _operationResult.emit(result)
            _operationProgress.value = null
            refresh()
        }
    }

    fun extract(archive: FileItem) {
        val destDir = archive.file.parent + "/" + archive.name.substringBeforeLast(".")
        launchOperation {
            val result = repo.extractZip(archive.path, destDir) { _operationProgress.value = it as? OperationResult.Progress }
            _operationResult.emit(result)
            _operationProgress.value = null
            refresh()
        }
    }

    fun splitFile(item: FileItem, partSizeMb: Int) {
        launchOperation {
            val result = repo.splitFile(item, partSizeMb) { _operationProgress.value = it as? OperationResult.Progress }
            _operationResult.emit(result)
            _operationProgress.value = null
            refresh()
        }
    }

    fun changeTimestamp(item: FileItem, timestampMs: Long) {
        viewModelScope.launch {
            val result = repo.changeTimestamp(item, timestampMs)
            _operationResult.emit(result)
            refresh()
        }
    }

    // ─── Bookmarks ────────────────────────────────────────────────────────

    fun toggleBookmark(item: FileItem) {
        viewModelScope.launch {
            if (repo.isBookmarked(item.path)) repo.removeBookmark(item.path)
            else repo.addBookmark(item)
            refresh()
        }
    }

    // ─── Recent Files ─────────────────────────────────────────────────────

    fun recordAccess(item: FileItem) { viewModelScope.launch { repo.addToRecent(item) } }

    val recentFiles = repo.getRecentFiles()

    fun clearRecentFiles() {
        viewModelScope.launch { repo.clearRecentFiles() }
    }

    // ─── Recent Folders ───────────────────────────────────────────────────

    val recentFolders = repo.getRecentFolders()

    fun clearRecentFolders() {
        viewModelScope.launch { repo.clearRecentFolders() }
    }

    // ─── Search ───────────────────────────────────────────────────────────

    fun search(filter: SearchFilter) {
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = repo.searchFiles(
                _currentPath.value, filter,
                sortOption = sortOption.value,
                foldersFirst = foldersFirst.value
            )
            _isSearching.value = false
            if (filter.query.isNotEmpty()) repo.recordSearch(filter.query)
        }
    }

    fun searchAll(filter: SearchFilter) {
        viewModelScope.launch {
            _isSearching.value = true
            val root = Environment.getExternalStorageDirectory().absolutePath
            _searchResults.value = repo.searchFiles(
                root, filter.copy(searchInSubFolders = true),
                sortOption = sortOption.value,
                foldersFirst = foldersFirst.value
            )
            _isSearching.value = false
            if (filter.query.isNotEmpty()) repo.recordSearch(filter.query)
        }
    }

    fun clearSearch() { _searchResults.value = emptyList() }

    suspend fun getSearchSuggestions(prefix: String) = repo.getSuggestions(prefix)
    fun getSearchHistory() = repo.getSearchHistory()
    fun deleteSearchHistory(query: String) = viewModelScope.launch { repo.deleteSearchHistory(query) }
    fun clearSearchHistory() = viewModelScope.launch { repo.clearSearchHistory() }

    // ─── Storage Info ─────────────────────────────────────────────────────

    fun getStorageList() = repo.getStorageList()
    fun getFileDetails(item: FileItem) = viewModelScope.launch {}
    suspend fun getFileDetails2(item: FileItem) = repo.getFileDetails(item)

    suspend fun computeHash(item: FileItem, algorithm: String = "SHA-256") =
        repo.computeHash(item.file, algorithm)

    suspend fun verifyChecksum(item: FileItem) = repo.verifyChecksum(item.file)

    suspend fun buildStorageTree(path: String) = repo.buildStorageTree(path, depth = 4)
}

class FileExplorerViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FileExplorerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FileExplorerViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
