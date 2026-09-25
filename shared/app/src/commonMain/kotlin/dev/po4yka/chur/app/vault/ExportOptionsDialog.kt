package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.po4yka.chur.app.ExportTarget

/** The only media classes Photos accepts from the vault catalog. */
fun canSaveToPhotos(mediaKind: Int): Boolean =
    mediaKind == MEDIA_CLASS_IMAGE || mediaKind == MEDIA_CLASS_VIDEO

/** The original leaves the vault only after the user chooses a destination. */
@Composable
fun ExportOptionsDialog(
    media: Boolean,
    files: Boolean,
    share: Boolean,
    downloads: Boolean = false,
    onChoose: (ExportTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export original") },
        text = {
            Column {
                Text("The destination or recipient may keep an unencrypted copy.")
                if (media) TextButton(onClick = { onChoose(ExportTarget.MEDIA_LIBRARY) }) {
                    Text("Save to Photos")
                }
                if (downloads) TextButton(onClick = { onChoose(ExportTarget.DEFAULT) }) {
                    Text("Save to Downloads")
                }
                if (files) TextButton(onClick = { onChoose(ExportTarget.FILES) }) {
                    Text("Save to Files")
                }
                if (share) TextButton(onClick = { onChoose(ExportTarget.SHARE) }) {
                    Text("Share")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
