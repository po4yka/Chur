package dev.po4yka.chur.android

import android.content.Context
import android.util.AtomicFile
import androidx.fragment.app.FragmentActivity
import dev.po4yka.chur.app.AndroidPrivacyCover
import dev.po4yka.chur.app.AppLockSetting
import dev.po4yka.chur.app.AutoLockSetting
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.DeviceSlotPolicySetting
import dev.po4yka.chur.app.PublicShellSetting
import dev.po4yka.chur.app.RepositorySyncBoundary
import dev.po4yka.chur.app.SettingText
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.sync.PeerSyncCoordinator
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The process-scoped half of the composition root.
 *
 * `docs/ARCHITECTURE.md` §9 keeps binding in the composition root, and
 * `docs/interop/FFI_CONTRACT.md` §14 permits one runtime per process. §8.1
 * spends that rule: "a second iOS scene or a second Android task shares the one
 * session rather than opening its own. There is no per-scene vault state."
 *
 * An activity is not process-scoped. The platform destroys and recreates it for
 * a locale change, a font-scale change, a display-size change, a multi-window
 * resize and a `recreate()`, and none of those is finishing. An activity that
 * built the controller therefore built a second runtime on each of them and
 * closed neither, because it closes only when it is finishing. This holds the
 * process-scoped half instead - the runtime, the repository, the controller and
 * the sync engine - and the activity binds itself to it.
 *
 * The two adapters that need a window read [activity] rather than hold one:
 * `AndroidPrivacyCover` sets a flag on the current window, and
 * `AndroidDeviceUnlock` gives `BiometricPrompt` the current `FragmentActivity`.
 *
 * The activity and the sync worker share it, so a process started for periodic
 * work can open the runtime without opening a vault session.
 */
internal class ChurHost private constructor(context: Context) {
    /**
     * The attached window, or `null` between one activity and the next.
     *
     * Written by `MainActivity.onCreate` and `onDestroy` and read by the two
     * adapters, all on the main thread, so it needs no synchronization.
     */
    var activity: FragmentActivity? = null

    /** The cover of `PLAINTEXT_LIFECYCLE.md` §1, over whichever window is attached. */
    val privacy = AndroidPrivacyCover { activity }

    /** Ephemeral foreground peer connections belong to this process session. */
    val sync = PeerSyncCoordinator(clock = { System.currentTimeMillis() })

    /**
     * The per-vault device-slot policy of `KEY_SLOTS.md` §1.
     *
     * It is one word in [securitySettingFile], outside every backup: a
     * device slot does not move to another device, and the file is written
     * only from an open vault, so a backed-up copy showed that a vault was
     * set up. A missing file is the convenient default; a file that holds
     * anything else, an empty one included, reads as strict, [SettingText].
     */
    private val deviceSlotPolicy =
        DeviceSlotPolicySetting(
            reader = { SettingText.strictPolicy(context.readSecuritySetting("device-slot-policy.txt")) },
            writer = { strict ->
                context.writeSecuritySetting("device-slot-policy.txt", SettingText.policyWord(strict))
            },
        )

    /**
     * The whole-app lock. It asks for a vault credential, which a restored
     * device does not have, and it is written only from an open vault, so it
     * is kept out of the backup with the device-slot policy.
     */
    private val appLockSetting = AppLockSetting(
        reader = { SettingText.wholeAppLock(context.readSecuritySetting("app-lock.txt")) },
        writer = { enabled ->
            context.writeSecuritySetting("app-lock.txt", SettingText.appLockWord(enabled))
        },
    )

    /**
     * The auto-lock choice of `DESIGN.md` §14.4, one number of seconds
     * beside the app-lock choice, so every identity reads the same one,
     * `DECOY_VAULT.md` §10. It is also written only from an open vault.
     */
    private val autoLockSetting = AutoLockSetting(
        reader = { context.readSecuritySetting("auto-lock.txt") },
        writer = { context.writeSecuritySetting("auto-lock.txt", it) },
    )

