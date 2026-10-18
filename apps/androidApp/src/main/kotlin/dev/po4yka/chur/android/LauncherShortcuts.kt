package dev.po4yka.chur.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import dev.po4yka.chur.app.LauncherAction
import java.util.concurrent.Executors

/** Fixed, public capabilities only. Never publish session state or private content. */
internal fun launcherShortcutIntent(context: Context, action: LauncherAction): Intent =
    Intent(context, MainActivity::class.java).apply {
        this.action = action.externalAction
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

internal fun launcherShortcutInfos(context: Context): List<ShortcutInfo> =
    LauncherAction.entries.mapIndexed { rank, action ->
        val (label, icon) = when (action) {
            LauncherAction.IMPORT -> R.string.launcher_import to R.drawable.ic_launcher_import
            LauncherAction.SEARCH -> R.string.launcher_search to R.drawable.ic_launcher_search
            LauncherAction.LOCK_AND_HIDE -> R.string.launcher_lock_and_hide to R.drawable.ic_launcher_lock
            LauncherAction.NEW_PRIVATE_NOTE -> R.string.launcher_new_private_note to R.drawable.ic_launcher_note
        }
        val shortLabel = when (action) {
            LauncherAction.LOCK_AND_HIDE -> R.string.launcher_lock_and_hide_short
            LauncherAction.NEW_PRIVATE_NOTE -> R.string.launcher_new_private_note_short
            else -> label
        }
        ShortcutInfo.Builder(context, action.shortcutId)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(context.getString(shortLabel))
            .setLongLabel(context.getString(label))
            .setIcon(Icon.createWithResource(context, icon))
            .setRank(rank)
            .setIntent(launcherShortcutIntent(context, action))
            .build()
    }

/** Consume external commands into process state; recreation retains no payload or action to replay. */
internal fun sanitizedLauncherIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)

internal object LauncherShortcuts {
    // These platform calls may block for seconds. Serialize refreshes across all
    // activity instances and locale broadcasts, without holding a window alive.
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Chur-launcher-shortcuts").apply { isDaemon = true }
    }
    private var publishedSignature: String? = null

    fun refresh(context: Context, finished: () -> Unit = {}) {
        val application = context.applicationContext
        worker.execute {
            try {
                publish(application)
            } catch (_: RuntimeException) {
                // The OS may refuse updates while the device user is locked.
                // Retry on the next foreground/locale refresh, never by polling.
                Log.w("ChurShortcuts", "App actions could not be refreshed.")
            } finally {
                finished()
            }
        }
    }

    private fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val all = launcherShortcutInfos(context)
        val visible = all.take(manager.maxShortcutCountPerActivity.coerceAtLeast(0))
        val signature = all.joinToString("|") { "${it.id}:${it.rank}:${it.shortLabel}" } + ":${visible.size}"
        val existingIds = manager.dynamicShortcuts.map { it.id }.toSet()
        if (publishedSignature == signature && existingIds == visible.map { it.id }.toSet()) return
        if (!manager.setDynamicShortcuts(visible)) {
            Log.w("ChurShortcuts", "App actions refresh was rate limited.")
            return
        }
        // Also update mutable pinned copies omitted from a small launcher's menu.
        val pinnedIds = manager.pinnedShortcuts.map { it.id }.toSet()
        val pinnedOnly = all.filter { it.id in pinnedIds && it.id !in visible.map { item -> item.id } }
        if (pinnedOnly.isNotEmpty() && !manager.updateShortcuts(pinnedOnly)) {
            Log.w("ChurShortcuts", "Pinned app actions refresh was rate limited.")
            return
        }
        publishedSignature = signature
    }
}

/** Refresh even when no activity is alive; labels stay generic in every locale. */
class LauncherLocaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED && intent.action != Intent.ACTION_APPLICATION_LOCALE_CHANGED) return
        val pending = goAsync()
        LauncherShortcuts.refresh(context) { pending.finish() }
    }
}
