package com.radiozport.ninegfiles.utils

import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager

/**
 * Helpers for adaptive layouts — supports phones (single pane)
 * and tablets/foldables (dual pane: file tree + preview/detail).
 */
object ScreenUtils {

    /** True on tablets (sw >= 600dp) or landscape phones >= 720dp */
    fun isLargeScreen(context: Context): Boolean {
        val config = context.resources.configuration
        val sw = config.smallestScreenWidthDp
        return sw >= 600
    }

    fun isTablet(context: Context): Boolean {
        val config = context.resources.configuration
        return config.smallestScreenWidthDp >= 720
    }

    fun isLandscape(context: Context): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /** Returns column count for grid based on screen width */
    fun suggestedGridSpan(context: Context): Int {
        val sw = context.resources.configuration.smallestScreenWidthDp
        return when {
            sw >= 900 -> 5
            sw >= 720 -> 4
            sw >= 600 -> 3
            sw >= 480 -> 3
            else -> 3
        }
    }

    /** Returns list item height category: COMPACT / NORMAL / LARGE */
    fun listItemDensity(context: Context): ItemDensity {
        val sw = context.resources.configuration.smallestScreenWidthDp
        return when {
            sw >= 720 -> ItemDensity.LARGE
            sw >= 480 -> ItemDensity.NORMAL
            else -> ItemDensity.COMPACT
        }
    }

    enum class ItemDensity { COMPACT, NORMAL, LARGE }

    /** Returns window width in dp */
    fun windowWidthDp(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val density = context.resources.displayMetrics.density
            (metrics.bounds.width() / density).toInt()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            val density = context.resources.displayMetrics.density
            (size.x / density).toInt()
        }
    }
}
