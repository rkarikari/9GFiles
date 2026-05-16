package com.radiozport.ninegfiles.ui.epub

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentEpubBuilderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ─── Models ────────────────────────────────────────────────────────────────────

private enum class ChapterState { PENDING, PROCESSING, DONE, ERROR }

private data class ChapterEntry(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    var chapterTitle: String,
    var state: ChapterState = ChapterState.PENDING,
    var pageCount: Int = 0,
    var errorMsg: String? = null
)

private enum class EpubTheme(val label: String, val bg: String, val fg: String, val link: String) {
    LIGHT("Light", "#FFFFFF", "#1a1a1a", "#0066cc"),
    SEPIA("Sepia", "#f4ecd8", "#5c4a37", "#7a5230"),
    DARK ("Dark",  "#1e1e2e", "#cdd6f4", "#89b4fa")
}

private data class BuiltChapter(
    val xhtmlName: String,
    val title: String,
    val imageItems: List<Pair<String, String>>,  // (manifest-id, OEBPS-relative-href)
    val isPdf: Boolean = false,
    val pdfPageCount: Int = 0
)

// ─── Fragment ──────────────────────────────────────────────────────────────────

class EpubBuilderFragment : Fragment() {

    private var _binding: FragmentEpubBuilderBinding? = null
    private val binding get() = _binding!!

    private val chapters       = mutableListOf<ChapterEntry>()
    private var coverUri: Uri? = null
    private var selectedTheme  = EpubTheme.LIGHT
    private var fontSizePt     = 16
    private var isBuilding     = false
    private var lastOutputPath: String? = null

