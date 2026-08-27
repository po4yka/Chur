package dev.po4yka.chur.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.po4yka.chur.app.AndroidPrivacyCover
import dev.po4yka.chur.app.ChurApp
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.GateResult
import dev.po4yka.chur.app.NativeHandshake
import dev.po4yka.chur.app.gate
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.launch

/**
 * The composition root.
 *
 * `docs/ARCHITECTURE.md` §9 says only the composition root and adapter modules
 * bind implementations, and this is that root: it creates the one repository,
 * binds the platform privacy cover, runs the ABI gate, and holds nothing else.
 *
 * The gate runs before anything private is composed. §2 of
 * `docs/interop/FFI_CONTRACT.md` makes a failing gate terminal for the process,
 * so the shell says so and composes no vault route rather than degrading.
 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: ChurController
    private lateinit var privacy: AndroidPrivacyCover

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        privacy = AndroidPrivacyCover(this)
        controller = ChurController(
            storageRoot = storageRoot(),
            privacy = privacy,
            exports = ExportDestinations(contentResolver),
            clock = { System.currentTimeMillis() },
            notes = FileNoteStore(java.io.File(filesDir, "notes.json").path),
        )

        val verdict = runGate()
        if (verdict is GateResult.Compatible) {
            lifecycleScope.launch { controller.start() }
        }

        setContent {
            val state by controller.vaultState.collectAsState()
            val route by controller.route.collectAsState()
            ChurApp(gate = verdict, route = route) {
                ChurRoutes(controller = controller, route = route, vaultState = state)
            }
        }

        // §14 of `DESIGN.md`: leaving the foreground locks under the default
        // policy, and the privacy cover goes on before the platform takes its
        // snapshot. `repeatOnLifecycle` at STARTED is what puts the two on the
        // same transition rather than on two.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.vaultState.collect { current ->
                    privacy.setEnabled(current is VaultState.Unlocked)
                }
            }
        }
    }

    /**
     * The gate of §2, run against the loaded library.
     *
     * `releaseApplication` is derived from the build rather than hard-coded, so
     * a debug build accepts a debug library and a release build does not.
     */
    private fun runGate(): GateResult {
        val handshake = ChurVault.handshake()
        return gate(
            NativeHandshake(
                abiVersionMajor = handshake.major.toUInt(),
                abiVersionMinor = handshake.minor.toUInt(),
                capabilities = handshake.capabilities.toULong(),
                objectFormatMin = handshake.objectFormatMin.toUInt(),
                objectFormatMax = handshake.objectFormatMax.toUInt(),
                keySlotFormatMin = handshake.keySlotFormatMin.toUInt(),
                keySlotFormatMax = handshake.keySlotFormatMax.toUInt(),
                buildFlavor = handshake.buildFlavor.toUInt(),
            ),
            releaseApplication = !BuildConfigCompat.debuggable(this),
        )
    }

    /**
     * The application left the foreground.
     *
     * The cover goes on first and the lock follows, because the snapshot is
     * taken as the activity stops: a lock that ran first would still leave the
     * unlocked frame in the picture if the cover were late.
     */
    override fun onPause() {
        privacy.setEnabled(true)
        super.onPause()
        lifecycleScope.launch { controller.onBackground() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // The runtime closes every handle it owns, §14, and a finishing
            // activity is the last chance to do it before the process may be
            // reused for another launch.
            kotlinx.coroutines.runBlocking { controller.shutdown() }
        }
    }
}

/**
 * The storage root, `docs/ARCHITECTURE.md` §14.4.
 *
 * `filesDir` is app-private and the manifest excludes the application from
 * backup, which is what `PLAINTEXT_LIFECYCLE.md` §5 needs of every directory
 * Chur writes into.
 */
private fun ComponentActivity.storageRoot(): String =
    java.io.File(filesDir, "chur").apply { mkdirs() }.absolutePath

/** Whether this build is debuggable, without generating a `BuildConfig`. */
internal object BuildConfigCompat {
    fun debuggable(activity: ComponentActivity): Boolean =
        activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
