package com.radiozport.ninegfiles.ui.editor

import android.graphics.Color
import android.os.Bundle
import android.text.*
import android.text.style.*
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentTextEditorBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.regex.Pattern

class TextEditorFragment : Fragment() {

    private var _binding: FragmentTextEditorBinding? = null
    private val binding get() = _binding!!

    private lateinit var filePath: String
    private var originalContent: String = ""
    private var isEditing = false
    private var hasUnsavedChanges = false
    private var isPreviewMode = false   // unified flag for both .md and .html/.htm preview
    private var fileExtension = ""

    private val isHtmlFile     get() = fileExtension == "html" || fileExtension == "htm"
    private val isMarkdownFile get() = fileExtension == "md"
    private val supportsPreview get() = isHtmlFile || isMarkdownFile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = arguments?.getString("filePath") ?: ""
        fileExtension = File(filePath).extension.lowercase()
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTextEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        loadFile()

        binding.webviewPreview?.apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = false  // overridden per-type in showPreview()
        }
    }

    private fun setupToolbar() {
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = File(filePath).name
            subtitle = filePath
        }
    }

    private fun loadFile() {
        if (filePath.isEmpty()) { showError("No file specified"); return }
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.scrollEditor.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    val file = File(filePath)
                    if (!file.exists()) throw FileNotFoundException("File not found: $filePath")
                    if (!file.canRead()) throw SecurityException("Cannot read: $filePath")
                    if (file.length() > 2 * 1024 * 1024) {
                        return@withContext "[File too large — ${FileUtils.formatSize(file.length())}]\n\nOnly files under 2 MB are shown inline."
                    }
                    file.readText(Charsets.UTF_8)
                }
                originalContent = content
                binding.editorContent.setText(content)
                binding.editorContent.isEnabled = false
                binding.loadingIndicator.visibility = View.GONE
                updateLineCount(content)
                applySyntaxHighlighting()

                // HTML and Markdown files open in preview by default; all others show source
                if (isHtmlFile || isMarkdownFile) {
                    showPreview(content)
                } else {
                    binding.scrollEditor.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                binding.loadingIndicator.visibility = View.GONE
                showError("Failed to load: ${e.message}")
            }
        }
    }

    private fun updateLineCount(content: String) {
        binding.tvFileInfo.text = "${content.lines().size} lines • ${content.length} chars"
    }

    // ─── Syntax Highlighting ──────────────────────────────────────────────

    private fun applySyntaxHighlighting() {
        if (isEditing) return
        val text = binding.editorContent.text ?: return

        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }

        val rules = getSyntaxRules(fileExtension)
        rules.forEach { (pattern, color) ->
            val m = pattern.matcher(text)
            while (m.find()) {
                text.setSpan(ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun getSyntaxRules(ext: String): List<Pair<Pattern, Int>> {
        val comment    = 0xFF57A64A.toInt()
        val keyword    = 0xFF569CD6.toInt()
        val string     = 0xFFCE9178.toInt()
        val number     = 0xFFB5CEA8.toInt()
        val annotation = 0xFF9CDCFE.toInt()
        val type       = 0xFF4EC9B0.toInt()

        val stringPat = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
        val numberPat = Pattern.compile("\\b\\d+\\.?\\d*\\b")

        return when (ext) {
            "kt", "java" -> listOf(
                Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL) to comment,
                Pattern.compile("\\b(fun|val|var|class|object|interface|if|else|when|for|while|return|import|package|null|true|false|override|private|public|protected|internal|data|sealed|enum|companion|suspend|coroutine|lateinit|by|in|is|as|try|catch|finally|throw)\\b") to keyword,
                Pattern.compile("\\b(Int|Long|String|Boolean|Float|Double|Unit|Any|Nothing|List|Map|Set|MutableList|MutableMap)\\b") to type,
                Pattern.compile("@\\w+") to annotation,
                stringPat to string,
                numberPat to number
            )
            "py" -> listOf(
                Pattern.compile("#[^\n]*") to comment,
                Pattern.compile("\\b(def|class|if|elif|else|for|while|return|import|from|as|in|is|not|and|or|True|False|None|try|except|finally|raise|with|yield|lambda|pass|break|continue)\\b") to keyword,
                stringPat to string,
                numberPat to number
            )
            "js", "ts" -> listOf(
                Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL) to comment,
                Pattern.compile("\\b(function|const|let|var|class|if|else|for|while|return|import|export|default|from|null|undefined|true|false|try|catch|finally|throw|new|this|async|await|of|in|instanceof|typeof)\\b") to keyword,
                stringPat to string,
                numberPat to number
            )
            "xml", "html", "htm" -> listOf(
                Pattern.compile("<!--.*?-->", Pattern.DOTALL) to comment,
                Pattern.compile("</?[\\w:.-]+") to keyword,
                Pattern.compile("\\b[\\w:.-]+=") to annotation,
                stringPat to string
            )
            "json" -> listOf(
                Pattern.compile("\"[^\"]*\"\\s*:") to annotation,
                stringPat to string,
                numberPat to number,
                Pattern.compile("\\b(true|false|null)\\b") to keyword
            )
            "md" -> listOf(
                Pattern.compile("^#{1,6}.*", Pattern.MULTILINE) to keyword,
                Pattern.compile("\\*\\*.*?\\*\\*|__.*?__") to type,
                Pattern.compile("`[^`]+`") to string,
                Pattern.compile("^[-*+]\\s", Pattern.MULTILINE) to annotation
            )
            else -> emptyList()
        }
    }

    // ─── Preview (HTML + Markdown) ────────────────────────────────────────

    /**
     * Render the file content in the WebView.
     *
     * • .html / .htm → loaded as real HTML (JS enabled, file:// base URL so relative
     *   assets resolve correctly).
     * • .md          → lightweight Markdown→HTML conversion (JS disabled).
     */
    private fun showPreview(content: String) {
        isPreviewMode = true
        binding.webviewPreview?.isVisible = true
        binding.scrollEditor.isVisible = false
        binding.tvFileInfo.text = if (isHtmlFile) "HTML Preview" else "Markdown Preview"

        binding.webviewPreview?.apply {
            if (isHtmlFile) {
                settings.javaScriptEnabled = true
                // Use the file's parent directory as the base URL so relative
                // paths (images, stylesheets, scripts) resolve correctly.
                loadDataWithBaseURL(
                    "file://${File(filePath).parent}/",
                    content,
                    "text/html",
                    "UTF-8",
                    null
                )
            } else {
                settings.javaScriptEnabled = false
                val html = markdownToHtml(content)
                loadDataWithBaseURL(
                    null,
                    "<html><head><meta charset='utf-8'><style>" +
                        "body{font-family:sans-serif;padding:16px;color:#e0e0e0;background:#1e1e1e}" +
                        "pre{background:#2d2d2d;padding:8px;border-radius:4px}" +
                        "code{color:#ce9178}h1,h2,h3{color:#569cd6}a{color:#4ec9b0}" +
                        "</style></head><body>$html</body></html>",
                    "text/html", "UTF-8", null
                )
            }
        }
        requireActivity().invalidateOptionsMenu()
    }

    /** Switch back to the raw source / syntax-highlighted text view. */
    private fun showSource() {
        isPreviewMode = false
        binding.webviewPreview?.isVisible = false
        binding.scrollEditor.isVisible = true
        updateLineCount(originalContent)
        requireActivity().invalidateOptionsMenu()
    }

    /**
     * Toggle between Preview and Source — called from the toolbar menu.
     * Works for both .html/.htm and .md files.
     */
    fun togglePreview() {
        if (!supportsPreview) {
            Snackbar.make(binding.root, "Preview available for .html and .md files only", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (isPreviewMode) {
            showSource()
        } else {
            showPreview(binding.editorContent.text?.toString() ?: originalContent)
        }
    }

    // Kept for source compatibility — delegates to the unified togglePreview().
    fun toggleMarkdownPreview() = togglePreview()

    private fun markdownToHtml(md: String): String {
        var html = md
        html = html.replace(Regex("^#{6}\\s(.+)$", RegexOption.MULTILINE), "<h6>$1</h6>")
        html = html.replace(Regex("^#{5}\\s(.+)$", RegexOption.MULTILINE), "<h5>$1</h5>")
        html = html.replace(Regex("^#{4}\\s(.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")
        html = html.replace(Regex("^#{3}\\s(.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        html = html.replace(Regex("^#{2}\\s(.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        html = html.replace(Regex("^#\\s(.+)$",    RegexOption.MULTILINE), "<h1>$1</h1>")
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        html = html.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        html = html.replace(Regex("`(.+?)`"), "<code>$1</code>")
        html = html.replace(Regex("```[\\w]*\\n([\\s\\S]*?)```")) { "<pre><code>${it.groupValues[1]}</code></pre>" }
        html = html.replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "<a href='$2'>$1</a>")
        html = html.replace(Regex("^---+$", RegexOption.MULTILINE), "<hr>")
        html = html.replace(Regex("^[-*+]\\s(.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        html = html.replace(Regex("\n\n"), "</p><p>")
        return "<p>$html</p>"
    }

    // ─── Edit Mode ────────────────────────────────────────────────────────

    fun toggleEditMode() {
        // If preview is active, drop back to source first so the editor is visible
        if (isPreviewMode) showSource()

        isEditing = !isEditing
        binding.editorContent.isEnabled = isEditing
        if (isEditing) {
            val text = binding.editorContent.text
            text?.getSpans(0, text.length, ForegroundColorSpan::class.java)?.forEach { text.removeSpan(it) }
            binding.editorContent.requestFocus()
            binding.editorContent.setSelection(binding.editorContent.text?.length ?: 0)
            binding.tvEditingBadge?.isVisible = true
            Snackbar.make(binding.root, "Editing mode enabled", Snackbar.LENGTH_SHORT).show()
        } else {
            binding.tvEditingBadge?.isVisible = false
            if (hasUnsavedChanges) confirmDiscard()
            else applySyntaxHighlighting()
        }
        requireActivity().invalidateOptionsMenu()
    }

    fun saveFile() {
        val newContent = binding.editorContent.text?.toString() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { File(filePath).writeText(newContent, Charsets.UTF_8) }
                originalContent = newContent
                hasUnsavedChanges = false
                Snackbar.make(binding.root, "Saved", Snackbar.LENGTH_SHORT).show()
                applySyntaxHighlighting()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Save failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard changes?")
            .setMessage("You have unsaved changes.")
            .setPositiveButton("Discard") { _, _ ->
                binding.editorContent.setText(originalContent)
                hasUnsavedChanges = false
                isEditing = false
                binding.editorContent.isEnabled = false
                applySyntaxHighlighting()
            }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    fun printText() {
        val content = binding.editorContent.text?.toString() ?: ""
        val printManager = requireContext()
            .getSystemService(android.content.Context.PRINT_SERVICE)
            as? android.print.PrintManager
            ?: run {
                Snackbar.make(binding.root, "Print not available on this device",
                    Snackbar.LENGTH_SHORT).show()
                return
            }
        val jobName = File(filePath).name
        val escaped = android.text.Html.escapeHtml(content)
        val html = """<!DOCTYPE html><html><head><meta charset='utf-8'>
            <style>body{font-family:monospace;font-size:11pt;white-space:pre-wrap;
            margin:16pt;color:#000;background:#fff}</style></head>
            <body>$escaped</body></html>"""
        val printWebView = android.webkit.WebView(requireContext())
        printWebView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, adapter,
                    android.print.PrintAttributes.Builder().build())
            }
        }
        printWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_text_editor, menu)
        menu.findItem(R.id.action_edit_toggle)?.title = if (isEditing) "View Mode" else "Edit"
        menu.findItem(R.id.action_save)?.isVisible = isEditing
        // Show the Preview/Source toggle for .html, .htm, and .md files
        menu.findItem(R.id.action_preview)?.isVisible = supportsPreview
        menu.findItem(R.id.action_preview)?.title = if (isPreviewMode) "Source" else "Preview"
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_edit_toggle -> { toggleEditMode(); true }
        R.id.action_save        -> { saveFile(); true }
        R.id.action_preview     -> { togglePreview(); true }
        R.id.action_print       -> { printText(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
