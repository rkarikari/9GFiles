package com.radiozport.ninegfiles.ui.viewer

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentMarkdownViewerBinding
import com.radiozport.ninegfiles.utils.DeviceKeyManager
import com.radiozport.ninegfiles.utils.EncryptionUtils
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.node.SoftLineBreak
import java.io.File

/**
 * Dedicated Markdown viewer using the [Markwon] library (io.noties.markwon:core).
 *
 * Three modes, toggled by toolbar buttons:
 *
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │  DEFAULT  │ Markwon-rendered TextView  │ (opens by default) │
 *  │  PREVIEW  │ WebView HTML + CSS, inline │ [ic_file_document] │
 *  │           │   images via file:// base  │                    │
 *  │  EDIT     │ Editable EditText + Save   │ [ic_menu_edit]     │
 *  └─────────────────────────────────────────────────────────────┘
 *
 * Routing: [com.radiozport.ninegfiles.utils.FileOpener] navigates here for
 *          any `.md` file instead of the generic [TextEditorFragment].
 */
class MarkdownViewerFragment : Fragment() {

    // ── View binding ──────────────────────────────────────────────────────────

    private var _binding: FragmentMarkdownViewerBinding? = null
    private val binding get() = _binding!!

    // ── State ─────────────────────────────────────────────────────────────────

    private enum class ViewMode { DEFAULT, PREVIEW, EDIT }

    private var currentMode = ViewMode.DEFAULT
    private var rawContent: String = ""
    private var filePath: String = ""

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val ARG_PATH = "mdPath"

        fun newInstance(path: String) = MarkdownViewerFragment().apply {
            arguments = bundleOf(ARG_PATH to path)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMarkdownViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filePath = arguments?.getString(ARG_PATH) ?: run {
            showError("No file path provided"); return
        }
        val file = File(filePath)
        if (!file.exists()) { showError("File not found"); return }

        binding.tvFileName.text = file.name
        binding.progressBar.isVisible = true

        // Configure WebView once — stays alive across mode switches
        binding.webviewPreview.apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readMarkdown(file) }
            if (_binding == null) return@launch
            binding.progressBar.isVisible = false

