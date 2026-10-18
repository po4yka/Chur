package dev.po4yka.chur.app.vault

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.po4yka.chur.ffi.AlbumSummary

/** A deliberate plaintext-boundary choice, before the host asks for a folder. */
@Composable
fun AlbumExportDialog(album: AlbumSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PrivateAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export album") },
        text = { Text("Export all items in ${album.name} and its subalbums into a new folder. " +
            "The current filter does not limit the export. An item in several albums is copied into each album. " +
            "Existing files are not replaced. Exported copies remain unencrypted outside the vault, " +
            "including copies completed before cancellation.",
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Choose folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
