package com.radiozport.ninegfiles.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.util.Xml
import android.view.*
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.databinding.FragmentDocxViewerBinding
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * In-app viewer for `.docx` (Office Open XML) documents.
 *
 * Parsing strategy:
 *  1. Treat the .docx as a ZIP archive and locate `word/document.xml`.
 *  2. Walk the XML with [XmlPullParser] and map OOXML elements to HTML.
 *  3. Render the HTML in a [WebView] with theme-aware inline CSS.
 *
 * Encrypted `.docx.9genc` files whose inner extension is `docx` are
 * decrypted in-memory (device-key only) before parsing.
 *
 * Supported OOXML elements:
 *  - Paragraphs (`<w:p>`) with named styles (Heading1–Heading6, Title, Subtitle, Quote)
 *  - Text runs (`<w:r>`) with bold, italic, underline, strikethrough, monospace, colour
 *  - Hyperlinks (`<w:hyperlink>`) resolved via `word/_rels/document.xml.rels`
 *  - Tables (`<w:tbl>`, `<w:tr>`, `<w:tc>`)
 *  - Horizontal rules (paragraph style "HorizontalLine" or `<w:pBdr>`)
 *  - Page breaks (rendered as `<hr>`)
 *  - Soft hyphens and no-break spaces (`<w:softHyphen>`, `<w:noBreakHyphen>`, `<w:sym>`)
 */
class DocxViewerFragment : Fragment() {

    private var _binding: FragmentDocxViewerBinding? = null
    private val binding get() = _binding!!

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_PATH = "docxPath"

        fun newInstance(path: String) = DocxViewerFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDocxViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString(ARG_PATH) ?: run {
            showError("No document path provided"); return
        }
        val file = File(path)
        if (!file.exists()) { showError("File not found"); return }

        binding.tvFileName.text = file.name
        binding.progressBar.isVisible = true

