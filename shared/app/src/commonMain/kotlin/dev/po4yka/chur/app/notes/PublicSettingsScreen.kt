package dev.po4yka.chur.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors

/**
 * The public shell's own settings, `docs/product/DISCREET_MODE.md`.
 *
 * It carries two things and nothing else.
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
fun PublicSettingsScreen(onBack: () -> Unit, onOpenVault: () -> Unit) {
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
        ) {
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
                text = Disclosure.SETTINGS,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }
    }
}
