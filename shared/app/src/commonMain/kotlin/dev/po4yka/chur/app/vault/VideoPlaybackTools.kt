package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Explicit, private viewing-copy controls shared by the native players. */
@Composable
internal fun VideoPlaybackTools(
    source: PlaybackSource,
    modifier: Modifier = Modifier,
    controlsVisible: Boolean = true,
    controlsPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable (PlaybackSource, Modifier, (() -> Unit)?) -> Unit,
) {
    val state by source.vault.state.collectAsState()
    val generation = (state as? VaultState.Unlocked)?.generation
    val key = VideoToolsKey(source.vault, source.objectId.toList(), source.kind, source.offset, source.length, generation)
    val currentSource by rememberUpdatedState(source)
    val eligible = videoCopyAvailable(source.contentType, source.kind, source.offset, source.length, source.plaintextSize)
    val encoder = rememberVideoCompatibilityEncoder()
    val scope = rememberCoroutineScope()
    var copy by remember(key) { mutableStateOf<PlaybackSource?>(null) }
    var useCopy by remember(key) { mutableStateOf(false) }
    var confirm by remember(key) { mutableStateOf(false) }
    var busy by remember(key) { mutableStateOf(false) }
    var progress by remember(key) { mutableStateOf(0.0) }
    var message by remember(key) { mutableStateOf<String?>(null) }
    var operation by remember(key) { mutableStateOf<Job?>(null) }

    LaunchedEffect(key) {
        if (eligible && generation != null) {
            try {
                copy = compatibleVideoSource(currentSource, generation)
                useCopy = true
            } catch (failure: CancellationException) { throw failure }
            catch (failure: ChurFailure) {
                if (failure.status != ChurStatus.NOT_FOUND && failure.status != ChurStatus.SESSION_EXPIRED &&
                    failure.status != ChurStatus.VAULT_LOCKED) message = videoCopyFailureMessage(failure.status)
            } catch (_: Exception) { message = "The saved viewing copy is not available. Verify the library." }
        }
    }
    DisposableEffect(key) { onDispose { operation?.cancel() } }
    val requestCopy: (() -> Unit)? = if (eligible && generation != null) ({
        if (copy != null) { useCopy = true; message = null } else if (!busy) confirm = true
    }) else null
    Box(modifier.background(ViewerColors.canvas)) {
        // Dispose the native player while another decoder owns the operation.
        // Reopening it after cancel or completion uses its usual encrypted reader.
        val activeCopy = remember(copy, source.onVerify, source.onExport) {
            copy?.let { PlaybackSource(it.vault, it.objectId, it.contentType, it.plaintextSize, it.kind,
                onVerify = source.onVerify, onExport = source.onExport) }
        }
        if (generation != null && !busy) content(if (useCopy) activeCopy ?: source else source, Modifier.fillMaxSize(), requestCopy)
        if (controlsVisible && eligible && generation != null && !busy) {
            Column(Modifier.align(Alignment.TopCenter).padding(controlsPadding).padding(8.dp)
                .background(ViewerColors.chromeScrim), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (copy != null) {
                        TextButton(onClick = { useCopy = false; message = null }, enabled = useCopy,
                            modifier = Modifier.testTag("video-original")) { Text("Original", color = ViewerColors.content) }
                        TextButton(onClick = { useCopy = true; message = null }, enabled = !useCopy,
                            modifier = Modifier.testTag("video-viewing-copy")) { Text("Viewing copy", color = ViewerColors.content) }
                    } else {
                        TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("video-create-copy")) {
                            Text("Create viewing copy", color = ViewerColors.content)
                        }
                    }
                }
                message?.let { Text(it, color = ViewerColors.content, modifier = Modifier.padding(8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        }
    }
    if (confirm && generation != null) PrivateAlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Create viewing copy?") },
        text = { Text("Create an encrypted H.264 SDR copy for this device. It uses up to 720p and 30 fps and can use up to 32 MiB of storage. Export still gives you the original file.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                busy = true
                progress = 0.0
                message = null
                val expectedGeneration = generation
                operation = scope.launch {
                    try {
                        val result = createCompatibleVideoCopy(currentSource, encoder, onProgress = { progress = it.coerceIn(0.0, 1.0) })
                        if ((source.vault.state.value as? VaultState.Unlocked)?.generation == expectedGeneration) {
                            copy = result
                            useCopy = true
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: ChurFailure) {
                        if ((source.vault.state.value as? VaultState.Unlocked)?.generation == expectedGeneration) {
                            message = videoCopyFailureMessage(failure.status)
                        }
                    } catch (_: Exception) {
                        if ((source.vault.state.value as? VaultState.Unlocked)?.generation == expectedGeneration) {
                            message = "This device could not create the viewing copy."
                        }
                    } finally { busy = false }
                }
            }, modifier = Modifier.testTag("video-confirm-copy")) { Text("Create copy") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
    if (busy && generation != null) PrivateAlertDialog(
        onDismissRequest = { operation?.cancel() },
        title = { Text("Creating viewing copy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { progress.toFloat() })
                Text("${(progress * 100).toInt()}%", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = {
            TextButton(onClick = { operation?.cancel() }, modifier = Modifier.testTag("video-cancel-copy")) { Text("Cancel") }
        },
    )
}

internal fun videoCopyFailureMessage(status: ChurStatus): String = when (status) {
    ChurStatus.OBJECT_CORRUPT, ChurStatus.VAULT_CORRUPT, ChurStatus.CATALOG_CORRUPT ->
        "The viewing copy stopped because this file failed its integrity check. Verify the library."
    ChurStatus.RESOURCE_LIMIT_EXCEEDED -> "This video exceeds the viewing-copy limits."
    ChurStatus.UNSUPPORTED_VERSION, ChurStatus.UNSUPPORTED_SUITE -> "This device cannot create this viewing copy."
    else -> "This device could not create the viewing copy."
}

private data class VideoToolsKey(
    val vault: dev.po4yka.chur.vault.VaultRepository,
    val objectId: List<Byte>,
    val kind: StreamKind,
    val offset: Long,
    val length: Long,
    val generation: Long?,
)

internal fun videoCopyAvailable(contentType: String, kind: StreamKind, offset: Long, length: Long, plaintextSize: Long): Boolean =
    contentType.startsWith("video/") && kind == StreamKind.ORIGINAL && offset == 0L && length == plaintextSize
