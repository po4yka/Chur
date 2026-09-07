package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.DiagnosticTextStyle
import dev.po4yka.chur.app.theme.LocalChurColors

/**
 * Vault creation, `docs/security/PROVISIONING.md` §3.
 *
 * The order on screen is §3's order, because §3 is an order and not a list:
 * the explanation comes before the password, the password is checked against
 * the profile before anything is generated, and the recovery offer comes after
 * the slot is verified and before the vault is usable.
 *
 * §8 forbids stating a claim `DISCREET_MODE.md` bars, so the copy says what is
 * true and no more: no server copy exists and no support path can recover it.
 *
 * [onRestore] is null unless the storage root holds no vault. A restore
 * installs an identity, `../format/BACKUP_FORMAT_V1.md` §8, so it is offered
 * where creation is offered and nowhere else: `DECOY_VAULT.md` §8 asks the
 * product to keep a restore away from another identity's descriptors, and §10
 * forbids a surface that differs by whether a second identity exists. With no
 * identity present there is none to overwrite and none to reveal.
 */
@Composable
fun CreateVaultScreen(
    busy: Boolean,
    error: String?,
    onCreate: (password: String, offerRecovery: Boolean) -> Unit,
    onCancel: () -> Unit,
    onRestore: (() -> Unit)? = null,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var offerRecovery by remember { mutableStateOf(true) }
    val colors = LocalChurColors.current
    val matching = password.isNotEmpty() && password == confirmation
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(ChurSpacing.gutterExpanded),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            ) {
                // §3 step 1.
                Text("Create a vault", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "A vault keeps photos, video, and audio on this device, encrypted. " +
                        "There is no server copy and no support path: if you lose the " +
                        "password and the recovery phrase, the contents are gone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                // §3 step 2.
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("Repeat password") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = confirmation.isNotEmpty() && !matching,
                    modifier = Modifier.fillMaxWidth(),
                )
                // §4: the offer, and the consequence of declining it.
                Column(verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = offerRecovery,
                            onCheckedChange = { offerRecovery = it },
                            enabled = !busy,
                        )
                        Text("Create a recovery phrase", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = if (offerRecovery) {
                            "You will see 24 words once. Write them down and keep them offline."
                        } else {
                            "Without a recovery phrase, a forgotten password cannot be " +
                                "recovered. You can add one later in settings."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                    )
                }
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
                Button(
                    onClick = { onCreate(password, offerRecovery) },
                    enabled = !busy && matching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Creating" else "Create vault")
                }
                if (onRestore != null) {
                    TextButton(onClick = onRestore, enabled = !busy) {
                        Text("Restore from a backup")
                    }
                }
                TextButton(onClick = onCancel, enabled = !busy) { Text("Not now") }
            }
        }
    }
}

/**
 * The recovery presentation of `RECOVERY.md` §2.
 *
 * The phrase is shown once and never again, which is why the confirmation is
 * explicit rather than a dismissal: §4 of `PROVISIONING.md` says declining is a
 * choice and not the dismissal of a sheet, and the same reasoning applies to
 * acknowledging.
 */
@Composable
fun RecoveryPhraseScreen(phrase: String, onAcknowledged: () -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    val colors = LocalChurColors.current
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(ChurSpacing.gutterExpanded),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            ) {
                Text("Your recovery phrase", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "These 24 words open your vault without the password. They are shown " +
                        "once. Write them down and keep them somewhere safe and offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                Surface(
                    color = colors.surfaceSunken,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(ChurSpacing.gutter)) {
                        phrase.split(" ").chunked(4).forEachIndexed { row, words ->
                            Text(
                                text = words.mapIndexed { index, word ->
                                    "${row * 4 + index + 1}. $word"
                                }.joinToString("   "),
                                style = DiagnosticTextStyle,
                                modifier = Modifier.padding(vertical = ChurSpacing.hairline),
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        "I have written the phrase down",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    onClick = onAcknowledged,
                    enabled = acknowledged,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text("Continue")
                }
            }
        }
    }
}

/**
 * The restore route, `docs/format/BACKUP_FORMAT_V1.md` §8.
 *
 * The password comes before the file because §8 does: step 2 obtains the
 * credential from the package's own portable descriptor and step 3
 * authenticates the manifest with it, so a package the credential does not open
 * is refused before any record of it is read.
 *
 * The copy names the package and never this device's vaults.
 * `../security/DECOY_VAULT.md` §10 forbids a surface that differs by whether a
 * second identity exists, and the hosts offer this screen only while the
 * storage root holds no identity at all.
 */
@Composable
fun RestoreBackupScreen(
    busy: Boolean,
    error: String?,
    onChoose: (password: String) -> Unit,
    onBack: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val colors = LocalChurColors.current
    Surface(color = colors.canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(ChurSpacing.gutterExpanded),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
            ) {
                Text("Restore from a backup", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter the password of the vault the backup came from. Then choose " +
                        "the backup file. The file is read on this device. Chur keeps no " +
                        "copy of the file and no copy of the password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
                Button(
                    onClick = { onChoose(password) },
                    enabled = !busy && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Restoring" else "Choose backup file")
                }
                TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            }
        }
    }
}
