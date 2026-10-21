package dev.po4yka.chur.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.vault.PrivateAlertDialog
import dev.po4yka.chur.app.vault.LocalPrivatePanic
import dev.po4yka.chur.app.vault.albumRows
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The public notice is neutral; destination names appear only inside the vault. */
@Composable
fun IncomingSharePrompt(
    inbox: IncomingShareInbox,
    controller: ChurController,
    route: AppRoute,
    importer: MediaImporter,
) {
    // Keep the operation's scope composed while the busy state hides its dialog.
    val scope = rememberCoroutineScope()
    val state by inbox.state.collectAsState()
    val pending = state ?: return
    if (pending.busy) return // The existing operation card owns progress and cancellation.
    val vault by controller.vaultState.collectAsState()
    val unlocked = vault as? VaultState.Unlocked
    if (route == AppRoute.PublicShell || route == AppRoute.PublicSettings) {
        AlertDialog(
            onDismissRequest = inbox::discard,
            title = { ExcludeWindowFromCapture(); Text("Shared with Chur") },
            text = { Text("${pending.count} shared ${if (pending.count == 1) "item is" else "items are"} ready. Open your vault to choose where to import. Originals stay in the source app.") },
            confirmButton = { TextButton(onClick = controller::openVaultEntry) { Text("Open vault") } },
            dismissButton = { TextButton(onClick = inbox::discard) { Text("Discard") } },
        )
    } else if (route == AppRoute.Vault && unlocked != null) {
        val albums by controller.albums.collectAsState()
        val operation by controller.activeOperation.collectAsState()
        var target by remember(pending.id, unlocked.generation) { mutableStateOf<String?>(null) }
        val selected = albums.firstOrNull { it.id == target }
        // No private destination or scroll state enters the host's saved state.
        CompositionLocalProvider(
            LocalSaveableStateRegistry provides null,
            LocalPrivatePanic provides { controller.panic() },
        ) {
            PrivateAlertDialog(
                onDismissRequest = inbox::discard,
                title = { Text("Import shared items") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
                        Text("Import ${pending.count} ${if (pending.count == 1) "item" else "items"} into this vault. Originals stay in the source app. If an item fails, share it again; items already imported remain saved.")
                        Text("Destination")
                        TextButton(onClick = { target = null }, modifier = Modifier.testTag("incoming-destination-library")) {
                            RadioButton(selected = selected == null, onClick = null)
                            Text("All items")
                        }
                        albumRows(albums).forEach { (album, depth) ->
                            TextButton(onClick = { target = album.id }) {
                                RadioButton(selected = selected?.id == album.id, onClick = null)
                                Text("${"  ".repeat(depth.coerceAtMost(6))}${album.name}")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = operation == null, modifier = Modifier.testTag("incoming-import"), onClick = {
                        val destination = selected?.albumId?.copyOf()
                        scope.launch {
                            try { inbox.import(controller, importer, destination) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                if ((controller.vaultState.value as? VaultState.Unlocked)?.generation == unlocked.generation) {
                                    controller.report("Import stopped. Items already imported remain saved. Share any remaining items again.")
                                }
                            }
                        }
                    }) { Text("Import") }
                },
                dismissButton = { TextButton(onClick = inbox::discard) { Text("Cancel") } },
            )
        }
    }
}
