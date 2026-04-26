package com.radiozport.ninegfiles.ui.cloud

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentCloudStorageBinding
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.data.db.BookmarkEntity
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CloudEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * Cloud Storage browser backed by Android's Storage Access Framework (SAF).
 *
 * Works with any SAF provider — Google Drive, Dropbox, OneDrive, Box, etc.
 * Authentication is handled entirely by the Android system picker; no
 * separate credentials are required here.
 *
 * Multiple drives can be connected simultaneously. Each granted drive URI is
 * stored persistently in SharedPreferences and appears as a card on the
 * home screen. Passing a [treeUri] nav argument opens that specific drive
 * directly; omitting it opens the last active drive (or the connect prompt).
 *
 * Features:
 *  • Connect any number of cloud folders via the system SAF picker
 *  • Persistent access — granted drives survive app restarts
 *  • Browse directory trees
 *  • Download files to device Downloads folder
 *  • Upload local files to the cloud folder
 *  • Delete cloud files (where the provider permits)
 *  • Disconnect individual drives
 */
class CloudStorageFragment : Fragment() {

    private var _binding: FragmentCloudStorageBinding? = null
    private val binding get() = _binding!!

    private val adapter = CloudEntryAdapter(
        onClick     = { entry -> onEntryClicked(entry) },
        onLongClick = { entry -> showEntryOptions(entry) }
    )

    private data class NavEntry(val treeUri: Uri, val dirUri: Uri, val name: String)
    private val navStack = ArrayDeque<NavEntry>()

    private var activeTreeUri: Uri? = null

