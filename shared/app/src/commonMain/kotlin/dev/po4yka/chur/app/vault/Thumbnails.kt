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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
 * without the cache growing to a million thumbnails. Both renditions share a
 * limit of 256 entries and an estimated 32 MiB of cache-resident bitmap bytes.
 * The estimate uses dimensions and pixel format. It does not include temporary
 * decode buffers or images held only by a presentation map. Eviction is FIFO:
 * a cache hit does not move an entry to the end of the insertion order.
 *
 * Only the decode is per platform, so only the decode is an `expect`. The
 * eviction, the key, and the lock rule are the same on both and live here,
 * where one reading of them covers both hosts.
 */
class ThumbnailCache internal constructor(
    private val capacity: Int,
    private val dispatcher: CoroutineDispatcher,
    private val maxDecodedBytes: Long = DEFAULT_BYTES,
    private val render: (ByteArray, ThumbnailRendition) -> ImageBitmap?,
) {
    constructor(capacity: Int = DEFAULT_CAPACITY) : this(capacity, Dispatchers.Default, DEFAULT_BYTES, ::renderThumbnail)

    init {
        require(capacity > 0) { "Thumbnail cache capacity must be positive" }
        require(maxDecodedBytes > 0) { "Thumbnail cache byte budget must be positive" }
    }

    private data class Key(
        val generation: Long,
        val id: String,
        val kind: StreamKind,
        val rendition: ThumbnailRendition,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, ImageBitmap>()
    private val epoch = MutableStateFlow(0L)
    /** Protected by mutex; entries from another epoch are never cache hits. */
    private var entriesEpoch = 0L
    private var cachedDecodedBytes = 0L

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
        val loadEpoch = epoch.value
        mutex.withLock {
            if (epoch.value != loadEpoch) return null
            if (entriesEpoch != loadEpoch) {
                entries.clear()
                cachedDecodedBytes = 0
                entriesEpoch = loadEpoch
            }
            entries[key]?.let { return it.takeIf { epoch.value == loadEpoch } }
        }
        val image = try {
            withContext(dispatcher) {
                val bytes = read()
                try {
                    // A lock during the read needs no decode or raster allocation.
                    if (epoch.value != loadEpoch) return@withContext null
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
            if (epoch.value != loadEpoch) return null
            val cost = estimatedDecodedBytes(image)
            if (cost > maxDecodedBytes) return image
            entries.remove(key)?.let { cachedDecodedBytes -= estimatedDecodedBytes(it) }
            entries[key] = image
            cachedDecodedBytes += cost
            while (entries.size > capacity || cachedDecodedBytes > maxDecodedBytes) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)?.let { cachedDecodedBytes -= estimatedDecodedBytes(it) }
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
    /** Invalidates cached hits and in-flight work on the caller's turn, without waiting for a mutex. */
    fun invalidate() {
        epoch.update { it + 1 }
    }

    suspend fun clear() {
        invalidate()
        mutex.withLock {
            entries.clear()
            cachedDecodedBytes = 0
            entriesEpoch = epoch.value
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

private fun estimatedDecodedBytes(image: ImageBitmap): Long = image.width.toLong() * image.height *
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
