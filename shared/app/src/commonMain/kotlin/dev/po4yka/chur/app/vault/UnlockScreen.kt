package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors

/**
 * The session gate of `DESIGN.md` §14.1.
 *
 * §14.1 lists what the screen must not reveal, and the list is the design: no
 * private item count, no last opened album, no real-or-decoy identity, no
 * reason a candidate slot failed, and no hint that a different credential
 * exists. The error region therefore carries one sentence for every failure of
 * a credential, which is also what `KEY_SLOTS.md` §8 requires of the boundary
 * underneath it.
 *
 * The locked state is neutral rather than red, §6.3.
 */
@Composable
fun UnlockScreen(
    busy: Boolean,
    failed: Boolean,
    onUnlock: (String) -> Unit,
    onUseRecovery: () -> Unit,
    deviceUnlockOffered: Boolean = false,
    onUseDevice: () -> Unit = {},
) {
    var password by remember { mutableStateOf("") }
    val colors = LocalChurColors.current
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ChurSpacing.gutterExpanded),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            ) {
                Text("Chur", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Enter your password to open the vault.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    isError = failed,
                    modifier = Modifier.fillMaxWidth(),
                )
                // §14.1: one message for every credential failure. It names no
                // slot, no identity, and no count.
                Text(
                    text = if (failed) "That password did not open a vault." else " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) colors.error else colors.inkMuted,
                )
                Button(
                    onClick = { onUnlock(password) },
                    enabled = !busy && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Opening" else "Unlock")
                }
                // §14.2: the platform draws its own prompt. This button says
                // what it will ask for and nothing about what the vault holds.
                if (deviceUnlockOffered) {
                    TextButton(onClick = onUseDevice, enabled = !busy) {
                        Text("Use screen lock")
                    }
                }
                TextButton(onClick = onUseRecovery, enabled = !busy) {
                    Text("Use recovery phrase")
                }
            }
        }
    }
}

/**
 * The recovery route of `RECOVERY.md`.
 *
 * §10 there is what the copy has to survive: a forgotten password with no
 * recovery slot is unrecoverable and support cannot help. The screen says so
 * before the user types, rather than after the attempt fails.
 */
@Composable
fun RecoveryScreen(
    busy: Boolean,
    failed: Boolean,
    onRecover: (String) -> Unit,
    onBack: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    val colors = LocalChurColors.current
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ChurSpacing.gutterExpanded),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            ) {
                Text("Recovery phrase", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter the 24 words in order. Chur has no copy of them and no " +
                        "support path can recover a vault without them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    enabled = !busy,
                    label = { Text("Recovery phrase") },
                    isError = failed,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (failed) "That phrase did not open a vault." else " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) colors.error else colors.inkMuted,
                )
                Button(
                    onClick = { onRecover(phrase) },
                    enabled = !busy && phrase.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Opening" else "Recover")
                }
                TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            }
        }
    }
}