    // SAF pickers ──────────────────────────────────────────────────────────

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        addDriveUri(uri)
        openDrive(uri)
    }

    private val uploadFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uploadFiles(uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvCloud.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCloud.adapter = adapter

        binding.btnConnectCloud.setOnClickListener { openTreeLauncher.launch(null) }
        binding.btnUpload.setOnClickListener { uploadFileLauncher.launch(arrayOf("*/*")) }
        binding.btnDisconnect.setOnClickListener { disconnectCurrentDrive() }

        // If launched from the home screen with a specific drive URI, open it directly.
        val argUri = arguments?.getString("treeUri")?.takeIf { it.isNotEmpty() }
        if (argUri != null) {
            val uri = Uri.parse(argUri)
            if (hasPermission(uri)) {
                openDrive(uri)
                return
            }
        }

        // Otherwise restore the last active drive, if any.
        val drives = loadDriveUris()
        if (drives.isNotEmpty()) {
            openDrive(drives.last())
        } else {
            showEmptyState(true)
        }
    }

    // Drive management ─────────────────────────────────────────────────────

    /** Open a specific drive at its root directory. */
    private fun openDrive(uri: Uri) {
        activeTreeUri = uri
        navStack.clear()
        val rootDocId = DocumentsContract.getTreeDocumentId(uri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, rootDocId)
        navStack.addLast(NavEntry(uri, rootUri, cloudProviderLabel(uri)))
        loadDirectory(rootUri)
    }

    private fun disconnectCurrentDrive() {
        val uri = activeTreeUri ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Disconnect ${cloudProviderLabel(uri)}?")
            .setMessage("The app will forget access to this folder.")
            .setPositiveButton("Disconnect") { _, _ ->
                runCatching {
                    requireContext().contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                removeDriveUri(uri)
                activeTreeUri = null
                navStack.clear()
                adapter.submitList(emptyList())
                // Reconnect to another saved drive if available, else show empty state.
                val remaining = loadDriveUris()
                if (remaining.isNotEmpty()) openDrive(remaining.last()) else showEmptyState(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Navigation ───────────────────────────────────────────────────────────

    private fun loadDirectory(dirUri: Uri) {
        binding.progressCloud.isVisible = true
        showEmptyState(false)
        updateBreadcrumb()

        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { listDirectory(dirUri) }
            binding.progressCloud.isVisible = false
            if (entries.isEmpty()) {
                binding.tvEmpty.text = "Empty folder"
                showEmptyState(true)
            } else {
                showEmptyState(false)
                adapter.submitList(entries)
            }
        }
    }

    private fun onEntryClicked(entry: CloudEntry) {
        if (entry.isDirectory) {
            navStack.addLast(NavEntry(activeTreeUri!!, entry.uri, entry.name))
            loadDirectory(entry.uri)
        } else {
            // Single tap on a file → full options menu (download / bookmark / delete)
            showEntryOptions(entry)
        }
    }

    fun onBackPressed(): Boolean {
        if (navStack.size > 1) {
            navStack.removeLast()
            loadDirectory(navStack.last().dirUri)
            return true
        }
        return false
    }

    private fun updateBreadcrumb() {
        binding.tvPath.text = navStack.joinToString(" › ") { it.name }
        val connected = navStack.isNotEmpty()
        binding.btnUpload.isVisible = connected
        binding.btnDisconnect.isVisible = connected
    }

    // SAF directory listing ────────────────────────────────────────────────

    private fun listDirectory(dirUri: Uri): List<CloudEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            activeTreeUri!!, DocumentsContract.getDocumentId(dirUri)
        )
        val entries = mutableListOf<CloudEntry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        var cursor: Cursor? = null
        try {
            cursor = requireContext().contentResolver.query(childrenUri, projection, null, null, null)
            while (cursor?.moveToNext() == true) {
                val docId  = cursor.getString(0) ?: continue
                val name   = cursor.getString(1) ?: "unknown"
                val mime   = cursor.getString(2) ?: "*/*"
                val size   = cursor.getLong(3)
                val isDir  = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val docUri = DocumentsContract.buildDocumentUriUsingTree(activeTreeUri!!, docId)
                entries += CloudEntry(docUri, name, mime, size, isDir)
            }
        } finally {
            cursor?.close()
        }
        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    // File operations ──────────────────────────────────────────────────────

    /** Long-press context menu for both files and folders. */
    private fun showEntryOptions(entry: CloudEntry) {
        val actions = if (entry.isDirectory) {
            arrayOf("Bookmark folder", "Cancel")
        } else {
            arrayOf("Download to device", "Bookmark", "Delete", "Cancel")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.name)
            .setItems(actions) { _, which ->
                if (entry.isDirectory) {
                    when (which) {
                        0 -> bookmarkCloudEntry(entry)
                        // 1 = Cancel — nothing to do
                    }
                } else {
                    when (which) {
                        0 -> downloadFile(entry)
                        1 -> bookmarkCloudEntry(entry)
                        2 -> deleteFile(entry)
                        // 3 = Cancel
                    }
                }
            }
            .show()
    }

    /** Tap handler: navigate into directories, show options for files. */
    private fun showFileOptions(entry: CloudEntry) = showEntryOptions(entry)

    private fun bookmarkCloudEntry(entry: CloudEntry) {
        val app = requireActivity().application as NineGFilesApp
        viewLifecycleOwner.lifecycleScope.launch {
            val entity = BookmarkEntity(
                path        = entry.uri.toString(),
                name        = entry.name,
                isDirectory = entry.isDirectory,
                emoji       = if (entry.isDirectory) "☁️" else "📄"
            )
            app.database.bookmarkDao().insertBookmark(entity)
            Snackbar.make(binding.root, "\"${entry.name}\" bookmarked", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun downloadFile(entry: CloudEntry) {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressCloud.isVisible = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                        entry.name
                    )
                    requireContext().contentResolver.openInputStream(entry.uri)?.use { inn ->
                        dest.outputStream().use { out -> inn.copyTo(out) }
                    }
                    true
                }.getOrElse { false }
            }
            binding.progressCloud.isVisible = false
            Snackbar.make(binding.root,
                if (ok) "Downloaded to Downloads/${entry.name}" else "Download failed",
                Snackbar.LENGTH_LONG).show()
        }
    }

    private fun deleteFile(entry: CloudEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete \"${entry.name}\"?")
            .setMessage("This will permanently delete the file from cloud storage.")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            DocumentsContract.deleteDocument(
                                requireContext().contentResolver, entry.uri)
                        }.getOrElse { false }
                    }
                    Snackbar.make(binding.root,
                        if (ok) "Deleted" else "Delete failed — provider may not allow it",
                        Snackbar.LENGTH_LONG).show()
                    if (ok) loadDirectory(navStack.last().dirUri)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadFiles(uris: List<Uri>) {
        val destUri = navStack.lastOrNull()?.dirUri ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressCloud.isVisible = true
            var success = 0; var failed = 0
            withContext(Dispatchers.IO) {
                uris.forEach { srcUri ->
                    runCatching {
                        val name = getFileName(srcUri) ?: "file_${System.currentTimeMillis()}"
                        val mimeType = requireContext().contentResolver.getType(srcUri) ?: "*/*"
                        val newDocUri = DocumentsContract.createDocument(
                            requireContext().contentResolver, destUri, mimeType, name
                        ) ?: throw Exception("Could not create destination")
                        requireContext().contentResolver.openInputStream(srcUri)?.use { inn ->
                            requireContext().contentResolver.openOutputStream(newDocUri)?.use { out ->
                                inn.copyTo(out)
                            }
                        }
                        success++
                    }.onFailure { failed++ }
                }
            }
            binding.progressCloud.isVisible = false
            Snackbar.make(binding.root,
                "Uploaded $success file(s)${if (failed > 0) ", $failed failed" else ""}",
                Snackbar.LENGTH_LONG).show()
            loadDirectory(navStack.last().dirUri)
        }
    }

    // Helpers ──────────────────────────────────────────────────────────────

    private fun showEmptyState(show: Boolean) {
        binding.layoutEmptyState.isVisible = show
        binding.rvCloud.isVisible = !show
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun hasPermission(uri: Uri): Boolean {
        val perms = requireContext().contentResolver.persistedUriPermissions
        return perms.any { it.uri == uri && it.isReadPermission }
    }

    // Persistence — stores a set of drive URIs ─────────────────────────────

    companion object {
        private const val PREFS = "cloud_prefs"
        private const val KEY_DRIVES = "drive_uris"

        fun cloudProviderLabel(uri: Uri): String = when {
            uri.authority?.contains("google")    == true -> "Google Drive"
            uri.authority?.contains("dropbox")   == true -> "Dropbox"
            uri.authority?.contains("onedrive")  == true ||
            uri.authority?.contains("microsoft") == true -> "OneDrive"
            uri.authority?.contains("box")       == true -> "Box"
            else -> "Cloud Storage"
        }

        /** Returns all configured, still-permitted drive URIs. */
        fun loadDriveUris(context: Context): List<Uri> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getStringSet(KEY_DRIVES, emptySet()) ?: emptySet()
            val perms = context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }.map { it.uri }.toSet()
            // Only return drives for which we still hold permission.
            return stored.mapNotNull { s ->
                val uri = Uri.parse(s)
                if (perms.contains(uri)) uri else null
            }
        }

        fun saveDriveUri(context: Context, uri: Uri) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(KEY_DRIVES, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.add(uri.toString())
            prefs.edit().putStringSet(KEY_DRIVES, current).apply()
        }
    }

    private fun loadDriveUris(): List<Uri> = loadDriveUris(requireContext())

    private fun addDriveUri(uri: Uri) = saveDriveUri(requireContext(), uri)

    private fun removeDriveUri(uri: Uri) {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_DRIVES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(uri.toString())
        prefs.edit().putStringSet(KEY_DRIVES, current).apply()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// Adapter ──────────────────────────────────────────────────────────────────

class CloudEntryAdapter(
    private val onClick: (CloudEntry) -> Unit,
    private val onLongClick: (CloudEntry) -> Unit = {}
) : ListAdapter<CloudEntry, CloudEntryAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<CloudEntry>() {
            override fun areItemsTheSame(a: CloudEntry, b: CloudEntry) = a.uri == b.uri
            override fun areContentsTheSame(a: CloudEntry, b: CloudEntry) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivFileIcon)
        val name: TextView  = view.findViewById(R.id.tvFileName)
        val info: TextView  = view.findViewById(R.id.tvFileInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file_list, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        holder.name.text = e.name
        holder.info.text = if (e.isDirectory) "Folder" else FileUtils.formatSize(e.size)
        holder.icon.setImageResource(when {
            e.isDirectory                   -> R.drawable.ic_folder
            e.mimeType.startsWith("image/") -> R.drawable.ic_file_image
            e.mimeType.startsWith("video/") -> R.drawable.ic_file_video
            e.mimeType.startsWith("audio/") -> R.drawable.ic_file_audio
            e.mimeType == "application/pdf" -> R.drawable.ic_file_pdf
            else                            -> R.drawable.ic_file_generic
        })
        holder.itemView.setOnClickListener { onClick(e) }
        holder.itemView.setOnLongClickListener { onLongClick(e); true }
    }
}
