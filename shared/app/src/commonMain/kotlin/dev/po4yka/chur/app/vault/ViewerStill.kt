package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.imports.LivePhotoBundle
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.ffi.withChurBuffer
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The still request ends even when no source can be decoded. */
enum class ViewerStillState { Loading, Ready, Unavailable }

/**
 * The streams the viewer tries for one object's still, sharpest first.
 *
 * One rule for both hosts. A video's still is its poster frame, which
 * `MEDIA_PIPELINE.md` §6 generates for every video. A photograph's is its
 * screen preview, and `requiredDerivatives` generates that one only for a
 * photograph larger than 2048 px: a smaller one is its own preview, so the
 * viewer decodes the original, which §8 permits for detailed viewing. The grid
 * preview and the thumbnail follow for an original the platform decoder
 * refuses, and every list ends with the thumbnail, which every picture has.
 *
 * The original is read whole into one native buffer, so it is tried only when
 * the catalog says it is inside the screen-preview edge and
 * [ORIGINAL_BYTES_MAX]. An image without its previews, or one the catalog has
 * no size for, gets the derivatives only. So does a quarantined one: `CATALOG_SCHEMA_V1.md` §5.1
 * says its container must not be presented or silently retried, as the
 * routes already do for playback. The test is for that value alone, because
 * [PresentedState.of] maps a trashed row to `CORRUPT` and the trash viewer
 * still shows the original.
 */
internal fun stillSources(projection: ObjectProjection, highResolution: Boolean = false): List<StreamKind> = when (projection.mediaKind) {
    MEDIA_CLASS_VIDEO -> listOf(StreamKind.VIDEO_POSTER, StreamKind.THUMBNAIL)
    // Never ask an image decoder to open an audio original. Existing recordings
    // without stored artwork simply exhaust these optional derivatives.
    MEDIA_CLASS_AUDIO -> listOf(StreamKind.SCREEN_PREVIEW, StreamKind.GRID_PREVIEW, StreamKind.THUMBNAIL)
    MEDIA_CLASS_IMAGE -> buildList {
        if (!highResolution) add(StreamKind.SCREEN_PREVIEW)
        if (originalImageCandidate(projection) &&
            (highResolution || maxOf(projection.width, projection.height) <= ORIGINAL_EDGE_MAX)) {
            add(StreamKind.ORIGINAL)
        }
        if (highResolution) add(StreamKind.SCREEN_PREVIEW)
        add(StreamKind.GRID_PREVIEW)
        add(StreamKind.THUMBNAIL)
    }
    else -> listOf(StreamKind.THUMBNAIL)
}

/**
 * The sharpest still [stillSources] finds for [projection], or `null` when it
 * finds none.
 *
 * Derivatives come through [cache]. The decoded original never goes there: the
 * cache bounds how many images it holds, not their size, and one 2048 px image
 * is about 16 MB. The caller keeps it in the viewer's own state, which a lock
 * disposes with the viewer, so `PLAINTEXT_LIFECYCLE.md` §4's session scope
 * holds without a second cache to clear.
 */
suspend fun viewerStill(
    vault: VaultRepository,
    cache: ThumbnailCache,
    generation: Long,
    projection: ObjectProjection,
    highResolution: Boolean = false,
): ImageBitmap? {
    val sources = stillSources(projection, highResolution)
    for (kind in sources) {
        val image = if (kind == StreamKind.ORIGINAL) {
            decodeOriginal(vault, projection.objectId, if (highResolution) ZOOM_EDGE_MAX else ORIGINAL_EDGE_MAX)
        } else {
            cache.load(vault, generation, projection.objectId, projection.id, kind)
        }
        if (image != null) return image
    }
    // Peer sync transfers originals only. Decode a bounded still when this host
    // has no derivatives, including images larger than the preview edge.
    if (StreamKind.ORIGINAL !in sources && originalImageCandidate(projection)) {
        return decodeOriginal(vault, projection.objectId, if (highResolution) ZOOM_EDGE_MAX else ORIGINAL_EDGE_MAX)
    }
    return null
}

internal fun originalImageCandidate(projection: ObjectProjection): Boolean =
    projection.mediaKind == MEDIA_CLASS_IMAGE &&
        projection.width in 1..dev.po4yka.chur.imports.MediaBounds.IMAGE_EDGE_MAX &&
        projection.height in 1..dev.po4yka.chur.imports.MediaBounds.IMAGE_EDGE_MAX &&
        projection.width.toLong() * projection.height <= dev.po4yka.chur.imports.MediaBounds.IMAGE_AREA_MAX &&
        projection.plaintextSize in 1..ORIGINAL_BYTES_MAX &&
        PresentedState.of(projection) != PresentedState.QUARANTINED

/**
 * Decodes the original of a small photograph, or `null` when it cannot.
 *
 * `derived::read` refuses the original, so it comes through a reader lease, as
 * a player's ranges do. The lease is taken under the repository's mutex and
 * read without it, so a read of many megabytes does not stall every catalog
 * query behind it. `FFI_CONTRACT.md` §6.1 serves an incomplete object only for
 * verification, and a decoder given a truncated file may draw part of it, so
 * such an object is left to its derivatives.
 *
 * Both platform decoders need the whole encoded file in memory. `read_at`
 * writes it into one native buffer the size of the file, and the decoder reads
 * that buffer in place, so the original never becomes a Kotlin `ByteArray` or a
 * Swift `Data`, `FFI_CONTRACT.md` §6 and `IOS.md` §31. The limits below keep
 * the buffer to a picture no larger than a screen preview. [withChurBuffer]
 * clears it after the decode; the decoder's own buffers are not cleared,
 * `PLAINTEXT_LIFECYCLE.md` §9. Nothing reaches the disk, `MEDIA_PIPELINE.md` §8.
 */
internal suspend fun decodeOriginal(vault: VaultRepository, objectId: ByteArray, maxEdgePx: Int): ImageBitmap? =
    withContext(Dispatchers.Default) {
        val reader = try {
            vault.leaseReader(objectId)
        } catch (_: ChurFailure) {
            return@withContext null
        }
        try {
            val info = vault.readerContentInfo(reader)
            if (!info.complete) return@withContext null
            var offset = 0L
            var length = info.plaintextSize
            if (info.contentType == LivePhotoBundle.CONTENT_TYPE) {
                val bundle = readVaultLivePhoto(vault, objectId) ?: return@withContext null
                offset = bundle.displayPhoto.offset
                length = bundle.displayPhoto.byteLength
            }
            if (length !in 1..ORIGINAL_BYTES_MAX) return@withContext null
            withChurBuffer(length.toInt()) { original ->
                vault.readLeasedInto(reader, original, offset)
                decodeForDisplay(original, maxEdgePx)
            }
        } catch (_: dev.po4yka.chur.imports.LivePhotoBundleException) {
            null
        } catch (_: ChurFailure) {
            // A lock during the read closes the lease, which is SESSION_EXPIRED.
            null
        } finally {
            vault.releaseReader(reader)
        }
    }

/** The screen-preview long edge of `MEDIA_PIPELINE.md` §12. */
private const val ORIGINAL_EDGE_MAX = 2_048

/** A zoom reads at most 32 MiB encoded and decodes at most 64 MiB of RGBA pixels. */
private const val ZOOM_EDGE_MAX = 4_096

/**
 * The largest original the viewer reads whole, 32 MiB.
 *
 * A 2048 px square decodes to 16 MiB of pixels, so this admits every encoding
 * of a picture that size with room to spare, and bounds the read when the
 * catalog's size is wrong.
 */
private const val ORIGINAL_BYTES_MAX = 32L * 1024 * 1024
