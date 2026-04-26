package com.radiozport.ninegfiles.ui.dialogs

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a QR code for any text/URL (file path, FTP URL, share link, etc.).
 * Uses ZXing [com.google.zxing:core] — no Activities, no camera permission needed.
 */
class QrShareDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CONTENT = "content"
        private const val ARG_LABEL   = "label"

        fun show(
            fm: androidx.fragment.app.FragmentManager,
            content: String,
            label: String = ""
        ) {
            QrShareDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTENT, content)
                    putString(ARG_LABEL,   label)
                }
            }.show(fm, "QrShareDialog")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }

        // Title
        val tvTitle = TextView(ctx).apply {
            text = "Share via QR Code"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() }
        }
        root.addView(tvTitle)

        // Label (e.g. FTP URL, file name)
        val label = arguments?.getString(ARG_LABEL) ?: ""
        val tvLabel = TextView(ctx).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ctx.getColor(com.radiozport.ninegfiles.R.color.on_surface_medium))
            gravity = Gravity.CENTER
            isVisible = label.isNotBlank()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (16 * dp).toInt() }
        }
        root.addView(tvLabel)

        // Progress
        val progress = ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
                bottomMargin = (16 * dp).toInt()
            }
        }
        root.addView(progress)

        // QR ImageView
        val ivQr = ImageView(ctx).apply {
            val size = (240 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = (16 * dp).toInt()
            }
            isVisible = false
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(ivQr)

        // Content text (selectable)
        val content = arguments?.getString(ARG_CONTENT) ?: ""
        val tvContent = TextView(ctx).apply {
            text = content
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (16 * dp).toInt() }
        }
        root.addView(tvContent)

        // Close button
        val btnClose = MaterialButton(ctx).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setOnClickListener { dismiss() }
        }
        root.addView(btnClose)

        // Generate QR off-thread
        viewLifecycleOwner.lifecycleScope.launch {
            val qrBitmap = withContext(Dispatchers.Default) { generateQr(content, 800) }
            if (!isAdded) return@launch
            progress.isVisible = false
            if (qrBitmap != null) {
                ivQr.setImageBitmap(qrBitmap)
                ivQr.isVisible    = true
                tvContent.isVisible = true
            } else {
                tvContent.text    = "QR generation failed.\n\n$content"
                tvContent.isVisible = true
            }
        }

        return ScrollView(ctx).apply { addView(root) }
    }

    private fun generateQr(content: String, size: Int): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2
            )
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: WriterException) { null }
          catch (_: Exception) { null }
    }
}
