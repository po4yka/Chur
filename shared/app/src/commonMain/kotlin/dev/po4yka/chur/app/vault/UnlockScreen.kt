package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.secretKeyboardOptions
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.PrivateBoundaryMark
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors

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
 *
 * The form sits inside the safe area and scrolls in the space the keyboard
 * leaves, `DESIGN.md` §25.5, so Unlock can always be reached. The keyboard's
 * Go key submits as the button does and under the same condition, §23.4.
 */
@Composable
fun UnlockScreen(
    busy: Boolean,
    failed: Boolean,
    onUnlock: (String) -> Unit,
    onUseRecovery: () -> Unit,
    deviceUnlockOffered: Boolean = false,
    onUseDevice: () -> Unit = {},
    appGate: Boolean = false,
) {
    var password by remember { mutableStateOf("") }
    var usePin by remember { mutableStateOf(false) }
    val canUnlock = !busy && password.isNotEmpty() && (!usePin || isValidVaultPin(password))
    val colors = LocalChurColors.current
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            ) {
                PrivateBoundaryMark(size = 56.dp)
                Text("Chur", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (appGate) {
                        "Enter your password or PIN to open Chur."
                    } else {
                        "Enter your password or PIN to open the vault."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                TextButton(
                    onClick = {
                        usePin = !usePin
                        password = ""
                    },
                    enabled = !busy,
                ) {
                    Text(if (usePin) "Use a password instead" else "Use a PIN instead")
                }
                OutlinedTextField(
                    colors = churOutlinedTextFieldColors(),
                    value = password,
                    onValueChange = {
                        if (!usePin || (it.length <= 20 && it.all { digit -> digit in '0'..'9' })) {
                            password = it
                        }
                    },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(if (usePin) "PIN" else "Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = secretKeyboardOptions(pin = usePin, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (canUnlock) onUnlock(password) }),
                    isError = failed,
                    modifier = Modifier.fillMaxWidth(),
                )
                // §14.1: one message for every credential failure. It names no
                // slot, no identity, and no count.
                Text(
                    text = if (failed) "Unable to unlock." else " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) colors.error else colors.inkMuted,
                )
                Button(
                    onClick = { onUnlock(password) },
                    enabled = canUnlock,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Opening" else "Unlock")
                }
                // §14.2: the platform draws its own prompt. This button says
                // what it will ask for and nothing about what the vault holds.
                if (deviceUnlockOffered) {
                    TextButton(onClick = onUseDevice, enabled = !busy) {
                        Text("Use device authentication")
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
 *
 * The phrase field is several lines tall, so the form scrolls above the
 * keyboard as the unlock form does, and Go submits it.
 */
@Composable
fun RecoveryScreen(
    busy: Boolean,
    failed: Boolean,
    onRecover: (String) -> Unit,
    onBack: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    val canRecover = !busy && phrase.isNotBlank()
    val colors = LocalChurColors.current
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(ChurSpacing.gutter),
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
                    colors = churOutlinedTextFieldColors(),
                    value = phrase,
                    onValueChange = { phrase = it },
                    enabled = !busy,
                    label = { Text("Recovery phrase") },
                    // PASSWORD_PROFILE.md §2: the phrase opens the vault as a password does.
                    keyboardOptions = secretKeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (canRecover) onRecover(phrase) }),
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
                    enabled = canRecover,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Opening" else "Recover")
                }
                TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            }
        }
    }
}
