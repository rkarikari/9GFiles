package com.radiozport.ninegfiles.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.NineGFilesApp
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.model.FileType
import com.radiozport.ninegfiles.databinding.FragmentHomeBinding
import com.radiozport.ninegfiles.ui.adapters.RecentAdapter
import com.radiozport.ninegfiles.ui.adapters.RecentFolderAdapter
import com.radiozport.ninegfiles.ui.adapters.StorageCategoryAdapter
import com.radiozport.ninegfiles.ui.cloud.CloudStorageFragment
import com.radiozport.ninegfiles.ui.dialogs.BatchRenameDialog
import com.radiozport.ninegfiles.ui.dialogs.CompressDialog
import com.radiozport.ninegfiles.ui.dialogs.FileActionsBottomSheet
import com.radiozport.ninegfiles.ui.dialogs.FileDetailsDialog
import com.radiozport.ninegfiles.ui.dialogs.FileHashDialog
import com.radiozport.ninegfiles.ui.dialogs.RenameDialog
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.radiozport.ninegfiles.ui.viewer.QuickPeekBottomSheet
import com.radiozport.ninegfiles.utils.ExternalStorageReceiver
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()

    private lateinit var recentAdapter: RecentAdapter
    private lateinit var recentFolderAdapter: RecentFolderAdapter
    private lateinit var categoryAdapter: StorageCategoryAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateDateTime()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    // Hot-plug receiver — refreshes the Drives row whenever an external
    // storage device (OTG/USB/SD card) is connected or disconnected.
    private val storageReceiver = ExternalStorageReceiver { _ -> setupDrivesRow() }
    // These cards appear as soon as the corresponding app is installed.
    // Tapping connects via a single system-picker "Allow" prompt (first
    // time) or opens directly into the cloud folder (already connected).
    // No API key or Google account sign-in is required — authentication
    // is handled entirely by Android's Storage Access Framework.
    private data class KnownProvider(
        val label: String,
        val pkg: String,
        val authority: String,
        val rootDocId: String = "root",
        val iconRes: Int,
        val iconTint: String,
        val bgTint: String
    )

    private val knownProviders by lazy {
        listOf(
            KnownProvider("Dropbox",  "com.dropbox.android",
                "com.dropbox.android.FileStorageProvider",
                iconRes = R.drawable.ic_storage, iconTint = "#0061FF", bgTint = "#E8F0FE"),
            // OneDrive is intentionally omitted — it is already represented
            // by the "Cloud Storage" entry in the navigation drawer / home page.
            KnownProvider("Box",      "com.box.android",
                "com.box.android.content",
                iconRes = R.drawable.ic_storage, iconTint = "#0061D5", bgTint = "#E5F0FF")
        )
    }

    // Launched when connecting a SAF provider for the first time.
    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        CloudStorageFragment.saveDriveUri(requireContext(), uri)
        setupDrivesRow()   // refresh row immediately
        val args = Bundle().apply { putString("treeUri", uri.toString()) }
        findNavController().navigate(R.id.action_home_to_cloud, args)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { requireActivity().finish() }
            })
        setupDateTime()
        setupUserAvatar()
        setupSearchBar()
        setupStorageInfo()
        setupCategories()
        setupRecentFiles()
        setupRecentFolders()
        setupQuickAccess()
        hideGoogleDriveCard()
        setupDrivesRow()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        // Register hot-plug receiver so the Drives row updates the instant
        // an OTG/USB/SD card is connected or removed.
        storageReceiver.register(requireContext())
    }

    override fun onStop() {
        super.onStop()
        storageReceiver.unregister(requireContext())
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        setupDrivesRow()   // also refresh on resume (covers config changes)
    }
    override fun onPause() { super.onPause(); clockHandler.removeCallbacks(clockRunnable) }

    // ── Date / time ───────────────────────────────────────────────────────

    private fun setupDateTime() { updateDateTime() }

    private fun updateDateTime() {
        val now = Date()
        binding.tvGreeting.text = buildString {
            append(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now).uppercase())
            append("  ·  ")
            append(SimpleDateFormat("h:mm a", Locale.getDefault()).format(now).uppercase())
        }
    }

    // ── User avatar ───────────────────────────────────────────────────────

    private fun setupUserAvatar() {
        binding.tvAvatarInitials.text = try {
            val name = Settings.Global.getString(requireContext().contentResolver, "device_name")
                ?: Settings.Secure.getString(requireContext().contentResolver, "bluetooth_name") ?: ""
            val words = name.replace(Regex("'s?\\b", RegexOption.IGNORE_CASE), "")
                .split(Regex("[ \\-_]+")).filter { it.isNotEmpty() && it[0].isLetter() }
            when {
                words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
                words.size == 1 && words[0].length >= 2 -> words[0].take(2).uppercase()
                else -> "9G"
            }
        } catch (_: Exception) { "9G" }
    }

    // ── Search bar ────────────────────────────────────────────────────────

    private fun setupSearchBar() {
        binding.searchBar.setOnClickListener { findNavController().navigate(R.id.action_home_to_search) }
    }

    // ── Storage info ──────────────────────────────────────────────────────

    private fun setupStorageInfo() {
        viewModel.getStorageList().firstOrNull()?.let { storage ->
            binding.tvStorageLabel.text = storage.label
            binding.tvStorageFree.text = "${formatBytes(storage.freeSpace)} free"
            binding.tvStorageTotal.text = "${formatBytes(storage.usedSpace)} used of ${formatBytes(storage.totalSpace)}"
            val pct = (storage.usagePercent * 100).toInt()
            binding.storageProgress.progress = pct
            binding.tvStoragePercent.text = "$pct% used"
        }
    }

    // ── Categories ────────────────────────────────────────────────────────

    private fun setupCategories() {
        val categories = listOf(
            CategoryItem("Images",    R.drawable.ic_file_image,    "—", R.color.category_image,    FileType.IMAGE),
            CategoryItem("Videos",    R.drawable.ic_file_video,    "—", R.color.category_video,    FileType.VIDEO),
            CategoryItem("Audio",     R.drawable.ic_file_audio,    "—", R.color.category_audio,    FileType.AUDIO),
            CategoryItem("Documents", R.drawable.ic_file_document, "—", R.color.category_document, FileType.DOCUMENT,
                listOf(FileType.DOCUMENT, FileType.PDF)),
            CategoryItem("Archives",  R.drawable.ic_file_archive,  "—", R.color.category_archive,  FileType.ARCHIVE),
            CategoryItem("APKs",      R.drawable.ic_file_apk,      "—", R.color.category_apk,      FileType.APK)
        )
        categoryAdapter = StorageCategoryAdapter(categories) { category ->
            findNavController().navigate(R.id.searchFragment,
                Bundle().apply { putString("filterTypes", category.types.joinToString(",") { it.name }) })
        }
        binding.rvCategories.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = categoryAdapter
        }
    }

    // ── Recent files ──────────────────────────────────────────────────────

    private fun setupRecentFiles() {
        recentAdapter = RecentAdapter(
            onClick = { path ->
                val file = File(path)
                if (file.exists()) openFile(FileItem.fromFile(file))
            },
            onLongClick = { path ->
                val file = File(path)
                if (file.exists()) showFileContextMenu(FileItem.fromFile(file))
            }
        )
        binding.rvRecentFiles.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
        }
        binding.btnClearRecent.isVisible = false
    }

    // ── Recent folders ────────────────────────────────────────────────────

    private fun setupRecentFolders() {
        recentFolderAdapter = RecentFolderAdapter { path ->
            viewModel.navigate(path)
            findNavController().navigate(R.id.action_home_to_explorer)
        }
        binding.rvRecentFolders?.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentFolderAdapter
        }
    }

    // ── Quick access ──────────────────────────────────────────────────────

    private fun setupQuickAccess() {
        val quickPaths = mapOf(
            binding.btnDownloads to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
            binding.btnDcim      to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath,
            binding.btnDocuments to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
            binding.btnMusic     to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
        )
        quickPaths.forEach { (button, path) ->
            button.setOnClickListener {
                viewModel.navigate(path)
                findNavController().navigate(R.id.explorerFragment)
            }
        }
    }

    // ── Google Drive card — hidden (API removed) ──────────────────────────
    // The dedicated Google Drive REST API integration has been removed.
    // Google Drive (and any other cloud provider) is accessible via the
    // SAF-based "Cloud Storage" browser that appears in the Drives row below.

    private fun hideGoogleDriveCard() {
        binding.cardGoogleDrive.isVisible = false
    }

    // ── ViewModel observation ─────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recentFiles.collectLatest { files ->
                        binding.tvRecentEmpty.isVisible = files.isEmpty()
                        recentAdapter.submitList(files)
                    }
                }
                launch {
                    viewModel.recentFolders.collectLatest { folders ->
                        binding.tvRecentFoldersEmpty?.isVisible = folders.isEmpty()
                        recentFolderAdapter.submitList(folders)
                    }
                }
            }
        }
    }

    // ── Drives row ────────────────────────────────────────────────────────
    //
    // Card order:
    //   1. Internal storage  — always present
    //   2. External volumes  — SD cards, OTG drives, USB sticks (auto-detected)
    //   3. Known SAF providers installed on the device (Dropbox, OneDrive, Box)
    //   4. Previously connected SAF drives not matching a known provider
    //   5. "Connect" button — SAF picker for any other provider

    private fun setupDrivesRow() {
        // Kept as an alias so onResume can call the same method.
        setupConnectedDrives()
    }

    private fun setupConnectedDrives() {
        val container = binding.driveCardsContainer
        container.removeAllViews()

        val pm = requireContext().packageManager

        // ── 1. Internal storage ────────────────────────────────────────────
        container.addView(makeDriveCard(
            label    = "Internal",
            iconRes  = R.drawable.ic_storage,
            iconTint = "#1A6B3C",
            bgTint   = "#D6F0E3",
            onClick  = {
                val root = Environment.getExternalStorageDirectory().absolutePath
                viewModel.navigate(root)
                findNavController().navigate(R.id.explorerFragment)
            }
        ))

        // ── 2. External volumes (SD cards, OTG, USB) ──────────────────────
        // StorageManager enumerates all mounted volumes. Removable + Mounted
        // volumes are exactly the external drives we want to expose.
        val sm = requireContext().getSystemService(StorageManager::class.java)
        val externalVolumes: List<StorageVolume> = sm.storageVolumes.filter { vol ->
            vol.isRemovable && vol.state == Environment.MEDIA_MOUNTED
        }

        externalVolumes.forEach { vol ->
            val dir = vol.directory ?: return@forEach
            val volPath = dir.absolutePath
            val rawLabel = vol.getDescription(requireContext())

            // Pick an icon tint / background based on a simple heuristic:
            // most OTG thumb-drives show up as "USB" in their description.
            val (iconTint, bgTint) = when {
                rawLabel.contains("usb", ignoreCase = true) ||
                rawLabel.contains("otg", ignoreCase = true) ->
                    "#E65100" to "#FBE9E7"   // orange — USB/OTG
                rawLabel.contains("sd",  ignoreCase = true) ||
                rawLabel.contains("card",ignoreCase = true) ->
                    "#6A1B9A" to "#F3E5F5"   // purple — SD card
                else ->
                    "#1565C0" to "#E3F2FD"   // blue — generic external
            }

            container.addView(makeDriveCard(
                label    = rawLabel,
                iconRes  = R.drawable.ic_storage,
                iconTint = iconTint,
                bgTint   = bgTint,
                onClick  = {
                    viewModel.navigate(volPath)
                    findNavController().navigate(R.id.explorerFragment)
                }
            ))
        }

        // ── 3. SAF cloud providers (installed apps) ────────────────────────
        fun String.normalizeAuthority() = removeSuffix(".legacy")

        val connectedUris = CloudStorageFragment.loadDriveUris(requireContext())
        val connectedByAuthority = connectedUris.associateBy {
            (it.authority ?: "").normalizeAuthority()
        }

        // Enumerate all installed SAF document providers
        val installedAuthorities = mutableSetOf<String>()
        pm.queryIntentContentProviders(
            Intent("android.content.action.DOCUMENTS_PROVIDER"),
            PackageManager.GET_META_DATA
        ).forEach { info: ResolveInfo ->
            installedAuthorities.add(info.providerInfo?.authority ?: return@forEach)
        }

        knownProviders.forEach { provider ->
            val packageInstalled = try {
                pm.getPackageInfo(provider.pkg, 0); true
            } catch (_: Exception) { false }

            val actualAuthority = installedAuthorities.firstOrNull { installed ->
                installed.normalizeAuthority() == provider.authority
            } ?: provider.authority
            val providerInstalled = packageInstalled || actualAuthority in installedAuthorities
            if (!providerInstalled) return@forEach

            val connectedUri = connectedByAuthority[provider.authority]
            container.addView(makeDriveCard(
                label    = provider.label,
                iconRes  = provider.iconRes,
                iconTint = provider.iconTint,
                bgTint   = provider.bgTint,
                onClick  = {
                    if (connectedUri != null) {
                        val args = Bundle().apply { putString("treeUri", connectedUri.toString()) }
                        findNavController().navigate(R.id.action_home_to_cloud, args)
                    } else {
                        val initialUri = try {
                            DocumentsContract.buildRootUri(actualAuthority, provider.rootDocId)
                        } catch (_: Exception) { null }
                        openTreeLauncher.launch(initialUri)
                    }
                }
            ))
        }

        // ── 4. Previously connected SAF drives not matching a known provider
        val knownAuthorities = knownProviders.map { it.authority }.toSet()
        connectedUris
            .filter { (it.authority ?: "").normalizeAuthority() !in knownAuthorities }
            .forEach { uri ->
                val label = CloudStorageFragment.cloudProviderLabel(uri)
                container.addView(makeDriveCard(
                    label    = label,
                    iconRes  = R.drawable.ic_menu_gallery,
                    iconTint = "#6A1B9A",
                    bgTint   = "#F3E5F5",
                    onClick  = {
                        val args = Bundle().apply { putString("treeUri", uri.toString()) }
                        findNavController().navigate(R.id.action_home_to_cloud, args)
                    }
                ))
            }

        // ── 5. Connect button — always present ────────────────────────────
        container.addView(makeDriveCard(
            label    = "Connect",
            iconRes  = R.drawable.ic_add,
            iconTint = "#1A56C4",
            bgTint   = "#DDE9FF",
            onClick  = { findNavController().navigate(R.id.action_home_to_cloud) }
        ))
    }

    // ── Drive card builder ────────────────────────────────────────────────

    private fun makeDriveCard(
        label: String,
        iconRes: Int,
        iconTint: String,
        bgTint: String,
        onClick: () -> Unit
    ): MaterialCardView {
        val dp = resources.displayMetrics.density
        return MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                (88 * dp).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * dp).toInt() }
            radius = 16 * dp
            cardElevation = 0f
            val surfaceAttr = intArrayOf(com.google.android.material.R.attr.colorSurfaceVariant)
            val ta0 = requireContext().theme.obtainStyledAttributes(surfaceAttr)
            setCardBackgroundColor(ta0.getColor(0, 0))
            ta0.recycle()
            strokeWidth = (1 * dp).toInt()
            val strokeAttr = intArrayOf(com.google.android.material.R.attr.colorOutlineVariant)
            val ta1 = requireContext().theme.obtainStyledAttributes(strokeAttr)
            strokeColor = ta1.getColor(0, 0)
            ta1.recycle()
            isClickable = true
            isFocusable = true

            val inner = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                val padH = (10 * dp).toInt()
                val padV = (14 * dp).toInt()
                setPadding(padH, padV, padH, padV)
            }

            val iconFrame = android.widget.FrameLayout(requireContext()).apply {
                val sz = (40 * dp).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz)
                    .also { it.bottomMargin = (8 * dp).toInt() }
                background = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), R.drawable.bg_icon_rounded)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor(bgTint))
            }

            val icon = android.widget.ImageView(requireContext()).apply {
                val sz = (22 * dp).toInt()
                layoutParams = android.widget.FrameLayout.LayoutParams(sz, sz,
                    android.view.Gravity.CENTER)
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor(iconTint))
            }
            iconFrame.addView(icon)

            val tv = android.widget.TextView(requireContext()).apply {
                text = label
                textSize = 11.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(
                    run {
                        val a = intArrayOf(com.google.android.material.R.attr.colorOnSurface)
                        val ta = requireContext().theme.obtainStyledAttributes(a)
                        val c = ta.getColor(0, 0); ta.recycle(); c
                    }
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            inner.addView(iconFrame)
            inner.addView(tv)
            addView(inner)
            setOnClickListener { onClick() }
        }
    }

    // ── File opening ──────────────────────────────────────────────────────

    private fun openFile(item: FileItem) {
        viewModel.recordAccess(item)
        val nav = findNavController()
        when {
            item.isDirectory -> {
                viewModel.navigate(item.path)
                nav.navigate(R.id.action_home_to_explorer)
            }
            item.fileType == FileType.IMAGE ->
                nav.navigate(R.id.action_home_to_image_viewer,
                    Bundle().apply { putString("path", item.path) })
            item.fileType == FileType.AUDIO || item.fileType == FileType.VIDEO ->
                nav.navigate(R.id.action_home_to_media_info,
                    Bundle().apply { putString("mediaPath", item.path) })
            item.fileType == FileType.PDF ->
                nav.navigate(R.id.action_home_to_pdf_viewer,
                    Bundle().apply { putString("pdfPath", item.path) })
            item.fileType == FileType.ARCHIVE &&
                item.extension in setOf("zip","tar","gz","bz2","xz","7z","rar","tgz","tbz2","txz") ->
                nav.navigate(R.id.action_home_to_zip_browser,
                    Bundle().apply { putString("archivePath", item.path) })
            item.fileType == FileType.APK ->
                nav.navigate(R.id.action_home_to_apk_info,
                    Bundle().apply { putString("apkPath", item.path) })
            item.fileType == FileType.CODE || item.fileType == FileType.DOCUMENT -> when {
                item.extension.lowercase() == "epub" ->
                    nav.navigate(R.id.action_home_to_epub_reader,
                        Bundle().apply { putString("epubPath", item.path) })
                FileUtils.isTextFile(item.file) ->
                    nav.navigate(R.id.action_home_to_text_editor,
                        Bundle().apply { putString("filePath", item.path) })
                else -> openWithSystem(item)
            }
            else -> openWithSystem(item)
        }
    }

    private fun openWithSystem(item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(),
                "${requireContext().packageName}.fileprovider", item.file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) { openFileWith(item) }
    }

    private fun openFileWith(item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(),
                "${requireContext().packageName}.fileprovider", item.file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Open with…"))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, "No app found to open this file", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── File context menu ─────────────────────────────────────────────────

    private fun showFileContextMenu(item: FileItem) {
        FileActionsBottomSheet.show(childFragmentManager, item) { actionId ->
            handleFileAction(actionId, item)
        }
    }

    private fun handleFileAction(actionId: Int, item: FileItem) {
        when (actionId) {
            R.id.action_quick_peek ->
                QuickPeekBottomSheet.newInstance(item).show(childFragmentManager, "QuickPeek")
            R.id.action_open      -> openFile(item)
            R.id.action_open_with -> openFileWith(item)
            R.id.action_copy      -> viewModel.copy(listOf(item))
            R.id.action_cut       -> viewModel.cut(listOf(item))
            R.id.action_delete    -> viewModel.trash(listOf(item))
            R.id.action_delete_permanently -> confirmPermanentDelete(item)
            R.id.action_shred     -> confirmShred(item)
            R.id.action_rename    -> RenameDialog(item) { newName ->
                viewModel.rename(item, newName)
            }.show(childFragmentManager, "RenameDialog")
            R.id.action_batch_rename -> BatchRenameDialog(listOf(item)) { template ->
                viewModel.batchRename(listOf(item), template)
            }.show(childFragmentManager, "BatchRenameDialog")
            R.id.action_share     -> shareFile(item)
            R.id.action_bookmark  -> viewModel.toggleBookmark(item)
            R.id.action_compress  -> {
                viewModel.navigate(item.file.parent ?: return)
                CompressDialog(listOf(item)) { outputName ->
                    viewModel.compress(listOf(item), outputName)
                }.show(childFragmentManager, "CompressDialog")
            }
            R.id.action_extract   -> {
                viewModel.navigate(item.file.parent ?: return)
                viewModel.extract(item)
            }
            R.id.action_split     -> showSplitDialog(item)
            R.id.action_details   -> showDetailsDialog(item)
            R.id.action_timestamp -> showTimestampDialog(item)
            R.id.action_checksums ->
                FileHashDialog(item).show(childFragmentManager, "FileHashDialog")
            R.id.action_copy_path -> {
                FileUtils.copyPathToClipboard(requireContext(), item.path)
                Snackbar.make(binding.root, "Path copied", Snackbar.LENGTH_SHORT).show()
            }
            R.id.action_home_shortcut -> pinToHomeScreen(item)
            R.id.action_encrypt ->
                com.radiozport.ninegfiles.ui.dialogs.EncryptFileDialog.show(
                    childFragmentManager, item
                ) { success, msg ->
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                }
            R.id.action_exif ->
                findNavController().navigate(R.id.action_home_to_exif_viewer,
                    Bundle().apply { putString("filePath", item.path) })
            R.id.action_combine ->
                com.radiozport.ninegfiles.ui.dialogs.FileCombineDialog.show(
                    childFragmentManager, item
                ) { success, msg ->
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                }
            R.id.action_wifi_direct_send ->
                findNavController().navigate(R.id.action_home_to_wifi_direct)
        }
    }

    private fun shareFile(item: FileItem) {
        MaterialAlertDialogBuilder(requireContext())
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
                        childFragmentManager, content = item.path, label = item.name)
                }
            }.show()
    }

    private fun showSplitDialog(item: FileItem) {
        val labels  = arrayOf("1 MB", "5 MB", "10 MB", "50 MB", "100 MB")
        val sizeMbs = intArrayOf(1, 5, 10, 50, 100)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Split File: ${item.name}")
            .setItems(labels) { _, which ->
                viewModel.navigate(item.file.parent ?: return@setItems)
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

    private fun showTimestampDialog(item: FileItem) {
        val cal = Calendar.getInstance().apply { timeInMillis = item.lastModified }
        android.app.DatePickerDialog(requireContext(), { _, year, month, day ->
            android.app.TimePickerDialog(requireContext(), { _, hour, minute ->
                cal.set(year, month, day, hour, minute, 0)
                viewModel.changeTimestamp(item, cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun confirmPermanentDelete(item: FileItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete permanently?")
            .setMessage("\"${item.name}\" will be deleted forever.")
            .setPositiveButton("Delete") { _, _ -> viewModel.delete(listOf(item)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmShred(item: FileItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Securely Shred?")
            .setMessage("\"${item.name}\" will be overwritten 3 times then deleted. This cannot be undone.")
            .setPositiveButton("Shred") { _, _ -> viewModel.shred(listOf(item)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinToHomeScreen(item: FileItem) {
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
                .setIcon(android.graphics.drawable.Icon.createWithResource(requireContext(), R.drawable.ic_folder))
                .setIntent(shortcutIntent)
                .build()
            sm.requestPinShortcut(shortcut, null)
            Snackbar.make(binding.root, "Shortcut added to home screen", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.root, "Requires Android 8+", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1048576L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1073741824L -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        _binding = null
    }

    data class CategoryItem(
        val label: String, val iconRes: Int, val count: String, val colorRes: Int,
        val type: FileType, val types: List<FileType> = listOf(type)
    )
}
