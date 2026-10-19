package dev.po4yka.chur.app.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.notes.NoteAttachments
import kotlinx.coroutines.*

/** Only safe opaque managed references are resolved, never a path or network URL. */
internal fun noteAttachmentId(uri: String): String? = uri.removePrefix("attachment:")
    .takeIf { uri.startsWith("attachment:") && NoteAttachments.validId(it) }

internal fun attachmentMarkdownReference(name: String, id: String, image: Boolean): String {
    require(NoteAttachments.validId(id))
    val label = name.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
    return "${if (image) "!" else ""}[$label](attachment:$id)"
}

/** Shared per-preview jobs ensure repeated references decode once; route disposal cancels them. */
internal class NoteAttachmentImageCache(private val scope: CoroutineScope) {
    private val images = mutableMapOf<String, Deferred<ImageBitmap?>>()
    suspend fun image(id: String, read: suspend () -> ByteArray): ImageBitmap? {
        if (!NoteAttachments.validId(id)) return null
        val existing = images[id]
        if (existing != null) return existing.await()
        if (images.size >= NoteAttachments.MAX_ATTACHMENTS) return null
        // Called on the composition's main dispatcher; only decoding leaves that dispatcher.
        val task = scope.async {
            try { withContext(Dispatchers.Default) {
                val bytes = read()
                try {
                    require(bytes.size <= NoteAttachments.MAX_FILE_BYTES)
                    if (!NoteAttachments.validate("image", bytes).startsWith("image/")) null
                    else decodeNoteAttachmentImage(bytes)
                } finally { bytes.fill(0) }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        images[id] = task
        return task.await()
    }
    fun clear() { images.values.forEach { it.cancel() }; images.clear() }
}

@Composable
internal fun rememberNoteAttachmentImageCache(attachmentIds: Set<String>): NoteAttachmentImageCache {
    val scope = rememberCoroutineScope()
    val cache = remember(attachmentIds) { NoteAttachmentImageCache(scope) }
    DisposableEffect(cache) { onDispose { cache.clear() } }
    return cache
}

@Composable
internal fun NoteAttachmentImage(id: String, name: String, read: suspend () -> ByteArray,
    cache: NoteAttachmentImageCache = rememberNoteAttachmentImageCache(setOf(id))) {
    val loader by rememberUpdatedState(read)
    var image by remember(id, cache) { mutableStateOf<ImageBitmap?>(null) }
    var finished by remember(id, cache) { mutableStateOf(false) }
    LaunchedEffect(id, cache) {
        try { image = cache.image(id) { loader() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { image = null
        } finally { finished = true }
    }
    image?.let { Image(it, name, Modifier.fillMaxWidth().heightIn(max = 256.dp), contentScale = ContentScale.Fit) }
        ?: Text(if (finished) "Image preview unavailable: $name" else "Loading image…")
}

/** At most 384 pixels per edge; animated/active content is never executed. */
internal expect fun decodeNoteAttachmentImage(bytes: ByteArray): ImageBitmap?
