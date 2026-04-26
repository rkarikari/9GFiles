package com.radiozport.ninegfiles.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
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
        val KEY_KEEP_PASTE_BAR    = booleanPreferencesKey("keep_paste_bar") // false = dismiss bar on paste (default)
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

    val keepPasteBar: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_PASTE_BAR] ?: false
    }

    suspend fun setKeepPasteBar(keep: Boolean) {
        context.dataStore.edit { it[KEY_KEEP_PASTE_BAR] = keep }
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
}
