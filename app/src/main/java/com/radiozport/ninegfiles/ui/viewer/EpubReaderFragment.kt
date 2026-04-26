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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Simple ePub reader.  An ePub is just a ZIP file whose OPF manifest lists
 * the chapter HTML/XHTML files in spine order.  We parse the OPF with the
 * standard XML DOM parser (no library needed), extract each chapter's HTML,
 * inject a legible CSS reset, and render it in a WebView.
 */
class EpubReaderFragment : Fragment() {

    companion object {
        const val ARG_PATH = "epubPath"
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

    private var chapters:   List<Chapter> = emptyList()
    private var currentIdx: Int = 0
    private var zipFile:    ZipFile? = null
    private var epubTitle:  String = "eBook"

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
            settings.javaScriptEnabled  = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
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

    private fun showChapter(idx: Int) {
        currentIdx = idx
        val ch = chapters[idx]
        val html = readEntry(ch.entryName) ?: "<p>Could not load chapter.</p>"
        val enriched = injectStyle(html, ch.basePath)
        webView.loadDataWithBaseURL("epub://content/", enriched, "text/html", "UTF-8", null)

        tvProgress.text = "${idx + 1} / ${chapters.size}"
        btnPrev.isEnabled = idx > 0
        btnNext.isEnabled = idx < chapters.size - 1
    }

    private fun injectStyle(html: String, @Suppress("UNUSED_PARAMETER") basePath: String): String {
        val css = """
            <style>
              body { font-family: serif; font-size: 18px; line-height: 1.7;
                     padding: 16px; max-width: 720px; margin: auto; word-wrap: break-word; }
              img  { max-width: 100%; height: auto; }
              pre, code { font-size: 0.85em; background: #f4f4f4; padding: 4px; border-radius: 4px; }
            </style>
        """.trimIndent()
        return if (html.contains("<head>", ignoreCase = true))
            html.replaceFirst(Regex("(?i)<head>"), "<head>$css")
        else
            "<!DOCTYPE html><html><head>$css</head><body>$html</body></html>"
    }

    private fun readEntry(entryName: String): String? = try {
        zipFile?.getEntry(entryName)?.let { entry ->
            zipFile!!.getInputStream(entry).use { it.reader(Charsets.UTF_8).readText() }
        }
    } catch (_: Exception) { null }

    // ---------- parsing -------------------------------------------------

    private fun parseEpub(path: String): List<Chapter>? {
        return try {
            val zf = ZipFile(File(path))
            zipFile = zf

            // 1. Locate container.xml → find the OPF path
            val containerEntry = zf.getEntry("META-INF/container.xml") ?: return null
            val containerXml   = zf.getInputStream(containerEntry).use { it.readBytes() }
            val containerDoc   = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(containerXml.inputStream())
            val rootfileEl = containerDoc.getElementsByTagName("rootfile").item(0) as? Element
                ?: return null
            val opfPath = rootfileEl.getAttribute("full-path")
            val opfBase = opfPath.substringBeforeLast("/", "")

            // 2. Parse the OPF
            val opfEntry = zf.getEntry(opfPath) ?: return null
            val opfXml   = zf.getInputStream(opfEntry).use { it.readBytes() }
            val opfDoc   = DocumentBuilderFactory.newInstance().newDocumentBuilder()
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
            val result    = mutableListOf<Chapter>()
            val spineItems = opfDoc.getElementsByTagName("itemref")
            for (i in 0 until spineItems.length) {
                val idref = (spineItems.item(i) as? Element)?.getAttribute("idref") ?: continue
                val href  = manifest[idref] ?: continue
                val fullPath = if (opfBase.isNotEmpty()) "$opfBase/$href" else href
                result.add(Chapter(
                    title    = "Chapter ${result.size + 1}",
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
        webView.destroy()
        super.onDestroyView()
    }

    override fun onDestroy() {
        try { zipFile?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
