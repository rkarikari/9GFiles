package com.radiozport.ninegfiles.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.FileItem
import com.radiozport.ninegfiles.data.model.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central file-opening router shared by all fragments that open files.
 *
 * ## `.9genc` dispatch
 *
 * When the tapped file has the `.9genc` extension the inner extension
 * (the extension immediately before `.9genc`) determines which reader is used:
 *
 * | Inner ext             | Reader                                        |
 * |-----------------------|-----------------------------------------------|
 * | `epub`                | [EpubReaderFragment]     (in-memory decrypt)  |
 * | `pdf`                 | [PdfViewerFragment]      (temp-file decrypt)  |
 * | `docx`, `doc`, `odt`  | [DocxViewerFragment]     (in-memory decrypt)  |
 * | `md`                  | [MarkdownViewerFragment] (in-memory decrypt)  |
 * | `txt`, `html`, `xml`, | [TextEditorFragment]     (in-memory decrypt)  |
 * | `json`, `kt`, …       |                                               |
 * | `xlsx`, `pptx`, …     | System viewer via temp-file decryption        |
 * | *(anything else)*     | System viewer via temp-file decryption        |
 *
 * Only `9GEK` (device-key) encrypted files are auto-opened; `9GEF`
 * (password-based) files surface an error — the user must decrypt them
 * first via the Secure Vault.
 *
 * ## Temp-file lifecycle
 * Decrypted temp files are written to [Context.cacheDir]/`9genc_tmp/`
 * and named `<original_name_without_9genc>`.  Callers are NOT responsible
 * for cleanup — the directory is cleared at next app launch by [purgeTempDir].
 */
object FileOpener {

    private const val TMP_DIR = "9genc_tmp"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens [item] in the most appropriate reader/viewer.
     *
     * Must be called from the main thread; background work is dispatched
     * internally via [fragment]'s [lifecycleScope].
     *
     * @param fragment      The calling fragment (provides context, lifecycle, snackbar root).
     * @param navController Navigation controller scoped to the root nav graph.
     * @param item          The file to open.
     * @param snackbarRoot  View used to anchor any error [Snackbar]s.
     */
    fun open(
        fragment: Fragment,
        navController: NavController,
        item: FileItem,
        snackbarRoot: android.view.View,
    ) {
        if (item.is9genc) {
            openEncrypted(fragment, navController, item, snackbarRoot)
        } else {
            openPlain(fragment, navController, item, snackbarRoot)
        }
    }

