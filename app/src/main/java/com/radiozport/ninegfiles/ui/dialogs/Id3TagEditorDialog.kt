package com.radiozport.ninegfiles.ui.dialogs

import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * BottomSheet that lets the user edit the ID3v2 tags of an MP3 file.
 * Uses mp3agic (pure Java, no NDK).  After saving it writes a temp file
 * and replaces the original atomically via renameTo.
 */
class Id3TagEditorDialog : BottomSheetDialogFragment() {

    private var filePath: String = ""
    private var onSaved: (() -> Unit)? = null

    // Views — created programmatically so no layout XML is needed
    private lateinit var etTitle:       TextInputEditText
    private lateinit var etArtist:      TextInputEditText
    private lateinit var etAlbum:       TextInputEditText
    private lateinit var etAlbumArtist: TextInputEditText
    private lateinit var etYear:        TextInputEditText
    private lateinit var etTrack:       TextInputEditText
    private lateinit var etGenre:       TextInputEditText
    private lateinit var etComposer:    TextInputEditText
    private lateinit var btnSave:       MaterialButton
    private lateinit var btnCancel:     MaterialButton
    private lateinit var progressBar:   android.widget.ProgressBar

    companion object {
        fun show(
            fm: androidx.fragment.app.FragmentManager,
            path: String,
            currentTags: Map<String, String?>,
            onSaved: () -> Unit
        ) {
            Id3TagEditorDialog().apply {
                filePath = path
                this.onSaved = onSaved
                // Pre-populate via arguments (survives config change)
                arguments = Bundle().apply {
                    putString("path", path)
                    putString("title",       currentTags["title"])
                    putString("artist",      currentTags["artist"])
                    putString("album",       currentTags["album"])
                    putString("albumArtist", currentTags["albumArtist"])
                    putString("year",        currentTags["year"])
                    putString("track",       currentTags["track"])
                    putString("genre",       currentTags["genre"])
                    putString("composer",    currentTags["composer"])
                }
            }.show(fm, "Id3TagEditor")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = arguments?.getString("path") ?: filePath
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp8  = (8  * resources.displayMetrics.density).toInt()
        val dp16 = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp16)
        }

        // Title row
        val titleText = android.widget.TextView(ctx).apply {
            text = "Edit Tags"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp16 }
        }
        root.addView(titleText)

        // Helper to add a labelled field
        fun addField(hint: String, init: String?): TextInputEditText {
            val til = TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp8 }
            }
            val et = TextInputEditText(til.context).apply {
                setText(init ?: "")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            til.addView(et)
            root.addView(til)
            return et
        }

        etTitle       = addField("Title",        arguments?.getString("title"))
        etArtist      = addField("Artist",       arguments?.getString("artist"))
        etAlbum       = addField("Album",        arguments?.getString("album"))
        etAlbumArtist = addField("Album Artist", arguments?.getString("albumArtist"))
        etYear        = addField("Year",         arguments?.getString("year"))
        etTrack       = addField("Track #",      arguments?.getString("track"))
        etGenre       = addField("Genre",        arguments?.getString("genre"))
        etComposer    = addField("Composer",     arguments?.getString("composer"))

        progressBar = android.widget.ProgressBar(ctx,null,
            android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp8 }
        }
        root.addView(progressBar)

        // Button row
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnCancel = MaterialButton(ctx,null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel"
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp8 }
            setOnClickListener { dismiss() }
        }

        btnSave = MaterialButton(ctx).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveTags() }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        root.addView(btnRow)

        // Wrap in a ScrollView so tall keyboard doesn't clip fields
        return androidx.core.widget.NestedScrollView(ctx).apply {
            addView(root)
        }
    }

    private fun saveTags() {
        val path = filePath.ifEmpty { arguments?.getString("path") ?: return }
        btnSave.isEnabled   = false
        btnCancel.isEnabled = false
        progressBar.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { writeId3Tags(path) }
            if (!isAdded) return@launch
            progressBar.isVisible = false
            btnSave.isEnabled   = true
            btnCancel.isEnabled = true
            if (error == null) {
                Toast.makeText(requireContext(), "Tags saved", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
                dismiss()
            } else {
                Toast.makeText(requireContext(), "Save failed: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Write ID3v2 tags to a temp file then rename over the original. */
    private fun writeId3Tags(path: String): String? = try {
        val mp3 = Mp3File(path)

        // Ensure ID3v2 tag exists (create if absent, prefer v2 over v1)
        if (!mp3.hasId3v2Tag()) {
            mp3.id3v2Tag = com.mpatric.mp3agic.ID3v24Tag()
        }
        val tag = mp3.id3v2Tag

        fun String.nullIfBlank(): String? = takeIf { isNotBlank() }

        tag.title       = etTitle.text?.toString()?.nullIfBlank()
        tag.artist      = etArtist.text?.toString()?.nullIfBlank()
        tag.album       = etAlbum.text?.toString()?.nullIfBlank()
        tag.albumArtist = etAlbumArtist.text?.toString()?.nullIfBlank()
        tag.year        = etYear.text?.toString()?.nullIfBlank()
        tag.track       = etTrack.text?.toString()?.nullIfBlank()
        tag.genreDescription = etGenre.text?.toString()?.nullIfBlank()
        tag.composer    = etComposer.text?.toString()?.nullIfBlank()

        // mp3agic cannot overwrite in-place; write to a temp then rename
        val tmpFile = File(path + ".tmp_id3")
        mp3.save(tmpFile.absolutePath)

        val original = File(path)
        tmpFile.renameTo(original)  // atomic on same FS
        null  // success
    } catch (e: Exception) {
        e.message ?: "Unknown error"
    }
}
