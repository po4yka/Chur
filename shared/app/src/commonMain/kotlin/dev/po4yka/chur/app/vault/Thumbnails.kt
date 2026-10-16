package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ThumbnailRendition { Plain, Mosaic }

/**
 * The decoded-image cache of `docs/security/PLAINTEXT_LIFECYCLE.md` §4.
 *
 * §4 requires a separate private image loader, cache keys scoped to the session
 * generation, and the cache cleared on lock. All three are here and none is
 * optional: a cache that survived a lock would keep decoded private pixels in
 * a locked process, which is the exact thing §8 step 7 clears.
 *
 * The cache is bounded. §12 of the media pipeline bounds one derivative and
 * this bounds how many are held, so a library of a million objects scrolls
 * without the cache growing to a million thumbnails.
 *
 * Only the decode is per platform, so only the decode is an `expect`. The
 * eviction, the key, and the lock rule are the same on both and live here,
 * where one reading of them covers both hosts.
 */
class ThumbnailCache internal constructor(
    private val capacity: Int,
    private val dispatcher: CoroutineDispatcher,
    private val maxBytes: Long = DEFAULT_BYTES,
    private val render: (ByteArray, ThumbnailRendition) -> ImageBitmap?,
) {
    constructor(capacity: Int = DEFAULT_CAPACITY) : this(capacity, Dispatchers.Default, DEFAULT_BYTES, ::renderThumbnail)

    init {
        require(capacity > 0) { "Thumbnail cache capacity must be positive" }
        require(maxBytes > 0) { "Thumbnail cache byte budget must be positive" }
    }

    private data class Key(
        val generation: Long,
        val id: String,
        val kind: StreamKind,
        val rendition: ThumbnailRendition,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, ImageBitmap>()
    private var epoch = 0L
    private var decodedBytes = 0L

    /**
     * The decoded derivative, loading it when the cache does not hold it.
     *
     * A missing derivative is `null` rather than an exception: an object whose
     * thumbnail has not been generated yet is ordinary, and §11.1 of
     * `DESIGN.md` has the grid show a placeholder for it.
     */
    suspend fun load(
        repository: VaultRepository,
        generation: Long,
        objectId: ByteArray,
        id: String,
        kind: StreamKind = StreamKind.THUMBNAIL,
        rendition: ThumbnailRendition = ThumbnailRendition.Plain,
    ): ImageBitmap? = load(generation, id, kind, rendition) { repository.readDerived(objectId, kind) }

    /** Internal source seam also permits deterministic checks of lock races. */
    internal suspend fun load(
        generation: Long,
        id: String,
        kind: StreamKind = StreamKind.THUMBNAIL,
        rendition: ThumbnailRendition = ThumbnailRendition.Plain,
        read: suspend () -> ByteArray,
    ): ImageBitmap? {
        currentCoroutineContext().ensureActive()
        val key = Key(generation, id, kind, rendition)
        val startedAt = mutex.withLock {
            entries[key]?.let { return it }
            epoch
        }
        val image = try {
            withContext(dispatcher) {
                val bytes = read()
                try {
                    // A lock during the read needs no decode or raster allocation.
                    if (mutex.withLock { epoch != startedAt }) return@withContext null
                    currentCoroutineContext().ensureActive()
                    render(bytes, rendition)
                } finally {
                    // Clear on this dispatcher, including cancellation at its return boundary.
                    bytes.fill(0)
                }
            }
        } catch (_: ChurFailure) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed mosaic must never reveal the plain derivative.
            null
        } ?: return null
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (epoch != startedAt) return null
            val cost = decodedSize(image)
            if (cost > maxBytes) return image
            entries.remove(key)?.let { decodedBytes -= decodedSize(it) }
            entries[key] = image
            decodedBytes += cost
            while (entries.size > capacity || decodedBytes > maxBytes) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)?.let { decodedBytes -= decodedSize(it) }
            }
            image
        }
    }

    /**
     * Clears the cache, which the controller's lock transitions do, §8 step 7.
     *
     * The generation in the key already makes a stale entry unreachable after a
     * new session opens; this is what makes it unreachable while the process is
     * still locked, which is the case §8 is about.
     */
    suspend fun clear() {
        mutex.withLock {
            epoch += 1
            entries.clear()
            decodedBytes = 0
        }
    }

    private companion object {
        /**
         * Enough for several screens of the widest grid.
         *
         * §11.1 clamps the grid to eight columns, so a tall expanded window
         * shows on the order of eighty tiles; this holds a few screens of
         * scrollback without holding a library.
         */
        const val DEFAULT_CAPACITY = 256
        const val DEFAULT_BYTES = 32L * 1024 * 1024
    }
}

private fun decodedSize(image: ImageBitmap): Long = image.width.toLong() * image.height *
    if (image.config == androidx.compose.ui.graphics.ImageBitmapConfig.F16) 8 else 4

private fun renderThumbnail(bytes: ByteArray, rendition: ThumbnailRendition): ImageBitmap? {
    val decoded = decodeThumbnail(bytes) ?: return null
    return when (rendition) {
        ThumbnailRendition.Plain -> decoded
        ThumbnailRendition.Mosaic -> thumbnailMosaic(decoded)
    }
}

/**
 * Decodes one derivative into an image.
 *
 * It returns `null` for bytes the platform decoder refuses rather than raising:
 * a derivative that will not decode is a defect in the object, and §11.1 shows
 * a placeholder for it exactly as it does for one that is not ready.
 */
internal expect fun decodeThumbnail(bytes: ByteArray): ImageBitmap?

/**
 * Decodes an original for the viewer, upright and scaled down to no more than
 * [maxEdgePx] on its long edge.
 *
 * [original] holds the whole encoded file and nothing else. The decoder reads
 * it in place and keeps no reference to it once it returns, because the caller
 * clears it next. Unlike [decodeThumbnail] it applies the EXIF orientation: §11
 * of the media pipeline normalizes the orientation of a derivative, and an
 * original has not been through that step. It returns `null` for a file the
 * platform decoder refuses or fails on, and the viewer then shows a derivative
 * instead.
 */
internal expect fun decodeForDisplay(original: ChurBuffer, maxEdgePx: Int): ImageBitmap?
