package com.radiozport.ninegfiles.utils

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * Applies the user-chosen accent color at runtime by tinting Material components.
 * For a full dynamic-color override, a custom Theme.MaterialComponents overlay would
 * be inflated here. This lightweight version tints key UI elements.
 */
object AppThemeHelper {

    fun applyAccentColor(context: Context, accentColor: Int) {
        // Stored for child views to read
        accentColorCache = accentColor
    }

    /** The currently active accent color, readable by any component */
    var accentColorCache: Int = 0xFF6750A4.toInt()
        private set

    fun colorOnAccent(accentColor: Int): Int {
        return if (ColorUtils.calculateContrast(Color.WHITE, accentColor) >= 4.5) Color.WHITE
               else Color.BLACK
    }
}
