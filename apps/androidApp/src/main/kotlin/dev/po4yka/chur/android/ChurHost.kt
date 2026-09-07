package dev.po4yka.chur.android

import android.content.Context
import androidx.fragment.app.FragmentActivity
import dev.po4yka.chur.app.AndroidPrivacyCover
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.RepositorySyncBoundary
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.sync.FileSyncStateStore
import dev.po4yka.chur.sync.SyncCoordinator
import java.io.File

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
 * It is built by the activity rather than by the application, so a process the
 * WorkManager schedule started opens no runtime and holds no vault state until
 * a person launches the application. That is the state [ChurSync] already
 * describes as a quiet no.
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

    /**
     * The sync engine, `SYNC_PROTOCOL_V1.md` §7.
     *
     * The worker of [ChurSync] reads it for its periodic cycle. Its state file
     * holds the device's transport token, so it lives in `noBackupFilesDir`,
     * which `docs/ANDROID.md` §13.4 names for exactly this kind of state and
     * every backup rule excludes.
     */
    val sync = SyncCoordinator(
        store = FileSyncStateStore(File(context.noBackupFilesDir, "chur-sync.json").path),
        clock = { System.currentTimeMillis() },
    )

    /** The one controller, over the one repository, over the one runtime. */
    val controller = ChurController(
        storageRoot = context.storageRoot(),
        privacy = privacy,
        exports = ExportDestinations(context.contentResolver),
        clock = { System.currentTimeMillis() },
        notes = FileNoteStore(context.publicShellFile("notes.json")),
        deviceUnlock = AndroidDeviceUnlock(host = { activity }),
        sync = sync,
    )

    init {
        sync.bind(RepositorySyncBoundary(controller.vault))
        ChurSync.coordinator = sync
    }

    companion object {
        private var instance: ChurHost? = null

        /**
         * The one host of this process, built on the first launch.
         *
         * It is never released. §14 gives the runtime the life of the process,
         * and the session inside it is closed by the background lock of
         * `DESIGN.md` §14 before a person can leave, so what outlives a
         * finished activity is a storage path and no plaintext.
         *
         * Called from `MainActivity.onCreate` alone, which the platform runs on
         * the main thread, so the check and the store need no lock.
         */
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
