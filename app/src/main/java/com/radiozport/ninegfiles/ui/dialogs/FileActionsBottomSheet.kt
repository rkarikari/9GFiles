package com.radiozport.ninegfiles.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Material 3 bottom sheet that replaces the plain PopupMenu for file context actions.
 *
 * Usage:
 *   FileActionsBottomSheet.show(childFragmentManager, item) { actionId ->
 *       handleContextMenuAction(actionId, item)
 *   }
 */
class FileActionsBottomSheet : BottomSheetDialogFragment() {

    interface ActionListener {
        fun onAction(actionId: Int)
    }

    private var listener: ActionListener? = null
    private lateinit var item: FileItem

    companion object {
        fun newInstance(
            item: FileItem,
            listener: ActionListener
        ): FileActionsBottomSheet = FileActionsBottomSheet().apply {
            this.item = item
            this.listener = listener
        }

        fun show(
            fm: androidx.fragment.app.FragmentManager,
            item: FileItem,
            onAction: (Int) -> Unit
        ) {
            newInstance(item, object : ActionListener {
                override fun onAction(actionId: Int) = onAction(actionId)
            }).show(fm, "FileActions")
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                bytes < 1024 -> "$bytes B"
                kb < 1024    -> "${"%.1f".format(kb)} KB"
                mb < 1024    -> "${"%.1f".format(mb)} MB"
                else         -> "${"%.2f".format(gb)} GB"
            }
        }

        private fun formatDate(timestamp: Long): String =
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_file_actions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Header ──────────────────────────────────────────────────────────
        view.findViewById<ImageView>(R.id.ivFileIcon).setImageResource(item.fileType.iconRes)
        view.findViewById<TextView>(R.id.tvFileName).text = item.name
        view.findViewById<TextView>(R.id.tvFileMeta).text = buildString {
            if (!item.isDirectory) {
                append(formatSize(item.size))
                append(" · ")
            }
            append(formatDate(item.lastModified))
        }

        // ── Helper: bind a row ───────────────────────────────────────────────
        fun bind(rowId: Int, iconRes: Int, label: String, actionId: Int, visible: Boolean = true) {
            val row = view.findViewById<View>(rowId)
            row.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) return
            row.findViewById<ImageView>(R.id.ivActionIcon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.tvActionLabel).text = label
            row.setOnClickListener { listener?.onAction(actionId); dismiss() }
        }

        // ── Group 1: Primary ─────────────────────────────────────────────────
        bind(R.id.actionQuickPeek, R.drawable.ic_file_generic, "Quick Look", R.id.action_quick_peek)
        bind(R.id.actionOpen,     R.drawable.ic_folder_open, "Open",       R.id.action_open)
        bind(R.id.actionOpenWith, R.drawable.ic_file_apk, "Open with…", R.id.action_open_with)
        bind(R.id.actionShare,    R.drawable.ic_share,       "Share",      R.id.action_share)
        bind(R.id.actionWifiDirect, R.drawable.ic_share,    "Send via Wi-Fi Direct…", R.id.action_wifi_direct_send, visible = !item.isDirectory)

        val isPrintable = !item.isDirectory && item.extension.lowercase() in
            setOf("pdf", "jpg", "jpeg", "png", "webp", "heic", "heif",
                  "tiff", "tif", "gif", "bmp", "txt", "md", "epub")
        bind(R.id.actionPrint, R.drawable.ic_print, "Print", R.id.action_print, visible = isPrintable)

        // ── Group 2: Edit ────────────────────────────────────────────────────
        bind(R.id.actionCopy,   R.drawable.ic_content_copy, "Copy",       R.id.action_copy)
        bind(R.id.actionCut,    R.drawable.ic_content_cut,  "Cut (Move)", R.id.action_cut)
        bind(R.id.actionRename, R.drawable.ic_edit,         "Rename",     R.id.action_rename)

        // ── Group 3: Archive / Split / Combine / Encrypt ─────────────────────
        val isArchive    = item.extension in listOf("zip", "rar", "7z", "tar", "gz")
        val isSplittable = !item.isDirectory && item.size > 0
        val isCombinePart = item.name.matches(Regex(".*\\.part\\d+$"))
        val isImage = item.extension.lowercase() in
                setOf("jpg", "jpeg", "png", "heic", "heif", "tiff", "tif", "webp", "dng", "raw")

        bind(R.id.actionCompress, R.drawable.ic_file_archive, "Compress to ZIP", R.id.action_compress)
        bind(R.id.actionExtract,  R.drawable.ic_file_archive, "Extract Here",    R.id.action_extract, visible = isArchive)
        bind(R.id.actionSplit,    R.drawable.ic_content_cut,  "Split File…",     R.id.action_split,   visible = isSplittable)
        bind(R.id.actionCombine,  R.drawable.ic_content_paste,"Combine Parts…",  R.id.action_combine, visible = isCombinePart)
        bind(R.id.actionEncrypt,  R.drawable.ic_lock_silent_mode_off,
            if (com.radiozport.ninegfiles.utils.EncryptionUtils.isEncrypted(item.file)) "Decrypt File…" else "Encrypt File…",
            R.id.action_encrypt,  visible = !item.isDirectory)
        bind(R.id.actionExif,     R.drawable.ic_file_image,   "View EXIF…",      R.id.action_exif,    visible = isImage)

        // ── Group 4: Info / Utility ──────────────────────────────────────────
        bind(R.id.actionProperties, R.drawable.ic_tune,        "Properties",          R.id.action_details)
        bind(R.id.actionChecksums,  R.drawable.ic_check,       "Checksums / Verify…", R.id.action_checksums)
        bind(R.id.actionCopyPath,   R.drawable.ic_content_copy,"Copy Path",           R.id.action_copy_path)
        bind(R.id.actionTimestamp,  R.drawable.ic_history,     "Change Timestamp…",   R.id.action_timestamp)

        // ── Group 5: Bookmark / Pin ──────────────────────────────────────────
        val bookmarkLabel = if (item.isBookmarked) "Remove Bookmark" else "Add Bookmark"
        bind(R.id.actionBookmark, R.drawable.ic_bookmark,        bookmarkLabel,        R.id.action_bookmark)
        bind(R.id.actionPin,      R.drawable.ic_home, "Pin to Home Screen", R.id.action_home_shortcut)

        // ── Group 6: Delete (destructive — tinted red) ───────────────────────
        fun bindDestructive(rowId: Int, iconRes: Int, label: String, actionId: Int) {
            bind(rowId, iconRes, label, actionId)
            view.findViewById<View>(rowId).apply {
                findViewById<ImageView>(R.id.ivActionIcon).imageTintList =
                    context.getColorStateList(R.color.md_theme_error)
                findViewById<TextView>(R.id.tvActionLabel)
                    .setTextColor(context.getColor(R.color.md_theme_error))
            }
        }

        bindDestructive(R.id.actionTrash,             R.drawable.ic_delete, "Move to Trash",           R.id.action_delete)
        bindDestructive(R.id.actionDeletePermanently, R.drawable.ic_delete, "Delete Permanently",      R.id.action_delete_permanently)
        bindDestructive(R.id.actionShred,             R.drawable.ic_delete, "Securely Shred (3-pass)", R.id.action_shred)
    }
}
