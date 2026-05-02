package com.radiozport.ninegfiles.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-level singleton that bridges [NineGFilesApp]'s ProcessLifecycleObserver
 * with [MainActivity]'s biometric prompt.
 *
 * Why this exists
 * ---------------
 * ProcessLifecycleOwner lives in the Application layer, while the biometric
 * prompt must be shown from a FragmentActivity. Connecting them via a shared
 * StateFlow avoids a direct Application→Activity reference (which leaks) and
 * keeps the lock logic decoupled from any specific Activity subclass.
 *
 * Usage
 * -----
 * - [NineGFilesApp] calls [markPendingIfEnabled] whenever the app returns to
 *   the foreground after having been backgrounded.
 * - [MainActivity.onResume] collects [lockPending] and, when `true`, clears it
 *   with [consume] and shows the biometric prompt.
 */
object AppLockState {

    private val _lockPending = MutableStateFlow(false)

    /**
     * `true` if an app-lock authentication prompt should be shown the next
     * time a [MainActivity] reaches `onResume`.
     */
    val lockPending: StateFlow<Boolean> = _lockPending

    /**
     * Called by [NineGFilesApp]'s ProcessLifecycleObserver when the process
     * returns to the foreground.  Sets [lockPending] to `true` only if the
     * App Lock feature is currently enabled in settings.
     */
    fun markPendingIfEnabled(context: Context) {
        if (AppLockManager.isAppLockEnabled(context)) {
            _lockPending.value = true
        }
    }

    /**
     * Called by [MainActivity] immediately before it shows the biometric
     * prompt, so that subsequent `onResume` calls (e.g. returning from the
     * system settings screen) don't re-trigger the prompt.
     */
    fun consume() {
        _lockPending.value = false
    }
}
