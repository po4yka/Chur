package dev.po4yka.chur.android

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build

/** The user-started system add-tile prompt. This object lives with its activity. */
internal class QuickSettingsLock(private val activity: Activity) {
    private var inFlight = false

    /** All results use public action text and contain no session information. */
    fun request(onResult: (String) -> Unit) {
        fun report(message: Int) = onResult(activity.getString(message))

        if (Build.VERSION.SDK_INT < 33) {
            report(R.string.quick_settings_lock_manual)
            return
        }
        if (inFlight) {
            report(R.string.quick_settings_lock_in_progress)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            report(R.string.quick_settings_lock_foreground)
            return
        }
        val manager = activity.getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            report(R.string.quick_settings_lock_unavailable)
            return
        }
        inFlight = true
        try {
            manager.requestAddTileService(
                ComponentName(activity, ChurLockTileService::class.java),
                activity.getString(R.string.quick_settings_lock_label),
                Icon.createWithResource(activity, R.drawable.ic_lock_tile),
                activity.mainExecutor,
            ) { result ->
                inFlight = false
                report(
                    when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.quick_settings_lock_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.quick_settings_lock_already_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.quick_settings_lock_not_added
                        StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> R.string.quick_settings_lock_in_progress
                        StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND -> R.string.quick_settings_lock_foreground
                        StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE -> R.string.quick_settings_lock_unavailable
                        else -> R.string.quick_settings_lock_failed
                    },
                )
            }
        } catch (_: RuntimeException) {
            inFlight = false
            report(R.string.quick_settings_lock_failed)
        }
    }
}
