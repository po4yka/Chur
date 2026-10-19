package dev.po4yka.chur.app.notes

import androidx.compose.runtime.Composable

/** Transfers ownership of a bounded input array to the callback; consumers clear it. */
@Composable
expect fun NoteBinaryImportAction(
    label: String,
    maxBytes: Int,
    enabled: Boolean = true,
    onPickerActive: (Boolean) -> Unit = {},
    onImported: (name: String, bytes: ByteArray) -> Unit,
    onFailure: () -> Unit,
)

/** Public content only. Private exports use the native warning/cancellation operation. */
@Composable
expect fun PublicNoteBinaryExportAction(
    filename: String,
    mimeType: String,
    enabled: Boolean = true,
    label: String = "Save file",
    load: suspend () -> ByteArray,
    onFailure: () -> Unit,
)
