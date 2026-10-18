package dev.po4yka.chur.app.notes

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun NoteImportAction(
    enabled: Boolean,
    onPickerActive: (Boolean) -> Unit,
    onImported: (title: String, body: String) -> Unit,
    onFailure: () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val imported by rememberUpdatedState(onImported)
    val pickerActive by rememberUpdatedState(onPickerActive)
    val pickerLease = remember { NotePickerActivityLease() }
    val failure by rememberUpdatedState(onFailure)
    var pending by remember { mutableStateOf(false) }
    var requestGeneration by remember { mutableStateOf(0) }
    var pendingGeneration by remember { mutableStateOf<Int?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false; pending = false; pendingGeneration = null; pickerLease.finish() } }
    DisposableEffect(enabled) {
        if (!enabled) { pending = false; pendingGeneration = null; pickerLease.finish() }
        onDispose { requestGeneration++ }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val generation = pendingGeneration
        val accepted = active && currentEnabled && pending && generation == requestGeneration
        pending = false
        pendingGeneration = null
        pickerLease.finish()
        if (accepted && uri != null) scope.launch {
            try {
                val note = withContext(Dispatchers.IO) {
                    val bytes = readMarkdownDocument(resolver, uri)
                    try { decodeMarkdownNote(documentName(resolver, uri), bytes) }
                    finally { bytes.fill(0) }
                }
                ensureActive()
                if (active && currentEnabled && generation == requestGeneration) imported(note.title, note.body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (active && currentEnabled && generation == requestGeneration) failure()
            }
        }
    }
    TextButton(enabled = enabled && !pending, onClick = {
        pending = true
        pendingGeneration = requestGeneration
        try {
            pickerLease.begin { pickerActive(it) }
            launcher.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream"))
        } catch (_: Exception) { pending = false; pendingGeneration = null; pickerLease.finish(); failure() }
    }) { Text("Import .md") }
}

@Composable
actual fun PublicNoteExportAction(
    title: String,
    body: String,
    markdown: Boolean,
    enabled: Boolean,
    onFailure: () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val failure by rememberUpdatedState(onFailure)
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false; pendingBytes?.fill(0); pendingBytes = null } }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (markdown) "text/markdown" else "text/plain"),
    ) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (bytes != null) {
            if (uri == null || !active || !currentEnabled) bytes.fill(0)
            else scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                            ?: error("cannot open export destination")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (active && currentEnabled) failure()
                } finally { bytes.fill(0) }
            }
        }
    }
    TextButton(enabled = enabled && pendingBytes == null, onClick = {
        pendingBytes = publicNoteExportBytes(title, body, markdown)
        try { launcher.launch(noteExportFilename(title, markdown)) }
        catch (_: Exception) { pendingBytes?.fill(0); pendingBytes = null; failure() }
    }) { Text(if (markdown) "Export .md" else "Export .txt") }
}

private fun documentName(resolver: ContentResolver, uri: Uri): String =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: error("document has no filename")

private fun readMarkdownDocument(resolver: ContentResolver, uri: Uri): ByteArray =
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(NOTE_IMPORT_MAX_BYTES + 1)
        var count = 0
        try {
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                require(read > 0) { "document stream made no progress" }
                count += read
            }
            require(count <= NOTE_IMPORT_MAX_BYTES) { "note is too large" }
            buffer.copyOf(count)
        } finally { buffer.fill(0) }
    } ?: error("cannot open document")
