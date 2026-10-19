package dev.po4yka.chur.app.notes

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

@Composable
actual fun NoteBinaryImportAction(label: String, maxBytes: Int, enabled: Boolean,
    onPickerActive: (Boolean) -> Unit, onImported: (String, ByteArray) -> Unit, onFailure: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val imported by rememberUpdatedState(onImported)
    val activity by rememberUpdatedState(onPickerActive)
    val failure by rememberUpdatedState(onFailure)
    val lease = remember { NotePickerActivityLease() }
    var pending by remember { mutableStateOf(false) }
    var epoch by remember { mutableIntStateOf(0) }
    var requested by remember { mutableStateOf<Int?>(null) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false; requested = null; lease.finish() } }
    DisposableEffect(enabled) {
        if (!enabled) { pending = false; requested = null; lease.finish() }
        onDispose { epoch++ }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ticket = requested
        val accepted = alive && latestEnabled && pending && ticket == epoch
        pending = false; requested = null; lease.finish()
        if (accepted && uri != null) scope.launch {
            var bytes: ByteArray? = null
            try {
                val name = withContext(Dispatchers.IO) {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: error("missing filename")
                }
                withContext(Dispatchers.IO) {
                    bytes = resolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(maxBytes + 1)
                        var count = 0
                        try {
                            while (count < buffer.size) {
                                val read = input.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                require(read > 0)
                                count += read
                            }
                            require(count <= maxBytes)
                            buffer.copyOf(count)
                        } finally { buffer.fill(0) }
                    } ?: error("cannot read document")
                }
                ensureActive()
                if (alive && latestEnabled && ticket == epoch) {
                    val owned = bytes!!
                    imported(name, owned)
                    bytes = null
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (alive && latestEnabled && ticket == epoch) failure()
            } finally { bytes?.fill(0) }
        }
    }
    TextButton(enabled = enabled && !pending, onClick = {
        pending = true; requested = epoch
        try { lease.begin { activity(it) }; launcher.launch(arrayOf("*/*")) }
        catch (_: Exception) { pending = false; requested = null; lease.finish(); failure() }
    }) { Text(label) }
}

@Composable
actual fun PublicNoteBinaryExportAction(filename: String, mimeType: String, enabled: Boolean,
    label: String, load: suspend () -> ByteArray, onFailure: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val loader by rememberUpdatedState(load)
    val failure by rememberUpdatedState(onFailure)
    var alive by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { alive = false } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        pending = false
        if (uri != null && alive && latestEnabled) scope.launch {
            var bytes: ByteArray? = null
            try {
                bytes = loader()
                ensureActive()
                check(alive && latestEnabled)
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes!!) } ?: error("cannot write document")
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (alive && latestEnabled) failure()
            } finally { bytes?.fill(0) }
        }
    }
    TextButton(enabled = enabled && !pending, onClick = {
        pending = true
        try { launcher.launch(filename) } catch (_: Exception) { pending = false; failure() }
    }) { Text(label) }
}