            result.fold(
                onSuccess = { (content, stats) ->
                    rawContent = content
                    binding.tvDocInfo.text = stats
                    renderMarkdown(content)       // pre-render Markwon for DEFAULT mode
                    setMode(ViewMode.PREVIEW)
                },
                onFailure = { err ->
                    showError("Could not read file: ${err.message}")
                }
            )
        }

        // ── Button listeners ──────────────────────────────────────────────────

        // Preview button (WebView render): PREVIEW ↔ DEFAULT
        binding.btnPreview.setOnClickListener {
            if (currentMode == ViewMode.EDIT && hasUnsavedChanges()) {
                confirmDiscardChanges {
                    setMode(ViewMode.PREVIEW)
                }
            } else {
                setMode(if (currentMode == ViewMode.PREVIEW) ViewMode.DEFAULT else ViewMode.PREVIEW)
            }
        }

        // Edit button: EDIT ↔ DEFAULT
        binding.btnEditInEditor.setOnClickListener {
            setMode(if (currentMode == ViewMode.EDIT) ViewMode.DEFAULT else ViewMode.EDIT)
        }

        // Save: write to disk, re-render, return to DEFAULT
        binding.btnSave.setOnClickListener { saveAndReturn() }

        binding.btnShare.setOnClickListener { shareFile(file) }
    }

    override fun onDestroyView() {
        binding.webviewPreview.destroy()
        _binding = null
        super.onDestroyView()
    }

    // ── Mode switching ────────────────────────────────────────────────────────

    private fun setMode(mode: ViewMode) {
        currentMode = mode

        // Hide all content panels
        binding.scrollView.isVisible      = false
        binding.webviewPreview.isVisible  = false
        binding.scrollEdit.isVisible      = false
        binding.tvModeLabel.isVisible     = false
        binding.btnSave.isVisible         = false

        // Reset button tints to inactive (match share button's default tint)
        val inactiveTint = binding.btnShare.imageTintList
        binding.btnPreview.imageTintList      = inactiveTint
        binding.btnEditInEditor.imageTintList = inactiveTint

        when (mode) {
            ViewMode.DEFAULT -> {
                binding.scrollView.isVisible = true
                // No mode label for the default rendered view
            }

            ViewMode.PREVIEW -> {
                loadWebPreview(rawContent)
                binding.webviewPreview.isVisible = true
                binding.tvModeLabel.text = "Markdown Preview"
                binding.tvModeLabel.isVisible = true
                highlightButton(binding.btnPreview)
            }

            ViewMode.EDIT -> {
                if (binding.etEditor.text.isNullOrEmpty() ||
                    binding.etEditor.text.toString() == rawContent) {
                    binding.etEditor.setText(rawContent)
                }
                binding.scrollEdit.isVisible  = true
                binding.btnSave.isVisible     = true
                binding.tvModeLabel.text = "EDITING"
                binding.tvModeLabel.isVisible = true
                highlightButton(binding.btnEditInEditor)
            }
        }
    }

    private fun highlightButton(btn: android.widget.ImageButton) {
        val ctx = context ?: return
        val ta = ctx.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimary))
        val primary = ta.getColor(0, 0xFF4A90E2.toInt())
        ta.recycle()
        btn.imageTintList = ColorStateList.valueOf(primary)
    }

    // ── WebView preview ───────────────────────────────────────────────────────

    // Cached asset strings — read once, reused on every preview load.
    private var markedJs: String = ""
    private var githubCss: String = ""

    /**
     * Renders the markdown as a true GitHub-style preview, fully offline.
     *
     * Both [marked.js](https://marked.js.org/) (GFM parser) and
     * [github-markdown-dark.min.css](https://github.com/sindresorhus/github-markdown-css)
     * are bundled in `src/main/assets/` and inlined directly into the HTML —
     * no network request is made at any point.
     *
     * The markdown is Base64-encoded before embedding so that backticks,
     * backslashes, or `${…}` in the source never break the JS string.
     *
     * The file's parent directory is the WebView base URL so relative image
     * paths (e.g. `![chart](images/chart.png)`) resolve from the file system.
     */
    private fun loadWebPreview(content: String) {
        val ctx = context ?: return

        // Load assets once and cache them
        if (markedJs.isEmpty()) {
            markedJs = ctx.assets.open("marked.min.js").bufferedReader().readText()
        }
        if (githubCss.isEmpty()) {
            githubCss = ctx.assets.open("github-markdown-dark.min.css").bufferedReader().readText()
        }

        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val baseUrl = "file://${File(filePath).parent}/"

        binding.webviewPreview.settings.javaScriptEnabled = true

        val html = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>$githubCss</style>
<style>
  body{background:#0d1117;margin:0;padding:16px;box-sizing:border-box}
  .markdown-body{max-width:980px;margin:0 auto;font-size:15px}
  .markdown-body img{max-width:100%;height:auto}
</style>
<script>$markedJs</script>
</head>
<body>
<article class="markdown-body" id="content"></article>
<script>
  function b64ToUtf8(b64){
    var bin=atob(b64),bytes=new Uint8Array(bin.length);
    for(var i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);
    return new TextDecoder('utf-8').decode(bytes);
  }
  marked.setOptions({gfm:true,breaks:false});
  document.getElementById('content').innerHTML=marked.parse(b64ToUtf8('$b64'));
</script>
</body>
</html>"""

        binding.webviewPreview.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun hasUnsavedChanges(): Boolean =
        binding.etEditor.text?.toString()?.let { it != rawContent } ?: false

    private fun saveAndReturn() {
        val edited = binding.etEditor.text?.toString() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { File(filePath).writeText(edited, Charsets.UTF_8) }.isSuccess
            }
            if (_binding == null) return@launch
            if (ok) {
                rawContent = edited
                renderMarkdown(rawContent)
                val lineCount = rawContent.lines().size
                val wordCount = rawContent.split(Regex("\\s+")).count { it.isNotEmpty() }
                binding.tvDocInfo.text = "$lineCount lines · $wordCount words"
                setMode(ViewMode.DEFAULT)
                Snackbar.make(binding.root, "Saved", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Save failed — check storage permissions", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDiscardChanges(onDiscard: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("Discard changes?")
            .setMessage("You have unsaved edits. Discard them?")
            .setPositiveButton("Discard") { _, _ -> onDiscard() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    // ── File reading ──────────────────────────────────────────────────────────

    private suspend fun readMarkdown(file: File): Result<Pair<String, String>> = runCatching {
        val content: String = if (EncryptionUtils.isEncrypted(file)) {
            when (EncryptionUtils.detectFormat(file)) {
                EncryptionUtils.EncryptionFormat.DEVICE_KEY -> {
                    val bytes = EncryptionUtils.decryptDeviceToBytes(file) {
                        DeviceKeyManager.decryptSessionKey(it)
                    } ?: error("Decryption failed — file may be encrypted for a different device")
                    bytes.toString(Charsets.UTF_8)
                }
                EncryptionUtils.EncryptionFormat.PASSWORD_BASED ->
                    error("Password-encrypted file. Decrypt it via the Secure Vault first.")
                null -> error("Unknown encryption format")
            }
        } else {
            if (file.length() > 4 * 1024 * 1024) {
                error("File too large to display (${file.length() / 1024 / 1024} MB). Use a desktop editor.")
            }
            file.readText(Charsets.UTF_8)
        }

        val lineCount = content.lines().size
        val wordCount = content.split(Regex("\\s+")).count { it.isNotEmpty() }
        Pair(content, "$lineCount lines · $wordCount words")
    }

    // ── Markwon render (DEFAULT mode) ─────────────────────────────────────────

    private fun renderMarkdown(content: String) {
        val ctx = context ?: return
        val ta = ctx.obtainStyledAttributes(
            intArrayOf(
                com.google.android.material.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorSecondary,
                com.google.android.material.R.attr.colorSurfaceVariant,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
        val colorPrimary        = ta.getColor(0, 0xFF4A90E2.toInt())
        val colorSurfaceVariant = ta.getColor(2, 0xFF333333.toInt())
        ta.recycle()

        val markwon = Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(ctx))
            .usePlugin(TaskListPlugin.create(ctx))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(colorPrimary)
                        .codeBackgroundColor(colorSurfaceVariant)
                        .codeTextColor(0xFFCE9178.toInt())
                        .headingBreakColor(colorSurfaceVariant)
                        .headingBreakHeight(1)
                        .blockMargin((resources.displayMetrics.density * 16).toInt())
                }
                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    builder.on(SoftLineBreak::class.java) { visitor, _ ->
                        visitor.forceNewLine()
                    }
                }
                override fun configure(registry: io.noties.markwon.MarkwonPlugin.Registry) {}
            })
            .build()

        markwon.setMarkdown(binding.tvMarkdown, content)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        binding.progressBar.isVisible = false
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    // ── Sharing ───────────────────────────────────────────────────────────────

    private fun shareFile(file: File) {
        try {
            val ctx = requireContext()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share"))
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Share failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }
}
