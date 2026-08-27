package dev.po4yka.chur.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.theme.LocalChurColors

/**
 * The application root.
 *
 * `docs/security/PROVISIONING.md` §2 fixes what happens first: the public shell
 * opens, with no account, no permission prompt, and no key generation, and the
 * route to the vault is a visible settings entry. [AppRoute] is that ordering
 * as a type, so a route that skipped the gate would have to be added here
 * rather than reached by accident.
 *
 * The gate of `docs/interop/FFI_CONTRACT.md` §2 runs before any of it. A
 * library that fails it is not called again in this process, and the shell says
 * so plainly instead of degrading: a vault that cannot be opened correctly must
 * not appear to open.
 */
@Composable
public fun ChurApp(gate: GateResult, route: AppRoute, content: @Composable () -> Unit) {
    ChurTheme {
        when (gate) {
            is GateResult.Incompatible -> IncompatibleLibrary(gate)
            is GateResult.Compatible -> {
                // The route is the caller's; this composable only refuses to
                // render anything private when the gate refused the library.
                val _unused = route
                content()
            }
        }
    }
}

/**
 * Where the application is.
 *
 * The private routes are never restored after process death, `DESIGN.md`
 * §10.3, which is why this is a value the host recreates at start rather than
 * something it persists.
 */
public sealed interface AppRoute {
    /** The public shell, which is where every launch begins. */
    public data object PublicShell : AppRoute

    /** The visible settings entry of `PROVISIONING.md` §2. */
    public data object PublicSettings : AppRoute

    /** Vault creation, §3 there. */
    public data object CreateVault : AppRoute

    /** The session gate, `DESIGN.md` §14. */
    public data object Unlock : AppRoute

    /** The recovery route. */
    public data object Recover : AppRoute

    /** The unlocked vault. */
    public data object Vault : AppRoute
}

/**
 * The refusal of §2.
 *
 * It names no version and no capability, because a host that cannot call the
 * library also cannot tell the user anything useful about it, and a number on
 * screen invites a user to try to fix it.
 */
@Composable
private fun IncompatibleLibrary(result: GateResult.Incompatible) {
    val colors = LocalChurColors.current
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ChurSpacing.gutterExpanded),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Chur cannot start", style = MaterialTheme.typography.headlineSmall)
            Text(
                gateSummary(result),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }
    }
}

/**
 * One line describing a gate verdict.
 *
 * The copy names the refusal, never the values the library returned:
 * `ERROR_MODEL.md` "Safe metadata" keeps raw untrusted input out of anything a
 * user or a log sees.
 */
internal fun gateSummary(result: GateResult): String =
    when (result) {
        is GateResult.Compatible ->
            "The native library is compatible. Capabilities: ${result.capabilities}."
        is GateResult.Incompatible ->
            when (result.reason) {
                GateResult.Reason.MAJOR_VERSION ->
                    "This version of Chur cannot use the installed native library. Update the application."
                GateResult.Reason.EMPTY_FORMAT_RANGE ->
                    "The native library reports no readable format version. Update the application."
                GateResult.Reason.BUILD_FLAVOR ->
                    "The native library is not a build this application accepts."
            }
    }
