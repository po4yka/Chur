package dev.po4yka.chur.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.PublicShell
import dev.po4yka.chur.app.publicshell.PUBLIC_CAMERA_DISCLOSURE
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors

/**
 * The public shell's own settings, `docs/product/DISCREET_MODE.md`.
 *
 * It carries the public screen choice, the disclosure, and the visible vault entry.
 *
 * The permanent disclosure that section requires, beside the entry that reaches
 * the vault: public-shell content is not encrypted by Chur and is in the
 * platform backup, and vault content is encrypted, is excluded from that
 * backup, and leaves the device only in a package the user makes. Putting the
 * two sentences beside each other is the point — the statement is about the
 * difference, and a disclosure the user reads without the comparison does not
 * tell them what to do about it.
 *
 * The vault entry itself, which [`../security/PROVISIONING.md`] §2 requires and
 * which "Do not dynamically remove all discoverable means of reopening or
 * managing the feature" forbids removing. `DISCREET_MODE.md`'s "The v1
 * decision" makes this route the session gate: visible, documented, and not a
 * secret.
 *
 * It reads no private state and shows no count. Every settings surface below
 * this line is public, and §10 of [`../security/DECOY_VAULT.md`] requires that
 * no surface reachable from a session differ by whether a sibling identity
 * exists — a public screen that named the vault's contents would differ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicSettingsScreen(
    onBack: () -> Unit,
    onOpenVault: () -> Unit,
    selectedShell: PublicShell = PublicShell.NOTES,
    onSelectShell: (PublicShell) -> Unit = {},
    error: String? = null,
    onEncryptExternalFiles: (() -> Unit)? = null,
) {
    val colors = LocalChurColors.current
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(BackGlyph, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
        ) {
            Text("Public screen", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                PublicShell.entries.forEach { shell ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = selectedShell == shell, role = Role.RadioButton, onClick = { onSelectShell(shell) })
                            .padding(horizontal = ChurSpacing.two),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedShell == shell, onClick = null)
                        Text(shell.label, modifier = Modifier.padding(start = ChurSpacing.two))
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Card(onClick = onOpenVault, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(ChurSpacing.three),
                    verticalArrangement = Arrangement.spacedBy(ChurSpacing.one),
                ) {
                    Text(
                        text = Disclosure.VAULT_ENTRY,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Open the encrypted vault.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                }
            }
            Text(
                "In the vault, tap the lock to hide private content immediately. " +
                    "You can also hold the lock or choose Lock and hide in a dialog or menu. " +
                    "Chur returns to your public screen, or the app lock when Lock whole app is on.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
            onEncryptExternalFiles?.let { encrypt ->
                Card(onClick = encrypt, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(ChurSpacing.three),
                        verticalArrangement = Arrangement.spacedBy(ChurSpacing.one),
                    ) {
                        Text("Encrypt external files", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Write a password-protected archive without adding files to the library. Original files stay in place.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkMuted,
                        )
                    }
                }
            }
            Text(
                text = Disclosure.SETTINGS,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
            Text(PUBLIC_CAMERA_DISCLOSURE, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        }
    }
}
