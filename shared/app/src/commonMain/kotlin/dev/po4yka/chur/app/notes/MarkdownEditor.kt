package dev.po4yka.chur.app.notes

import dev.po4yka.chur.app.publicshell.utilityText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.model.ReferenceLinkHandler
import dev.po4yka.chur.app.privateKeyboardOptions
import dev.po4yka.chur.app.privateNoteTextFieldPolicy
import dev.po4yka.chur.app.utf8Size
import dev.po4yka.chur.app.vault.PrivateAlertDialog
import dev.po4yka.chur.app.vault.PrivateDropdownMenu
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Route-owned state only: the private caller supplies no saveable registry. */
@Composable
internal fun MarkdownEditor(
    body: String,
    onBodyChange: (String) -> Unit,
    markdown: Boolean,
    onMarkdownChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    private: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    readAttachment: (suspend (String) -> ByteArray)? = null,
    attachmentIds: Set<String> = emptySet(),
) {
    var value by remember { mutableStateOf(TextFieldValue(body)) }
    val history = remember { MarkdownHistory(value) }
    var revision by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf(readOnly && markdown) }
    var finding by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    var formatting by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(body) {
        if (value.text != body) value = TextFieldValue(body, TextRange(value.selection.start.coerceAtMost(body.length)))
    }
    DisposableEffect(history) { onDispose { history.clear() } }
    fun publish(next: TextFieldValue, record: Boolean = true) {
        if (!enabled || readOnly) return
        if (private && utf8Size(next.text) > 262_144) { message = "The note exceeds 256 KiB."; return }
        if ('\u0000' in next.text) { message = "Notes cannot contain NUL characters."; return }
        val changed = next.text != value.text
        if (record) history.record(next)
        value = next
        revision++
        message = null
        if (changed) onBodyChange(next.text)
    }
    fun command(command: MarkdownCommand) = publish(markdownEdit(value, command))
    // Reading revision makes history button state observable without retaining a second document.
    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
    BoxWithConstraints(modifier) {
        // Find/outline controls may grow with accessibility text. Scroll them
        // above a bounded document, instead of shrinking the document to zero.
        val documentHeight = if (maxHeight == Dp.Infinity) 320.dp else maxOf(240.dp, maxHeight - 72.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(enabled = enabled && !readOnly, onClick = { onMarkdownChange(!markdown); preview = false }) {
                    Text(if (markdown) "Markdown" else "Plain text")
                }
                if (markdown) TextButton(onClick = { preview = !preview }, enabled = enabled) { Text(if (preview) "Edit source" else "Preview") }
                if (!readOnly) {
                    TextButton(enabled = enabled && history.canUndo, onClick = { history.undo()?.let { publish(it, false) } }) { Text(utilityText("Undo", "Отменить")) }
                    TextButton(enabled = enabled && history.canRedo, onClick = { history.redo()?.let { publish(it, false) } }) { Text(utilityText("Redo", "Повторить")) }
                    TextButton(onClick = { finding = !finding; preview = false }, enabled = enabled) { Text(utilityText("Find", "Найти")) }
                }
                if (markdown) TextButton(onClick = { outline = !outline }, enabled = enabled) { Text(utilityText("Outline", "Оглавление")) }
                if (markdown && !readOnly && !preview) Box {
                    TextButton(enabled = enabled, onClick = { formatting = true }) { Text(utilityText("Format", "Формат")) }
                    PrivateDropdownMenu(expanded = formatting, onDismissRequest = { formatting = false }) {
                        MarkdownCommand.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label) },
                            onClick = { formatting = false; command(action) }) }
                    }
                }
            }
            if (finding && !readOnly) {
                OutlinedTextField(query, { query = it }, label = { Text(utilityText("Find text", "Найти текст")) }, singleLine = true,
                    keyboardOptions = if (private) privateKeyboardOptions() else androidx.compose.foundation.text.KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().then(if (private) Modifier.privateNoteTextFieldPolicy() else Modifier)
                        .testTag(if (private) "private-note-find" else "note-find"))
                OutlinedTextField(replacement, { replacement = it }, label = { Text(utilityText("Replace with", "Заменить на")) }, singleLine = true,
                    keyboardOptions = if (private) privateKeyboardOptions() else androidx.compose.foundation.text.KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().then(if (private) Modifier.privateNoteTextFieldPolicy() else Modifier)
                        .testTag(if (private) "private-note-replace" else "note-replace"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = query.isNotEmpty(), onClick = {
                        val source = value.text
                        val from = value.selection.max.coerceAtMost(source.length)
                        val found = source.indexOf(query, from, ignoreCase = true).let { if (it < 0) source.indexOf(query, ignoreCase = true) else it }
                        if (found < 0) message = "No matches." else value = value.copy(selection = TextRange(found, found + query.length), composition = null)
                    }) { Text(utilityText("Next match", "Следующее")) }
                    TextButton(enabled = enabled && query.isNotEmpty(), onClick = {
                        if (value.text.substring(value.selection.min, value.selection.max).equals(query, ignoreCase = true)) {
                            val start = value.selection.min
                            publish(TextFieldValue(value.text.replaceRange(start, value.selection.max, replacement), TextRange(start + replacement.length)))
                        }
                    }) { Text(utilityText("Replace", "Заменить")) }
                    TextButton(enabled = enabled && query.isNotEmpty(), onClick = {
                        publish(TextFieldValue(value.text.replace(query, replacement, ignoreCase = true)))
                    }) { Text(utilityText("Replace all", "Заменить всё")) }
                }
            }
            if (outline && markdown) MarkdownOutline(value.text) { offset -> preview = false; value = value.copy(selection = TextRange(offset)); outline = false }
            if (preview && markdown) {
                MarkdownPreview(value.text, readAttachment = readAttachment, attachmentIds = attachmentIds, modifier = Modifier.height(documentHeight).fillMaxWidth(), onToggleTask = if (!enabled || readOnly) null else { start, end ->
                    toggleMarkdownTask(value.text, start, end)?.let { publish(TextFieldValue(it, TextRange(start))) }
                })
            } else {
                OutlinedTextField(value = value, onValueChange = { next ->
                    publish(if (markdown) continueMarkdownList(value, next) else next)
                }, enabled = enabled, readOnly = readOnly,
                    label = { Text(if (markdown) "Markdown source" else "Note") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (markdown) FontFamily.Monospace else FontFamily.Default),
                    keyboardOptions = if (private) privateKeyboardOptions() else androidx.compose.foundation.text.KeyboardOptions.Default,
                    modifier = Modifier.height(documentHeight).fillMaxWidth()
                        .then(if (private) Modifier.privateNoteTextFieldPolicy() else Modifier)
                        .testTag(if (private) "private-note-body" else "note-body")
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed) || !enabled || readOnly) false
                            else when (event.key) {
                                Key.Z -> { (if (event.isShiftPressed) history.redo() else history.undo())?.let { publish(it, false) }; true }
                                Key.Y -> { history.redo()?.let { publish(it, false) }; true }
                                Key.B -> if (markdown) { command(MarkdownCommand.BOLD); true } else false
                                Key.I -> if (markdown) { command(MarkdownCommand.ITALIC); true } else false
                                Key.F -> { finding = true; true }
                                else -> false
                            }
                        })
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun toggleMarkdownTask(source: String, start: Int, end: Int): String? {
    if (start < 0 || end > source.length || end < start + 3) return null
    val token = source.substring(start, end)
    val marker = token.take(3)
    // JetBrains CHECK_BOX nodes also cover trailing list indentation. Keep it byte-for-byte.
    if ((marker != "[ ]" && marker != "[x]" && marker != "[X]") || token.drop(3).any { !it.isWhitespace() }) return null
    return source.replaceRange(start + 1, start + 2, if (marker == "[ ]") "x" else " ")
}

