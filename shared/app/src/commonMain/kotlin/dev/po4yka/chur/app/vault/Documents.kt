package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.FileGlyph
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.imports.normalizedFileContentType
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val MEDIA_CLASS_FILE = 4
const val DOCUMENT_TEXT_PREVIEW_MAX_BYTES = 1_048_576
const val DOCUMENT_PDF_PREVIEW_MAX_BYTES = 33_554_432L
const val DOCUMENT_PDF_RASTER_EDGE_MAX = 2_048

enum class DocumentPreviewKind { PDF, TEXT, NONE }

/** A preview choice, never a restriction on storing or exporting the original. */
fun documentPreviewKind(contentType: String): DocumentPreviewKind {
    val type = normalizedFileContentType(contentType)
    return when {
        type == "application/pdf" -> DocumentPreviewKind.PDF
        type.startsWith("text/") || type in setOf("application/json", "application/xml") -> DocumentPreviewKind.TEXT
        else -> DocumentPreviewKind.NONE
    }
}

/** Internal read-only previews. Platforms render PDF; text uses the shared bounded reader. */
@Composable
expect fun VaultDocument(
    vault: VaultRepository,
    generation: Long,
    projection: ObjectProjection,
    detail: ObjectDetail?,
    modifier: Modifier,
    contentPadding: PaddingValues,
)

/** Private metadata fetched only for the currently open document. */
@Composable
fun DocumentHeader(projection: ObjectProjection, detail: ObjectDetail?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
        Icon(FileGlyph, contentDescription = null, tint = ViewerColors.content)
        Text(detail?.filename?.ifBlank { "File" } ?: "File", color = ViewerColors.content,
            style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("${detail?.contentType ?: "File"} · ${humanSize(detail?.plaintextSize ?: projection.plaintextSize)}",
            color = ViewerColors.content, style = MaterialTheme.typography.bodySmall)
    }
}

/** Unsupported preview formats stay inside the vault until an explicit export. */
@Composable
fun DocumentFallback(
    projection: ObjectProjection,
    detail: ObjectDetail?,
    message: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(ChurSpacing.gutter), verticalArrangement = Arrangement.spacedBy(ChurSpacing.two)) {
        item { DocumentHeader(projection, detail) }
        item { Text(message, color = ViewerColors.content, style = MaterialTheme.typography.bodyMedium) }
        item { Text("Use Export below to save an unencrypted copy. The original stays in the vault.",
            color = ViewerColors.content, style = MaterialTheme.typography.bodySmall) }
    }
}

/** One bounded authenticated prefix. The caller clears [bytes] as soon as decoding ends. */
class DocumentPrefix(val bytes: ByteArray, val totalSize: Long) {
    val truncated: Boolean get() = totalSize > bytes.size
}

/** Session-bound reads never pass a private path to a platform preview. */
suspend fun readDocumentPrefix(
    vault: VaultRepository,
    generation: Long,
    projection: ObjectProjection,
    maxBytes: Int,
): DocumentPrefix {
    var result: DocumentPrefix? = null
    try {
        return withContext(Dispatchers.Default) {
            require(maxBytes in 1..DOCUMENT_PDF_PREVIEW_MAX_BYTES.toInt())
            fun checkSession() {
                if ((vault.state.value as? VaultState.Unlocked)?.generation != generation) {
                    throw ChurFailure(ChurStatus.SESSION_EXPIRED, "the document preview")
                }
            }
            checkSession()
            val reader = vault.leaseReader(projection.objectId)
            var plaintext: ByteArray? = null
            try {
                currentCoroutineContext().ensureActive()
                checkSession()
                val info = vault.readerContentInfo(reader)
                if (!info.complete) throw ChurFailure(ChurStatus.OBJECT_INCOMPLETE, "the document preview")
                val size = info.plaintextSize
                if (size < 0) throw ChurFailure(ChurStatus.INVALID_INPUT, "the document preview size")
                val bytes = if (size == 0L) byteArrayOf() else vault.readLeased(reader, 0, minOf(size, maxBytes.toLong()).toInt())
                plaintext = bytes
                currentCoroutineContext().ensureActive()
                checkSession()
                DocumentPrefix(bytes, size).also { result = it; plaintext = null }
            } finally {
                plaintext?.fill(0)
                vault.releaseReader(reader)
            }
        }
    } catch (failure: Exception) {
        // Cancellation can discard a withContext result at its dispatcher
        // handoff. Keep ownership until the caller actually receives it.
        result?.bytes?.fill(0)
        throw failure
    }
}

internal data class TextDocumentPreview(val text: String, val truncated: Boolean)