    private val chapterPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) addChapters(uris)
        }
    private val coverPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { applyCover(it) }
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentEpubBuilderBinding.inflate(i, c, false); return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initMetadata(); initChapters(); initStyle(); initBuild(); refreshBuildButton()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    // ── Metadata ───────────────────────────────────────────────────────────────

    private fun initMetadata() {
        val langs = listOf(
            "English (en)", "French (fr)", "German (de)", "Spanish (es)",
            "Portuguese (pt)", "Italian (it)", "Dutch (nl)", "Russian (ru)",
            "Japanese (ja)", "Chinese Simplified (zh)", "Arabic (ar)", "Korean (ko)"
        )
        binding.actvLanguage.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, langs))
        binding.actvLanguage.setText(langs[0], false)

        binding.btnPickCover.setOnClickListener { coverPicker.launch(arrayOf("image/*")) }
        binding.btnRemoveCover.setOnClickListener {
            coverUri?.let { uri ->
                runCatching {
                    requireContext().contentResolver.releasePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            coverUri = null
            // Restore the green icon tint that was cleared in applyCover()
            binding.ivCoverThumb.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.category_document))
            binding.ivCoverThumb.setImageResource(R.drawable.ic_file_image)
            binding.btnRemoveCover.isVisible = false
            binding.tvCoverHint.text = "No cover image selected"
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { refreshBuildButton() }
        }
        binding.etEbookTitle.addTextChangedListener(watcher)
        binding.etEbookAuthor.addTextChangedListener(watcher)
    }

    private fun applyCover(uri: Uri) {
        coverUri = uri
        // Persist read permission so Glide's background thread can still access
        // the content URI after the system file-picker activity has finished.
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        binding.tvCoverHint.text = queryDisplayName(uri)
        binding.btnRemoveCover.isVisible = true
        // The ImageView has app:tint="@color/category_document" (#4CAF50) in the
        // layout, which is correct for the placeholder icon but tints the real photo
        // solid green. Clear it before loading the image; it is restored on remove.
        binding.ivCoverThumb.imageTintList = null
        // Bind Glide to the ImageView (not `this` Fragment) so that the brief
        // onPause/onResume cycle the fragment goes through when returning from
        // the picker does not cancel the image load before it completes.
        Glide.with(binding.ivCoverThumb)
            .load(uri)
            .centerCrop()
            .placeholder(R.drawable.ic_file_image)
            .error(R.drawable.ic_file_image)
            .into(binding.ivCoverThumb)
    }

    // ── Chapters ───────────────────────────────────────────────────────────────

    private fun initChapters() {
        binding.btnAddChapterFiles.setOnClickListener {
            // Only advertise types the builder can actually process.
            // "*/*" is intentionally omitted — it would expose epub and other
            // binary formats that cannot be embedded meaningfully.
            chapterPicker.launch(arrayOf(
                "application/pdf",
                "text/plain", "text/markdown", "text/html",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword",
                "image/*"
            ))
        }
    }

    private fun addChapters(uris: List<Uri>) {
        val skipped = mutableListOf<String>()
        for (uri in uris) {
            val name  = queryDisplayName(uri)
            val mime  = requireContext().contentResolver.getType(uri) ?: guessMime(name)
            if (!isSupportedChapterType(mime, name)) {
                skipped.add(name); continue
            }
            if (chapters.none { it.uri == uri }) {
                val t = name.substringBeforeLast('.').replaceFirstChar { it.uppercase() }
                chapters.add(ChapterEntry(uri, name, mime, t))
            }
        }
        if (skipped.isNotEmpty()) {
            val msg = if (skipped.size == 1)
                "\"${skipped[0]}\" is not a supported type (supported: PDF, TXT, MD, HTML, DOCX, image)"
            else
                "${skipped.size} files skipped — unsupported type (supported: PDF, TXT, MD, HTML, DOCX, image)"
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
        renderChapters(); refreshBuildButton()
    }

    private fun renderChapters() {
        val c = binding.llChapterList; c.removeAllViews()
        binding.tvEmptyChapters.isVisible = chapters.isEmpty()
        val inf = LayoutInflater.from(requireContext())
        chapters.forEachIndexed { i, entry ->
            val row = inf.inflate(R.layout.item_epub_chapter_row, c, false)
            row.findViewById<TextView>(R.id.tvChapterNum).text = "${i + 1}"
            row.findViewById<TextView>(R.id.tvChapterTitle).also { tv ->
                tv.text = entry.chapterTitle
                tv.setOnClickListener { if (!isBuilding) promptRename(i, entry) }
            }
            row.findViewById<TextView>(R.id.tvChapterSource).text =
                "${entry.displayName}  ·  ${mimeLabel(entry.mimeType)}"
            val tvState = row.findViewById<TextView>(R.id.tvChapterState)
            when (entry.state) {
                ChapterState.PENDING     -> tvState.isVisible = false
                ChapterState.PROCESSING  -> { tvState.text = "Processing…"
                    tvState.setTextColor(requireContext().getColor(android.R.color.holo_blue_dark))
                    tvState.isVisible = true }
                ChapterState.DONE        -> {
                    tvState.text = "✓ Done${if (entry.pageCount > 0) "  ${entry.pageCount} pages" else ""}"
                    tvState.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                    tvState.isVisible = true }
                ChapterState.ERROR       -> { tvState.text = "✕ ${entry.errorMsg ?: "Error"}"
                    tvState.setTextColor(requireContext().getColor(android.R.color.holo_red_light))
                    tvState.isVisible = true }
            }
            row.findViewById<ImageButton>(R.id.btnMoveUp).apply {
                isEnabled = i > 0 && !isBuilding; alpha = if (isEnabled) 1f else 0.3f
                setOnClickListener { chapters.add(i-1, chapters.removeAt(i)); renderChapters() }
            }
            row.findViewById<ImageButton>(R.id.btnMoveDown).apply {
                isEnabled = i < chapters.lastIndex && !isBuilding; alpha = if (isEnabled) 1f else 0.3f
                setOnClickListener { chapters.add(i+1, chapters.removeAt(i)); renderChapters() }
            }
            row.findViewById<ImageButton>(R.id.btnRemoveChapter).apply {
                isEnabled = !isBuilding
                setOnClickListener { chapters.removeAt(i); renderChapters(); refreshBuildButton() }
            }
            c.addView(row)
        }
    }

    private fun promptRename(idx: Int, entry: ChapterEntry) {
        val et = EditText(requireContext()).apply { setText(entry.chapterTitle); setSingleLine(); setPadding(48,24,48,8) }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Rename Chapter").setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { chapters[idx].chapterTitle = t; renderChapters() }
            }.setNegativeButton("Cancel", null).show()
    }

    // ── Style ──────────────────────────────────────────────────────────────────

    private fun initStyle() {
        binding.chipGroupTheme.setOnCheckedStateChangeListener { _, ids ->
            selectedTheme = when (ids.firstOrNull()) {
                R.id.chipSepia -> EpubTheme.SEPIA
                R.id.chipDark  -> EpubTheme.DARK
                else           -> EpubTheme.LIGHT
            }
        }
        binding.sliderFontSize.addOnChangeListener { _, v, _ ->
            fontSizePt = v.toInt(); binding.tvFontSizeLabel.text = "${fontSizePt}pt"
        }
        binding.tvFontSizeLabel.text = "${fontSizePt}pt"
    }

    // ── Build ──────────────────────────────────────────────────────────────────

    private fun initBuild() {
        binding.btnBuildEpub.setOnClickListener  { startBuild() }
        binding.btnShareEpub.setOnClickListener  { lastOutputPath?.let { shareEpub(it) } }
        binding.btnOpenEpub.setOnClickListener   { lastOutputPath?.let { openEpub(it)  } }
    }

    private fun refreshBuildButton() {
        val hasTitle = !binding.etEbookTitle.text?.toString()?.trim().isNullOrEmpty()
        binding.btnBuildEpub.isEnabled = hasTitle && chapters.isNotEmpty() && !isBuilding
        binding.tvBuildStatus.text = when {
            isBuilding         -> "Building ePub…"
            !hasTitle          -> "Enter a book title to continue"
            chapters.isEmpty() -> "Add at least one source file"
            else               -> "${chapters.size} chapter${if (chapters.size==1) "" else "s"} ready — tap Build"
        }
    }

    private fun startBuild() {
        isBuilding = true; lastOutputPath = null
        binding.btnBuildEpub.isEnabled = false
        binding.btnShareEpub.isVisible = false; binding.btnOpenEpub.isVisible = false
        binding.progressBar.isVisible = true
        binding.progressBar.max = chapters.size + 3; binding.progressBar.progress = 0

        chapters.forEach { it.state = ChapterState.PENDING; it.errorMsg = null; it.pageCount = 0 }
        renderChapters()

        val title     = binding.etEbookTitle.text?.toString()?.trim() ?: "Untitled"
        val author    = binding.etEbookAuthor.text?.toString()?.trim().orEmpty().ifBlank { "Unknown Author" }
        val langStr   = binding.actvLanguage.text?.toString() ?: "English (en)"
        val lang      = Regex("\\(([a-z]+)\\)").find(langStr)?.groupValues?.get(1) ?: "en"
        val desc      = binding.etEbookDescription.text?.toString()?.trim().orEmpty()
        val publisher = binding.etEbookPublisher.text?.toString()?.trim().orEmpty()
        val cUri      = coverUri
        val theme     = selectedTheme; val fSize = fontSizePt
        val ctx       = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { buildEpub(ctx, title, author, lang, desc, publisher, cUri, theme, fSize) }
            }
            isBuilding = false
            binding.progressBar.isVisible = false
            renderChapters(); refreshBuildButton()
            result.fold(
                onSuccess = { path ->
                    lastOutputPath = path
                    binding.tvBuildStatus.text = "Saved: ${File(path).name}"
                    binding.btnShareEpub.isVisible = true; binding.btnOpenEpub.isVisible = true
                    Snackbar.make(binding.root, "ePub created ✓", Snackbar.LENGTH_LONG).show()
                },
                onFailure = { e ->
                    binding.tvBuildStatus.text = "Failed: ${e.message?.take(80)}"
                    Snackbar.make(binding.root, "Build failed: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            )
        }
    }

    // ─── Core EPUB build (IO thread) ──────────────────────────────────────────

    private suspend fun buildEpub(
        ctx: Context, title: String, author: String, lang: String,
        desc: String, publisher: String, cUri: Uri?,
        theme: EpubTheme, fontSize: Int
    ): String {
        val uid       = UUID.randomUUID().toString()
        val safeTitle = title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50)
        val ts        = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile   = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "${safeTitle}_$ts.epub"
        ).also { it.parentFile?.mkdirs() }
        val tmpDir    = File(ctx.cacheDir, "epubBld_${System.currentTimeMillis()}").also { it.mkdirs() }

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile), 131072)).use { zos ->

                // mimetype — STORED (uncompressed), first entry
                val mt = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                val cr = CRC32().also { it.update(mt) }
                zos.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = mt.size.toLong()
                    compressedSize = mt.size.toLong(); crc = cr.value
                }); zos.write(mt); zos.closeEntry()
                bump()

                zos.text("META-INF/container.xml", CONTAINER_XML)
                bump()

                val built = mutableListOf<BuiltChapter>()

                for ((idx, entry) in chapters.withIndex()) {
                    setState(idx, ChapterState.PROCESSING)
                    val chId = "ch_%03d".format(idx + 1)
                    val xn   = "$chId.xhtml"   // fallback name used only by the error handler
                    val imgs = mutableListOf<Pair<String, String>>()
                    // PDFs write one XHTML per page and push directly to `built`;
                    // all other types write a single XHTML and fall through to the
                    // common built.add() below.
                    var addedToBuilt = false
                    try {
                        when {
                            isPdf(entry)   -> {
                                val pages = renderPdfPages(ctx, entry, chId, zos, tmpDir)
                                entry.pageCount = pages.size
                                // One XHTML file per rendered page keeps each spine item
                                // small.  The reader (EpubReaderFragment) loads one spine
                                // item at a time; putting all pages in a single file would
                                // force it to base64-embed every image at once, producing a
                                // string that can exceed the process heap limit on large PDFs.
                                pages.forEachIndexed { pi, (imgId, imgHref) ->
                                    val pgXn = "${chId}_p%03d.xhtml".format(pi + 1)
                                    val pgTitle = if (pages.size == 1) entry.chapterTitle
                                                  else "${entry.chapterTitle} · p.${pi + 1}"
                                    zos.text("OEBPS/$pgXn",
                                        pdfXhtml(pgTitle, listOf(imgId to imgHref)))
                                    built.add(BuiltChapter(
                                        xhtmlName    = pgXn,
                                        title        = pgTitle,
                                        imageItems   = listOf(imgId to imgHref),
                                        isPdf        = true,
                                        pdfPageCount = 1
                                    ))
                                }
                                addedToBuilt = true
                            }
                            isDocx(entry)  -> zos.text("OEBPS/$xn",
                                textXhtml(entry.chapterTitle, extractDocxText(ctx, entry.uri)))
                            isHtml(entry)  -> zos.text("OEBPS/$xn",
                                htmlXhtml(entry.chapterTitle,
                                    ctx.contentResolver.openInputStream(entry.uri)
                                        ?.bufferedReader()?.readText() ?: ""))
                            isImage(entry) -> {
                                // Derive extension and canonical media-type from what the
                                // ContentResolver or guessMime() already resolved.
                                val ext = when {
                                    entry.mimeType == "image/png"     -> "png"
                                    entry.mimeType == "image/gif"     -> "gif"
                                    entry.mimeType == "image/webp"    -> "webp"
                                    entry.mimeType == "image/svg+xml" -> "svg"
                                    else                              -> "jpg"   // jpeg / fallback
                                }
                                val mediaMime = when (ext) {
                                    "png"  -> "image/png"
                                    "gif"  -> "image/gif"
                                    "webp" -> "image/webp"
                                    "svg"  -> "image/svg+xml"
                                    else   -> "image/jpeg"
                                }
                                val imgId   = "${chId}_img"
                                val imgHref = "images/${imgId}.$ext"
                                // Binary copy — never go through a text reader
                                zos.putNextEntry(ZipEntry("OEBPS/$imgHref"))
                                ctx.contentResolver.openInputStream(entry.uri)
                                    ?.use { it.copyTo(zos) }
                                zos.closeEntry()
                                imgs.add(imgId to imgHref)
                                entry.pageCount = 1
                                zos.text("OEBPS/$xn", imageXhtml(entry.chapterTitle, imgHref, mediaMime))
                            }
                            isMarkdown(entry) -> zos.text("OEBPS/$xn",
                                markdownXhtml(entry.chapterTitle,
                                    ctx.contentResolver.openInputStream(entry.uri)
                                        ?.bufferedReader()?.readText() ?: ""))
                            else           -> zos.text("OEBPS/$xn",
                                textXhtml(entry.chapterTitle,
                                    ctx.contentResolver.openInputStream(entry.uri)
                                        ?.bufferedReader()?.readText() ?: ""))
                        }
                        setState(idx, ChapterState.DONE)
                    } catch (e: Exception) {
                        entry.errorMsg = e.message?.take(60)
                        setState(idx, ChapterState.ERROR)
                        // Write a fallback error page under the base chapter name so
                        // the OPF spine always has a valid entry for this source file.
                        zos.text("OEBPS/$xn", textXhtml(entry.chapterTitle,
                            "[Error: ${entry.displayName}: ${e.message}]"))
                        addedToBuilt = false   // ensure the error entry lands in built
                    }
                    if (!addedToBuilt) {
                        built.add(BuiltChapter(
                            xhtmlName    = xn,
                            title        = entry.chapterTitle,
                            imageItems   = imgs.toList(),
                            isPdf        = false,
                            pdfPageCount = 0
                        ))
                    }
                    bump()
                }

                if (cUri != null) {
                    // Detect the real cover format — never assume JPEG
                    val rawMime  = ctx.contentResolver.getType(cUri) ?: "image/jpeg"
                    val coverExt = when (rawMime) {
                        "image/png"  -> "png"
                        "image/gif"  -> "gif"
                        "image/webp" -> "webp"
                        else         -> "jpg"
                    }
                    val coverMime = when (coverExt) {
                        "png"  -> "image/png"
                        "gif"  -> "image/gif"
                        "webp" -> "image/webp"
                        else   -> "image/jpeg"
                    }
                    val coverFilename = "cover.$coverExt"
                    var coverWritten  = false
                    ctx.contentResolver.openInputStream(cUri)?.use { ins ->
                        zos.putNextEntry(ZipEntry("OEBPS/$coverFilename"))
                        ins.copyTo(zos); zos.closeEntry()
                        coverWritten = true
                    }
                    if (coverWritten) {
                        zos.text("OEBPS/cover.xhtml", buildCoverXhtml(coverFilename))
                        zos.text("OEBPS/style.css",   buildCss(theme, fontSize))
                        zos.text("OEBPS/nav.xhtml",   buildNav(title, built))
                        zos.text("OEBPS/content.opf", buildOpf(title, author, lang, desc, publisher,
                            uid, hasCover = true, chs = built,
                            coverFilename = coverFilename, coverMime = coverMime))
                        bump()
                    } else {
                        // Cover stream was null — build without cover so OPF stays consistent
                        zos.text("OEBPS/style.css",   buildCss(theme, fontSize))
                        zos.text("OEBPS/nav.xhtml",   buildNav(title, built))
                        zos.text("OEBPS/content.opf", buildOpf(title, author, lang, desc, publisher,
                            uid, hasCover = false, chs = built))
                        bump()
                    }
                } else {
                    // No cover selected
                    zos.text("OEBPS/style.css",   buildCss(theme, fontSize))
                    zos.text("OEBPS/nav.xhtml",   buildNav(title, built))
                    zos.text("OEBPS/content.opf", buildOpf(title, author, lang, desc, publisher,
                        uid, hasCover = false, chs = built))
                    bump()
                }
            }   // end ZipOutputStream.use
        } finally { tmpDir.deleteRecursively() }

        return outFile.absolutePath
    }

    private suspend fun bump() = withContext(Dispatchers.Main) {
        binding.progressBar.progress = (binding.progressBar.progress + 1).coerceAtMost(binding.progressBar.max)
    }
    private suspend fun setState(i: Int, s: ChapterState) = withContext(Dispatchers.Main) {
        if (i < chapters.size) { chapters[i].state = s; renderChapters() }
    }

    // ─── PDF rendering ────────────────────────────────────────────────────────

    private fun renderPdfPages(
        ctx: Context, entry: ChapterEntry,
        chId: String, zos: ZipOutputStream, tmpDir: File
    ): List<Pair<String, String>> {
        val tmp = File(tmpDir, "${chId}.pdf")
        ctx.contentResolver.openInputStream(entry.uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        val rnd = PdfRenderer(pfd)
        val out = mutableListOf<Pair<String, String>>()
        try {
            repeat(rnd.pageCount) { pi ->
                val page = rnd.openPage(pi)
                try {
                    val maxW = 1200  // raised from 900 — sharper text and fine lines
                    val scale = if (page.width > maxW) maxW.toFloat()/page.width else 1f
                    val bw   = (page.width * scale).toInt().coerceAtLeast(1)
                    val bh   = (page.height * scale).toInt().coerceAtLeast(1)
                    // PdfRenderer only supports ARGB_8888; RGB_565 throws "unsupported pixel format"
                    val bmp  = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    // "img_" prefix keeps this id distinct from the XHTML chapter id
                    // ("${chId}_p%03d"), which has the same numeric suffix.  Without it,
                    // both the <item> for the XHTML and the <item> for the image land in
                    // the OPF manifest with the same id string; the reader's manifest map
                    // (id → href) lets the image entry overwrite the XHTML entry, so the
                    // spine resolves to the raw JPEG path instead of the XHTML path, and
                    // the reader displays JPEG binary as text — producing garbage pages.
                    val imgId   = "${chId}_img_p%03d".format(pi + 1)
                    val imgHref = "images/${imgId}.jpg"
                    zos.putNextEntry(ZipEntry("OEBPS/$imgHref"))
                    try {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, zos)  // raised from 82 — less text blurring
                    } finally {
                        zos.closeEntry()
                        bmp.recycle()
                    }
                    out.add(imgId to imgHref)
                } finally {
                    page.close()
                }
            }
        } finally { rnd.close(); pfd.close(); tmp.delete() }
        return out
    }

    // ─── DOCX text extraction ─────────────────────────────────────────────────

    private fun extractDocxText(ctx: Context, uri: Uri): String {
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") { parseWordXml(zis, sb); break }
                    zis.closeEntry(); e = zis.nextEntry
                }
            }
        }
        return sb.toString().ifBlank { "(No extractable text)" }
    }

    private fun parseWordXml(stream: InputStream, sb: StringBuilder) {
        val xpp = XmlPullParserFactory.newInstance().newPullParser()
        xpp.setInput(stream, "UTF-8")
        var inP = false; var ev = xpp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val tag = xpp.name ?: ""
            when (ev) {
                XmlPullParser.START_TAG -> when (tag) {
                    "w:p"       -> inP = true
                    "w:t"       -> if (inP) { val t = xpp.nextText(); if (t.isNotEmpty()) sb.append(t) }
                    "w:br","w:cr" -> sb.append("\n")
                }
                XmlPullParser.END_TAG -> if (tag == "w:p") { sb.append("\n"); inP = false }
            }
            ev = xpp.next()
        }
    }

    // ─── XHTML generators ─────────────────────────────────────────────────────

    private fun textXhtml(title: String, text: String): String {
        val paras = text.split(Regex("\n{2,}")).filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${it.trim().lines().joinToString(" ").xe()}</p>" }
        return xDoc(title, "<h1 class=\"chapter-title\">${title.xe()}</h1>\n$paras")
    }

    private fun pdfXhtml(title: String, pages: List<Pair<String,String>>): String {
        // Each page div gets:
        //  • id="page_N"              — anchor target for the nav page-list
        //  • epub:type="page-break"   — ePub3 structural semantics on the div
        //  • <span epub:type="pagebreak"> — the IDPF-recommended inline marker
        //  • aria-label               — accessibility: announces page number
        val imgs = pages.mapIndexed { idx, (_, href) ->
            val pg = idx + 1
            "<div class=\"pdf-page\" id=\"page_$pg\" epub:type=\"page-break\">" +
            "<span epub:type=\"pagebreak\" id=\"pg$pg\" aria-label=\"Page $pg\"/>" +
            "<img src=\"$href\" alt=\"Page $pg\" " +
            "style=\"max-width:100%;height:auto;display:block;margin:0 auto;\"/>" +
            "</div>"
        }.joinToString("\n")
        return xDoc(title, "<h1 class=\"chapter-title\">${title.xe()}</h1>\n$imgs")
    }

    private fun imageXhtml(title: String, imgHref: String, mediaMime: String): String {
        // SVG: wrap in <object> with <img> fallback for wider reader compatibility
        val body = if (mediaMime == "image/svg+xml") {
            "<div class=\"page\"><object type=\"image/svg+xml\" data=\"$imgHref\" " +
            "style=\"max-width:100%;height:auto;display:block;margin:0 auto;\">" +
            "<img src=\"$imgHref\" alt=\"${title.xe()}\" style=\"max-width:100%;height:auto;\"/>" +
            "</object></div>"
        } else {
            "<div class=\"page\"><img src=\"$imgHref\" alt=\"${title.xe()}\" " +
            "style=\"max-width:100%;height:auto;display:block;margin:0 auto;\"/></div>"
        }
        return xDoc(title, "<h1 class=\"chapter-title\">${title.xe()}</h1>\n$body")
    }

    private fun htmlXhtml(title: String, raw: String): String {
        val body = Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1) ?: raw
        return xDoc(title, "<h1 class=\"chapter-title\">${title.xe()}</h1>\n$body")
    }

    /**
     * Converts a Markdown source file to a valid XHTML ePub chapter.
     *
     * Uses the CommonMark reference parser (org.commonmark:commonmark), which is
     * already on the classpath as a transitive dependency of the Markwon library.
     * This produces the same semantic HTML that MarkdownViewerFragment renders in
     * the standalone viewer, so the ePub chapter matches the in-app preview.
     */
    private fun markdownXhtml(title: String, markdownText: String): String {
        val parser   = Parser.builder().build()
        val renderer = HtmlRenderer.builder()
            .escapeHtml(false)   // preserve any intentional inline HTML in the .md file
            .build()
        val html = renderer.render(parser.parse(markdownText))
        return xDoc(title, "<h1 class=\"chapter-title\">${title.xe()}</h1>\n$html")
    }

    private fun xDoc(title: String, body: String) = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"
      xmlns:epub="http://www.idpf.org/2007/ops"
      xml:lang="en">
