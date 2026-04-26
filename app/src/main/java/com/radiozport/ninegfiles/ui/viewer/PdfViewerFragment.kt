package com.radiozport.ninegfiles.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.databinding.FragmentPdfViewerBinding
import com.radiozport.ninegfiles.databinding.ItemPdfPageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewerFragment : Fragment() {

    private var _binding: FragmentPdfViewerBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance(path: String) = PdfViewerFragment().apply {
            arguments = bundleOf("pdfPath" to path)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPdfViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString("pdfPath") ?: run {
            showError("No PDF path provided")
            return
        }

        val file = File(path)
        if (!file.exists()) { showError("File not found"); return }

        binding.tvFileName.text = file.name
        binding.progressBar.isVisible = true

        // Print button
        binding.btnPrint.setOnClickListener { printPdf(file) }

        viewLifecycleOwner.lifecycleScope.launch {
            val pageCount = withContext(Dispatchers.IO) { getPageCount(path) }
            if (_binding == null) return@launch

            binding.progressBar.isVisible = false

            if (pageCount <= 0) { showError("Could not open PDF"); return@launch }

            binding.tvPageCount.text = "$pageCount page${if (pageCount == 1) "" else "s"}"

            val adapter = PdfPageAdapter(path, pageCount)
            binding.rvPages.layoutManager = LinearLayoutManager(requireContext())
            binding.rvPages.adapter = adapter
            binding.rvPages.setRecycledViewPool(RecyclerView.RecycledViewPool().also {
                it.setMaxRecycledViews(0, 3) // Keep only 3 bitmaps in pool
            })
        }
    }

    private fun getPageCount(path: String): Int {
        return try {
            val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (_: Exception) { 0 }
    }

    private fun printPdf(file: File) {
        val printManager = requireContext()
            .getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager
            ?: run {
                android.widget.Toast.makeText(requireContext(),
                    "Print not available on this device", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

        // Count pages once so both onLayout and onWrite can reference it.
        val totalPages = getPageCount(file.absolutePath)
        val jobName = file.name

        val adapter = object : android.print.PrintDocumentAdapter() {

            override fun onLayout(
                oldAttributes: android.print.PrintAttributes?,
                newAttributes: android.print.PrintAttributes,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback,
                extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                val pageCount = if (totalPages > 0)
                    totalPages
                else
                    android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN
                val info = android.print.PrintDocumentInfo.Builder(jobName)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, newAttributes != oldAttributes)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: android.os.ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback
            ) {
                if (cancellationSignal?.isCanceled == true) { callback.onWriteCancelled(); return }
                try {
                    val isAllPages = pages == null ||
                        pages.any { it == android.print.PageRange.ALL_PAGES }

                    if (isAllPages || totalPages <= 0) {
                        // Stream raw PDF bytes — the print service handles page selection.
                        file.inputStream().use { input ->
                            android.os.ParcelFileDescriptor.AutoCloseOutputStream(destination)
                                .use { output -> input.copyTo(output) }
                        }
                        callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } else {
                        // Render only the requested pages via PdfRenderer → PdfDocument
                        // so the printer receives exactly what the user selected.
                        val requestedIndices = expandPageRanges(pages, totalPages)
                        val pfd = android.os.ParcelFileDescriptor.open(
                            file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = android.graphics.pdf.PdfRenderer(pfd)
                        val pdfDoc  = android.graphics.pdf.PdfDocument()

                        for (idx in requestedIndices) {
                            if (cancellationSignal?.isCanceled == true) {
                                pdfDoc.close(); renderer.close(); pfd.close()
                                callback.onWriteCancelled(); return
                            }
                            val page = renderer.openPage(idx)
                            val bmp  = android.graphics.Bitmap.createBitmap(
                                page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            page.close()

                            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo
                                .Builder(bmp.width, bmp.height, idx + 1).create()
                            val docPage = pdfDoc.startPage(pageInfo)
                            docPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                            bmp.recycle()
                            pdfDoc.finishPage(docPage)
                        }

                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(destination)
                            .use { pdfDoc.writeTo(it) }
                        pdfDoc.close(); renderer.close(); pfd.close()
                        callback.onWriteFinished(pages)
                    }
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        printManager.print(jobName, adapter, android.print.PrintAttributes.Builder().build())
    }

    /** Expands an array of [PageRange] objects into a sorted list of 0-based page indices. */
    private fun expandPageRanges(
        ranges: Array<out android.print.PageRange>,
        totalPages: Int
    ): List<Int> {
        val set = sortedSetOf<Int>()
        for (range in ranges) {
            for (i in range.start..minOf(range.end, totalPages - 1)) set.add(i)
        }
        return set.toList()
    }

    private fun showError(msg: String) {
        binding.progressBar.isVisible = false
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── PdfPageAdapter ───────────────────────────────────────────────────────────

class PdfPageAdapter(
    private val path: String,
    private val pageCount: Int
) : RecyclerView.Adapter<PdfPageAdapter.VH>() {

    inner class VH(private val b: ItemPdfPageBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(pageIndex: Int) {
            b.tvPageNumber.text = "Page ${pageIndex + 1} of $pageCount"
            b.ivPage.setImageBitmap(null)

            // Render off the main thread, cancel if recycled
            val tag = Any()
            b.root.tag = tag

            b.root.post {
                if (b.root.tag !== tag) return@post
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    val bmp = renderPage(pageIndex)
                    if (b.root.tag !== tag) { bmp?.recycle(); return@launch }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        if (b.root.tag === tag) b.ivPage.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun renderPage(pageIndex: Int): Bitmap? {
        return try {
            val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val page = renderer.openPage(pageIndex)

            // Render at 2× the physical screen width so pages stay sharp up to ~2× zoom.
            // The container's MAX_SCALE is 5×; content will soften beyond 2× but remains readable.
            val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val renderWidth = screenWidth * 2
            val scale = renderWidth.toFloat() / page.width
            val bmpWidth = (page.width * scale).toInt()
            val bmpHeight = (page.height * scale).toInt()

            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()
            renderer.close()
            pfd.close()
            bitmap
        } catch (_: Exception) { null }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(pos)
    override fun getItemCount() = pageCount

    override fun onViewRecycled(h: VH) {
        super.onViewRecycled(h)
        h.itemView.tag = null // cancel any pending render
    }
}
