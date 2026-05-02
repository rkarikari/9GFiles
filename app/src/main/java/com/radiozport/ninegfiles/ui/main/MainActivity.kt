package com.radiozport.ninegfiles.ui.main

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.*
import com.radiozport.ninegfiles.R
import com.radiozport.ninegfiles.data.model.OperationResult
import com.radiozport.ninegfiles.databinding.ActivityMainBinding
import com.radiozport.ninegfiles.ui.dialogs.BatchRenameDialog
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModel
import com.radiozport.ninegfiles.ui.explorer.FileExplorerViewModelFactory
import com.radiozport.ninegfiles.utils.AppLockManager
import com.radiozport.ninegfiles.utils.AppLockState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    /** True while biometric auth is pending after the app returns from background.
     *  The source of truth is [AppLockState] (set by the ProcessLifecycleObserver
     *  in NineGFilesApp); this local flag is kept for immediate onResume reads
     *  before the StateFlow collection starts. */
    private var lockPending: Boolean
        get()      = AppLockState.lockPending.value
        set(value) { if (!value) AppLockState.consume() }

    val viewModel: FileExplorerViewModel by viewModels {
        FileExplorerViewModelFactory(application)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) viewModel.refresh()
        else showPermissionRationale()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
            viewModel.refresh()
        else showPermissionRationale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restoreStatusBar()
        setupNavigation()
        setupToolbar()
        setupBottomNav()
        observeViewModel()
        requestStoragePermissions()
        handleIncomingIntent(intent)
        // Restore the last-visited folder on cold start (if preference is on and no explicit intent)
        if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
            lifecycleScope.launch {
                val app = application as com.radiozport.ninegfiles.NineGFilesApp
                val remember = app.preferences.rememberLastPath.first()
                if (remember) {
                    val lastPath = app.preferences.lastPath.first()
                    if (lastPath.isNotEmpty() && java.io.File(lastPath).isDirectory) {
                        viewModel.navigate(lastPath)
                    }
                }
            }
        }
    }

    /**
     * The splash-screen window temporarily applies its own background color
     * (windowSplashScreenBackground = md_theme_primary) which can leave the
     * status bar in a darkened state.  Explicitly re-apply the correct icon
     * tint for the current day/night mode so the status bar is always readable.
     */
    private fun restoreStatusBar() {
        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = !isNight   // dark icons in day, light icons in night
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.explorerFragment, R.id.searchFragment,
                  R.id.toolsFragment, R.id.bookmarksFragment),
            binding.drawerLayout
        )
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.isInSelectionMode.value) viewModel.clearSelection()
            else if (!navController.navigateUp(appBarConfiguration)) onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBottomNav() {
        // setupWithNavController keeps the visual tab indicator in sync with the
        // current destination and enables multi-back-stack save/restore for
        // non-home tabs. We then override the item-selected listener below to
        // fix the Home button behaviour.
        binding.bottomNav.setupWithNavController(navController)
        binding.navigationView.setupWithNavController(navController)

        // Populate the nav-drawer version label from PackageManager so build.gradle
        // is the single source of truth — no hardcoded version strings elsewhere.
        try {
            val versionName = packageManager
                .getPackageInfo(packageName, 0).versionName
            binding.navigationView.getHeaderView(0)
                .findViewById<android.widget.TextView>(R.id.tvNavVersion)
                ?.text = "v$versionName"
        } catch (_: Exception) { }

        // Override item-selected so the Home tab ALWAYS pops the entire back
        // stack back to homeFragment, no matter how deep navigation went.
        // Without this override, fragments opened via a raw navigate() call
        // (e.g. quick-access Downloads button) bypass the NavigationUI
        // save/restore mechanism, causing the default handler to silently
        // re-navigate to explorerFragment instead of going back to Home.
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.homeFragment) {
                // Pop everything above homeFragment (the start destination).
                // Returns false only if homeFragment is not in the stack, which
                // should never happen — the navigate() fallback is a safety net.
                val popped = navController.popBackStack(R.id.homeFragment, false)
                if (!popped) {
                    navController.navigate(R.id.homeFragment)
                }
                true
            } else {
                // All other tabs use the standard NavigationUI logic which
                // correctly handles multi-back-stack save/restore.
                NavigationUI.onNavDestinationSelected(item, navController)
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val showBottom = destination.id in listOf(
                R.id.homeFragment, R.id.explorerFragment, R.id.searchFragment,
                R.id.toolsFragment, R.id.bookmarksFragment)
            binding.bottomNav.visibility = if (showBottom) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.viewMode.collectLatest { invalidateOptionsMenu() }
                }
                launch {
                    viewModel.isInSelectionMode.collectLatest { inSelectionMode ->
                        invalidateOptionsMenu()
                        if (inSelectionMode)
                            binding.toolbar.title = "${viewModel.selectionCount.value} selected"
                    }
                }
                launch {
                    viewModel.selectionCount.collectLatest { count ->
                        if (viewModel.isInSelectionMode.value)
                            binding.toolbar.title = "$count selected"
                    }
                }
                launch {
                    viewModel.operationResult.collectLatest { result ->
                        when (result) {
                            is OperationResult.Success ->
                                Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT)
                                    .setAnchorView(binding.bottomNav).show()
                            is OperationResult.Failure ->
                                Snackbar.make(binding.root, "Error: ${result.error}", Snackbar.LENGTH_LONG)
                                    .setAnchorView(binding.bottomNav)
                                    .setAction("Dismiss") {}.show()
                            else -> {}
                        }
                    }
                }
                // Apply / clear FLAG_KEEP_SCREEN_ON whenever the preference changes.
                launch {
                    (application as com.radiozport.ninegfiles.NineGFilesApp)
                        .preferences.keepScreenOn.collectLatest { keep ->
                        if (keep) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else      window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (viewModel.isInSelectionMode.value) menuInflater.inflate(R.menu.menu_selection, menu)
        else {
            menuInflater.inflate(R.menu.menu_main, menu)
            // Update the toggle icon to reflect the current view mode
            val toggleItem = menu.findItem(R.id.action_view_toggle)
            toggleItem?.setIcon(when (viewModel.viewMode.value) {
                com.radiozport.ninegfiles.data.model.ViewMode.LIST    -> R.drawable.ic_view_list
                com.radiozport.ninegfiles.data.model.ViewMode.GRID    -> R.drawable.ic_view_list
                com.radiozport.ninegfiles.data.model.ViewMode.COMPACT -> R.drawable.ic_view_list
            })
            toggleItem?.title = when (viewModel.viewMode.value) {
                com.radiozport.ninegfiles.data.model.ViewMode.LIST    -> "Grid View"
                com.radiozport.ninegfiles.data.model.ViewMode.GRID    -> "Compact View"
                com.radiozport.ninegfiles.data.model.ViewMode.COMPACT -> "List View"
            }
            menu.findItem(R.id.action_show_hidden)?.isChecked = viewModel.showHidden.value
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val selected = viewModel.getSelectedFileItems()
        return when (item.itemId) {
            R.id.action_select_all    -> { viewModel.selectAll(); true }
            R.id.action_copy          -> { viewModel.copy(selected); true }
            R.id.action_cut           -> { viewModel.cut(selected); true }
            R.id.action_delete        -> { confirmTrash(selected.size); true }
            R.id.action_delete_permanently -> { confirmPermanentDelete(selected.size); true }
            R.id.action_shred         -> { confirmShred(selected.size); true }
            R.id.action_batch_rename  -> {
                if (selected.isNotEmpty()) {
                    BatchRenameDialog(selected) { template ->
                        viewModel.batchRename(selected, template)
                    }.show(supportFragmentManager, "BatchRenameDialog")
                }
                true
            }
            R.id.action_compress      -> {
                if (selected.isNotEmpty()) {
                    com.radiozport.ninegfiles.ui.dialogs.CompressDialog(selected) { name ->
                        viewModel.compress(selected, name)
                    }.show(supportFragmentManager, "CompressDialog")
                }
                true
            }
            R.id.action_compress_encrypted -> {
                if (selected.isNotEmpty()) {
                    com.radiozport.ninegfiles.ui.dialogs.EncryptedZipDialog.show(
                        supportFragmentManager, selected
                    ) { path ->
                        com.google.android.material.snackbar.Snackbar
                            .make(binding.root, "Saved: $path", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .setAnchorView(binding.bottomNav).show()
                        viewModel.refresh()
                    }
                }
                true
            }
            R.id.action_share         -> {
                if (selected.size == 1) {
                    val item = selected.first()
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", item.file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = item.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share"))
                }
                true
            }
            R.id.action_view_toggle -> {
                val next = when (viewModel.viewMode.value) {
                    com.radiozport.ninegfiles.data.model.ViewMode.LIST    -> com.radiozport.ninegfiles.data.model.ViewMode.GRID
                    com.radiozport.ninegfiles.data.model.ViewMode.GRID    -> com.radiozport.ninegfiles.data.model.ViewMode.COMPACT
                    com.radiozport.ninegfiles.data.model.ViewMode.COMPACT -> com.radiozport.ninegfiles.data.model.ViewMode.LIST
                }
                viewModel.setViewMode(next)
                true
            }
            R.id.action_sort -> {
                com.radiozport.ninegfiles.ui.dialogs.SortDialog(
                    viewModel.getEffectiveSortOption()
                ) { sortOption ->
                    if (navController.currentDestination?.id == R.id.searchFragment) {
                        viewModel.setSortOption(sortOption)
                    } else {
                        viewModel.setFolderSortOption(sortOption)
                    }
                }.show(supportFragmentManager, "SortDialog")
                true
            }
            R.id.action_show_hidden -> {
                val newVal = !viewModel.showHidden.value
                viewModel.setShowHidden(newVal)
                item.isChecked = newVal
                true
            }
            R.id.action_settings -> { navController.navigate(R.id.settingsFragment); true }
            R.id.action_search   -> { navController.navigate(R.id.searchFragment); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmTrash(count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Move $count item(s) to Trash?")
            .setPositiveButton("Move to Trash") { _, _ -> viewModel.trash(viewModel.getSelectedFileItems()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmPermanentDelete(count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permanently delete $count item(s)?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> viewModel.delete(viewModel.getSelectedFileItems()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmShred(count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Securely shred $count item(s)?")
            .setMessage("Files will be overwritten 3 times then deleted. Cannot be undone.")
            .setPositiveButton("Shred") { _, _ -> viewModel.shred(viewModel.getSelectedFileItems()) }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onSupportNavigateUp() = navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    // ─── App Lock gate ────────────────────────────────────────────────────

    override fun onStop() {
        super.onStop()
        // NineGFilesApp's ProcessLifecycleObserver is the primary mechanism for
        // setting the lock-pending flag across all entry points. MainActivity's
        // onStop is a belt-and-suspenders backup for the case where the observer
        // hasn't fired yet (e.g. the activity is the only one in the stack and
        // the process is about to die).
        if (AppLockManager.isAppLockEnabled(this)) AppLockState.markPendingIfEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        if (lockPending && AppLockManager.isAppLockEnabled(this)) {
            lockPending = false          // clears AppLockState via the property setter
            binding.root.alpha = 0f
            AppLockManager.authenticate(
                activity = this,
                title    = "Unlock 9G Files",
                subtitle = "Authenticate to continue"
            ) { success, _ ->
                if (success) {
                    binding.root.alpha = 1f
                } else {
                    AppLockState.markPendingIfEnabled(this)   // re-arm for next resume
                    finish()
                }
            }
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("9G Files needs access to manage all files on your device.")
                    .setPositiveButton("Grant Access") { _, _ ->
                        manageStorageLauncher.launch(Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .setCancelable(false).show()
            }
        } else {
            storagePermissionLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Denied")
            .setMessage("Without storage permission the app cannot function. Please grant it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        // Handle ACTION_OPEN_PATH from home screen shortcuts
        if (intent?.action == "com.radiozport.ninegfiles.ACTION_OPEN_PATH") {
            val path = intent.getStringExtra("open_path") ?: return
            openPathInExplorer(path)
        }
        // Handle navigate_to from Wi-Fi Direct / other notification taps
        intent?.getStringExtra("navigate_to")?.let { path -> openPathInExplorer(path) }
    }

    private fun openPathInExplorer(path: String) {
        viewModel.navigate(path)
        navController.addOnDestinationChangedListener(object :
            NavController.OnDestinationChangedListener {
            override fun onDestinationChanged(
                controller: NavController,
                destination: androidx.navigation.NavDestination,
                arguments: Bundle?
            ) {
                if (destination.id == R.id.homeFragment) {
                    controller.navigate(R.id.explorerFragment)
                    controller.removeOnDestinationChangedListener(this)
                }
            }
        })
    }
}