    /**
     * Deletes all files in the temp-decrypt directory.
     * Call once at app startup (e.g. from [Application.onCreate]).
     */
    fun purgeTempDir(context: Context) {
        try {
            File(context.cacheDir, TMP_DIR).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { /* best-effort */ }
    }

    // ── Plain (non-encrypted) routing ─────────────────────────────────────────

    fun openPlain(
        fragment: Fragment,
        navController: NavController,
        item: FileItem,
        snackbarRoot: android.view.View,
    ) {
        when (item.fileType) {
            FileType.IMAGE ->
                navController.navigate(R.id.imageViewerFragment,
                    android.os.Bundle().apply { putString("path", item.path) })
            FileType.AUDIO, FileType.VIDEO ->
                navController.navigate(R.id.mediaInfoFragment,
                    android.os.Bundle().apply { putString("mediaPath", item.path) })
            FileType.PDF ->
                navController.navigate(R.id.pdfViewerFragment,
                    android.os.Bundle().apply { putString("pdfPath", item.path) })
            FileType.ARCHIVE -> {
                val supported = item.extension in
                    setOf("zip","tar","gz","bz2","xz","7z","rar","tgz","tbz2","txz")
                if (supported)
                    navController.navigate(R.id.zipBrowserFragment,
                        android.os.Bundle().apply { putString("archivePath", item.path) })
                else openWithSystem(fragment, item, snackbarRoot)
            }
            FileType.APK ->
                navController.navigate(R.id.apkInfoFragment,
                    android.os.Bundle().apply { putString("apkPath", item.path) })
            FileType.EBOOK ->
                navController.navigate(R.id.epubReaderFragment,
                    android.os.Bundle().apply { putString("epubPath", item.path) })
            FileType.CODE, FileType.DOCUMENT, FileType.SPREADSHEET, FileType.PRESENTATION -> {
                when (item.extension.lowercase()) {
                    // ── DOCX: in-app viewer ──────────────────────────────────
                    "docx", "doc", "odt" ->
                        navController.navigate(R.id.docxViewerFragment,
                            android.os.Bundle().apply { putString("docxPath", item.path) })
                    // ── Markdown: dedicated renderer via Markwon ─────────────
                    "md" ->
                        navController.navigate(R.id.markdownViewerFragment,
                            android.os.Bundle().apply { putString("mdPath", item.path) })
                    // ── Plain text / code ────────────────────────────────────
                    else ->
                        if (FileUtils.isTextFile(item.file))
                            navController.navigate(R.id.textEditorFragment,
                                android.os.Bundle().apply { putString("filePath", item.path) })
                        else openWithSystem(fragment, item, snackbarRoot)
                }
            }
            else -> openWithSystem(fragment, item, snackbarRoot)
        }
    }

    // ── Encrypted (.9genc) routing ────────────────────────────────────────────

    private fun openEncrypted(
        fragment: Fragment,
        navController: NavController,
        item: FileItem,
        snackbarRoot: android.view.View,
    ) {
        val file = item.file

        // Check encryption format synchronously from the magic header (tiny read)
        val format = EncryptionUtils.detectFormat(file)
        if (format == EncryptionUtils.EncryptionFormat.PASSWORD_BASED) {
            Snackbar.make(snackbarRoot,
                "This file is password-encrypted. Decrypt it via the Secure Vault first.",
                Snackbar.LENGTH_LONG).show()
            return
        }
        if (format == null) {
            Snackbar.make(snackbarRoot,
                "Unknown encryption format — cannot open this file.",
                Snackbar.LENGTH_LONG).show()
            return
        }

        // 9GEK — dispatch by inner extension
        val innerExt = EncryptionUtils.innerExtension(file)

        when {
            // ── ePub: in-memory decrypt; EpubReaderFragment handles it natively
            innerExt == "epub" ->
                navController.navigate(R.id.epubReaderFragment,
                    android.os.Bundle().apply { putString("epubPath", item.path) })

            // ── PDF: decrypt to temp file; PdfViewerFragment handles it natively
            innerExt == "pdf" ->
                navController.navigate(R.id.pdfViewerFragment,
                    android.os.Bundle().apply { putString("pdfPath", item.path) })

            // ── DOCX: in-app viewer decrypts in-memory
            innerExt in setOf("docx", "doc", "odt") ->
                navController.navigate(R.id.docxViewerFragment,
                    android.os.Bundle().apply { putString("docxPath", item.path) })

            // ── Markdown: dedicated Markwon renderer; decrypts in-memory
            innerExt == "md" ->
                navController.navigate(R.id.markdownViewerFragment,
                    android.os.Bundle().apply { putString("mdPath", item.path) })

            // ── Text-family: in-memory decrypt; TextEditorFragment handles it
            innerExt in setOf("txt","html","htm","xml","json","yaml","yml",
                               "csv","log","kt","java","py","js","ts","cpp","c",
                               "h","cs","go","rb","php","swift","rs") ->
                navController.navigate(R.id.textEditorFragment,
                    android.os.Bundle().apply { putString("filePath", item.path) })

            // ── Office / everything else: decrypt to temp file → open with system
            else ->
                openEncryptedViaSystem(fragment, item, innerExt, snackbarRoot)
        }
    }

    /**
     * Decrypts [item] to a temp file then fires a system `ACTION_VIEW` intent.
     *
     * Used for file types that have no internal viewer (docx, xlsx, pptx, odt, …).
     * The temp file is given the correct inner extension so the system can identify
     * an appropriate app.
     */
    private fun openEncryptedViaSystem(
        fragment: Fragment,
        item: FileItem,
        innerExt: String,
        snackbarRoot: android.view.View,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val ctx = fragment.requireContext()

            // Derive the decrypted file's name (strip .9genc suffix)
            val decryptedName = item.file.name
                .removeSuffix(EncryptionUtils.ENCRYPTED_EXT)
                .removeSuffix(EncryptionUtils.ENCRYPTED_EXT.uppercase())

            val tmpDir  = File(ctx.cacheDir, TMP_DIR).also { it.mkdirs() }
            val tmpFile = File(tmpDir, decryptedName)

            val ok = withContext(Dispatchers.IO) {
                EncryptionUtils.decryptDeviceToTempFile(
                    source              = item.file,
                    tempFile            = tmpFile,
                    sessionKeyDecryptor = { DeviceKeyManager.decryptSessionKey(it) }
                )
            }

            if (!isFragmentActive(fragment)) return@launch

            if (!ok) {
                Snackbar.make(snackbarRoot,
                    "Could not decrypt — file may be encrypted for a different device.",
                    Snackbar.LENGTH_LONG).show()
                return@launch
            }

            // Determine MIME from the inner extension
            val mimeRaw = com.radiozport.ninegfiles.data.model.MimeTypeHelper.getMimeType(innerExt)
            val mime = if (mimeRaw.isNotEmpty()) mimeRaw else "application/octet-stream"

            try {
                val uri = FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", tmpFile)
                ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: ActivityNotFoundException) {
                Snackbar.make(snackbarRoot,
                    "No app found to open .$innerExt files.",
                    Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // ── System open (plain files) ─────────────────────────────────────────────

    fun openWithSystem(
        fragment: Fragment,
        item: FileItem,
        snackbarRoot: android.view.View,
    ) {
        try {
            val ctx = fragment.requireContext()
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", item.file)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: ActivityNotFoundException) {
            openWithChooser(fragment, item, snackbarRoot)
        }
    }

    fun openWithChooser(
        fragment: Fragment,
        item: FileItem,
        snackbarRoot: android.view.View,
    ) {
        try {
            val ctx = fragment.requireContext()
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", item.file)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Open with…").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            Snackbar.make(snackbarRoot,
                "No app available to open this file.",
                Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isFragmentActive(fragment: Fragment): Boolean =
        fragment.isAdded && !fragment.isDetached && fragment.view != null
}