    private var shareHostActivity = false

    private fun beginShareActivity() {
        if (!shareHostActivity) {
            shareHostActivity = true
            controller.beginHostActivity()
        }
    }

    fun endShareActivity() {
        if (shareHostActivity) {
            shareHostActivity = false
            exports.completePendingShares()
            controller.endHostActivity()
        }
    }

    private val exports = ExportDestinations(context, ::beginShareActivity, ::endShareActivity)

    /** Incoming grants exist only in this process and never in public Notes. */
    val incomingShares = dev.po4yka.chur.app.IncomingShareInbox()

    /** The one controller, over the one repository, over the one runtime. */
    val controller = ChurController(
        storageRoot = context.storageRoot(),
        privacy = privacy,
        exports = exports,
        clock = { System.currentTimeMillis() },
        notes = FileNoteStore(context.publicShellFile("notes.json")),
        deviceUnlock =
            AndroidDeviceUnlock(
                host = { activity },
                policy = {
                    if (deviceSlotPolicy.read()) {
                        DeviceSlotPolicy.STRICT
                    } else {
                        DeviceSlotPolicy.CONVENIENT
                    }
                },
            ),
        deviceSlotPolicy = deviceSlotPolicy,
        appLockSetting = appLockSetting,
        publicUtilities = dev.po4yka.chur.app.publicshell.publicUtilityStore(
            File(context.publicShellFile("notes.json")).parent!!, context.noBackupFilesDir.resolve("public-utility-settings").path),
        publicShellSetting = PublicShellSetting(
            reader = {
                File(context.publicShellFile("shell.txt")).takeIf { it.isFile && it.length() <= 32 }?.readText()
            },
            writer = { File(context.publicShellFile("shell.txt")).writeText(it) },
        ),
        autoLockSetting = autoLockSetting,
        sync = sync,
        contentExtractor = dev.po4yka.chur.app.vault.AndroidContentTextExtractor(context.applicationContext),
    )

    /** Explicit, foreground-only desktop control of the open session. */
    val deviceControl = DeviceControlBridge(context.applicationContext, controller)

    /** Locks the process session even if the activity that opened it is gone. */
    internal fun lockFromSystemControl() {
        try {
            privacy.setEnabled(true)
            try {
                deviceControl.stop()
            } catch (_: IOException) {
                // A failed socket close must not leave the vault open.
            }
        } finally {
            controller.panic()
        }
    }

    init {
        for (name in listOf("chur-sync.json", "chur-sync.json.tmp")) {
            val obsolete = File(context.noBackupFilesDir, name)
            if (obsolete.exists() && !obsolete.delete()) throw IOException("obsolete network state could not be removed")
        }
        sync.bind(RepositorySyncBoundary(controller.vault))
    }

    companion object {
        private var instance: ChurHost? = null

        /** A tile must never initialize storage or a runtime in a cold process. */
        @Synchronized
        internal fun existing(): ChurHost? = instance

        /** Returns false when there is no process session to lock. */
        internal fun lockExistingFromSystemControl(): Boolean {
            val host = existing() ?: return false
            host.lockFromSystemControl()
            return true
        }

        /**
         * The one host of this process, built on the first launch.
         *
         * It is never released. §14 gives the runtime the life of the process,
         * and the session inside it is closed by the background lock of
         * `DESIGN.md` §14 before a person can leave, so what outlives a
         * finished activity is a storage path and no plaintext.
         *
         * Android entry points may call this on different threads.
         */
        @Synchronized
        fun of(context: Context): ChurHost =
            instance ?: ChurHost(context.applicationContext).also { instance = it }
    }
}

