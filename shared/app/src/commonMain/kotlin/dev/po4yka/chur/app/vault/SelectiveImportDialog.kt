package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.utf8Size

/** Import adds copies to the open vault; it does not replace that vault. */
@Composable
fun SelectiveImportDialog(
    enabled: Boolean = true,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { password = "" } }
    val dismiss = { password = ""; onDismiss() }
    ArchiveSecretInput {
        PrivateAlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Import encrypted archive") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ChurSpacing.two)) {
                    Text("Enter the archive password, then choose its file. Chur checks the whole archive before adding items to this vault. " +
                        "Existing items are kept. If import stops or is cancelled, saved originals and notes remain. Tags, previews, or album organization may be incomplete; unfinished originals are discarded.")
                    ArchivePasswordField(password, { value ->
                        tooLong = utf8Size(value) > 1_024
                        if (!tooLong) password = value
                    }, "Archive password", "archive-import-password", enabled)
                    if (tooLong) Text("Use at most 1024 UTF-8 bytes for the password.")
                }
            },
            confirmButton = { TextButton(enabled = enabled && archivePasswordValid(password) && !tooLong, onClick = {
                if (enabled && archivePasswordValid(password) && !tooLong) {
                    val entered = password
                    password = ""
                    onChoose(entered)
                }
            }) { Text("Choose archive") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}
