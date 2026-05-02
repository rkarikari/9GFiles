package com.radiozport.ninegfiles.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*import androidx.datastore.preferences.preferencesDataStore
import com.radiozport.ninegfiles.data.model.SortOption
import com.radiozport.ninegfiles.data.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "file_manager_prefs")

class AppPreferences(private val context: Context) {

    companion object {
        val KEY_SORT_OPTION = stringPreferencesKey("sort_option")
        val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        val KEY_SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val KEY_SHOW_EXTENSIONS = booleanPreferencesKey("show_extensions")
        val KEY_CONFIRM_DELETE = booleanPreferencesKey("confirm_delete")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_GRID_SPAN_COUNT = intPreferencesKey("grid_span_count")
        val KEY_FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        val KEY_LAST_PATH = stringPreferencesKey("last_path")
        val KEY_SHOW_FILE_INFO = booleanPreferencesKey("show_file_info")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_VIBRATE_ON_ACTION = booleanPreferencesKey("vibrate_on_action")
        val KEY_DOUBLE_TAP_BACK = booleanPreferencesKey("double_tap_back")
        val KEY_ACCENT_COLOR = intPreferencesKey("accent_color")
        val KEY_THUMBNAIL_QUALITY = intPreferencesKey("thumbnail_quality")
        val KEY_LIST_DENSITY      = stringPreferencesKey("list_density")   // compact | normal | comfortable
        val KEY_KEEP_PASTE_BAR         = booleanPreferencesKey("keep_paste_bar") // false = dismiss bar on paste (default)

        // ── Vault behaviour ───────────────────────────────────────────────
        val KEY_VAULT_DELETE_ORIGINAL     = booleanPreferencesKey("vault_delete_original")   // default true
        val KEY_VAULT_RESTORE_ON_EXPORT   = booleanPreferencesKey("vault_restore_on_export") // default true
        val KEY_VAULT_DELETE_AFTER_EXPORT = booleanPreferencesKey("vault_delete_after_export") // default true

        // ── Image viewer ──────────────────────────────────────────────────────
        val KEY_SLIDESHOW_INTERVAL     = intPreferencesKey("slideshow_interval_sec")   // default 4
        val KEY_SLIDESHOW_LOOP         = booleanPreferencesKey("slideshow_loop")        // default true

        // ── Media player ──────────────────────────────────────────────────────
        val KEY_PLAYER_SPEED           = floatPreferencesKey("player_speed")            // default 1.0
        val KEY_PLAYER_REPEAT_MODE     = stringPreferencesKey("player_repeat_mode")     // OFF | ALL | ONE
        val KEY_PLAYER_SHUFFLE         = booleanPreferencesKey("player_shuffle")         // default false

        // ── Text editor ──────────────────────────────────────────────────────
        val KEY_EDITOR_WORD_WRAP       = booleanPreferencesKey("editor_word_wrap")      // default true
        val KEY_EDITOR_FONT_SIZE       = intPreferencesKey("editor_font_size")          // sp, default 14
        val KEY_EDITOR_SHOW_LINE_NUMS  = booleanPreferencesKey("editor_line_numbers")   // default true

        // ── Advanced ─────────────────────────────────────────────────────────
        val KEY_TRASH_AUTO_CLEAN_DAYS  = intPreferencesKey("trash_auto_clean_days")     // 0 = off; default 30
        val KEY_DATE_FORMAT            = stringPreferencesKey("date_format")            // short | medium | iso
        val KEY_REMEMBER_LAST_PATH     = booleanPreferencesKey("remember_last_path")    // default true
        val KEY_SHOW_THUMBNAILS        = booleanPreferencesKey("show_thumbnails")        // default true
    }

    val sortOption: Flow<SortOption> = context.dataStore.data.map { prefs ->
        prefs[KEY_SORT_OPTION]?.let { SortOption.valueOf(it) } ?: SortOption.NAME_ASC
    }