<head><title>${title.xe()}</title><link rel="stylesheet" href="style.css" type="text/css"/></head>
<body>
$body
</body>
</html>"""

    // ─── Cover page XHTML ─────────────────────────────────────────────────────

    private fun buildCoverXhtml(coverFilename: String = "cover.jpg") = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">
<head>
  <title>Cover</title>
  <style>html,body{margin:0;padding:0;background:#000;}
div{display:flex;align-items:center;justify-content:center;height:100vh;}
img{max-width:100%;max-height:100vh;object-fit:contain;}</style>
</head>
<body epub:type="cover">
  <div><img src="$coverFilename" alt="Cover" epub:type="cover-image"/></div>
</body>
</html>"""

    // ─── CSS ──────────────────────────────────────────────────────────────────

    private fun buildCss(t: EpubTheme, fs: Int) = """
/* 9GFiles ePub — ${t.label} */
body{background:${t.bg};color:${t.fg};font-family:Georgia,'Times New Roman',serif;font-size:${fs}pt;line-height:1.78;max-width:38em;margin:0 auto;padding:1.2em 1.8em}
h1,h2,h3,h4,h5,h6{font-family:'Helvetica Neue',Helvetica,Arial,sans-serif;font-weight:600;color:${t.fg};line-height:1.3}
h1{font-size:1.9em;margin:.5em 0 1em}
h2{font-size:1.45em;margin:1.2em 0 .6em;border-bottom:1px solid ${t.link}44;padding-bottom:.2em}
h3{font-size:1.15em;margin:1em 0 .4em}
p{margin:.45em 0;text-indent:1.6em}
p:first-of-type,h1+p,h2+p,h3+p{text-indent:0}
a{color:${t.link};text-decoration:underline}
.chapter-title{text-indent:0;font-size:1.05em;border-bottom:1px solid ${t.link}88;padding-bottom:.25em;margin-bottom:.9em}
/* .page — generic image/single-image chapters */
.page{text-align:center;margin:.5em 0;page-break-inside:avoid}
/* .pdf-page — one rendered PDF page per ePub screen:
   page-break-after forces paginated readers to display exactly one PDF page
   per "screen page"; break-after is the CSS3 equivalent understood by
   more modern reading systems (Kobo, Thorium, Apple Books). */
.pdf-page{text-align:center;margin:0;padding:0;
  page-break-inside:avoid;page-break-after:always;
  break-inside:avoid;break-after:page}
.pdf-page:last-child{page-break-after:auto;break-after:auto}
.pdf-page img{max-width:100%;height:auto;display:block;margin:0 auto}
img{max-width:100%;height:auto;display:block;margin:.5em auto}
pre,code{font-family:'Courier New',monospace;font-size:.87em;background:rgba(128,128,128,.12);padding:.2em .45em;border-radius:3px}
pre{padding:.8em;overflow-x:auto}
blockquote{border-left:4px solid ${t.link};margin:1em 0 1em .5em;padding:.3em .9em;opacity:.88}
table{border-collapse:collapse;width:100%;margin:1em 0;font-size:.93em}
th,td{border:1px solid rgba(128,128,128,.30);padding:.38em .65em}
th{background:rgba(128,128,128,.14);font-weight:600}
""".trimIndent()

    // ─── nav.xhtml ────────────────────────────────────────────────────────────

    private fun buildNav(bookTitle: String, chs: List<BuiltChapter>): String {
        // ── Page-list: sequential global page numbers pointing into PDF chapters ──
        // This <nav epub:type="page-list"> block lets conforming ePub reading systems
        // (e.g. Apple Books, Kobo, Thorium) show a "Go to page N" control that maps
        // to the rendered PDF pages.  Only chapters converted from PDF contribute
        // entries; text/image chapters are skipped.
        var globalPage = 1
        val pageListItems = buildString {
            for (ch in chs) {
                if (!ch.isPdf || ch.pdfPageCount == 0) continue
                for (localPage in 1..ch.pdfPageCount) {
                    appendLine("      <li><a href=\"${ch.xhtmlName}#pg$localPage\">${globalPage++}</a></li>")
                }
            }
        }.trimEnd()

        val pageListNav = if (pageListItems.isNotEmpty()) {
            "\n  <nav epub:type=\"page-list\" aria-label=\"List of Pages\">\n    <ol>\n$pageListItems\n    </ol>\n  </nav>"
        } else ""

        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">
<head><title>Table of Contents</title><link rel="stylesheet" href="style.css" type="text/css"/></head>
<body>
  <nav epub:type="toc" id="toc" aria-label="Table of Contents">
    <h1>Table of Contents — ${bookTitle.xe()}</h1>
    <ol>
${chs.joinToString("\n") { "      <li><a href=\"${it.xhtmlName}\">${it.title.xe()}</a></li>" }}
    </ol>
  </nav>$pageListNav
</body>
</html>"""
    }

    // ─── content.opf ─────────────────────────────────────────────────────────

    private fun buildOpf(
        title: String, author: String, lang: String,
        desc: String, publisher: String, uid: String,
        hasCover: Boolean, chs: List<BuiltChapter>,
        coverFilename: String = "cover.jpg",
        coverMime: String     = "image/jpeg"
    ): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val mf = buildString {
            appendLine("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
            appendLine("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
            if (hasCover) {
                appendLine("    <item id=\"cover-image\" href=\"$coverFilename\" media-type=\"$coverMime\" properties=\"cover-image\"/>")
                appendLine("    <item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>")
            }
            for (ch in chs) {
                val cid = ch.xhtmlName.removeSuffix(".xhtml")
                appendLine("    <item id=\"$cid\" href=\"${ch.xhtmlName}\" media-type=\"application/xhtml+xml\"/>")
                for ((iid, ihref) in ch.imageItems) {
                    val imgMime = when {
                        ihref.endsWith(".png",  true) -> "image/png"
                        ihref.endsWith(".gif",  true) -> "image/gif"
                        ihref.endsWith(".webp", true) -> "image/webp"
                        ihref.endsWith(".svg",  true) -> "image/svg+xml"
                        else                          -> "image/jpeg"
                    }
                    appendLine("    <item id=\"$iid\" href=\"$ihref\" media-type=\"$imgMime\"/>")
                }
            }
        }.trimEnd()
        val coverSpine = if (hasCover) "    <itemref idref=\"cover\" linear=\"yes\"/>\n" else ""
        val sp = coverSpine + chs.joinToString("\n") { "    <itemref idref=\"${it.xhtmlName.removeSuffix(".xhtml")}\"/>" }
        val extra = buildString {
            if (desc.isNotBlank())      appendLine("\n    <dc:description>${desc.xe()}</dc:description>")
            if (publisher.isNotBlank()) appendLine("    <dc:publisher>${publisher.xe()}</dc:publisher>")
            if (hasCover)               appendLine("    <meta name=\"cover\" content=\"cover-image\"/>")
        }.trimEnd()
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"
         version="3.0" unique-identifier="uid">
  <metadata>
    <dc:identifier id="uid">urn:uuid:$uid</dc:identifier>
    <dc:title>${title.xe()}</dc:title>
    <dc:creator>${author.xe()}</dc:creator>
    <dc:language>$lang</dc:language>$extra
    <meta property="dcterms:modified">$now</meta>
    <meta name="generator">9GFiles ePub Builder</meta>
  </metadata>
  <manifest>
$mf
  </manifest>
  <spine>
$sp
  </spine>
</package>"""
    }

    // ─── Share / Open ─────────────────────────────────────────────────────────

    private fun shareEpub(path: String) {
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", File(path))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/epub+zip"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share ePub"))
    }

    private fun openEpub(path: String) {
        // Navigate to the app's built-in EpubReaderFragment instead of firing an
        // external Intent, so the freshly-built ePub opens inside 9GFiles.
        runCatching {
            val args = android.os.Bundle().apply { putString("epubPath", path) }
            findNavController().navigate(R.id.action_epub_builder_to_epub_reader, args)
        }.onFailure {
            // Fallback: if nav fails for any reason open externally
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/epub+zip")
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(intent, "Open ePub with…")) }
                .onFailure {
                    Snackbar.make(binding.root, "No ePub reader found", Snackbar.LENGTH_SHORT).show()
                }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun queryDisplayName(uri: Uri): String =
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            c.moveToFirst(); if (ni >= 0) c.getString(ni) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun guessMime(n: String) = when {
        n.endsWith(".pdf",  true) -> "application/pdf"
        n.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        n.endsWith(".html", true) || n.endsWith(".htm", true) -> "text/html"
        n.endsWith(".md",   true) -> "text/markdown"
        n.endsWith(".jpg",  true) || n.endsWith(".jpeg", true) -> "image/jpeg"
        n.endsWith(".png",  true) -> "image/png"
        n.endsWith(".gif",  true) -> "image/gif"
        n.endsWith(".webp", true) -> "image/webp"
        n.endsWith(".svg",  true) -> "image/svg+xml"
        else -> "text/plain"
    }

    private fun isPdf(e: ChapterEntry)   = e.mimeType == "application/pdf" || e.displayName.endsWith(".pdf", true)
    private fun isDocx(e: ChapterEntry)  = e.mimeType.contains("wordprocessingml") ||
        e.mimeType == "application/msword" ||
        e.displayName.endsWith(".docx", true) || e.displayName.endsWith(".doc", true)
    private fun isHtml(e: ChapterEntry)  = e.mimeType.startsWith("text/html") || e.displayName.endsWith(".html", true) || e.displayName.endsWith(".htm", true)
    private fun isMarkdown(e: ChapterEntry) = e.mimeType == "text/markdown" ||
        e.mimeType == "text/x-markdown" ||
        e.displayName.endsWith(".md", true) || e.displayName.endsWith(".markdown", true)
    private fun isImage(e: ChapterEntry) = e.mimeType.startsWith("image/") ||
        e.displayName.run { endsWith(".jpg", true) || endsWith(".jpeg", true) ||
            endsWith(".png", true) || endsWith(".gif", true) ||
            endsWith(".webp", true) || endsWith(".svg", true) }

    /** Returns true only for types the build pipeline can meaningfully embed. */
    private fun isSupportedChapterType(mime: String, name: String): Boolean {
        // Anything epub/zip/archive-based cannot be embedded — it would appear as garbage
        if (mime == "application/epub+zip" || name.endsWith(".epub", true)) return false
        if (mime.startsWith("application/zip") || mime.startsWith("application/x-zip")) return false
        return mime == "application/pdf" ||
               mime.startsWith("text/") ||
               mime.contains("wordprocessingml") ||
               mime == "application/msword" ||
               mime.startsWith("image/") ||
               name.run {
                   endsWith(".pdf",  true) || endsWith(".docx", true) ||
                   endsWith(".doc",  true) || endsWith(".txt",  true) ||
                   endsWith(".md",   true) || endsWith(".html", true) ||
                   endsWith(".htm",  true) || endsWith(".jpg",  true) ||
                   endsWith(".jpeg", true) || endsWith(".png",  true) ||
                   endsWith(".gif",  true) || endsWith(".webp", true) ||
                   endsWith(".svg",  true)
               }
    }

    private fun mimeLabel(mime: String) = when {
        mime.contains("pdf")              -> "PDF"
        mime.contains("wordprocessingml") -> "DOCX"
        mime.contains("html")             -> "HTML"
        mime.contains("markdown")         -> "Markdown"
        mime.startsWith("image/")         -> "Image"
        mime.contains("text")             -> "Text"
        else                              -> "Document"
    }

    private fun String.xe() = replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        .replace("\"","&quot;").replace("'","&apos;")

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name)); write(content.toByteArray(Charsets.UTF_8)); closeEntry()
    }

    companion object {
        private const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf"
              media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
    }
}
