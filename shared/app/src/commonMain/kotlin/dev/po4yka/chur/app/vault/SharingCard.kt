package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.ffi.SharingMember
import dev.po4yka.chur.ffi.SharingPermission
import dev.po4yka.chur.ffi.toHex

/** One Settings flow for sharing the vault's whole cryptographic collection. */
@Composable
internal fun SharingCard(state: VaultUiState, actions: VaultActions) {
    var enrollment by remember { mutableStateOf("") }
    var inspected by remember { mutableStateOf("") }
    var fingerprintConfirmed by remember(enrollment) { mutableStateOf(false) }
    var scopeConfirmed by remember(enrollment) { mutableStateOf(false) }
    var permission by remember { mutableStateOf(SharingPermission.READ) }
    var revoking by remember { mutableStateOf<SharingMember?>(null) }
    val recipient = state.sharingRecipient.takeIf { inspected == enrollment && inspected.isNotBlank() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(ChurSpacing.three),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
        ) {
            Text("Encrypted access", style = MaterialTheme.typography.titleMedium)
            Text(
                "This grant covers the entire vault collection, including future imports. " +
                    "Albums do not limit access. Both devices must use the same sync server. " +
                    "Photos do not yet sync between devices.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.sharingIdentity?.let { identity ->
                Text("Your device fingerprint: ${identity.fingerprint}")
                Text("Give this enrollment to the person who will grant you access:")
                SelectionContainer {
                    Text(
                        identity.enrollment.toHex().chunked(32).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = actions.onSyncNow) { Text("Check for incoming access") }
            OutlinedTextField(
                value = enrollment,
                onValueChange = { enrollment = it; inspected = "" },
                label = { Text("Recipient enrollment (hex)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            Button(
                onClick = { inspected = enrollment; actions.onInspectSharingRecipient(enrollment) },
                enabled = enrollment.isNotBlank(),
            ) { Text("Check recipient identity") }
            if (recipient != null) {
                Text("Recipient fingerprint: ${recipient.fingerprint}")
                Text("Compare this fingerprint with the recipient on a separate channel.")
                ConfirmRow(
                    "I compared the recipient fingerprint",
                    fingerprintConfirmed,
                ) { fingerprintConfirmed = it }
                ConfirmRow(
                    "I agree to share the entire vault collection",
                    scopeConfirmed,
                ) { scopeConfirmed = it }
                Text("Permission", style = MaterialTheme.typography.titleSmall)
                SharingPermission.entries.forEach { option ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = permission == option, onClick = { permission = option })
                        Text(option.displayName(), modifier = Modifier.padding(top = ChurSpacing.two))
                    }
                }
                Button(
                    onClick = {
                        actions.onShareWithRecipient(permission)
                    },
                    enabled = fingerprintConfirmed && scopeConfirmed && state.sync?.busy != true,
                ) { Text("Grant encrypted access") }
            }
            state.sharingOverview?.members?.forEach { member ->
                Text("Recipient: ${member.fingerprint}")
                Text(
                    "${if (member.active) member.permissions.displayName() else "Revoked"} · " +
                        (if (member.verified) "Verified" else "Not verified"),
                )
                TextButton(
                    onClick = { revoking = member },
                    enabled = state.sync?.busy != true,
                ) { Text(if (member.active) "Revoke access" else "Check revocation") }
            }
        }
    }

    revoking?.let { member ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(if (member.active) "Revoke encrypted access?" else "Check revocation?") },
            text = {
                Text(
                    if (member.active) {
                        "The collection key will rotate for future updates. " +
                            "This cannot erase data the recipient already received."
                    } else {
                        "Check the key rotation and retry any unpublished revocation records. " +
                            "This cannot erase data the recipient already received."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    actions.onRevokeSharingMember(member)
                }) { Text(if (member.active) "Revoke" else "Check") }
            },
            dismissButton = {
                TextButton(onClick = { revoking = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConfirmRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, modifier = Modifier.padding(top = ChurSpacing.two))
    }
}

private fun SharingPermission.displayName(): String = when (this) {
    SharingPermission.READ -> "Read"
    SharingPermission.CONTRIBUTE -> "Contribute"
    SharingPermission.MANAGE_MEMBERS -> "Manage members"
}
