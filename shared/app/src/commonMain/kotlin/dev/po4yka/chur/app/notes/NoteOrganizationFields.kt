package dev.po4yka.chur.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.po4yka.chur.app.privateKeyboardOptions
import dev.po4yka.chur.app.privateNoteTextFieldPolicy
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors
import dev.po4yka.chur.notes.NoteOrganization

/** Callers provide the privacy boundary and persistence; these fields retain no saved state. */
@Composable
fun NoteOrganizationFields(folder: String, tags: List<String>, enabled: Boolean, private: Boolean = false,
    onActivity: () -> Unit = {}, onApply: (String, List<String>) -> Unit) {
    var folderDraft by remember(folder) { mutableStateOf(folder) }
    var tagDraft by remember(tags) { mutableStateOf(tags.joinToString("\n")) }
    var invalid by remember { mutableStateOf(false) }
    val field = if (private) Modifier.fillMaxWidth().privateNoteTextFieldPolicy() else Modifier.fillMaxWidth()
    Column(verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
        Text("Organization", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(folderDraft, { if (it != folderDraft) onActivity(); folderDraft = it.take(128); invalid = false }, enabled = enabled,
            singleLine = true, label = { Text("Folder (empty for no folder)") }, modifier = field.testTag("note-folder"),
            keyboardOptions = if (private) privateKeyboardOptions() else androidx.compose.foundation.text.KeyboardOptions.Default,
            colors = churOutlinedTextFieldColors())
        OutlinedTextField(tagDraft, { if (it != tagDraft) onActivity(); tagDraft = it.take(2048); invalid = false }, enabled = enabled,
            singleLine = false, maxLines = 4, label = { Text("Tags (one per line)") }, modifier = field.testTag("note-tags"),
            keyboardOptions = if (private) privateKeyboardOptions() else androidx.compose.foundation.text.KeyboardOptions.Default,
            colors = churOutlinedTextFieldColors())
        TextButton(enabled = enabled && (folderDraft != folder || tagDraft != tags.joinToString("\n")), onClick = {
            val canonical = try { NoteOrganization.folder(folderDraft).orEmpty() to
                NoteOrganization.tags(tagDraft.lines().map { it.trim() }.filter { it.isNotEmpty() })
            } catch (_: IllegalArgumentException) { invalid = true; null }
            canonical?.let { onApply(it.first, it.second) }
        }) { Text("Apply organization") }
        if (invalid) Text("Use up to 16 tags and 64 UTF-8 bytes per folder or tag.")
    }
}

@Composable
fun NoteFilterFields(folder: String, tag: String, enabled: Boolean, private: Boolean = false,
    onActivity: () -> Unit = {}, onApply: (String, String) -> Unit) {
    var folderDraft by remember(folder) { mutableStateOf(folder) }
    var tagDraft by remember(tag) { mutableStateOf(tag) }
    val field = if (private) Modifier.fillMaxWidth().privateNoteTextFieldPolicy() else Modifier.fillMaxWidth()
    Column(verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
        OutlinedTextField(folderDraft, { if (it != folderDraft) onActivity(); if (it.encodeToByteArray().size <= 64) folderDraft = it },
            enabled = enabled, singleLine = true, label = { Text("Filter folder (empty for any)") }, modifier = field.testTag("note-filter-folder"),
            keyboardOptions = if (private) privateKeyboardOptions() else androidx.compose.foundation.text.KeyboardOptions.Default,
            colors = churOutlinedTextFieldColors())
        OutlinedTextField(tagDraft, { if (it != tagDraft) onActivity(); if (it.encodeToByteArray().size <= 64) tagDraft = it },
            enabled = enabled, singleLine = true, label = { Text("Filter tag (empty for any)") }, modifier = field.testTag("note-filter-tag"),
            keyboardOptions = if (private) privateKeyboardOptions() else androidx.compose.foundation.text.KeyboardOptions.Default,
            colors = churOutlinedTextFieldColors())
        FlowRow {
            TextButton(enabled = enabled, onClick = { onApply(folderDraft.trim(), tagDraft.trim()) }) { Text("Apply filters") }
            TextButton(enabled = enabled, onClick = { folderDraft = ""; tagDraft = ""; onApply("", "") }) { Text("Clear filters") }
        }
    }
}
