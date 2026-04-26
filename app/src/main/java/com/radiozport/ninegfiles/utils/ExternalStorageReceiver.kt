package com.radiozport.ninegfiles.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

/**
 * Dual-path hot-plug detector for external storage volumes
 * (OTG thumb-drives, USB mass-storage, SD cards).
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  API 29+ (Android 10+)                                          │
 * │  Uses StorageManager.StorageVolumeCallback — the OS calls us    │
 * │  the instant a volume state changes, before any broadcast fires. │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  API 21–28 fallback                                             │
 * │  BroadcastReceiver listening for ACTION_MEDIA_MOUNTED /          │
 * │  UNMOUNTED / REMOVED / BAD_REMOVAL / EJECT / CHECKING / NOFS.  │
 * │  A 250 ms delay is added so StorageManager.storageVolumes has   │
 * │  time to reflect the new state before the UI reads it.          │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * On Android 13+ (API 33) the BroadcastReceiver is registered with
 * RECEIVER_EXPORTED so the system process (uid 1000) can deliver the
 * protected ACTION_MEDIA_* broadcasts to it.
 *
 * Usage:
 * ```kotlin
 * private val storageReceiver = ExternalStorageReceiver { mounted ->
 *     setupDrivesRow()   // called on main thread, drive list already updated
 * }
 * override fun onStart() { super.onStart(); storageReceiver.register(requireContext()) }
 * override fun onStop()  { super.onStop();  storageReceiver.unregister(requireContext()) }
 * ```
 */
class ExternalStorageReceiver(
    private val onVolumeChanged: (mounted: Boolean) -> Unit
) : BroadcastReceiver() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── API 29+ StorageVolumeCallback ─────────────────────────────────────
    private val volumeCallback: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("NewApi")
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    val mounted = volume.state == android.os.Environment.MEDIA_MOUNTED
                    mainHandler.post { onVolumeChanged(mounted) }
                }
            }
        } else null

    // ── BroadcastReceiver (API 21-28 fallback) ────────────────────────────
    override fun onReceive(context: Context, intent: Intent) {
        val mounted = intent.action == Intent.ACTION_MEDIA_MOUNTED ||
                      intent.action == Intent.ACTION_MEDIA_CHECKING
        // Delay 250 ms so StorageManager.storageVolumes reflects the new state
        mainHandler.postDelayed({ onVolumeChanged(mounted) }, 250L)
    }

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeCallback != null) {
            // Primary path: StorageVolumeCallback — immediate, no races
            @Suppress("NewApi")
            val sm = context.getSystemService(StorageManager::class.java)
            @Suppress("NewApi")
            sm.registerStorageVolumeCallback(
                context.mainExecutor,
                volumeCallback as StorageManager.StorageVolumeCallback
            )
        }

        // Always also register the broadcast receiver:
        //  • On API < 29 it is the only path.
        //  • On API 29+ it is a belt-and-suspenders fallback that catches
        //    edge cases where the volume callback may not fire (e.g. some
        //    OEM USB-host implementations).
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addAction(Intent.ACTION_MEDIA_NOFS)
            addDataScheme("file")          // required for all ACTION_MEDIA_* intents
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ACTION_MEDIA_* are protected system broadcasts from uid 1000.
            // RECEIVER_EXPORTED is required — NOT_EXPORTED silently drops them
            // on many OEM builds running Android 13+.
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
    }

    fun unregister(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volumeCallback != null) {
            @Suppress("NewApi")
            val sm = context.getSystemService(StorageManager::class.java)
            @Suppress("NewApi")
            sm.unregisterStorageVolumeCallback(
                volumeCallback as StorageManager.StorageVolumeCallback
            )
        }
        try { context.unregisterReceiver(this) } catch (_: IllegalArgumentException) {}
        mainHandler.removeCallbacksAndMessages(null)
    }
}

