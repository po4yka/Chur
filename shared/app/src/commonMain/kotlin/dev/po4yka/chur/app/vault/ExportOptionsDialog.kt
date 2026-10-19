package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onRestoreLivePhoto: (() -> Unit)? = null,
    presentation: PrivateModalPresentation = PrivateModalPresentation.Popover,
) {
    PrivateAlertDialog(
        presentation = presentation,
        anchor = PrivateModalAnchor.Export,
        onDismissRequest = onDismiss,
        title = { Text("Export original") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("The destination or recipient may keep an unencrypted copy.")
                if (onRestoreLivePhoto != null) TextButton(onClick = privateModalAction(onRestoreLivePhoto)) {
                    Text("Restore Live Photo resources")
                }
                if (media) TextButton(onClick = privateModalAction { onChoose(ExportTarget.MEDIA_LIBRARY) }) {
                    Text("Save to Photos")
                }
                if (downloads) TextButton(onClick = privateModalAction { onChoose(ExportTarget.DEFAULT) }) {
                    Text("Save to Downloads")
                }
                if (files) TextButton(onClick = privateModalAction { onChoose(ExportTarget.FILES) }) {
                    Text("Save to Files")
                }
                if (share) TextButton(onClick = privateModalAction { onChoose(ExportTarget.SHARE) }) {
                    Text("Share")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = privateModalAction(onDismiss)) { Text("Cancel") } },
    )
}
