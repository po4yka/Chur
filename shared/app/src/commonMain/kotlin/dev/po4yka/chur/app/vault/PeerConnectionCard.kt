package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.po4yka.chur.app.privateKeyboardOptions
import dev.po4yka.chur.app.rememberSensitiveCopy
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.app.peerCopy
import dev.po4yka.chur.sync.PeerSyncStatus

/** One foreground connection. Invitations are secrets and are never saved or copied automatically. */
@Composable
internal fun PeerConnectionCard(state: PeerSyncStatus, onListen: (String) -> Unit, onConnect: (String) -> Unit, onStop: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    val copy = rememberSensitiveCopy()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ChurSpacing.three), verticalArrangement = Arrangement.spacedBy(ChurSpacing.two)) {
            Text("Direct device sync", style = MaterialTheme.typography.titleMedium)
            Text("No account or service. Keep both vaults open. Use the same local network or another direct route between your devices.")
            if (!state.busy) {
                if (state.canApprove) {
                    OutlinedTextField(value = address, onValueChange = { address = it.take(128) },
                        label = { Text("This device's IPv4 address") }, singleLine = true,
                        keyboardOptions = privateKeyboardOptions(), colors = churOutlinedTextFieldColors(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onListen(address) }, enabled = address.isNotBlank()) { Text("Create one-time invitation") }
                }
                OutlinedTextField(value = invitation, onValueChange = { invitation = it.take(256) },
                    label = { Text("Invitation from your other device") },
                    keyboardOptions = privateKeyboardOptions(), colors = churOutlinedTextFieldColors(), modifier = Modifier.fillMaxWidth(), maxLines = 4)
                Button(onClick = { val code = invitation; invitation = ""; onConnect(code) }, enabled = invitation.isNotBlank()) { Text("Connect and sync") }
            } else {
                state.invitation?.let { code ->
                    Text("Enter this invitation on your other device. Share it only with that device; it expires when you stop or leave the app.")
                    Text(code, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copy("Chur sync invitation", code) }) { Text("Copy invitation") }
                }
                OutlinedButton(onClick = onStop) { Text("Stop connection") }
            }
            (state.failure?.let(::peerCopy) ?: state.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