    val viewMode: Flow<ViewMode> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIEW_MODE]?.let { ViewMode.valueOf(it) } ?: ViewMode.LIST
    }

    val showHidden: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_HIDDEN] ?: false
    }

    val showExtensions: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_EXTENSIONS] ?: true
    }

    val confirmDelete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONFIRM_DELETE] ?: true
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "system"
    }

    val gridSpanCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_GRID_SPAN_COUNT] ?: 3
    }

    val foldersFirst: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FOLDERS_FIRST] ?: true
    }

    val lastPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_PATH] ?: ""
    }

    val vibrateOnAction: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIBRATE_ON_ACTION] ?: true
    }

    val doubleTapBack: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOUBLE_TAP_BACK] ?: true
    }

    val thumbnailQuality: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_THUMBNAIL_QUALITY] ?: 80
    }

    val listDensity: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LIST_DENSITY] ?: "normal"
    }

    val showFileInfo: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_FILE_INFO] ?: true
    }

    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_SCREEN_ON] ?: false
    }

    val keepPasteBar: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_PASTE_BAR] ?: false
    }

    val vaultDeleteOriginal: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VAULT_DELETE_ORIGINAL] ?: true
    }

    val vaultRestoreOnExport: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VAULT_RESTORE_ON_EXPORT] ?: true
    }

    val vaultDeleteAfterExport: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VAULT_DELETE_AFTER_EXPORT] ?: true
    }

    suspend fun setShowFileInfo(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_FILE_INFO] = show }
    }

    suspend fun setKeepScreenOn(keep: Boolean) {
        context.dataStore.edit { it[KEY_KEEP_SCREEN_ON] = keep }
    }

    suspend fun setKeepPasteBar(keep: Boolean) {
        context.dataStore.edit { it[KEY_KEEP_PASTE_BAR] = keep }
    }

    suspend fun setVaultDeleteOriginal(v: Boolean) {
        context.dataStore.edit { it[KEY_VAULT_DELETE_ORIGINAL] = v }
    }

    suspend fun setVaultRestoreOnExport(v: Boolean) {
        context.dataStore.edit { it[KEY_VAULT_RESTORE_ON_EXPORT] = v }
    }

    suspend fun setVaultDeleteAfterExport(v: Boolean) {
        context.dataStore.edit { it[KEY_VAULT_DELETE_AFTER_EXPORT] = v }
    }

    suspend fun setSortOption(option: SortOption) {
        context.dataStore.edit { it[KEY_SORT_OPTION] = option.name }
    }

    suspend fun setViewMode(mode: ViewMode) {
        context.dataStore.edit { it[KEY_VIEW_MODE] = mode.name }
    }

    suspend fun setShowHidden(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_HIDDEN] = show }
    }

    suspend fun setShowExtensions(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_EXTENSIONS] = show }
    }

    suspend fun setConfirmDelete(confirm: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_DELETE] = confirm }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setGridSpanCount(count: Int) {
        context.dataStore.edit { it[KEY_GRID_SPAN_COUNT] = count }
    }

    suspend fun setFoldersFirst(first: Boolean) {
        context.dataStore.edit { it[KEY_FOLDERS_FIRST] = first }
    }

    suspend fun setLastPath(path: String) {
        context.dataStore.edit { it[KEY_LAST_PATH] = path }
    }

    suspend fun setVibrateOnAction(vibrate: Boolean) {
        context.dataStore.edit { it[KEY_VIBRATE_ON_ACTION] = vibrate }
    }

    suspend fun setDoubleTapBack(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DOUBLE_TAP_BACK] = enabled }
    }

    suspend fun setThumbnailQuality(quality: Int) {
        context.dataStore.edit { it[KEY_THUMBNAIL_QUALITY] = quality }
    }

    suspend fun setListDensity(density: String) {
        context.dataStore.edit { it[KEY_LIST_DENSITY] = density }
    }

    val accentColor: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACCENT_COLOR] ?: 0xFF6750A4.toInt()
    }

    suspend fun setAccentColor(color: Int) {
        context.dataStore.edit { it[KEY_ACCENT_COLOR] = color }
    }

    // ── Image Viewer ─────────────────────────────────────────────────────────

    val slideshowInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SLIDESHOW_INTERVAL] ?: 4
    }
    val slideshowLoop: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SLIDESHOW_LOOP] ?: true
    }
    suspend fun setSlideshowInterval(sec: Int) { context.dataStore.edit { it[KEY_SLIDESHOW_INTERVAL] = sec } }
    suspend fun setSlideshowLoop(loop: Boolean) { context.dataStore.edit { it[KEY_SLIDESHOW_LOOP] = loop } }

    // ── Media Player ─────────────────────────────────────────────────────────

    val playerSpeed: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_PLAYER_SPEED] ?: 1.0f
    }
    val playerRepeatMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PLAYER_REPEAT_MODE] ?: "OFF"
    }
    val playerShuffle: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PLAYER_SHUFFLE] ?: false
    }
    suspend fun setPlayerSpeed(speed: Float) { context.dataStore.edit { it[KEY_PLAYER_SPEED] = speed } }
    suspend fun setPlayerRepeatMode(mode: String) { context.dataStore.edit { it[KEY_PLAYER_REPEAT_MODE] = mode } }
    suspend fun setPlayerShuffle(enabled: Boolean) { context.dataStore.edit { it[KEY_PLAYER_SHUFFLE] = enabled } }

    // ── Text Editor ──────────────────────────────────────────────────────────

    val editorWordWrap: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EDITOR_WORD_WRAP] ?: true
    }
    val editorFontSize: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_EDITOR_FONT_SIZE] ?: 14
    }
    val editorShowLineNumbers: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EDITOR_SHOW_LINE_NUMS] ?: true
    }
    suspend fun setEditorWordWrap(wrap: Boolean) { context.dataStore.edit { it[KEY_EDITOR_WORD_WRAP] = wrap } }
    suspend fun setEditorFontSize(sp: Int) { context.dataStore.edit { it[KEY_EDITOR_FONT_SIZE] = sp } }
    suspend fun setEditorShowLineNumbers(show: Boolean) { context.dataStore.edit { it[KEY_EDITOR_SHOW_LINE_NUMS] = show } }

    // ── Advanced ─────────────────────────────────────────────────────────────

    val trashAutoCleanDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_TRASH_AUTO_CLEAN_DAYS] ?: 30
    }
    val dateFormat: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DATE_FORMAT] ?: "medium"
    }
    val rememberLastPath: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_REMEMBER_LAST_PATH] ?: true
    }
    val showThumbnails: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_THUMBNAILS] ?: true
    }
    suspend fun setTrashAutoCleanDays(days: Int) { context.dataStore.edit { it[KEY_TRASH_AUTO_CLEAN_DAYS] = days } }
    suspend fun setDateFormat(format: String) { context.dataStore.edit { it[KEY_DATE_FORMAT] = format } }
    suspend fun setRememberLastPath(remember: Boolean) { context.dataStore.edit { it[KEY_REMEMBER_LAST_PATH] = remember } }
    suspend fun setShowThumbnails(show: Boolean) { context.dataStore.edit { it[KEY_SHOW_THUMBNAILS] = show } }
}
