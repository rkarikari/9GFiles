package com.radiozport.ninegfiles.ui.viewer

import android.os.Bundle
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.radiozport.ninegfiles.utils.EncryptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Simple ePub reader.  An ePub is just a ZIP file whose OPF manifest lists
 * the chapter HTML/XHTML files in spine order.  We parse the OPF with the
 * standard XML DOM parser (no library needed), extract each chapter's HTML,
 * inject a legible CSS reset, embed all images as base64 data-URIs (so they
 * render correctly without needing a real base URL), and display in a WebView.
 *
 * For study-guide / Q&A ePubs (detected by the presence of amateur-radio-style
 * question IDs such as T1A01), the chapter HTML is further transformed into a
 * structured card layout matching the proper display format.
 */
class EpubReaderFragment : Fragment() {

    companion object {
        const val ARG_PATH = "epubPath"

        /**
         * Default key used to open `.9genc` encrypted ePub files.
         * Decryption is performed entirely in memory — no plaintext file
         * is ever written to disk or exposed outside this process.
         */
        private const val DEFAULT_DECRYPT_KEY = "radiosport"

        fun newInstance(path: String) = EpubReaderFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

    data class Chapter(val title: String, val entryName: String, val basePath: String)

    private lateinit var webView:     WebView
    private lateinit var tvTitle:     TextView
    private lateinit var tvProgress:  TextView
    private lateinit var btnPrev:     MaterialButton
    private lateinit var btnNext:     MaterialButton
    private lateinit var btnChapters: MaterialButton
    private lateinit var progressBar: ProgressBar

    private var chapters:        List<Chapter> = emptyList()
    private var currentIdx:      Int = 0
    private var zipFile:         ZipFile? = null
    /**
     * Populated instead of [zipFile] when the source is a `.9genc` file.
     * All ZIP entries are decrypted once into this map (entry path → raw bytes)
     * and live only in process memory for the lifetime of this fragment.
     */
    private var epubEntries:     Map<String, ByteArray>? = null
    private var epubTitle:       String = "eBook"
    private var chapterLoadJob:  Job? = null   // cancellable per-chapter load

    // ---------- lifecycle -----------------------------------------------

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        // ── top bar ──────────────────────────────────────────────────────
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ctx.getColor(com.radiozport.ninegfiles.R.color.md_theme_surface))
            layoutParams = LinearLayout.LayoutParams(-1, (56 * dp).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }

