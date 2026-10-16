package dev.po4yka.chur.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.vault.OperationProgressCard
import dev.po4yka.chur.app.vault.SelectiveExportDialog
import dev.po4yka.chur.imports.PickedMedia

/** Provider grants are transient; only the active controller job may consume them. */
class ExternalArchiveSelection(
    val count: Int,
    val open: suspend (Int, (Long, Long) -> Boolean) -> PickedMedia?,
    close: () -> Unit,
) {
    private val release = close
    private var closed = false
    fun close() { if (!closed) { closed = true; release() } }
    override fun toString(): String = "ExternalArchiveSelection(<redacted>)"
}

/** Main-thread owner of picker grants and credentials before transfer to the job. */
class ExternalArchiveOwner(private val controller: ChurController) {
    private val visit = controller.externalArchiveVisit
    private var disposed = false
    private var request = 0L
    private var endActivity: (() -> Unit)? = null
    private var credential: ArchiveCredential? = null
    var selection by mutableStateOf<ExternalArchiveSelection?>(null)
        private set
    var pickerPending by mutableStateOf(false)
        private set
    private fun current() = !disposed && controller.externalArchiveCurrent(visit)
    private fun releaseActivity() { endActivity?.invoke(); endActivity = null; pickerPending = false }

    fun choose(picker: ((ExternalArchiveSelection?) -> Unit) -> Unit) {
        if (!current() || pickerPending) return
        selection?.close(); selection = null
        endActivity = controller.externalArchiveActivity(visit) ?: return
        pickerPending = true
        val id = ++request
        try {
            picker { result ->
                if (id != request) { result?.close(); return@picker }
                request++
                releaseActivity()
                if (!current() || result == null || result.count !in 1..32) {
                    result?.close()
                    if (current() && result != null) controller.report("Choose between 1 and 32 files.")
                } else selection = result
            }
        } catch (_: Exception) {
            releaseActivity()
            controller.report("The file picker could not be opened.")
        }
    }

    fun export(secret: ArchiveCredential, target: ExportTarget,
        chooseDestination: (((String?) -> Unit) -> Unit)? = null) {
        if (!current() || pickerPending || selection == null) { secret.clear(); return }
        if (target != ExportTarget.FILES || chooseDestination == null) {
            val sources = selection!!; selection = null
            controller.exportExternalArchive(visit, sources, secret, target)
            return
        }
        endActivity = controller.externalArchiveActivity(visit)
        if (endActivity == null) { secret.clear(); return }
        pickerPending = true
        credential = secret
        try { chooseDestination(::exportedFile) }
        catch (_: Exception) { exportedFile(null); controller.report("The file picker could not be opened.") }
    }

    fun exportedFile(uri: String?) {
        val secret = credential; credential = null
        val sources = selection; selection = null
        releaseActivity()
        if (uri == null || secret == null || sources == null || !current()) {
            secret?.clear(); sources?.close()
            if (uri != null) controller.discardArchiveDestination(uri)
            return
        }
        controller.exportExternalArchive(visit, sources, secret, ExportTarget.FILES, uri)
    }

    fun dispose() {
        if (disposed) return
        if (current()) controller.cancelActiveOperation()
        disposed = true
        request++
        releaseActivity()
        credential?.clear(); credential = null
        selection?.close(); selection = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalArchiveScreen(
    controller: ChurController,
    selectFiles: ((ExternalArchiveSelection?) -> Unit) -> Unit,
    chooseDestination: (((String?) -> Unit) -> Unit)? = null,
) {
    val owner = remember(controller) { ExternalArchiveOwner(controller) }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    val operation by controller.activeOperation.collectAsState()
    val notice by controller.notice.collectAsState()
    var passwordDialog by remember { mutableStateOf(false) }
    val enabled = operation == null && !owner.pickerPending
    Scaffold(topBar = {
        TopAppBar(title = { Text("Encrypt external files") }, navigationIcon = {
            IconButton(onClick = controller::closeExternalArchive) { Icon(BackGlyph, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three)) {
            Text("Create a password-protected CHURBAK1 archive without adding files to the library. Original files are kept.")
            Text("Choose up to 32 files, with up to 4 GiB of source data. The destination may have a smaller limit. " +
                "The archive has independent keys and its own password; keep it because it cannot be recovered.")
            Text("Open the result with Restore a backup or archive, or Import encrypted archive. " +
                "File names and types are stored inside the encrypted archive. Providers still control their original files.")
            owner.selection?.let { Text("${it.count} files selected") }
            Button(enabled = enabled, onClick = { owner.choose(selectFiles) }) { Text("Choose files") }
            Button(enabled = enabled && owner.selection != null, onClick = { passwordDialog = true }) {
                Text("Create encrypted archive")
            }
            operation?.let { OperationProgressCard(it, controller::cancelActiveOperation) }
            notice?.let { Text(it.text) }
        }
    }
    if (passwordDialog) SelectiveExportDialog(
        summary = "${owner.selection?.count ?: 0} external files",
        enabled = enabled,
        externalFiles = true,
        onChoose = { password, target ->
            passwordDialog = false
            owner.export(ArchiveCredential(password), target, chooseDestination)
        },
        onDismiss = { passwordDialog = false },
    )
}
