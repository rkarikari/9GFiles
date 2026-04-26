package com.radiozport.ninegfiles.ui.viewer

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentExifViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class ExifViewerFragment : Fragment() {

    private var _binding: FragmentExifViewerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExifViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString("filePath") ?: run {
            binding.tvExifEmpty.isVisible = true
            binding.tvExifEmpty.text = "No file specified"
            return
        }

        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = "EXIF / Metadata"
            subtitle = File(filePath).name
        }

        binding.rvExif.layoutManager = LinearLayoutManager(requireContext())
        binding.rvExif.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        loadExif(filePath)
    }

    private fun loadExif(path: String) {
        binding.progressExif.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { readExifRows(path) }
            binding.progressExif.isVisible = false
            if (rows.isEmpty()) {
                binding.tvExifEmpty.isVisible = true
                binding.tvExifEmpty.text = "No EXIF metadata found in this file"
            } else {
                binding.rvExif.adapter = ExifAdapter(rows)
            }
        }
    }

    private fun readExifRows(path: String): List<ExifRow> {
        val rows = mutableListOf<ExifRow>()
        try {
            val exif = ExifInterface(path)

            // ── Image basics ─────────────────────────────────────────────
            rows += section("Image")
            addTag(rows, "Width",       exif, ExifInterface.TAG_IMAGE_WIDTH)
            addTag(rows, "Height",      exif, ExifInterface.TAG_IMAGE_LENGTH)
            addTag(rows, "Orientation", exif, ExifInterface.TAG_ORIENTATION)
            addTag(rows, "Color Space", exif, ExifInterface.TAG_COLOR_SPACE)
            addTag(rows, "Compression", exif, ExifInterface.TAG_COMPRESSION)
            addTag(rows, "Bits/Sample", exif, ExifInterface.TAG_BITS_PER_SAMPLE)
            addTag(rows, "Description", exif, ExifInterface.TAG_IMAGE_DESCRIPTION)

            // ── Camera ───────────────────────────────────────────────────
            rows += section("Camera")
            addTag(rows, "Make",        exif, ExifInterface.TAG_MAKE)
            addTag(rows, "Model",       exif, ExifInterface.TAG_MODEL)
            addTag(rows, "Lens Make",   exif, ExifInterface.TAG_LENS_MAKE)
            addTag(rows, "Lens Model",  exif, ExifInterface.TAG_LENS_MODEL)
            addTag(rows, "Software",    exif, ExifInterface.TAG_SOFTWARE)

            // ── Exposure ─────────────────────────────────────────────────
            rows += section("Exposure")
            addTag(rows, "Exposure Time",  exif, ExifInterface.TAG_EXPOSURE_TIME)
            addTag(rows, "F-Number",       exif, ExifInterface.TAG_F_NUMBER)
            addTag(rows, "ISO",            exif, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            addTag(rows, "Focal Length",   exif, ExifInterface.TAG_FOCAL_LENGTH)
            addTag(rows, "Focal Len 35mm", exif, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            addTag(rows, "Aperture",       exif, ExifInterface.TAG_APERTURE_VALUE)
            addTag(rows, "Shutter Speed",  exif, ExifInterface.TAG_SHUTTER_SPEED_VALUE)
            addTag(rows, "Exposure Prog.", exif, ExifInterface.TAG_EXPOSURE_PROGRAM)
            addTag(rows, "Exposure Mode",  exif, ExifInterface.TAG_EXPOSURE_MODE)
            addTag(rows, "Exp. Comp.",     exif, ExifInterface.TAG_EXPOSURE_BIAS_VALUE)
            addTag(rows, "Metering Mode",  exif, ExifInterface.TAG_METERING_MODE)
            addTag(rows, "Flash",          exif, ExifInterface.TAG_FLASH)
            addTag(rows, "White Balance",  exif, ExifInterface.TAG_WHITE_BALANCE)

            // ── Date/Time ────────────────────────────────────────────────
            rows += section("Date & Time")
            addTag(rows, "Date Taken",    exif, ExifInterface.TAG_DATETIME)
            addTag(rows, "Date Original", exif, ExifInterface.TAG_DATETIME_ORIGINAL)
            addTag(rows, "Date Digitized",exif, ExifInterface.TAG_DATETIME_DIGITIZED)
            addTag(rows, "Offset Time",   exif, ExifInterface.TAG_OFFSET_TIME)

            // ── GPS ──────────────────────────────────────────────────────
            rows += section("GPS")
            val latLon = exif.latLong
            if (latLon != null) {
                rows += ExifRow.Value("Latitude",  "%.6f°".format(latLon[0]))
                rows += ExifRow.Value("Longitude", "%.6f°".format(latLon[1]))
            }
            addTag(rows, "GPS Altitude",  exif, ExifInterface.TAG_GPS_ALTITUDE)
            addTag(rows, "GPS Speed",     exif, ExifInterface.TAG_GPS_SPEED)
            addTag(rows, "GPS Direction", exif, ExifInterface.TAG_GPS_IMG_DIRECTION)
            addTag(rows, "GPS Timestamp", exif, ExifInterface.TAG_GPS_TIMESTAMP)
            addTag(rows, "GPS Datestamp", exif, ExifInterface.TAG_GPS_DATESTAMP)

            // ── Copyright ────────────────────────────────────────────────
            rows += section("Copyright & Author")
            addTag(rows, "Artist",    exif, ExifInterface.TAG_ARTIST)
            addTag(rows, "Copyright", exif, ExifInterface.TAG_COPYRIGHT)

            // Remove trailing empty sections
            rows.removeAll { it is ExifRow.Section && rows.indexOf(it) == rows.lastIndex }
            // Remove sections with no following values
            val toRemove = mutableListOf<Int>()
            rows.forEachIndexed { i, row ->
                if (row is ExifRow.Section) {
                    val nextSection = rows.drop(i + 1).indexOfFirst { it is ExifRow.Section }
                    val valueCount = if (nextSection == -1) rows.size - i - 1
                    else nextSection
                    if (valueCount == 0) toRemove += i
                }
            }
            toRemove.sortedDescending().forEach { rows.removeAt(it) }

        } catch (e: Exception) {
            rows += ExifRow.Value("Error", e.message ?: "Could not read EXIF")
        }
        return rows
    }

    private fun addTag(rows: MutableList<ExifRow>, label: String, exif: ExifInterface, tag: String) {
        val value = exif.getAttribute(tag) ?: return
        if (value.isBlank()) return
        rows += ExifRow.Value(label, value)
    }

    private fun section(title: String) = ExifRow.Section(title)

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    // ─── Data ─────────────────────────────────────────────────────────────

    sealed class ExifRow {
        data class Section(val title: String) : ExifRow()
        data class Value(val label: String, val value: String) : ExifRow()
    }

    // ─── Adapter ──────────────────────────────────────────────────────────

    inner class ExifAdapter(private val items: List<ExifRow>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_SECTION = 0
        private val TYPE_VALUE   = 1

        override fun getItemViewType(position: Int) =
            if (items[position] is ExifRow.Section) TYPE_SECTION else TYPE_VALUE

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_SECTION) {
                val tv = inflater.inflate(android.R.layout.preference_category, parent, false) as TextView
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                val v = inflater.inflate(R.layout.item_exif_row, parent, false)
                object : RecyclerView.ViewHolder(v) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is ExifRow.Section -> (holder.itemView as TextView).text = row.title
                is ExifRow.Value   -> {
                    holder.itemView.findViewById<TextView>(R.id.tvExifLabel).text = row.label
                    holder.itemView.findViewById<TextView>(R.id.tvExifValue).text = row.value
                }
            }
        }
    }
}
