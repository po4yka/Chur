package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.PrivateNoteInputPolicy
import dev.po4yka.chur.app.PrivateTextInput
import dev.po4yka.chur.app.privateNoteTextFieldPolicy
import dev.po4yka.chur.app.secretKeyboardOptions
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.app.utf8Size

internal fun archivePasswordValid(password: String): Boolean = password.isNotEmpty() && utf8Size(password) <= 1_024

/** The host removes this generation-bound dialog before it opens a destination picker. */
@Composable
fun SelectiveExportDialog(
    summary: String,
    enabled: Boolean = true,
    files: Boolean = true,
    share: Boolean = true,
    onChoose: (String, ExportTarget) -> Unit,
    onDismiss: () -> Unit,
    externalFiles: Boolean = false,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { password = ""; confirmation = "" } }
    val matching = archivePasswordValid(password) && password == confirmation && !tooLong
    val dismiss = { password = ""; confirmation = ""; onDismiss() }
    val choose: (ExportTarget) -> Unit = { target ->
        if (enabled && archivePasswordValid(password) && password == confirmation && !tooLong) {
            val entered = password
            password = ""
            confirmation = ""
            onChoose(entered, target)
        }
    }
    ArchiveSecretInput {
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Create encrypted archive") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ChurSpacing.two)) {
                    Text(summary)
                    Text(if (externalFiles) "Only the chosen external files are included. They are not added to the library. " +
                        "This archive has its own password. Keep the password: it cannot be recovered."
                    else "Only the selected items are included. Selected albums include their subalbums. " +
                        "This archive has its own password. Keep the password: it cannot be recovered.")
                    ArchivePasswordField(password, { value ->
                        tooLong = utf8Size(value) > 1_024
                        if (!tooLong) password = value
                    }, "New archive password", "archive-password", enabled, next = true)
                    ArchivePasswordField(confirmation, { value ->
                        tooLong = utf8Size(value) > 1_024
                        if (!tooLong) confirmation = value
                    }, "Repeat archive password", "archive-password-repeat", enabled,
                        error = confirmation.isNotEmpty() && !matching)
                    if (tooLong) Text("Use at most 1024 UTF-8 bytes for the password.")
                    else if (confirmation.isNotEmpty() && !matching) Text("The passwords do not match.")
                }
            },
            confirmButton = {
                FlowRow {
                    if (files) TextButton(enabled = enabled && matching, onClick = { choose(ExportTarget.FILES) }) { Text("Save to Files") }
                    if (share) TextButton(enabled = enabled && matching, onClick = { choose(ExportTarget.SHARE) }) { Text("Share") }
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ArchiveSecretInput(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        PrivateTextInput(enabled = true) { PrivateNoteInputPolicy(content) }
    }
}

@Composable
internal fun ArchivePasswordField(value: String, onValueChange: (String) -> Unit, label: String,
    tag: String, enabled: Boolean, next: Boolean = false, error: Boolean = false) {
    OutlinedTextField(value, onValueChange, enabled = enabled, singleLine = true,
        label = { Text(label) }, isError = error, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = secretKeyboardOptions(imeAction = if (next) ImeAction.Next else ImeAction.Done),
        modifier = Modifier.fillMaxWidth().privateNoteTextFieldPolicy().testTag(tag),
        colors = churOutlinedTextFieldColors())
}
