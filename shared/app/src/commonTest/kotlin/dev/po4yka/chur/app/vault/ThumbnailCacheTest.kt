package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.po4yka.chur.ffi.StreamKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cache logic only. Real raster checks run on iOS and Android devices. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailCacheTest {
    @Test
    fun a_synced_original_raster_has_its_own_stream_and_concealment_keys() = runTest {
        val cache = ThumbnailCache(4, StandardTestDispatcher(testScheduler)) { _, _ -> CacheImage() }
        var reads = 0
        suspend fun original(rendition: ThumbnailRendition) = cache.loadRendered(1, "synced", StreamKind.ORIGINAL, rendition) {
            reads += 1
            CacheImage()
        }
        val plain = original(ThumbnailRendition.Plain)
        assertSame(plain, original(ThumbnailRendition.Plain))
        val concealed = original(ThumbnailRendition.Mosaic)
        assertNotSame(plain, concealed)
        assertSame(concealed, original(ThumbnailRendition.Mosaic))
        assertEquals(2, reads)
        cache.invalidate()
        assertNotSame(plain, original(ThumbnailRendition.Plain))
    }

    @Test
    fun locking_during_synced_original_decode_cannot_publish_or_cache_its_raster() = runTest {
        val cache = ThumbnailCache(4, StandardTestDispatcher(testScheduler)) { _, _ -> CacheImage() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loading = async {
            cache.loadRendered(1, "synced", StreamKind.ORIGINAL, ThumbnailRendition.Plain) {
                entered.complete(Unit)
                release.await()
                CacheImage()
            }
        }
        entered.await()
        cache.invalidate()
        release.complete(Unit)
        assertNull(loading.await())
        val next = CacheImage()
        assertSame(next, cache.loadRendered(2, "synced", StreamKind.ORIGINAL, ThumbnailRendition.Plain) { next })
    }

    @Test
    fun rendition_generation_and_stream_are_part_of_the_same_bounded_cache() = runTest {
        var renders = 0
        var reads = 0
        val cache = ThumbnailCache(3, StandardTestDispatcher(testScheduler)) { _, _ ->
            renders += 1
            CacheImage()
        }
        suspend fun load(
            generation: Long = 1,
            kind: StreamKind = StreamKind.THUMBNAIL,
            rendition: ThumbnailRendition = ThumbnailRendition.Plain,
        ) = cache.load(generation, "photo", kind, rendition) {
            reads += 1
            byteArrayOf(1)
        }
        val plain = load()
        assertSame(plain, load())
        val mosaic = load(rendition = ThumbnailRendition.Mosaic)
        assertSame(mosaic, load(rendition = ThumbnailRendition.Mosaic))
        load(kind = StreamKind.VIDEO_POSTER)
        assertEquals(3, renders)
        load(generation = 2)
        load()
        assertEquals(5, renders, "both renditions share the capacity")
        assertEquals(renders, reads, "a redraw performs no read or raster on a hit")
    }

    @Test
    fun clear_during_read_discards_the_bytes_without_decoding_or_repopulating() = runTest {
        var renders = 0
        val cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler)) { _, _ ->
            renders += 1
            CacheImage()
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bytes = byteArrayOf(1, 2, 3)
        val old = async {
            cache.load(1, "photo") {
                entered.complete(Unit)
                release.await()
                bytes
            }
        }
        entered.await()
        cache.clear()
        release.complete(Unit)
        assertNull(old.await())
        assertEquals(0, renders)
        assertTrue(bytes.all { it == 0.toByte() })
        cache.load(1, "photo") { byteArrayOf(4) }
        assertEquals(1, renders)
    }

    @Test
    fun clear_during_render_discards_the_result_before_publication() = runTest {
        var clearDuringRender = true
        var renders = 0
        lateinit var cache: ThumbnailCache
        cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler)) { _, _ ->
            renders += 1
            if (clearDuringRender) {
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { cache.clear() }
            }
            CacheImage()
        }
        assertNull(cache.load(1, "photo") { byteArrayOf(1) })
        clearDuringRender = false
        cache.load(1, "photo") { byteArrayOf(2) }
        assertEquals(2, renders)
    }

    @Test
    fun synchronous_invalidation_rejects_old_cache_hits_before_suspending_cleanup() = runTest {
        var renders = 0
        val cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler)) { _, _ ->
            renders += 1
            CacheImage()
        }
        val previous = cache.load(1, "photo") { byteArrayOf(1) }
        cache.invalidate()
        val current = cache.load(1, "photo") { byteArrayOf(2) }
        assertNotSame(previous, current)
        assertEquals(2, renders)
        cache.clear()
        assertNotSame(current, cache.load(1, "photo") { byteArrayOf(3) })
        assertEquals(3, renders)
    }

    @Test
    fun synchronous_invalidation_during_read_discards_bytes_before_any_decode() = runTest {
        var renders = 0
        val cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler)) { _, _ ->
            renders += 1
            CacheImage()
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bytes = byteArrayOf(1, 2, 3)
        val old = async {
            cache.load(1, "photo") { entered.complete(Unit); release.await(); bytes }
        }
        entered.await()
        cache.invalidate()
        release.complete(Unit)
        assertNull(old.await())
        assertEquals(0, renders)
        assertTrue(bytes.all { it == 0.toByte() })
        cache.clear()
    }

    @Test
    fun synchronous_invalidation_during_render_rejects_the_completed_image() = runTest {
        lateinit var cache: ThumbnailCache
        cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler)) { _, _ ->
            cache.invalidate()
            CacheImage()
        }
        assertNull(cache.load(1, "photo") { byteArrayOf(1) })
        cache.clear()
    }

    @Test
    fun cancellation_during_read_cannot_publish_and_a_new_request_can_retry() = runTest {
        var renders = 0
        val cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler)) { _, _ ->
            renders += 1
            CacheImage()
        }
        val entered = CompletableDeferred<Unit>()
        val waiting = async {
            cache.load(1, "photo") {
                entered.complete(Unit)
                CompletableDeferred<ByteArray>().await()
            }
        }
        entered.await()
        waiting.cancel()
        runCurrent()
        assertTrue(waiting.isCancelled)
        assertEquals(0, renders)
        cache.load(1, "photo") { byteArrayOf(1) }
        assertEquals(1, renders)
    }

    @Test
    fun a_failed_mosaic_returns_no_plain_image_and_does_not_cache_the_failure() = runTest {
        var reads = 0
        var renders = 0
        val failedBytes = byteArrayOf(1, 2)
        val retryBytes = byteArrayOf(3, 4)
        val image = CacheImage()
        val cache = ThumbnailCache(256, StandardTestDispatcher(testScheduler)) { _, rendition ->
            assertEquals(ThumbnailRendition.Mosaic, rendition, "a failed mosaic must not fall back to Plain")
            renders += 1
            if (renders == 1) error("raster failed")
            image
        }
        suspend fun load() = cache.load(1, "photo", rendition = ThumbnailRendition.Mosaic) {
            reads += 1
            if (reads == 1) failedBytes else retryBytes
        }

        assertNull(load())
        assertEquals(1, renders)
        assertEquals(1, reads)
        assertTrue(failedBytes.all { it == 0.toByte() })
        assertSame(image, load(), "the same key retries after a render failure")
        assertEquals(2, renders)
        assertEquals(2, reads)
        assertTrue(retryBytes.all { it == 0.toByte() })
        assertSame(image, load(), "the successful retry is cached")
        assertEquals(2, renders)
        assertEquals(2, reads)
    }

    /** No platform bitmap calls: this is a token for cache identity checks. */
    private class CacheImage : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace get() = ColorSpaces.Srgb
        override val hasAlpha = false
        override val config get() = ImageBitmapConfig.Argb8888
        override fun prepareToDraw() = Unit
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = error("This test checks cache identity, not pixels")
    }
}
