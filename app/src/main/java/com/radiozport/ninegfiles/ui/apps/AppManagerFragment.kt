package com.radiozport.ninegfiles.ui.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.databinding.FragmentAppManagerBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppItem(
    val packageName: String,
    val label: String,
    val versionName: String,
    val apkPath: String,
    val apkSize: Long,
    val isSystemApp: Boolean,
    val installTime: Long,
    val updatedTime: Long
)

class AppManagerFragment : Fragment() {

    private var _binding: FragmentAppManagerBinding? = null
    private val binding get() = _binding!!

    private val adapter = AppListAdapter { item -> showAppActions(item) }
    private var allApps: List<AppItem> = emptyList()
    private var showSystem = false
    private var sortMode = SortMode.NAME

    enum class SortMode { NAME, SIZE, INSTALL_DATE }

    // Reload the list when returning from the system uninstall dialog (confirmed or cancelled).
    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { loadApps() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter

        // Filter chips
        binding.chipUser.setOnCheckedChangeListener { _, _ -> applyFilter() }
        binding.chipSystem.setOnCheckedChangeListener { _, checked ->
            showSystem = checked; applyFilter()
        }

        // Sort chips
        binding.chipSortName.setOnClickListener { sortMode = SortMode.NAME; applyFilter() }
        binding.chipSortSize.setOnClickListener { sortMode = SortMode.SIZE; applyFilter() }
        binding.chipSortDate.setOnClickListener { sortMode = SortMode.INSTALL_DATE; applyFilter() }

        // Search
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadApps()
    }

    // ─── Load ─────────────────────────────────────────────────────────────

    private fun loadApps() {
        binding.progressApps.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            allApps = withContext(Dispatchers.IO) { queryInstalledApps() }
            binding.progressApps.isVisible = false
            applyFilter()
        }
    }

    private fun queryInstalledApps(): List<AppItem> {
        val pm = requireContext().packageManager
        val flags = PackageManager.GET_META_DATA
        return pm.getInstalledPackages(flags).mapNotNull { pkg ->
            runCatching {
                val ai = pkg.applicationInfo ?: return@mapNotNull null
                val label = pm.getApplicationLabel(ai).toString()
                AppItem(
                    packageName  = pkg.packageName,
                    label        = label,
                    versionName  = pkg.versionName ?: "?",
                    apkPath      = ai.sourceDir ?: "",
                    apkSize      = File(ai.sourceDir ?: "").length(),
                    isSystemApp  = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    installTime  = pkg.firstInstallTime,
                    updatedTime  = pkg.lastUpdateTime
                )
            }.getOrNull()
        }
    }

    private fun applyFilter() {
        val query = binding.etSearch.text?.toString()?.lowercase() ?: ""
        var list = allApps.filter { app ->
            val matchQuery = query.isEmpty() || app.label.lowercase().contains(query) ||
                app.packageName.lowercase().contains(query)
            val matchType = when {
                !showSystem && !binding.chipUser.isChecked -> true          // no filter active
                showSystem && !binding.chipUser.isChecked  -> app.isSystemApp
                !showSystem && binding.chipUser.isChecked  -> !app.isSystemApp
                else -> true
            }
            matchQuery && matchType
        }
        list = when (sortMode) {
            SortMode.NAME         -> list.sortedBy { it.label.lowercase() }
            SortMode.SIZE         -> list.sortedByDescending { it.apkSize }
            SortMode.INSTALL_DATE -> list.sortedByDescending { it.installTime }
        }
        binding.tvAppCount.text = "${list.size} apps"
        adapter.submitList(list)
    }

    // ─── Actions bottom sheet ─────────────────────────────────────────────

    private fun showAppActions(app: AppItem) {
        val dialog = BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.bottom_sheet_app_actions, null)
        dialog.setContentView(v)

        v.findViewById<TextView>(R.id.tvAppName).text = app.label
        v.findViewById<TextView>(R.id.tvAppPkg).text = app.packageName
        v.findViewById<TextView>(R.id.tvAppVersion).text = "v${app.versionName} · ${FileUtils.formatSize(app.apkSize)}"

        val pm = requireContext().packageManager
        v.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(
            runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
        )

        v.findViewById<View>(R.id.btnLaunch).setOnClickListener {
            dialog.dismiss()
            pm.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
                ?: Snackbar.make(binding.root, "App cannot be launched", Snackbar.LENGTH_SHORT).show()
        }
        v.findViewById<View>(R.id.btnAppInfo).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${app.packageName}")))
        }
        v.findViewById<View>(R.id.btnBackupApk).setOnClickListener {
            dialog.dismiss()
            backupApk(app)
        }
        v.findViewById<View>(R.id.btnUninstall).apply {
            isEnabled = !app.isSystemApp
            alpha = if (!app.isSystemApp) 1f else 0.4f
            setOnClickListener {
                dialog.dismiss()
                confirmUninstall(app)
            }
        }

        dialog.show()
    }

    private fun backupApk(app: AppItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                        "${app.label.replace("[^a-zA-Z0-9]".toRegex(), "_")}_${app.versionName}.apk"
                    )
                    File(app.apkPath).copyTo(dest, overwrite = true)
                    true
                }.getOrElse { false }
            }
            Snackbar.make(binding.root,
                if (ok) "APK saved to Downloads/${app.label}.apk" else "Backup failed",
                Snackbar.LENGTH_LONG).show()
        }
    }

    private fun confirmUninstall(app: AppItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Uninstall ${app.label}?")
            .setMessage("This will remove the app and all its data.")
            .setPositiveButton("Uninstall") { _, _ ->
                uninstallLauncher.launch(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─── Adapter ──────────────────────────────────────────────────────────────

class AppListAdapter(
    private val onClick: (AppItem) -> Unit
) : ListAdapter<AppItem, AppListAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) = a == b
        }
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView   = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView    = view.findViewById(R.id.tvAppName)
        val tvPkg: TextView     = view.findViewById(R.id.tvAppPkg)
        val tvSize: TextView    = view.findViewById(R.id.tvAppSize)
        val chipSystem: Chip    = view.findViewById(R.id.chipSystem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        val pm = holder.view.context.packageManager
        Glide.with(holder.view).load(
            runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
        ).into(holder.ivIcon)
        holder.tvName.text = app.label
        holder.tvPkg.text = app.packageName
        holder.tvSize.text = FileUtils.formatSize(app.apkSize)
        holder.chipSystem.isVisible = app.isSystemApp
        holder.view.setOnClickListener { onClick(app) }
    }
}
