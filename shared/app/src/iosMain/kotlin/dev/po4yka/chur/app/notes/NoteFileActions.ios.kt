@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.notes

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
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read

@Composable
actual fun NoteImportAction(
    enabled: Boolean,
    onPickerActive: (Boolean) -> Unit,
    onImported: (title: String, body: String) -> Unit,
    onFailure: () -> Unit,
) {
    val presenter = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val imported by rememberUpdatedState(onImported)
    val pickerActive by rememberUpdatedState(onPickerActive)
    val pickerLease = remember { NotePickerActivityLease() }
    val failure by rememberUpdatedState(onFailure)
    val presentation = remember { NotePickerPresentation() }
    var active by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }
    var requestGeneration by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { active = false; presentation.clear(); pending = false; pickerLease.finish() } }
    DisposableEffect(enabled) {
        if (!enabled) { pending = false; presentation.clear(); pickerLease.finish() }
        onDispose { requestGeneration++ }
    }
    TextButton(enabled = enabled && !pending, onClick = {
        val generation = requestGeneration
        try {
            val types = listOfNotNull(UTType.typeWithFilenameExtension("md"), UTType.typeWithFilenameExtension("markdown"))
            check(types.isNotEmpty()) { "Markdown type is unavailable" }
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = false)
            picker.allowsMultipleSelection = false
            val delegate = NotePickerDelegate { url ->
                val accepted = pending && active && currentEnabled && generation == requestGeneration
                pending = false
                presentation.clear()
                pickerLease.finish()
                if (accepted && url != null) scope.launch {
                    try {
                        val note = withContext(Dispatchers.Default) {
                            val bytes = readMarkdownDocument(url)
                            try { decodeMarkdownNote(url.lastPathComponent ?: error("missing filename"), bytes) }
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
            pending = true
            pickerLease.begin { pickerActive(it) }
            presentation.show(presenter, picker, delegate)
        } catch (_: Exception) { presentation.clear(); pending = false; pickerLease.finish(); failure() }
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
    val presenter = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val failure by rememberUpdatedState(onFailure)
    val presentation = remember { NotePickerPresentation() }
    var active by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { active = false; presentation.clear(); pending = false } }
    TextButton(enabled = enabled && !pending, onClick = {
        pending = true
        val filename = noteExportFilename(title, markdown)
        val bytes = publicNoteExportBytes(title, body, markdown)
        val directory = NSTemporaryDirectory() + "chur-public-note-" + NSUUID().UUIDString
        scope.launch {
            try {
                presentation.temporaryDirectory = directory
                val path = "$directory/$filename"
                withContext(Dispatchers.Default) {
                    check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
                    val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
                        NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
                    }
                    check(data.writeToFile(path, atomically = true)) { "cannot write public note export" }
                }
                ensureActive()
                if (!active || !currentEnabled) {
                    presentation.clear()
                    pending = false
                    return@launch
                }
                val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(path)), asCopy = true)
                val delegate = NotePickerDelegate { _ -> pending = false; presentation.clear() }
                presentation.show(presenter, picker, delegate)
            } catch (cancelled: CancellationException) {
                presentation.clear()
                NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
                throw cancelled
            } catch (_: Exception) {
                presentation.clear()
                NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
                pending = false
                if (active && currentEnabled) failure()
            } finally { bytes.fill(0) }
        }
    }) { Text(if (markdown) "Export .md" else "Export .txt") }
}

private class NotePickerDelegate(private val completion: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        completion(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        completion(null)
    }
}

/** UIKit delegates are weak; retain them exactly as long as their presentation. */
private class NotePickerPresentation {
    private var picker: UIDocumentPickerViewController? = null
    private var delegate: NotePickerDelegate? = null
    var temporaryDirectory: String? = null

    fun show(presenter: UIViewController, picker: UIDocumentPickerViewController, delegate: NotePickerDelegate) {
        check(this.picker == null)
        this.picker = picker
        this.delegate = delegate
        picker.delegate = delegate
        var top = presenter
        while (top.presentedViewController != null) top = top.presentedViewController!!
        top.presentViewController(picker, animated = true, completion = null)
    }

    fun clear() {
        picker?.delegate = null
        picker?.dismissViewControllerAnimated(false, completion = null)
        picker = null
        delegate = null
        temporaryDirectory?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        temporaryDirectory = null
    }
}

private fun readMarkdownDocument(url: NSURL): ByteArray {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        val descriptor = open(url.path ?: error("document has no path"), O_RDONLY)
        check(descriptor >= 0) { "cannot open document" }
        try {
            val buffer = ByteArray(NOTE_IMPORT_MAX_BYTES + 1)
            var count = 0
            try {
                buffer.usePinned { pinned ->
                    while (count < buffer.size) {
                        val received = read(descriptor, pinned.addressOf(count), (buffer.size - count).toULong()).toInt()
                        check(received >= 0) { "cannot read document" }
                        if (received == 0) break
                        count += received
                    }
                }
                require(count <= NOTE_IMPORT_MAX_BYTES) { "note is too large" }
                return buffer.copyOf(count)
            } finally { buffer.fill(0) }
        } finally { close(descriptor) }
    } finally { if (scoped) url.stopAccessingSecurityScopedResource() }
}
