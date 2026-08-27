package dev.po4yka.chur.android

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
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
 */
class ThumbnailCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private data class Key(val generation: Long, val id: String, val kind: StreamKind)

    private val entries = ConcurrentHashMap<Key, ImageBitmap>()
    private val order = ArrayDeque<Key>()

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
        entries[key]?.let { return it }
        val bytes = try {
            withContext(Dispatchers.IO) { repository.readDerived(objectId, kind) }
        } catch (_: ChurFailure) {
            return null
        }
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } ?: return null
        val image = bitmap.asImageBitmap()
        put(key, image)
        return image
    }

    /**
     * Clears the cache, which lock does, §8 step 7.
     *
     * The generation in the key already makes a stale entry unreachable after a
     * new session opens; this is what makes it unreachable while the process is
     * still locked, which is the case §8 is about.
     */
    fun clear() {
        entries.clear()
        synchronized(order) { order.clear() }
    }

    private fun put(key: Key, image: ImageBitmap) {
        entries[key] = image
        synchronized(order) {
            order.addLast(key)
            while (order.size > capacity) {
                entries.remove(order.removeFirst())
            }
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
    }
}