        val btnBack = ImageButton(ctx).apply {
            setImageResource(com.radiozport.ninegfiles.R.drawable.ic_close)
            background = ctx.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).getDrawable(0)
            contentDescription = "Close"
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
        }

        tvTitle = TextView(ctx).apply {
            text = "Loading…"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = (8 * dp).toInt()
            }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        btnChapters = MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Chapters"
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (4 * dp).toInt() }
        }

        val btnPrint = ImageButton(ctx).apply {
            setImageResource(com.radiozport.ninegfiles.R.drawable.ic_print)
            background = ctx.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).getDrawable(0)
            contentDescription = "Print chapter"
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            setOnClickListener { printCurrentChapter() }
        }

        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        topBar.addView(btnChapters)
        topBar.addView(btnPrint)
        root.addView(topBar)

        // ── divider ──────────────────────────────────────────────────────
        root.addView(View(ctx).apply {
            setBackgroundColor(ctx.getColor(com.radiozport.ninegfiles.R.color.divider))
            layoutParams = LinearLayout.LayoutParams(-1, 1)
        })

        // ── progress bar (loading) ───────────────────────────────────────
        progressBar = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(progressBar)

        // ── web view ─────────────────────────────────────────────────────
        webView = WebView(ctx).apply {
            settings.javaScriptEnabled   = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                /**
                 * Intercept every link click inside the WebView.
                 *
                 * Because we load HTML via loadDataWithBaseURL() with the synthetic
                 * base "file:///android_asset/", any relative href in the content
                 * (e.g. <a href="ch02.xhtml">) is resolved by the WebView to
                 * "file:///android_asset/ch02.xhtml" — a path that does not exist
                 * on disk, so the WebView raises ERR_FILE_NOT_FOUND.
                 *
                 * Here we strip the base prefix back off, match the remainder
                 * against our chapter list, and jump to the correct chapter
                 * programmatically instead of letting the WebView navigate.
                 */
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    // Drop any #fragment so we match the file path cleanly
                    val urlNoFragment = url.substringBefore("#")

                    if (urlNoFragment.startsWith("file:///android_asset/")) {
                        // e.g. "file:///android_asset/ch02.xhtml"
                        //   or "file:///android_asset/OEBPS/Text/ch02.xhtml"
                        val linkedFile = urlNoFragment.removePrefix("file:///android_asset/")

                        // Match against the chapter's ZIP entry path:
                        //   exact match  : "OEBPS/Text/ch02.xhtml" == linkedFile
                        //   suffix match : any entry whose last component(s) match
                        val idx = chapters.indexOfFirst { ch ->
                            ch.entryName == linkedFile ||
                            ch.entryName.endsWith("/$linkedFile")
                        }
                        if (idx >= 0) {
                            showChapter(idx)
                            return true   // consumed — do NOT let WebView navigate
                        }
                    }
                    // For any other URL (http/https, etc.) fall through to default
                    return super.shouldOverrideUrlLoading(view, request)
                }
            }
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(webView)

        // ── bottom nav bar ───────────────────────────────────────────────
        val navBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setBackgroundColor(ctx.getColor(com.radiozport.ninegfiles.R.color.md_theme_surface))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        btnPrev = MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "◀  Prev"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * dp).toInt() }
        }

        tvProgress = TextView(ctx).apply {
            text = "- / -"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

        btnNext = MaterialButton(ctx).apply {
            text = "Next  ▶"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (8 * dp).toInt() }
        }

        navBar.addView(btnPrev)
        navBar.addView(tvProgress)
        navBar.addView(btnNext)
        root.addView(navBar)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString(ARG_PATH) ?: run {
            Toast.makeText(requireContext(), "No ePub path", Toast.LENGTH_SHORT).show()
            return
        }

        btnPrev.setOnClickListener { if (currentIdx > 0) showChapter(currentIdx - 1) }
        btnNext.setOnClickListener { if (currentIdx < chapters.size - 1) showChapter(currentIdx + 1) }

        btnChapters.setOnClickListener {
            if (chapters.isEmpty()) return@setOnClickListener
            val titles = chapters.mapIndexed { i, c ->
                if (c.title.isNotBlank()) c.title else "Chapter ${i + 1}"
            }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Chapters")
                .setItems(titles) { _, which -> showChapter(which) }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { parseEpub(path) }
            if (!isAdded) return@launch
            progressBar.isVisible = false
            if (parsed == null) {
                tvTitle.text = "Could not read ePub"
                return@launch
            }
            chapters = parsed
            tvTitle.text = epubTitle
            if (chapters.isNotEmpty()) showChapter(0)
        }
    }

    // ---------- rendering -----------------------------------------------

    /**
     * Loads a chapter into the WebView.  Runs IO work (HTML + image reads)
     * on a background thread; any previous in-flight load is cancelled first.
     */
    private fun showChapter(idx: Int) {
        currentIdx = idx
        chapterLoadJob?.cancel()

        // Disable nav buttons while loading to prevent double-taps
        btnPrev.isEnabled = false
        btnNext.isEnabled = false
        progressBar.isVisible = true

        chapterLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val ch = chapters[idx]

            // Read + process entirely on IO so the main thread is never blocked
            val finalHtml = withContext(Dispatchers.IO) {
                val raw = readEntry(ch.entryName) ?: "<p>Could not load chapter.</p>"
                // Inline every resource the WebView cannot reach inside the ZIP:
                // first CSS (so the ePub's own styles render correctly), then images.
                val withCss    = embedStylesheets(raw, ch.basePath)
                val withImages = embedImages(withCss, ch.basePath)
                ensureReadableDefaults(withImages)
            }

            if (!isAdded) return@launch

            progressBar.isVisible = false
            // Use file:///android_asset/ as a safe, neutral base URL.
            // All images are already embedded as data-URIs so no relative
            // URL resolution is needed by the WebView.
            webView.loadDataWithBaseURL(
                "file:///android_asset/", finalHtml, "text/html", "UTF-8", null
            )

            tvProgress.text  = "${idx + 1} / ${chapters.size}"
            btnPrev.isEnabled = idx > 0
            btnNext.isEnabled = idx < chapters.size - 1
        }
    }

    // ── Stylesheet embedding ─────────────────────────────────────────────────

    /**
     * Finds every <link rel="stylesheet" href="..."> in [html], reads the CSS
     * file from the ZIP (relative to [chapterBasePath]), and replaces the <link>
     * with an inline <style> block.  This is the same approach used for images:
     * the WebView cannot resolve ZIP-internal URLs, so we must inline the resource.
     */
    private fun embedStylesheets(html: String, chapterBasePath: String): String {
        // Matches <link> tags that carry both rel="stylesheet" and an href, in any attribute order.
        val linkRegex = Regex(
            """<link\b[^>]*\brel=["']stylesheet["'][^>]*>|<link\b[^>]*\bhref=["'][^"']+["'][^>]*\brel=["']stylesheet["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val hrefAttr = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        return linkRegex.replace(html) { match ->
            val href = hrefAttr.find(match.value)?.groupValues?.get(1)
                ?: return@replace match.value

            // Leave external stylesheets for the WebView (it can reach the internet)
            if (href.startsWith("http://", ignoreCase = true) ||
                href.startsWith("https://", ignoreCase = true)) {
                return@replace match.value
            }

            val zipPath = resolvePath(chapterBasePath, href)
            val css     = readEntry(zipPath) ?: return@replace match.value
            "<style>\n$css\n</style>"
        }
    }

    /**
     * Appends a minimal safety-net <style> block so that chapters with no
     * bundled stylesheet (or a very sparse one) are still readable.  The rules
     * use low specificity so the ePub's own styles always win.
     */
    private fun ensureReadableDefaults(html: String): String {
        val defaults = """
            <style>
              body { font-family: serif; font-size: 18px; line-height: 1.7;
                     padding: 16px; max-width: 720px; margin: 0 auto; word-wrap: break-word; }
              img  { display: block; max-width: 100%; height: auto; margin: 8px auto; }
            </style>
        """.trimIndent()
        return if (html.contains("<head>", ignoreCase = true))
            html.replaceFirst(Regex("(?i)<head>"), "<head>\n$defaults")
        else
            "<!DOCTYPE html><html><head>$defaults</head><body>$html</body></html>"
    }

    // ── Image embedding ──────────────────────────────────────────────────────

    /**
     * Finds every <img src="..."> (and <image href/xlink:href for SVG/EPUB3)
     * in [html], reads the corresponding ZIP entry relative to [chapterBasePath],
     * and replaces the src with a base64 data-URI so the WebView can display it
     * without needing a resolvable base URL.
     */
    private fun embedImages(html: String, chapterBasePath: String): String {
        // Match src="..." and href="..." / xlink:href="..." on img / image elements.
        // The regex is intentionally broad; we guard against non-image hrefs below.
        val imgSrcRegex = Regex(
            """(<(?:img|image)[^>]*?\s)(?:src|href|xlink:href)=(["'])([^"']+)\2""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        return imgSrcRegex.replace(html) { match ->
            val prefix = match.groupValues[1]   // "<img " or "<image "
            val quote  = match.groupValues[2]   // " or '
            val src    = match.groupValues[3]

            // Skip already-embedded data URIs and absolute http(s) URLs
            if (src.startsWith("data:", ignoreCase = true) ||
                src.startsWith("http://", ignoreCase = true) ||
                src.startsWith("https://", ignoreCase = true)) {
                return@replace match.value
            }

            val zipPath = resolvePath(chapterBasePath, src)
            val bytes   = readEntryBytes(zipPath) ?: return@replace match.value

            val mime = mimeForPath(zipPath)
            val b64  = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            // Reconstruct the tag attribute with the data-URI
            "${prefix}src=$quote data:$mime;base64,$b64 $quote"
        }
    }

    /**
     * Resolves [href] relative to [basePath] inside the ZIP, handling ".." segments.
     * Examples:
     *   resolvePath("OEBPS/Text", "../Images/fig1.png") → "OEBPS/Images/fig1.png"
     *   resolvePath("OEBPS",      "Images/fig1.png")    → "OEBPS/Images/fig1.png"
     *   resolvePath("",           "Images/fig1.png")    → "Images/fig1.png"
     */
    private fun resolvePath(basePath: String, href: String): String {
        // Absolute path within the ZIP
        if (href.startsWith("/")) return href.trimStart('/')
        val combined = if (basePath.isEmpty()) href else "$basePath/$href"
        val segments = combined.split("/")
        val result   = mutableListOf<String>()
        for (seg in segments) {
            when {
                seg == ".."      -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                seg == "." || seg.isEmpty() -> { /* skip */ }
                else             -> result.add(seg)
            }
        }
        return result.joinToString("/")
    }

    private fun mimeForPath(path: String): String = when {
        path.endsWith(".png",  ignoreCase = true) -> "image/png"
        path.endsWith(".gif",  ignoreCase = true) -> "image/gif"
        path.endsWith(".svg",  ignoreCase = true) -> "image/svg+xml"
        path.endsWith(".webp", ignoreCase = true) -> "image/webp"
        path.endsWith(".bmp",  ignoreCase = true) -> "image/bmp"
        else                                       -> "image/jpeg"
    }

    // ── ZIP helpers ──────────────────────────────────────────────────────────

    /**
     * Reads a ZIP entry as a UTF-8 string.
     * For encrypted ePubs the entry is sourced from [epubEntries] (already in
     * memory); for plain ePubs it is streamed from the on-disk [zipFile].
     */
    private fun readEntry(entryName: String): String? {
        epubEntries?.get(entryName)?.let { return it.toString(Charsets.UTF_8) }
        return try {
            zipFile?.getEntry(entryName)?.let { entry ->
                zipFile!!.getInputStream(entry).use { it.reader(Charsets.UTF_8).readText() }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Reads a ZIP entry as raw bytes.
     * Same dual-source logic as [readEntry].
     */
    private fun readEntryBytes(entryName: String): ByteArray? {
        epubEntries?.get(entryName)?.let { return it }
        return try {
            zipFile?.getEntry(entryName)?.let { entry ->
                zipFile!!.getInputStream(entry).use { it.readBytes() }
            }
        } catch (_: Exception) { null }
    }

    // ---------- parsing -------------------------------------------------

    /**
     * Entry point for ePub parsing.  Automatically detects whether [path]
     * is a plain `.epub` or an encrypted `.9genc` file and delegates to the
     * appropriate loading strategy.
     *
     * - **Plain `.epub`**: opened as a [ZipFile] (on-disk, random-access).
     * - **`.9genc`**: decrypted in-memory via [EncryptionUtils.decryptToBytes]
     *   with [DEFAULT_DECRYPT_KEY]; the resulting ZIP bytes are expanded into
     *   [epubEntries] and never written to any file.
     */
    private suspend fun parseEpub(path: String): List<Chapter>? {
        return if (path.endsWith(EncryptionUtils.ENCRYPTED_EXT, ignoreCase = true)) {
            parseEncryptedEpub(path)
        } else {
            parsePlainEpub(path)
        }
    }

    /**
     * Opens a plain `.epub` file using [ZipFile] and parses its OPF manifest.
     */
    private fun parsePlainEpub(path: String): List<Chapter>? {
        return try {
            val zf = ZipFile(File(path))
            zipFile = zf
            parseOpf { entryName ->
                zf.getEntry(entryName)?.let { entry ->
                    zf.getInputStream(entry).use { it.readBytes() }
                }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Decrypts [path] (a `.9genc` file) entirely in memory using
     * [DEFAULT_DECRYPT_KEY], loads every ZIP entry into [epubEntries], then
     * parses the OPF manifest from that in-memory map.
     *
     * No plaintext data is ever written to disk or exposed outside this process.
     */
    private suspend fun parseEncryptedEpub(path: String): List<Chapter>? {
        val decryptedBytes = EncryptionUtils.decryptToBytes(File(path), DEFAULT_DECRYPT_KEY)
            ?: return null   // bad magic, wrong key, or I/O error
        val entries = loadZipEntries(decryptedBytes)
        if (entries.isEmpty()) return null
        epubEntries = entries
        return parseOpf { entryName -> entries[entryName] }
    }

    /**
     * Reads all entries from a ZIP held in [bytes] into a [Map].
     * This is the mechanism that keeps decrypted content exclusively in memory.
     */
    private fun loadZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[entry.name] = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) { /* return whatever was read so far */ }
        return map
    }

    /**
     * Core OPF parsing logic shared by both the plain and encrypted paths.
     *
     * @param getBytes Lambda that retrieves a ZIP entry's raw bytes by name;
     *                 returns `null` when the entry is absent.
     */
    private fun parseOpf(getBytes: (String) -> ByteArray?): List<Chapter>? {
        return try {
            // 1. Locate container.xml → find the OPF path
            val containerXml = getBytes("META-INF/container.xml") ?: return null
            val containerDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(containerXml.inputStream())
            val rootfileEl = containerDoc.getElementsByTagName("rootfile").item(0) as? Element
                ?: return null
            val opfPath = rootfileEl.getAttribute("full-path")
            val opfBase = opfPath.substringBeforeLast("/", "")

            // 2. Parse the OPF
            val opfXml = getBytes(opfPath) ?: return null
            val opfDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(opfXml.inputStream())

            // Book title
            opfDoc.getElementsByTagName("dc:title").item(0)?.textContent?.let {
                epubTitle = it
            }

            // 3. Build id → href manifest
            val manifest = mutableMapOf<String, String>()
            val items    = opfDoc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val el = items.item(i) as? Element ?: continue
                manifest[el.getAttribute("id")] = el.getAttribute("href")
            }

            // 4. Follow the spine
            val result     = mutableListOf<Chapter>()
            val spineItems = opfDoc.getElementsByTagName("itemref")
            for (i in 0 until spineItems.length) {
                val idref = (spineItems.item(i) as? Element)?.getAttribute("idref") ?: continue
                val href  = manifest[idref] ?: continue
                val fullPath = if (opfBase.isNotEmpty()) "$opfBase/$href" else href
                result.add(Chapter(
                    title     = "Chapter ${result.size + 1}",
                    entryName = fullPath,
                    basePath  = fullPath.substringBeforeLast("/", "")
                ))
            }

            result.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    // ---------- printing ------------------------------------------------

    private fun printCurrentChapter() {
        val printManager = requireContext()
            .getSystemService(android.content.Context.PRINT_SERVICE)
            as? android.print.PrintManager
            ?: run {
                android.widget.Toast.makeText(requireContext(),
                    "Print not available on this device",
                    android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        val chapterTitle = chapters.getOrNull(currentIdx)?.title
            ?.takeIf { it.isNotBlank() } ?: "Chapter ${currentIdx + 1}"
        val jobName = "$epubTitle – $chapterTitle"
        // The WebView already has the current chapter loaded; hand its adapter
        // straight to PrintManager so Android handles pagination automatically.
        val adapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, adapter,
            android.print.PrintAttributes.Builder().build())
    }

    override fun onDestroyView() {
        chapterLoadJob?.cancel()
        webView.destroy()
        super.onDestroyView()
    }

    override fun onDestroy() {
        try { zipFile?.close() } catch (_: Exception) {}
        // Eagerly release decrypted content so the GC can reclaim it promptly
        epubEntries = null
        super.onDestroy()
    }
}
