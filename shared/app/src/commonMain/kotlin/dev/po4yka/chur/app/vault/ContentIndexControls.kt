package dev.po4yka.chur.app.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.po4yka.chur.app.ContentIndexState
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.LocalChurMotion

/** Local index controls contain no source names or extracted text. */
@Composable
internal fun ContentIndexControls(state: ContentIndexState, actions: VaultActions) {
    if (!state.available) return
    var expanded by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    val colors = LocalChurColors.current
    val motion = LocalChurMotion.current
    Column(Modifier.fillMaxWidth().padding(horizontal = ChurSpacing.gutter)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("content-index-details")
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
            Text("Local content search")
        }
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = ChurSpacing.two))
            if (state.clearing) Text("Clearing content index…", style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted)
            Text("${state.indexed} indexed · ${state.partial} partial · ${state.failed} need retry" +
                if (state.pages > 0) " · Page ${state.page} of ${state.pages}" else "",
                style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            if (!state.clearing) TextButton(onClick = actions.onCancelContentIndex, enabled = !state.stopping,
                modifier = Modifier.testTag("content-index-cancel")) {
                Text(if (state.stopping) "Stopping…" else "Stop indexing")
            }
        } else {
            state.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            }
            if (state.message != null || state.partial > 0 || state.failed > 0) {
                Text("${state.indexed} indexed · ${state.partial} partial · ${state.failed} need retry",
                    style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            }
        }
        AnimatedVisibility(visible = expanded,
            enter = fadeIn(tween(motion.stateDurationMillis)), exit = fadeOut(tween(motion.stateDurationMillis))) {
            Column {
                Text("Local OCR for photos, PDFs, and UTF-8 text. The index stays encrypted. OCR can miss words.",
                    style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                if (!state.running) {
                    TextButton(onClick = actions.onIndexContent, modifier = Modifier.testTag("content-index-start")) {
                        Text("Index new items and retry failures")
                    }
                    TextButton(onClick = { options = true }, modifier = Modifier.testTag("content-index-options")) {
                        Text("Index options")
                    }
                }
            }
        }
    }
    if (options) AlertDialog(
        onDismissRequest = { options = false },
        title = { Text("Content index") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Photos and PDF pages use local English and Russian OCR. Text files must use UTF-8. PDFs are limited to 32 MiB and 100 pages; extracted text is limited to 256 KiB per item. Partial results are marked in the scan summary. Other file formats are skipped.")
                TextButton(onClick = { options = false; actions.onRebuildContentIndex() }) { Text("Rebuild all supported items") }
                TextButton(onClick = { options = false; clear = true }) { Text("Clear content index") }
            }
        },
        confirmButton = { TextButton(onClick = { options = false }) { Text("Close") } },
    )
    if (clear) AlertDialog(
        onDismissRequest = { clear = false },
        title = { Text("Clear content index?") },
        text = { Text("This removes recognized text from content search. Your files, filenames, captions, and tags remain available. You can rebuild the index later.") },
        confirmButton = { TextButton(onClick = { clear = false; actions.onClearContentIndex() }) { Text("Clear index") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } },
    )
}
