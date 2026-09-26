package dev.po4yka.chur.android

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.po4yka.chur.app.ChurApp
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.GateResult
import dev.po4yka.chur.app.NativeHandshake
import dev.po4yka.chur.app.gate
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

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
        host.privacy.setEnabled(needsSecureWindow(host.controller.vaultState.value, host.controller.route.value))
        ChurSync.enqueue(this)

        val controller = host.controller
        val verdict = runGate(this)
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

        // Observe only while resumed. After onPause enables the cover, a
        // background lock can emit Locked before onStop; a STARTED collector
        // would clear FLAG_SECURE while the window is still visible to recents.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(controller.vaultState, controller.route) { state, route ->
                    needsSecureWindow(state, route)
                }.collect { secure ->
                    host.privacy.setEnabled(secure)
                }
            }
        }
        lifecycleScope.launch {
            host.deviceControl.pairing.collect { pairing ->
                if (pairing != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
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
    override fun onResume() {
        super.onResume()
        host.endShareActivity()
    }

    override fun onPause() {
        host.privacy.setEnabled(true)
        host.deviceControl.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

private fun needsSecureWindow(state: VaultState, route: AppRoute): Boolean =
    state is VaultState.Unlocked || route == AppRoute.Unlock || route == AppRoute.Recover ||
        route == AppRoute.AppUnlock || route == AppRoute.AppRecover

/** Run the native gate before either host entry point opens a runtime. */
internal fun runGate(context: Context): GateResult {
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
        releaseApplication = !BuildConfigCompat.debuggable(context),
    )
}

/** Whether this build is debuggable, without generating a `BuildConfig`. */
internal object BuildConfigCompat {
    fun debuggable(context: Context): Boolean =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
