package dev.po4yka.chur.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import dev.po4yka.chur.app.LauncherAction
import java.util.concurrent.Executors

/**
 * Fixed, public capabilities only. Never publish session state or private content.
 *
 * The one extra is the per-install [LauncherToken]. MainActivity is exported, so
 * any app can send it an action; only the launcher that holds the shortcut has the
 * token, and an action without it is an ordinary launch.
 */
internal fun launcherShortcutIntent(context: Context, action: LauncherAction): Intent =
    Intent(context, MainActivity::class.java).apply {
        this.action = action.externalAction
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_LAUNCHER_TOKEN, LauncherToken.get(context))
    }

/** The launcher action of [intent], or null when the action or the launcher token is wrong. */
internal fun externalLauncherAction(context: Context, intent: Intent): LauncherAction? =
    LauncherAction.fromExternalAction(intent.action)?.takeIf { LauncherToken.matches(context, intent) }

internal const val EXTRA_LAUNCHER_TOKEN = "dev.po4yka.chur.extra.LAUNCHER_TOKEN"

/**
 * A random value that only Chur and the launcher know. It names no vault, session or
 * identity, and it stays in noBackupFilesDir, so a restored install makes a new one
 * and republishes its shortcuts.
 */
internal object LauncherToken {
    @Volatile private var cached: String? = null

    fun get(context: Context): String = cached ?: synchronized(this) {
        cached ?: load(context.applicationContext).also { cached = it }
    }

    /**
     * True only when [intent] carries this install's token. A token that cannot be
     * read or stored matches nothing: the shortcut then opens the app with no command.
     */
    fun matches(context: Context, intent: Intent): Boolean {
        val supplied = try { intent.getStringExtra(EXTRA_LAUNCHER_TOKEN) } catch (_: RuntimeException) { null }
            ?: return false
        val expected = try { get(context) } catch (_: IOException) { return false } catch (_: RuntimeException) { return false }
        return MessageDigest.isEqual(supplied.toByteArray(), expected.toByteArray())
    }

    /** Drops the cached value, so the next use reads storage again. Tests only. */
    @androidx.annotation.VisibleForTesting
    internal fun forget() = synchronized(this) { cached = null }

    private fun load(context: Context): String {
        val file = File(context.noBackupFilesDir, "launcher-token")
        val stored = try { file.readText() } catch (_: IOException) { null }
        if (stored != null && TOKEN.matches(stored)) return stored
        val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val temporary = File(context.noBackupFilesDir, "launcher-token.tmp")
        temporary.writeText(token)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("the launcher token could not be stored")
        }
        return token
    }

    private val TOKEN = Regex("[0-9a-f]{32}")
}

internal fun launcherShortcutInfos(context: Context): List<ShortcutInfo> =
    (if (PublicLauncherPresentation.enabled(context)) listOf(LauncherAction.PUBLIC_NOTES, LauncherAction.PUBLIC_CALCULATOR, LauncherAction.PUBLIC_CAMERA)
        else LauncherAction.entries.filter { it.publicShell == null }).mapIndexed { rank, action ->
        val (label, icon) = when (action) {
            LauncherAction.PUBLIC_NOTES -> R.string.public_new_note to R.drawable.ic_public_notes
            LauncherAction.PUBLIC_CALCULATOR -> R.string.public_calculator to R.drawable.ic_public_calculator
            LauncherAction.PUBLIC_CAMERA -> R.string.public_camera to R.drawable.ic_public_camera
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
            .setActivity(PublicLauncherPresentation.component(context))
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
            } catch (_: IOException) {
                // The launcher token could not be read or stored; publish nothing
                // rather than shortcuts that no launch would accept.
                Log.w("ChurShortcuts", "App actions could not be refreshed.")
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
        val obsolete = manager.pinnedShortcuts.filter { it.id !in all.map { item -> item.id } }.map { it.id }
        if (obsolete.isNotEmpty()) manager.disableShortcuts(obsolete)
        manager.enableShortcuts(all.map { it.id })
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
