package com.radiozport.ninegfiles.ui.viewer

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.radiozport.ninegfiles.databinding.FragmentApkInfoBinding
import com.radiozport.ninegfiles.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ApkInfoFragment : Fragment() {

    private var _binding: FragmentApkInfoBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance(path: String) = ApkInfoFragment().apply {
            arguments = bundleOf("apkPath" to path)
        }
    }

    data class ApkInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val appName: String,
        val minSdk: Int,
        val targetSdk: Int,
        val permissions: List<String>,
        val icon: Drawable?,
        val fileSize: Long,
        val isInstalled: Boolean,
        val installedVersion: String?
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApkInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = arguments?.getString("apkPath") ?: return
        val file = File(path)

        binding.tvFileName.text = file.name
        binding.tvFileSize.text = FileUtils.formatSize(file.length())
        binding.progressBar.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { parseApk(path) }
            if (_binding == null) return@launch

            binding.progressBar.isVisible = false

            if (info == null) {
                binding.tvError.text = "Failed to parse APK"
                binding.tvError.isVisible = true
                return@launch
            }

            bindApkInfo(info, path)
        }
    }

    private fun bindApkInfo(info: ApkInfo, path: String) {
        // App icon
        if (info.icon != null) {
            binding.ivAppIcon.setImageDrawable(info.icon)
        } else {
            binding.ivAppIcon.setImageResource(com.radiozport.ninegfiles.R.drawable.ic_file_apk)
        }

        // Basic info
        binding.tvAppName.text = info.appName.ifEmpty { info.packageName }
        binding.tvPackageName.text = info.packageName
        binding.tvVersion.text = "${info.versionName} (${info.versionCode})"
        binding.tvSdkRange.text = "API ${info.minSdk} – ${info.targetSdk}"

        // Install status
        if (info.isInstalled) {
            binding.chipInstalled.isVisible = true
            binding.tvInstalledVersion.text = "Installed: v${info.installedVersion}"
            binding.tvInstalledVersion.isVisible = true
            binding.btnInstall.text = if (info.installedVersion == info.versionName) "Reinstall" else "Update"
        } else {
            binding.chipInstalled.isVisible = false
            binding.tvInstalledVersion.isVisible = false
            binding.btnInstall.text = "Install"
        }

        // Permissions
        val dangerousPerms = info.permissions.filter { perm ->
            try {
                val info2 = requireContext().packageManager.getPermissionInfo(perm, 0)
                @Suppress("DEPRECATION")
                (info2.protectionLevel and 0xFF) == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
            } catch (_: Exception) { false }
        }

        binding.tvPermCount.text = "${info.permissions.size} permissions (${dangerousPerms.size} sensitive)"
        binding.tvPermissions.text = info.permissions
            .sortedBy { it.substringAfterLast('.') }
            .joinToString("\n") { "• ${it.substringAfterLast('.')}" }

        // Install action
        binding.btnInstall.setOnClickListener {
            installApk(path)
        }

        // Open installed app
        binding.btnOpenApp.isVisible = info.isInstalled
        binding.btnOpenApp.setOnClickListener {
            val launchIntent = requireContext().packageManager
                .getLaunchIntentForPackage(info.packageName)
            if (launchIntent != null) startActivity(launchIntent)
        }

        // App info (if installed)
        binding.btnAppInfo.isVisible = info.isInstalled
        binding.btnAppInfo.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${info.packageName}")
            })
        }
    }

    private fun parseApk(path: String): ApkInfo? {
        return try {
            val pm = requireContext().packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                path,
                PackageManager.GET_PERMISSIONS
            ) ?: return null

            packageInfo.applicationInfo?.sourceDir = path
            packageInfo.applicationInfo?.publicSourceDir = path

            val appName = try {
                packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: ""
            } catch (_: Exception) { "" }

            val icon: Drawable? = try {
                packageInfo.applicationInfo?.loadIcon(pm)
            } catch (_: Exception) { null }

            val installedInfo = try {
                pm.getPackageInfo(packageInfo.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) { null }

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            ApkInfo(
                packageName = packageInfo.packageName ?: "",
                versionName = packageInfo.versionName ?: "?",
                versionCode = versionCode,
                appName = appName,
                minSdk = packageInfo.applicationInfo?.minSdkVersion ?: 0,
                targetSdk = packageInfo.applicationInfo?.targetSdkVersion ?: 0,
                permissions = packageInfo.requestedPermissions?.toList() ?: emptyList(),
                icon = icon,
                fileSize = File(path).length(),
                isInstalled = installedInfo != null,
                installedVersion = installedInfo?.versionName
            )
        } catch (e: Exception) { null }
    }

    private fun installApk(path: String) {
        // On Android 8+, the app needs "Install unknown apps" permission.
        // Check and redirect to settings if not yet granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !requireContext().packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            )
            com.google.android.material.snackbar.Snackbar
                .make(binding.root,
                    "Enable \"Install unknown apps\" for 9GFiles, then try again",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            File(path)
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
