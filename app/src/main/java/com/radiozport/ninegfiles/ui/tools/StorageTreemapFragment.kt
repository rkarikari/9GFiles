package com.radiozport.ninegfiles.ui.tools

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.storage.StorageManager
import android.util.AttributeSet
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.radiozport.ninegfiles.data.repository.StorageNode
import com.radiozport.ninegfiles.databinding.FragmentStorageTreemapBinding
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class StorageTreemapFragment : Fragment() {

    private var _binding: FragmentStorageTreemapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FileExplorerViewModel by activityViewModels()

    private val breadcrumb = ArrayDeque<StorageNode>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (breadcrumb.size > 1) {
                breadcrumb.removeLast()
                showNode(breadcrumb.last(), push = false)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStorageTreemapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val initialPath = arguments?.getString("path")
            ?: android.os.Environment.getExternalStorageDirectory().absolutePath

        setupDriveSwitcher()
        loadTree(initialPath)

        binding.treemapView.onNodeTapped = { node ->
            if (node.children.any { it.size > 0 }) {
                showNode(node, push = true)
            } else {
                binding.tvNodeInfo.text = "📄 ${node.name}  ·  ${formatBytes(node.size)}"
            }
        }

        binding.treemapView.onNodeLongPressed = { node ->
            binding.tvNodeInfo.text = buildString {
                append(if (node.isDirectory) "📁" else "📄")
                append(" ${node.name}  ·  ${formatBytes(node.size)}")
                if (node.children.isNotEmpty()) append("  ·  ${node.children.size} items")
            }
        }
    }

    // ── Drive Switcher ──────────────────────────────────────────────────────
    private fun setupDriveSwitcher() {
        binding.btnSwitchDrive.setOnClickListener { anchor ->
            showDriveSwitcherMenu(anchor)
        }
    }

    private fun showDriveSwitcherMenu(anchor: View) {
        val sm = requireContext().getSystemService(StorageManager::class.java)
        val volumes = sm.storageVolumes

        data class DriveEntry(val label: String, val path: String)
        val drives = mutableListOf<DriveEntry>()
        volumes.forEach { vol ->
            val dir = vol.directory ?: return@forEach
            val label = if (!vol.isRemovable) "Internal Storage"
                        else vol.getDescription(requireContext())
            drives.add(DriveEntry(label, dir.absolutePath))
        }

        if (drives.isEmpty()) {
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "No drives found", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
            return
        }

        val labels = drives.map { it.label }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Switch Drive")
            .setItems(labels) { _, which ->
                loadTree(drives[which].path)
            }
            .show()
    }
    // ────────────────────────────────────────────────────────────────────────

    private fun loadTree(path: String) {
        val rootLabel = if (path == android.os.Environment.getExternalStorageDirectory().absolutePath)
            "Internal Storage" else java.io.File(path).name

        binding.tvTitle.text = rootLabel
        binding.progressBar.isVisible = true
        binding.treemapView.isVisible = false
        binding.tvNodeInfo.text = "Scanning storage…"
        breadcrumb.clear()

        viewLifecycleOwner.lifecycleScope.launch {
            val tree = withContext(Dispatchers.IO) { viewModel.buildStorageTree(path) }
            if (_binding == null) return@launch
            binding.progressBar.isVisible = false
            binding.treemapView.isVisible = true
            breadcrumb.addLast(tree)
            showNode(tree, push = false)
        }
    }

    private fun showNode(node: StorageNode, push: Boolean) {
        if (push) breadcrumb.addLast(node)
        backCallback.isEnabled = breadcrumb.size > 1
        binding.treemapView.setData(node)
        binding.tvTitle.text = breadcrumb.joinToString(" › ") { it.name }
        val childCount = node.children.count { it.size > 0 }
        binding.tvNodeInfo.text = "${formatBytes(node.size)}  ·  $childCount items" +
            if (breadcrumb.size > 1) "  ·  tap ← to go back" else "  ·  tap a tile to drill down"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private fun formatBytes(b: Long) = when {
        b < 1024L            -> "$b B"
        b < 1024L * 1024     -> "%.1f KB".format(b / 1024.0)
        b < 1024L shl 20     -> "%.1f MB".format(b / (1024.0 * 1024))
        else                 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    }
}

// ─── TreemapView ─────────────────────────────────────────────────────────────

class TreemapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val PALETTE = intArrayOf(
        0xFF3A6186.toInt(), 0xFF5C8A5A.toInt(), 0xFF8A4A4A.toInt(), 0xFF8A7830.toInt(),
        0xFF5A3A8A.toInt(), 0xFF2E8A80.toInt(), 0xFF8A5A30.toInt(), 0xFF2E5A8A.toInt(),
        0xFF7A3060.toInt(), 0xFF2E7A50.toInt(), 0xFF6A5030.toInt(), 0xFF4A6A30.toInt()
    )

    private data class TRect(val rect: RectF, val node: StorageNode, val color: Int, val depth: Int)

    private val fillPaint        = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderDarkPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val highlightFill    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); style = Paint.Style.FILL
    }
    private val highlightBorder  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val sizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }

    private var root: StorageNode? = null
    private val rects = mutableListOf<TRect>()
    private var selected: StorageNode? = null

    var onNodeTapped: ((StorageNode) -> Unit)? = null
    var onNodeLongPressed: ((StorageNode) -> Unit)? = null

    fun setData(node: StorageNode) {
        root = node; selected = null; rects.clear()
        if (width > 0 && height > 0) buildLayout(node)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        root?.let { rects.clear(); buildLayout(it); invalidate() }
    }

    private fun buildLayout(node: StorageNode) {
        rects.clear()
        val children = node.children.filter { it.size > 0 }.sortedByDescending { it.size }
        if (children.isEmpty() || node.size == 0L) return
        squarify(children, RectF(0f, 0f, width.toFloat(), height.toFloat()), node.size, 0, -1)
    }

    private fun paletteIdx(name: String) = abs(name.hashCode()) % PALETTE.size

    private fun darken(color: Int, f: Float): Int {
        val r = ((color shr 16 and 0xFF) * f).toInt().coerceIn(0,255)
        val g = ((color shr  8 and 0xFF) * f).toInt().coerceIn(0,255)
        val b = ((color        and 0xFF) * f).toInt().coerceIn(0,255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun nodeColor(node: StorageNode, depth: Int, parentColor: Int): Int =
        if (depth == 0) PALETTE[paletteIdx(node.name)] else darken(parentColor, 0.82f)

    private fun squarify(nodes: List<StorageNode>, bounds: RectF, total: Long, depth: Int, parentColorRaw: Int) {
        if (nodes.isEmpty() || bounds.width() < 3f || bounds.height() < 3f) return
        val w = minOf(bounds.width(), bounds.height())
        val row = mutableListOf<StorageNode>()
        var rem = nodes.toMutableList()
        while (rem.isNotEmpty()) {
            val candidate = row + rem.first()
            if (row.isEmpty() || worst(candidate, w, bounds, total) <= worst(row, w, bounds, total)) {
                row.add(rem.removeFirst())
            } else {
                layoutRow(row, bounds, total, depth, parentColorRaw)
                squarify(rem, shrink(bounds, row, total), total, depth, parentColorRaw)
                return
            }
        }
        if (row.isNotEmpty()) layoutRow(row, bounds, total, depth, parentColorRaw)
    }

    private fun worst(row: List<StorageNode>, w: Float, bounds: RectF, total: Long): Float {
        if (row.isEmpty()) return Float.MAX_VALUE
        val area = bounds.width() * bounds.height()
        val s = row.sumOf { it.size }.toFloat() / total * area
        val max = row.maxOf { it.size }.toFloat() / total * area
        val min = row.minOf { it.size }.toFloat() / total * area
        if (s == 0f || min == 0f) return Float.MAX_VALUE
        return maxOf((w * w * max) / (s * s), (s * s) / (w * w * min))
    }

    private fun layoutRow(row: List<StorageNode>, bounds: RectF, total: Long, depth: Int, parentColorRaw: Int) {
        val rowFrac = row.sumOf { it.size }.toFloat() / total
        val horiz   = bounds.width() >= bounds.height()
        val rowLen  = if (horiz) rowFrac * bounds.width() else rowFrac * bounds.height()
        val rowSum  = row.sumOf { it.size }
        var cursor  = if (horiz) bounds.top else bounds.left

        row.forEach { node ->
            val frac = if (rowSum > 0) node.size.toFloat() / rowSum else 0f
            val r = if (horiz)
                RectF(bounds.left, cursor, bounds.left + rowLen, cursor + bounds.height() * frac)
            else
                RectF(cursor, bounds.top, cursor + bounds.width() * frac, bounds.top + rowLen)

            val color = nodeColor(node, depth, parentColorRaw)
            rects.add(TRect(r, node, color, depth))

            // Recurse into children for depth 0 and 1 only
            if (node.children.isNotEmpty() && depth < 2 && r.width() > 18f && r.height() > 18f) {
                squarify(
                    node.children.filter { it.size > 0 }.sortedByDescending { it.size },
                    RectF(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2),
                    node.size, depth + 1, color
                )
            }
            cursor += if (horiz) bounds.height() * frac else bounds.width() * frac
        }
    }

    private fun shrink(bounds: RectF, row: List<StorageNode>, total: Long): RectF {
        val f = row.sumOf { it.size }.toFloat() / total
        return if (bounds.width() >= bounds.height())
            RectF(bounds.left + f * bounds.width(), bounds.top, bounds.right, bounds.bottom)
        else
            RectF(bounds.left, bounds.top + f * bounds.height(), bounds.right, bounds.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Pass 1: fills
        rects.forEach { (r, _, color, depth) ->
            fillPaint.color = color
            fillPaint.alpha = if (depth == 0) 255 else 210
            canvas.drawRect(r, fillPaint)
        }
        // Pass 2: borders
        rects.forEach { (r, _, _, _) -> canvas.drawRect(r, borderDarkPaint) }

        // Pass 3: labels on depth-0 tiles only
        rects.forEach { (r, node, _, depth) ->
            if (depth != 0 || r.width() < 44f || r.height() < 26f) return@forEach
            val maxChars = (r.width() / 8.5f).toInt().coerceAtLeast(3)
            val label = node.name.let { if (it.length > maxChars) "${it.take(maxChars - 1)}…" else it }
            val ts = (r.height() / 5.5f).coerceIn(10f, 20f)
            titlePaint.textSize = ts
            canvas.drawText(label, r.left + 8f, r.top + ts + 6f, titlePaint)
            if (r.height() > ts * 2.6f) {
                val ss = (ts * 0.80f).coerceIn(9f, 15f)
                sizePaint.textSize = ss
                canvas.drawText(formatBytes(node.size), r.left + 8f, r.top + ts + ss + 10f, sizePaint)
            }
        }

        // Pass 4: selection highlight
        selected?.let { sel ->
            rects.firstOrNull { it.node === sel }?.let { (r, _, _, _) ->
                canvas.drawRect(r, highlightFill)
                canvas.drawRect(r, highlightBorder)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_UP -> {
                val hit = rects.filter { (r, _) -> r.contains(event.x, event.y) }
                    .minByOrNull { (r, _) -> r.width() * r.height() }
                if (hit != null) {
                    selected = hit.node
                    invalidate()
                    performClick()
                    onNodeTapped?.invoke(hit.node)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun formatBytes(b: Long) = when {
        b < 1024L        -> "$b B"
        b < 1048576L     -> "%.1f KB".format(b / 1024.0)
        b < 1073741824L  -> "%.1f MB".format(b / 1048576.0)
        else             -> "%.2f GB".format(b / 1073741824.0)
    }
}
