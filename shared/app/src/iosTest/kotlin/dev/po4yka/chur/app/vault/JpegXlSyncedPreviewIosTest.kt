@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared peer transport stores the original only. This exercises its iOS presentation path. */
class JpegXlSyncedPreviewIosTest {
    @Test
    fun original_only_image_supports_gallery_mosaic_viewer_and_session_cache_invalidation(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "chur-jpeg-xl-original-only-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val fixtures = assertNotNull(platform.posix.getenv("CHUR_JPEG_XL_FIXTURES")?.toKString())
        val fixture = assertNotNull(NSData.dataWithContentsOfFile("$fixtures/large.jxl"))
        val source = "$root/source.jxl"
        assertTrue(fixture.writeToFile(source, atomically = true))
        val password = "jpeg xl original only iOS password".encodeToByteArray()
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        val cache = ThumbnailCache()
        try {
            vault.start()
            vault.create(password, false)
            val descriptor = open(source, O_RDONLY)
            assertTrue(descriptor >= 0)
            val objectId = try {
                val operation = vault.beginImport(descriptor, ImportRequest(
                    "image/jxl", true, MEDIA_CLASS_IMAGE, width = 2050, height = 1025,
                    knownLength = fixture.length.toLong(), originalFilename = "large.jxl",
                ))
                try {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                    assertEquals(0, progress.status)
                    assertNotNull(progress.importedObjectId())
                } finally { vault.closeOperation(operation) }
            } finally { close(descriptor) }
            val projection = vault.page(ObjectQuery()).objects.single { it.objectId.contentEquals(objectId) }
            assertFalse(projection.thumbnailReady)
            assertTrue(runCatching { vault.readDerived(objectId, StreamKind.THUMBNAIL) }.isFailure)
            val generation = (vault.state.value as VaultState.Unlocked).generation
            val thumbnail = assertNotNull(cache.loadOriginal(vault, generation, projection))
            assertEquals(320 to 160, thumbnail.width to thumbnail.height)
            val mosaic = assertNotNull(cache.loadOriginal(vault, generation, projection, ThumbnailRendition.Mosaic))
            assertEquals(16 to 16, mosaic.width to mosaic.height)
            val still = assertNotNull(viewerStill(vault, cache, generation, projection))
            assertEquals(2048 to 1024, still.width to still.height)
            cache.invalidate()
            vault.lock(LockReason.USER)
            assertNull(cache.loadOriginal(vault, generation, projection))
            vault.unlock(password)
            val nextGeneration = (vault.state.value as VaultState.Unlocked).generation
            assertNotEquals(generation, nextGeneration)
            assertNull(cache.loadOriginal(vault, generation, projection))
            val reloaded = assertNotNull(cache.loadOriginal(vault, nextGeneration, projection))
            assertNotSame(thumbnail, reloaded)
            assertEquals(320 to 160, reloaded.width to reloaded.height)
        } finally {
            cache.clear()
            vault.shutdown()
            password.fill(0)
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }
}
