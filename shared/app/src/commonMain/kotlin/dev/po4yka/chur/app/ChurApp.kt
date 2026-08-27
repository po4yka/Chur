package dev.po4yka.chur.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Phase 0 shell.
 *
 * It renders one thing: whether the native library passed the ABI gate. That is
 * the whole of what Phase 0 owns on screen. The Notes public shell, the vault,
 * and every private screen are Phase 1, and none of them can exist before the
 * control plane does.
 *
 * Nothing here reads private data, and there is nothing private to read.
 */
@Composable
public fun ChurApp(result: GateResult) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "Chur", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = gateSummary(result),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