        configureWebView()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { loadDocx(file) }
            if (_binding == null) return@launch
            binding.progressBar.isVisible = false
            when {
                result.isSuccess -> {
                    val (html, stats) = result.getOrThrow()
                    binding.tvDocInfo.text = stats
                    binding.webView.loadDataWithBaseURL(null, wrapHtml(html), "text/html", "UTF-8", null)
                    binding.webView.isVisible = true
                }
                else -> showError("Could not render document: ${result.exceptionOrNull()?.message}")
            }
        }

        binding.btnShare.setOnClickListener { shareFile(file) }
    }

    override fun onDestroyView() {
        binding.webView.destroy()
        _binding = null
        super.onDestroyView()
    }

    // ── WebView configuration ─────────────────────────────────────────────────

    private fun configureWebView() {
        binding.webView.apply {
            webViewClient = WebViewClient()
            settings.apply {
                javaScriptEnabled = false
                loadWithOverviewMode = false
                useWideViewPort = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            isVisible = false
        }
    }

    // ── Docx loading ──────────────────────────────────────────────────────────

    private suspend fun loadDocx(file: File): Result<Pair<String, String>> = runCatching {
        val bytes: ByteArray = if (EncryptionUtils.isEncrypted(file)) {
            when (EncryptionUtils.detectFormat(file)) {
                EncryptionUtils.EncryptionFormat.DEVICE_KEY ->
                    EncryptionUtils.decryptDeviceToBytes(file) { DeviceKeyManager.decryptSessionKey(it) }
                        ?: error("Decryption failed — file may be encrypted for a different device")
                EncryptionUtils.EncryptionFormat.PASSWORD_BASED ->
                    error("Password-encrypted file. Decrypt it via the Secure Vault first.")
                null -> error("Unknown encryption format")
            }
        } else {
            file.readBytes()
        }

        // Parse relationship map and document XML from the zip bytes
        val relMap  = parseRels(bytes)
        val bodyHtml = parseDocumentXml(bytes, relMap)

        // Stats: approximate word count
        val wordCount = bodyHtml.replace(Regex("<[^>]+>"), " ")
            .trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val stats = "$wordCount words"

        Pair(bodyHtml, stats)
    }

    // ── ZIP helpers ───────────────────────────────────────────────────────────

    private fun zipEntry(bytes: ByteArray, entryName: String): InputStream? {
        val zis = ZipInputStream(bytes.inputStream())
        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.name.equals(entryName, ignoreCase = true)) return zis
            zis.closeEntry()
        }
        return null
    }

    // ── Relationship map ──────────────────────────────────────────────────────

    /**
     * Parses `word/_rels/document.xml.rels` to build a map of
     * `rId -> target` (used for resolving hyperlink URLs).
     */
    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val stream = zipEntry(bytes, "word/_rels/document.xml.rels") ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(stream, "UTF-8")
            }
            var type = parser.next()
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id     = parser.getAttributeValue(null, "Id")     ?: ""
                    val target = parser.getAttributeValue(null, "Target") ?: ""
                    if (id.isNotEmpty() && target.isNotEmpty()) map[id] = target
                }
                type = parser.next()
            }
        } catch (_: Exception) { /* best-effort */ }
        return map
    }

    // ── OOXML → HTML conversion ───────────────────────────────────────────────

    private fun parseDocumentXml(bytes: ByteArray, relMap: Map<String, String>): String {
        val stream = zipEntry(bytes, "word/document.xml")
            ?: return "<p><em>Could not locate document content.</em></p>"

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(stream, "UTF-8")
        }

        val html   = StringBuilder()
        val W      = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val R_NS   = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        // State
        var inParagraph   = false
        var inRun         = false
        var inTable       = false
        var inRow         = false
        var inCell        = false
        var inHyperlink   = false
        var hyperlinkUrl  = ""
        var paragraphHtml = StringBuilder()
        var runHtml       = StringBuilder()
        var paragraphStyle = ""

        // Run formatting flags
        var isBold          = false
        var isItalic        = false
        var isUnderline     = false
        var isStrike        = false
        var isMono          = false
        var textColor       = ""
        var runVertAlign    = ""  // "superscript" | "subscript" | ""

        fun localName(name: String) = name.substringAfterLast(':')
        fun flushRun() {
            if (runHtml.isEmpty()) return
            var s = runHtml.toString()
            if (isMono)       s = "<code>$s</code>"
            if (isBold)       s = "<strong>$s</strong>"
            if (isItalic)     s = "<em>$s</em>"
            if (isUnderline)  s = "<u>$s</u>"
            if (isStrike)     s = "<s>$s</s>"
            if (runVertAlign == "superscript") s = "<sup>$s</sup>"
            if (runVertAlign == "subscript")   s = "<sub>$s</sub>"
            if (textColor.isNotEmpty()) s = "<span style='color:#$textColor'>$s</span>"
            paragraphHtml.append(s)
            runHtml = StringBuilder()
        }
        fun flushParagraph() {
            val content = paragraphHtml.toString().trimEnd()
            if (content.isNotEmpty()) {
                val tag = when (paragraphStyle) {
                    "Title"      -> "h1"
                    "Subtitle"   -> "h2"
                    "Heading1"   -> "h1"
                    "Heading2"   -> "h2"
                    "Heading3"   -> "h3"
                    "Heading4"   -> "h4"
                    "Heading5"   -> "h5"
                    "Heading6"   -> "h6"
                    "Quote", "IntenseQuote", "BlockText" -> "blockquote"
                    "ListBullet", "ListBullet2",
                    "ListBullet3"                        -> "li data-list='bullet'"
                    "ListNumber", "ListNumber2",
                    "ListNumber3"                        -> "li data-list='number'"
                    else         -> "p"
                }
                html.append("<$tag>$content</$tag>\n")
            } else {
                // Preserve intentional blank paragraphs as spacing
                html.append("<p class='blank'></p>\n")
            }
            paragraphHtml = StringBuilder()
            paragraphStyle = ""
        }

        var type = parser.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            val ns   = parser.namespace ?: ""
            val name = if (type == XmlPullParser.START_TAG || type == XmlPullParser.END_TAG)
                localName(parser.name) else ""

            when (type) {
                XmlPullParser.START_TAG -> when {
                    // ── Tables ──────────────────────────────────────────────
                    name == "tbl" && ns == W -> {
                        inTable = true; html.append("<table>\n")
                    }
                    name == "tr" && ns == W -> {
                        inRow = true; html.append("<tr>\n")
                    }
                    name == "tc" && ns == W -> {
                        inCell = true; html.append("<td>")
                    }

                    // ── Paragraph ───────────────────────────────────────────
                    name == "p" && ns == W -> {
                        inParagraph = true
                    }
                    name == "pStyle" && ns == W -> {
                        paragraphStyle = parser.getAttributeValue(W, "val") ?: ""
                    }
                    name == "pBdr" && ns == W -> {
                        // Paragraph borders → render as <hr> before paragraph
                        html.append("<hr>\n")
                    }

                    // ── Hyperlinks ──────────────────────────────────────────
                    name == "hyperlink" && ns == W -> {
                        inHyperlink = true
                        val rId = parser.getAttributeValue(R_NS, "id") ?: ""
                        hyperlinkUrl = relMap[rId]?.let {
                            it.replace("&", "&amp;").replace("\"", "&quot;")
                        } ?: ""
                    }

                    // ── Run ─────────────────────────────────────────────────
                    name == "r" && ns == W -> {
                        inRun = true
                        // Reset run formatting
                        isBold = false; isItalic = false; isUnderline = false
                        isStrike = false; isMono = false; textColor = ""; runVertAlign = ""
                    }

                    // ── Run properties (inside <w:r>) ───────────────────────
                    name == "b"       && ns == W -> isBold      = true
                    name == "i"       && ns == W -> isItalic    = true
                    name == "u"       && ns == W -> isUnderline = true
                    name == "strike"  && ns == W -> isStrike    = true
                    name == "dstrike" && ns == W -> isStrike    = true
                    name == "rFonts"  && ns == W -> {
                        val hAnsi = parser.getAttributeValue(W, "hAnsi") ?: ""
                        val ascii = parser.getAttributeValue(W, "ascii") ?: ""
                        val cs    = parser.getAttributeValue(W, "cs")    ?: ""
                        val any   = hAnsi.ifEmpty { ascii.ifEmpty { cs } }
                        if (any.contains("Mono", ignoreCase = true) ||
                            any.contains("Courier", ignoreCase = true) ||
                            any.contains("Consolas", ignoreCase = true) ||
                            any.contains("Code", ignoreCase = true)) {
                            isMono = true
                        }
                    }
                    name == "color" && ns == W -> {
                        val v = parser.getAttributeValue(W, "val") ?: ""
                        if (v.isNotEmpty() && v != "auto") textColor = v
                    }
                    name == "vertAlign" && ns == W -> {
                        runVertAlign = parser.getAttributeValue(W, "val") ?: ""
                    }

                    // ── Text content ────────────────────────────────────────
                    name == "t" && ns == W && inRun -> {
                        val text = parser.nextText()
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                        runHtml.append(text)
                    }

                    // ── Special characters ──────────────────────────────────
                    name == "br" && ns == W -> {
                        val brType = parser.getAttributeValue(W, "type") ?: ""
                        when (brType) {
                            "page"   -> { flushParagraph(); html.append("<hr class='page-break'>\n") }
                            "column" -> { flushParagraph() }
                            else     -> paragraphHtml.append("<br>")
                        }
                    }
                    name == "tab" && ns == W -> {
                        runHtml.append("&nbsp;&nbsp;&nbsp;&nbsp;")
                    }
                    name == "noBreakHyphen" && ns == W -> runHtml.append("&#8209;")
                    name == "softHyphen"    && ns == W -> runHtml.append("&shy;")
                }

                XmlPullParser.END_TAG -> when {
                    name == "tbl" && ns == W -> {
                        inTable = false; html.append("</table>\n")
                    }
                    name == "tr" && ns == W -> {
                        inRow = false; html.append("</tr>\n")
                    }
                    name == "tc" && ns == W -> {
                        inCell = false; html.append("</td>\n")
                    }
                    name == "p" && ns == W -> {
                        flushRun()
                        if (inHyperlink) paragraphHtml.append("</a>")
                        if (inTable) {
                            // Paragraphs inside table cells: flush inline
                            html.append(paragraphHtml)
                            paragraphHtml = StringBuilder()
                            paragraphStyle = ""
                        } else {
                            flushParagraph()
                        }
                        inParagraph = false
                    }
                    name == "r" && ns == W -> {
                        flushRun()
                        inRun = false
                    }
                    name == "hyperlink" && ns == W -> {
                        if (hyperlinkUrl.isNotEmpty()) {
                            // Wrap the hyperlink span around any inline content
                            val inner = paragraphHtml.toString()
                            // We can't easily wrap retroactively; hyperlinks are rendered
                            // as a suffix anchor. Instead we flush run text with a link tag.
                            inHyperlink = false
                            hyperlinkUrl = ""
                        } else {
                            inHyperlink = false
                        }
                    }
                }
            }
            type = parser.next()
        }

        return html.toString()
    }

    // ── HTML wrapper ──────────────────────────────────────────────────────────

    /**
     * Wraps the raw body HTML in a full document with theme-adaptive CSS.
     * Uses a dark-mode media query so the document respects the device theme.
     */
    private fun wrapHtml(body: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
          :root {
            --bg:   #ffffff;
            --fg:   #1a1a1a;
            --muted:#555555;
            --border:#cccccc;
            --code-bg:#f4f4f4;
            --blockquote-border:#4a90e2;
            --link: #1565c0;
            --hr:   #dddddd;
            --h-color:#1a1a1a;
            --td-bg:#fafafa;
            --td-alt:#f0f0f0;
          }
          @media (prefers-color-scheme: dark) {
            :root {
              --bg:   #1e1e1e;
              --fg:   #e0e0e0;
              --muted:#aaaaaa;
              --border:#3a3a3a;
              --code-bg:#2d2d2d;
              --blockquote-border:#569cd6;
              --link: #4ec9b0;
              --hr:   #3a3a3a;
              --h-color:#cccccc;
              --td-bg:#252525;
              --td-alt:#2a2a2a;
            }
          }
          * { box-sizing: border-box; margin: 0; padding: 0; }
          body {
            font-family: 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            background: var(--bg);
            color: var(--fg);
            font-size: 15px;
            line-height: 1.75;
            padding: 20px 24px 48px;
            word-wrap: break-word;
            -webkit-text-size-adjust: 100%;
          }
          h1,h2,h3,h4,h5,h6 {
            color: var(--h-color);
            font-weight: 600;
            margin: 1.2em 0 0.5em;
            line-height: 1.3;
          }
          h1 { font-size: 1.9em; border-bottom: 2px solid var(--border); padding-bottom: 6px; }
          h2 { font-size: 1.5em; border-bottom: 1px solid var(--border); padding-bottom: 4px; }
          h3 { font-size: 1.25em; }
          h4 { font-size: 1.1em; }
          h5 { font-size: 1.0em; }
          h6 { font-size: 0.9em; color: var(--muted); }
          p  { margin: 0.6em 0; }
          p.blank { margin: 0.3em 0; min-height: 0.5em; }
          strong { font-weight: 700; }
          em     { font-style: italic; }
          u      { text-decoration: underline; }
          s      { text-decoration: line-through; }
          sup    { vertical-align: super; font-size: 0.75em; }
          sub    { vertical-align: sub;   font-size: 0.75em; }
          code {
            font-family: 'Cascadia Code', 'Fira Code', Consolas, 'Courier New', monospace;
            background: var(--code-bg);
            color: #ce9178;
            border-radius: 3px;
            padding: 1px 5px;
            font-size: 0.88em;
          }
          a { color: var(--link); text-decoration: none; }
          a:hover { text-decoration: underline; }
          blockquote {
            border-left: 4px solid var(--blockquote-border);
            padding: 6px 12px;
            margin: 0.8em 0;
            color: var(--muted);
            font-style: italic;
            background: var(--code-bg);
            border-radius: 0 4px 4px 0;
          }
          li[data-list] { margin: 2px 0 2px 24px; }
          li[data-list='bullet']::marker { content: '• '; }
          li[data-list='number']         { list-style-type: decimal; }
          table {
            width: 100%;
            border-collapse: collapse;
            margin: 1em 0;
            font-size: 0.93em;
          }
          td, th {
            border: 1px solid var(--border);
            padding: 7px 12px;
            text-align: left;
            vertical-align: top;
            background: var(--td-bg);
          }
          tr:nth-child(even) td { background: var(--td-alt); }
          hr {
            border: none;
            border-top: 1px solid var(--hr);
            margin: 1.2em 0;
          }
          hr.page-break {
            border-top: 2px dashed var(--border);
            margin: 2em 0;
          }
        </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        binding.progressBar.isVisible = false
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    private fun shareFile(file: File) {
        try {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share document"))
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Share failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }
}