/**
 * The storage root, `docs/ARCHITECTURE.md` §14.4.
 *
 * `filesDir` is app-private, and `res/xml/data_extraction_rules.xml` names only
 * `public/` and the public shell's preferences, so everything under this path
 * is excluded from every archive by the absence of a rule. That is what
 * `PLAINTEXT_LIFECYCLE.md` §5 needs of every directory the vault writes into,
 * and `docs/ANDROID.md` §13.4 makes a rule that reached one a release blocker.
 */
private fun Context.storageRoot(): String =
    File(filesDir, "chur").apply { mkdirs() }.absolutePath

/**
 * A file of the public shell, `docs/ANDROID.md` §13.4.
 *
 * It is under `filesDir/public/` because that is the one directory the backup
 * rules include. `DISCREET_MODE.md` puts the shell's content in the platform
 * backup deliberately: a Notes surface that loses everything on device transfer
 * is not functional, and a shell nobody would use announces what it hides. The
 * path is the whole mechanism - a note file anywhere else would be excluded by
 * the same rules that exclude the vault.
 */
private fun Context.publicShellFile(name: String): String =
    File(filesDir, "public").apply { mkdirs() }.resolve(name).path

/**
 * Serializes every read, write and migration of a security setting.
 *
 * The settings are read from the main thread and written from coroutines, and
 * a migration both reads and deletes. Without one lock a toggle could run
 * between a migration's read and its write and lose the user's new choice,
 * and two writers would share one [AtomicFile] temporary file.
 */
private val SETTINGS_LOCK = Any()

/** Reads a security setting, or `null` when it was never written. */
internal fun Context.readSecuritySetting(name: String): String? =
    synchronized(SETTINGS_LOCK) { readSetting(securitySettingFile(name)) }

/** Writes a security setting atomically. */
internal fun Context.writeSecuritySetting(name: String, value: String) {
    synchronized(SETTINGS_LOCK) { writeSetting(securitySettingFile(name), value) }
}

/**
 * A security setting, in `noBackupFilesDir/settings/`.
 *
 * These settings were once in `filesDir/public/`, which the backup rules
 * include. Each one is written only from an open vault, so its presence in a
 * backup showed that a vault was set up. An older file is moved here the first
 * time it is asked for, and the public copy is deleted.
 */
internal fun Context.securitySettingFile(name: String): String = synchronized(SETTINGS_LOCK) {
    val target = File(noBackupFilesDir, "settings").apply { mkdirs() }.resolve(name)
    val legacy = File(publicShellFile(name))
    if (legacy.exists() || File("${legacy.path}.bak").exists()) {
        if (!target.exists()) {
            readSetting(legacy.path)?.let { writeSetting(target.path, it) }
        }
        AtomicFile(legacy).delete()
    }
    target.path
}

/**
 * Reads one setting file, or `null` when it does not exist.
 *
 * [AtomicFile] also restores the previous content when a write was cut short
 * before its rename. A file that is too long to be a setting reads as an
 * unknown word, which [SettingText] and `AutoLockSetting` treat as the
 * stricter choice.
 */
private fun readSetting(path: String): String? = try {
    val file = AtomicFile(File(path))
    // An interrupted write on an older release can leave only the AtomicFile
    // backup, which `readFully` restores.
    if (!file.baseFile.exists() && !File("$path.bak").exists()) return null
    val bytes = file.readFully()
    if (bytes.size > MAX_SETTING_BYTES) "" else bytes.decodeToString()
} catch (_: FileNotFoundException) {
    null
}

/**
 * Writes one setting file with [AtomicFile]: a new file, fsync, then rename.
 *
 * `File.writeText` truncated the file before it wrote, so a power loss could
 * leave it empty, and an empty security setting used to read as the weaker
 * choice.
 */
private fun writeSetting(path: String, value: String) {
    val file = AtomicFile(File(path))
    val stream = file.startWrite()
    try {
        stream.write(value.encodeToByteArray())
        file.finishWrite(stream)
    } catch (failure: IOException) {
        file.failWrite(stream)
        throw failure
    }
}

private const val MAX_SETTING_BYTES = 64
