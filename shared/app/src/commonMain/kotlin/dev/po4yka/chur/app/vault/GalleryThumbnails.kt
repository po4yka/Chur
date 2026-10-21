package dev.po4yka.chur.app.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Photos, video posters, and embedded audio covers can reveal private image details. */
fun thumbnailRendition(mediaKind: Int, concealed: Boolean): ThumbnailRendition =
    if (concealed && (mediaKind == MEDIA_CLASS_IMAGE || mediaKind == MEDIA_CLASS_VIDEO || mediaKind == MEDIA_CLASS_AUDIO))
        ThumbnailRendition.Mosaic else ThumbnailRendition.Plain

/**
 * A presentation map belongs to one session and rendition. A switch replaces
 * the map during composition, before a frame can show the previous rendition.
 * The caller supplies only the objects visible in the gallery viewport.
 */
@Composable
fun rememberGalleryThumbnails(
    repository: VaultRepository,
    cache: ThumbnailCache,
    generation: Long,
    objects: List<ObjectProjection>,
    concealed: Boolean,
    enabled: Boolean = true,
): Map<String, ImageBitmap> = rememberThumbnailImages(
    repository, cache, generation, objects, concealed, enabled,
)

/**
 * Plain previews for the current viewer page and its immediate neighbours.
 * No preview is requested while [currentObjectId] is null. The viewer has a
 * separate map, so its Plain pixels cannot replace concealed gallery pixels.
 */
@Composable
fun rememberViewerThumbnails(
    repository: VaultRepository,
    cache: ThumbnailCache,
    generation: Long,
    objects: List<ObjectProjection>,
    currentObjectId: String?,
): Map<String, ImageBitmap> = rememberThumbnailImages(
    repository, cache, generation, viewerThumbnailWindow(objects, currentObjectId),
    concealed = false, enabled = currentObjectId != null,
)

/** The current page and at most one loaded neighbour on each side. */
internal fun viewerThumbnailWindow(
    objects: List<ObjectProjection>,
    currentObjectId: String?,
): List<ObjectProjection> {
    if (currentObjectId == null) return emptyList()
    val currentIndex = objects.indexOfFirst { it.id == currentObjectId }
    if (currentIndex < 0) return emptyList()
    return objects.subList(
        (currentIndex - 1).coerceAtLeast(0), (currentIndex + 2).coerceAtMost(objects.size),
    )
}

@Composable
private fun rememberThumbnailImages(
    repository: VaultRepository,
    cache: ThumbnailCache,
    generation: Long,
    objects: List<ObjectProjection>,
    concealed: Boolean,
    enabled: Boolean,
): Map<String, ImageBitmap> {
    val images = remember(repository, cache, generation, concealed, enabled) {
        mutableStateMapOf<String, ImageBitmap>()
    }
    DisposableEffect(repository, cache, generation, concealed, enabled) { onDispose { images.clear() } }
    LaunchedEffect(repository, cache, generation, concealed, enabled, objects) {
        if (!enabled) return@LaunchedEffect
        val visible = objects.map { it.id }.toSet()
        images.keys.toList().filterNot { it in visible }.forEach { images.remove(it) }
        for (projection in objects) {
            if (images.containsKey(projection.id)) continue
            val rendition = thumbnailRendition(projection.mediaKind, concealed)
            val image = (if (projection.thumbnailReady) cache.load(
                repository = repository,
                generation = generation,
                objectId = projection.objectId,
                id = projection.id,
                rendition = rendition,
            ) else null) ?: cache.loadOriginal(repository, generation, projection, rendition)
            currentCoroutineContext().ensureActive()
            if ((repository.state.value as? VaultState.Unlocked)?.generation != generation) {
                images.clear()
                return@LaunchedEffect
            }
            if (image != null) images[projection.id] = image
        }
    }
    return images
}
