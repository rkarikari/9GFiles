package com.radiozport.ninegfiles.data.drive

import android.content.Context

/**
 * DriveManager — Google Drive REST API removed.
 *
 * Google Drive access is now handled exclusively through Android's
 * Storage Access Framework (SAF) via CloudStorageFragment, which works
 * with any cloud provider (Drive, Dropbox, OneDrive, Box, etc.) without
 * requiring an API key, OAuth client ID, or Google Play Services.
 *
 * This stub is kept so that any remaining import references compile;
 * all actual functionality has been removed.
 */
@Suppress("unused")
object DriveManager {
    /** Always returns false — Drive API sign-in is no longer used. */
    fun isSignedIn(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    /** Always returns null — Drive API accounts are no longer used. */
    fun getAccount(@Suppress("UNUSED_PARAMETER") context: Context): Nothing? = null
}
