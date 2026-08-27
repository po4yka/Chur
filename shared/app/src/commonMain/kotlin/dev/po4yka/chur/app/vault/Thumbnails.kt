package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
class ThumbnailCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private data class Key(val generation: Long, val id: String, val kind: StreamKind)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<Key, ImageBitmap>()

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
    ): ImageBitmap? {
        val key = Key(generation, id, kind)
        mutex.withLock { entries[key] }?.let { return it }
        val bytes = try {
            withContext(Dispatchers.Default) { repository.readDerived(objectId, kind) }
        } catch (_: ChurFailure) {
            return null
        }
        val image = withContext(Dispatchers.Default) { decodeThumbnail(bytes) } ?: return null
        mutex.withLock {
            entries[key] = image
            while (entries.size > capacity) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
        return image
    }

    /**
     * Clears the cache, which lock does, §8 step 7.
     *
     * The generation in the key already makes a stale entry unreachable after a
     * new session opens; this is what makes it unreachable while the process is
     * still locked, which is the case §8 is about.
     */
    suspend fun clear() {
        mutex.withLock { entries.clear() }
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
