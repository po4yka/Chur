package dev.po4yka.chur.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.po4yka.chur.app.ChurApp
import dev.po4yka.chur.app.GateResult
import dev.po4yka.chur.app.NativeHandshake
import dev.po4yka.chur.app.gate
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.launch

/**
 * The window half of the composition root.
 *
 * `docs/ARCHITECTURE.md` §9 says only the composition root and adapter modules
 * bind implementations. The root is split in two, because its two halves have
 * two lifetimes: [ChurHost] holds what `docs/interop/FFI_CONTRACT.md` §14 makes
 * process-scoped - the runtime, the repository, the controller, the engine -
 * and this holds the window, which the platform destroys and recreates under
 * it. §8.1 has the recreated window share the one session: "there is no
 * per-scene vault state".
 *
 * The gate runs before anything private is composed. §2 of
 * `docs/interop/FFI_CONTRACT.md` makes a failing gate terminal for the process,
 * so the shell says so and composes no vault route rather than degrading.
 */
/*
 * It is a `FragmentActivity` rather than a bare `ComponentActivity` because
 * `BiometricPrompt` requires one, and the device slot of `KEY_SLOTS.md` §4
 * cannot be authorized without it. `FragmentActivity` extends
 * `ComponentActivity`, so everything else here is unchanged.
 */
class MainActivity : FragmentActivity() {
    private lateinit var host: ChurHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        host = ChurHost.of(this)
        host.activity = this
        // The window is new and the session it will show may already be open.
        // §1 of `PLAINTEXT_LIFECYCLE.md` puts the switcher snapshot in the
        // forbidden column, and the platform can take one before the collector
        // below has run, so the cover is set from the state here rather than
        // waiting for the first collection.
        host.privacy.setEnabled(host.controller.vaultState.value is VaultState.Unlocked)
        ChurSync.enqueue(this)

        val controller = host.controller
        val verdict = runGate()
        if (verdict is GateResult.Compatible) {
            // It runs once per process: the controller refuses a second start,
            // because a second one would leave a second idle timer over the one
            // session. It runs on the controller's scope and not on this
            // activity's, because `lifecycleScope` is cancelled at `onDestroy`
            // and a start cancelled part-way arms no timer at all.
            controller.begin()
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
                    host.privacy.setEnabled(current is VaultState.Unlocked)
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
     *
     * The lock runs on the controller's scope, not on this activity's. A back
     * press runs `onPause` and `onDestroy` in one pass, and `lifecycleScope`
     * dies with the second, so a lock launched here used to be cancelled at its
     * first suspension point and the session stayed open in a process the
     * platform keeps.
     */
    override fun onPause() {
        host.privacy.setEnabled(true)
        super.onPause()
        // A configuration change is not the user leaving. The platform destroys
        // this activity and creates another one in the same task, in the
        // foreground, over the same session - §8.1 says there is no per-scene
        // vault state. Locking here would put the unlock screen in front of an
        // application that never left, which is the same false positive
        // `beginHostActivity` answers for the media picker. The cover above
        // still goes on, so the transition is covered either way.
        if (isChangingConfigurations) return
        host.controller.background()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The window goes; the runtime, the repository and the engine stay,
        // because §14 gives them the life of the process and §8.1 has the next
        // activity share the one session. The two adapters that need a window
        // find none until one is attached again.
        if (host.activity === this) {
            host.activity = null
        }
    }
}

/** Whether this build is debuggable, without generating a `BuildConfig`. */
internal object BuildConfigCompat {
    fun debuggable(activity: ComponentActivity): Boolean =
        activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