/** Strict UTF-8. Only an incomplete final code point of a truncated prefix may be omitted. */
internal fun decodeDocumentText(bytes: ByteArray, truncated: Boolean): TextDocumentPreview? {
    if (bytes.any { it == 0.toByte() }) return null
    for (trim in 0..(if (truncated) minOf(3, bytes.size) else 0)) {
        try {
            val text = bytes.decodeToString(endIndex = bytes.size - trim, throwOnInvalidSequence = true)
            if (trim > 0) {
                val start = bytes.size - trim
                val lead = bytes[start].toInt() and 255
                val expected = when (lead) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 0 }
                if (expected <= trim || (start + 1 until bytes.size).any { (bytes[it].toInt() and 0xc0) != 0x80 }) return null
                if (trim > 1) {
                    val second = bytes[start + 1].toInt() and 255
                    if ((lead == 0xe0 && second < 0xa0) || (lead == 0xed && second > 0x9f) ||
                        (lead == 0xf0 && second < 0x90) || (lead == 0xf4 && second > 0x8f)) return null
                }
            }
            return TextDocumentPreview(text.removePrefix("\uFEFF"), truncated)
        } catch (_: CharacterCodingException) {
            // A truncated prefix can end part-way through one UTF-8 character.
        }
    }
    return null
}

/** Layout chunks do not split a UTF-16 surrogate pair. Nothing is interpreted as markup. */
internal fun documentTextChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var at = 0
    while (at < text.length) {
        var end = minOf(at + 4_096, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end -= 1
        chunks += text.substring(at, end)
        at = end
    }
    return chunks
}

private sealed interface TextPreviewState {
    data object Loading : TextPreviewState
    data class Ready(val preview: TextDocumentPreview) : TextPreviewState
    data class Unavailable(val message: String) : TextPreviewState
}

/** No editable field, external viewer, clipboard action or persistent plaintext cache. */
@Composable
fun ReadOnlyTextDocument(
    vault: VaultRepository,
    generation: Long,
    projection: ObjectProjection,
    detail: ObjectDetail?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val vaultState by vault.state.collectAsState()
    val valid = (vaultState as? VaultState.Unlocked)?.generation == generation
    var state by remember(vault, generation, projection.id) { mutableStateOf<TextPreviewState>(TextPreviewState.Loading) }
    DisposableEffect(vault, generation, projection.id) { onDispose { state = TextPreviewState.Loading } }
    LaunchedEffect(vault, generation, projection.id, valid) {
        state = TextPreviewState.Loading
        if (!valid) return@LaunchedEffect
        val loaded = try {
            withContext(Dispatchers.Default) {
                val prefix = readDocumentPrefix(vault, generation, projection, DOCUMENT_TEXT_PREVIEW_MAX_BYTES)
                try {
                    decodeDocumentText(prefix.bytes, prefix.truncated)?.let { TextPreviewState.Ready(it) }
                        ?: TextPreviewState.Unavailable("This file is not plain UTF-8 text. Its original can still be exported.")
                } finally { prefix.bytes.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: ChurFailure) {
            TextPreviewState.Unavailable("This text preview could not be verified or opened. Try an integrity check before exporting.")
        } catch (_: Exception) {
            TextPreviewState.Unavailable("This text preview is unavailable. Its original can still be exported.")
        }
        state = if ((vault.state.value as? VaultState.Unlocked)?.generation == generation) loaded else TextPreviewState.Loading
    }
    if (!valid) return
    when (val current = state) {
        TextPreviewState.Loading -> DocumentFallback(projection, detail, "Opening text preview…", modifier, contentPadding)
        is TextPreviewState.Unavailable -> DocumentFallback(projection, detail, current.message, modifier, contentPadding)
        is TextPreviewState.Ready -> DocumentTextContent(projection, detail, current.preview.text,
            current.preview.truncated, modifier, contentPadding)
    }
}


/** Pure rendering also lets public synthetic fixtures exercise the real text UI. */
@Composable
fun DocumentTextContent(
    projection: ObjectProjection,
    detail: ObjectDetail?,
    text: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val paragraphs = remember(text) { documentTextChunks(text) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(contentPadding), contentPadding = PaddingValues(ChurSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(ChurSpacing.one)) {
        item { DocumentHeader(projection, detail) }
        if (truncated) item {
            Text("Preview limited to the first 1 MiB. Export to read the full file.",
                color = ViewerColors.content, style = MaterialTheme.typography.bodySmall)
        }
        itemsIndexed(paragraphs) { _, paragraph ->
            Text(paragraph, color = ViewerColors.content, style = MaterialTheme.typography.bodyMedium)
        }
        if (paragraphs.isEmpty()) item { Text("Empty text file", color = ViewerColors.content) }
    }
}