/** The parsed source for this AST, which can differ from the next asynchronously parsed edit. */
private val LocalRenderedMarkdownSource = staticCompositionLocalOf<String> { error("Markdown source is unavailable") }

/** Inline component content is already the URL; its node describes the enclosing source block. */
internal fun markdownInlineImageAlt(source: String, node: ASTNode, link: String,
    references: ReferenceLinkHandler): String? {
    val labels = mutableSetOf<String?>()
    fun visit(image: ASTNode) {
        if (image.type == MarkdownElementTypes.IMAGE) {
            if (image.resolveImageLink(source, references) == link) labels += image.resolveImageAlt(source)
        } else image.children.forEach(::visit)
    }
    visit(node)
    // The upstream inline API deduplicates by URL and provides no placement identity.
    // Different descriptions of that URL must not all inherit the first one's label.
    return labels.singleOrNull()
}

@Composable
internal fun MarkdownPreview(body: String, modifier: Modifier = Modifier, onToggleTask: ((Int, Int) -> Unit)? = null,
    readAttachment: (suspend (String) -> ByteArray)? = null, attachmentIds: Set<String> = emptySet()) {
    val images = rememberNoteAttachmentImageCache(attachmentIds)
    val host = LocalUriHandler.current
    var link by remember { mutableStateOf<String?>(null) }
    var openFailed by remember { mutableStateOf(false) }
    val guarded = remember { object : UriHandler { override fun openUri(uri: String) {
        if (uri.startsWith("https://", true) || uri.startsWith("http://", true) || uri.startsWith("mailto:", true)) { openFailed = false; link = uri }
    } } }
    CompositionLocalProvider(LocalUriHandler provides guarded) {
        Markdown(body, modifier = modifier.verticalScroll(rememberScrollState()),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.headlineMedium,
                h2 = MaterialTheme.typography.headlineSmall,
                h3 = MaterialTheme.typography.titleLarge,
                h4 = MaterialTheme.typography.titleMedium,
                h5 = MaterialTheme.typography.titleMedium,
                h6 = MaterialTheme.typography.titleSmall,
            ),
            components = markdownComponents(
                codeBlock = highlightedCodeBlock, codeFence = highlightedCodeFence,
                image = { model ->
                    val id = model.node.resolveImageLink(model.content, LocalReferenceLinkHandler.current)?.let(::noteAttachmentId)
                    val name = model.node.resolveImageAlt(model.content).orEmpty()
                    if (id != null && readAttachment != null && id in attachmentIds) NoteAttachmentImage(id, name, { readAttachment(id) }, images)
                    else if (id != null) Text("Image unavailable: $name")
                    else Text("Image: $name (only managed attachments are loaded)")
                },
                inlineImage = { model ->
                    // Renderer 0.46 passes a resolved URL here, unlike the block image component.
                    val id = noteAttachmentId(model.content)
                    val name = markdownInlineImageAlt(LocalRenderedMarkdownSource.current, model.node,
                        model.content, LocalReferenceLinkHandler.current) ?: "Image"
                    if (id != null && readAttachment != null && id in attachmentIds) NoteAttachmentImage(id, name, { readAttachment(id) }, images)
                    else if (id != null) Text("Image unavailable: $name")
                    else Text("Image: $name (only managed attachments are loaded)")
                },
                checkbox = { model ->
                    val start = model.node.startOffset
                    val end = model.node.endOffset
                    val checked = model.content.substring(start, end).contains("x", true)
                    Checkbox(checked, onCheckedChange = if (onToggleTask == null) null else ({ onToggleTask(start, end) }),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Checklist item" })
                },
            ),
            success = { state, components, renderedModifier ->
                CompositionLocalProvider(LocalRenderedMarkdownSource provides state.content) {
                    MarkdownSuccess(state, components, renderedModifier)
                }
            },
            loading = { Text(utilityText("Preparing preview…", "Подготовка просмотра…")) },
            error = { Text(utilityText("Preview could not be rendered. The original source is available in Edit source.", "Не удалось показать заметку. Исходный текст доступен в редакторе.")) })
    }
    link?.let { uri -> PrivateAlertDialog(onDismissRequest = { link = null }, title = { Text(utilityText("Open external link?", "Открыть внешнюю ссылку?")) },
        text = { Text(if (openFailed) "The link could not be opened. Try again or cancel." else "The destination will receive this address.\n$uri") },
        confirmButton = { TextButton(onClick = { try { host.openUri(uri); link = null } catch (_: Exception) { openFailed = true } }) { Text(utilityText("Open", "Открыть")) } },
        dismissButton = { TextButton(onClick = { link = null }) { Text(utilityText("Cancel", "Отмена")) } }) }
}

@Composable
private fun MarkdownOutline(source: String, onSelect: (Int) -> Unit) {
    var headings by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    LaunchedEffect(source) {
        headings = withContext(Dispatchers.Default) {
            val root = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable).buildMarkdownTreeFromString(source)
            val result = mutableListOf<Pair<Int, String>>()
            val headingTypes = setOf(MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
                    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
                    MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2)
            val pending = ArrayDeque<ASTNode>().apply { add(root) }
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                if (node.type in headingTypes) result += node.startOffset to source.substring(node.startOffset, node.endOffset).lineSequence().first().trim().trim('#').trim()
                else pending.addAll(node.children.asReversed())
            }
            result
        }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
        if (headings.isEmpty()) item { Text(utilityText("No headings.", "Заголовков нет.")) }
        items(headings, key = { it.first }) { (offset, title) -> TextButton(onClick = { onSelect(offset) }) { Text(title) } }
    }
}
