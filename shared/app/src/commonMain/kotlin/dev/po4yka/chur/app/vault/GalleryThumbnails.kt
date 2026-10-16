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

/** Only photos and video posters have image details to conceal. */
fun thumbnailRendition(mediaKind: Int, concealed: Boolean): ThumbnailRendition =
    if (concealed && (mediaKind == MEDIA_CLASS_IMAGE || mediaKind == MEDIA_CLASS_VIDEO))
        ThumbnailRendition.Mosaic else ThumbnailRendition.Plain

/**
 * A presentation map belongs to one session and rendition. A switch replaces
 * the map during composition, before a frame can show the previous rendition.
 * The viewer requests its own Plain map only while it is open.
 */
@Composable
fun rememberGalleryThumbnails(
    repository: VaultRepository,
    cache: ThumbnailCache,
    generation: Long,
    objects: List<ObjectProjection>,
    concealed: Boolean,
    enabled: Boolean = true,
): Map<String, ImageBitmap> {
    val images = remember(repository, cache, generation, concealed, enabled) {
        mutableStateMapOf<String, ImageBitmap>()
    }
    DisposableEffect(repository, cache, generation, concealed, enabled) { onDispose { images.clear() } }
    LaunchedEffect(repository, cache, generation, concealed, enabled, objects) {
        if (!enabled) return@LaunchedEffect
        val visible = objects.map { it.id }.toSet()
        images.keys.toList().filterNot { it in visible }.forEach { images.remove(it) }
        for (projection in objects.filter { it.thumbnailReady }) {
            if (images.containsKey(projection.id)) continue
            val image = cache.load(
                repository = repository,
                generation = generation,
                objectId = projection.objectId,
                id = projection.id,
                rendition = thumbnailRendition(projection.mediaKind, concealed),
            )
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
