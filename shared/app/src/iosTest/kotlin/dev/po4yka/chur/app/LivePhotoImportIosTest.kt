@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.*
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.*
import dev.po4yka.chur.imports.*
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.*
import platform.posix.*
import kotlin.test.*

/** Real iOS ImageIO/AVPlayer, SQLCipher, encryption, restart and resource export. */
class LivePhotoImportIosTest {
    @Test
    fun full_bundle_survives_restart_and_plays_its_encrypted_movie_with_audio(): Unit = runBlocking {
        val root = temporary(); val path = "$root/picked.churlive"
        val original = fixture("native.churlive"); write(path, original)
        val password = "Live-photo-iOS-integration".encodeToByteArray()
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        var reopened: VaultRepository? = null
        var loader: ChurResourceLoader? = null; var player: AVPlayer? = null
        try {
            vault.start(); vault.create(password, false)
            val codec = IosMediaCodec()
            val media = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Live Photo.churlive"))
            val table = assertNotNull(media.livePhoto)
            val probe = assertNotNull(codec.probe(media))
            assertEquals(64 to 64, probe.width to probe.height)
            assertEquals(LivePhotoBundle.CONTENT_TYPE, probe.contentType)
            val imported = assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(vault, media))
            assertTrue(imported.derivatives >= 1)
            val id = imported.objectId
            assertEquals(0, unlink(path))
            assertContentEquals(original, vault.readRange(id, 0, original.size))
            val thumbnail = vault.readDerived(id, StreamKind.THUMBNAIL)
            withChurBuffer(thumbnail.size) { buffer ->
                buffer.copyIn(thumbnail)
                val image = assertNotNull(decodeForDisplay(buffer, 320))
                assertEquals(64 to 64, image.width to image.height)
            }
            table.resources.forEach { resource ->
                val expected = fixture(if (resource.type == 1) "still.jpg" else "motion.mov")
                assertContentEquals(expected, vault.readRange(id, resource.offset, resource.byteLength.toInt()))
                val digest = livePhotoDigest().apply { update(expected) }.finish()
                assertContentEquals(resource.sha256, digest); digest.fill(0)
            }
            vault.shutdown()
            val next = VaultRepository("$root/vault", { 1_700_000_000_001L }).also { reopened = it }
            next.start(); next.unlock(password)
            assertContentEquals(original, next.readRange(id, 0, original.size))
            val restoredTable = assertNotNull(readVaultLivePhoto(next, id))
            assertEquals(table.resources.map { it.originalFilename }, restoredTable.resources.map { it.originalFilename })
            val source = assertNotNull(livePhotoPlaybackFor(next, id))
            assertEquals(table.displayVideo.offset, source.offset)
            assertEquals(table.displayVideo.byteLength, source.length)
            val nativeLoader = ChurResourceLoader(source).also { loader = it }
            val item = privatePlayerItem(nativeLoader)
            val nativePlayer = privatePlayer(item).also { player = it; it.muted = true }
            waitFor { item.status == AVPlayerItemStatusReadyToPlay || item.error != null }
            assertNull(item.error); assertEquals(AVPlayerItemStatusReadyToPlay, item.status)
            assertEquals(1, item.asset.tracksWithMediaType(AVMediaTypeAudio).size)
            nativePlayer.play(); waitFor { CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.10 }
            assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.10)
            nativePlayer.pause(); nativePlayer.seekToTime(CMTimeMakeWithSeconds(0.5, 600))
            waitFor { CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.45 }
            assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.45)
            assertFalse(nativePlayer.allowsExternalPlayback)
            nativePlayer.pause(); nativePlayer.replaceCurrentItemWithPlayerItem(null); nativeLoader.release()
            player = null; loader = null
            val outputs = NativeDirectory("$root/outputs")
            try {
                assertEquals(2, restoreLivePhotoResources(next, id, outputs))
                assertEquals(3, outputs.files.size)
                assertContentEquals(fixture("still.jpg"), read(outputs.files[0].path))
                assertContentEquals(fixture("motion.mov"), read(outputs.files[1].path))
                assertContentEquals(original, read(outputs.files[2].path))
            } finally { outputs.close() }
        } finally {
            player?.pause(); player?.replaceCurrentItemWithPlayerItem(null); loader?.release()
            runCatching { vault.shutdown() }; runCatching { reopened?.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun corrupt_hash_and_native_pre_cancel_never_create_an_object(): Unit = runBlocking {
        val root = temporary(); val path = "$root/source.churlive"
        val original = fixture("native.churlive"); write(path, original)
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        try {
            val input = open(path, O_RDONLY); assertTrue(input >= 0)
            try {
                assertNotNull(readLivePhotoBundle(input, 1, validatePayloads = true))
                assertFailsWith<CancellationException> { readLivePhotoBundle(input, null, cancelRequested = { true }) }
            } finally { close(input) }
            val damaged = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            write(path, damaged)
            val bad = open(path, O_RDONLY)
            try { assertFailsWith<LivePhotoBundleException> { readLivePhotoBundle(bad, damaged.size.toLong()) } }
            finally { close(bad) }
            vault.start(); vault.create("Live-photo-failures".encodeToByteArray(), false)
            val codec = IosMediaCodec()
            val rejected = assertIs<MediaImporter.Outcome.Refused>(MediaImporter(codec).import(vault,
                assertNotNull(codec.open(NSURL.fileURLWithPath(path), "broken.churlive"))))
            assertEquals(ChurStatus.OBJECT_CORRUPT, rejected.status)
            write(path, original)
            val cancellation = assertIs<MediaImporter.Outcome.Refused>(MediaImporter(codec).import(vault,
                assertNotNull(codec.open(NSURL.fileURLWithPath(path), "cancelled.churlive")), cancelRequested = { true }))
            assertEquals(ChurStatus.CANCELLED, cancellation.status)
            assertEquals(0L, vault.page(ObjectQuery()).totalCount)
        } finally { runCatching { vault.shutdown() }; NSFileManager.defaultManager.removeItemAtPath(root, null) }
    }

    @Test
    fun lock_after_first_published_resource_removes_export_plaintext(): Unit = runBlocking {
        val root = temporary(); val path = "$root/source.churlive"
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        write(path, fixture("native.churlive"))
        try {
            vault.start(); vault.create("Live-photo-lock-window".encodeToByteArray(), false)
            val codec = IosMediaCodec()
            val imported = assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(vault,
                assertNotNull(codec.open(NSURL.fileURLWithPath(path), "locked.churlive"))))
            val directory = NativeDirectory("$root/outputs")
            write("${directory.root}/unrelated.txt", "keep".encodeToByteArray())
            try {
                val failure = assertFailsWith<ChurFailure> {
                    restoreLivePhotoResources(vault, imported.objectId, directory, onProgress = { completed, _ ->
                        assertEquals(1, completed); assertTrue(directory.files.single().published)
                        runBlocking { vault.lock(LockReason.USER) }
                    })
                }
                assertTrue(failure.status == ChurStatus.SESSION_EXPIRED || failure.status == ChurStatus.VAULT_LOCKED)
                assertTrue(directory.files.all { it.closed && !NSFileManager.defaultManager.fileExistsAtPath(it.path) })
                assertEquals(listOf("unrelated.txt"), NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory.root, null))
                assertContentEquals("keep".encodeToByteArray(), read("${directory.root}/unrelated.txt"))
            } finally { directory.close() }
        } finally { runCatching { vault.shutdown() }; NSFileManager.defaultManager.removeItemAtPath(root, null) }
    }

    private class NativeDirectory(val root: String) : ExportDirectory {
        val files = mutableListOf<NativeOutput>()
        init { check(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)) }
        override fun createDirectory(displayName: String): ExportDirectory? = null
        override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination {
            check('/' !in displayName && '\\' !in displayName)
            return NativeOutput("$root/.partial-${files.size}", "$root/$displayName", plaintextSize).also(files::add)
        }
        override fun close() { files.forEach { it.close() } }
    }
    private class NativeOutput(var path: String, val target: String, val length: Long) : ExportSink.Destination {
        override val descriptor = open(path, O_WRONLY or O_CREAT or O_EXCL, 384).also { check(it >= 0) }
        var published = false; var closed = false
        override fun publish() { check(publishChecked()) }
        override fun publishChecked(): Boolean {
            check(!closed && lseek(descriptor, 0, SEEK_END) == length); check(fsync(descriptor) == 0)
            if (rename(path, target) != 0) return false
            path = target; published = true; return true
        }
        override fun discard() { unlink(path); published = false }
        override fun close() { if (!closed) { platform.posix.close(descriptor); closed = true }; if (!published) unlink(path) }
    }
    private fun fixture(name: String): ByteArray = read("${assertNotNull(getenv("CHUR_LIVE_PHOTO_FIXTURES")?.toKString(), "CHUR_LIVE_PHOTO_FIXTURES is required")}/$name")
    private fun temporary(): String = (NSTemporaryDirectory() + "chur-live-photo-" + NSUUID().UUIDString).also {
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null))
    }
    private fun read(path: String): ByteArray = assertNotNull(NSData.dataWithContentsOfFile(path)).let {
        if (it.length == 0uL) ByteArray(0) else assertNotNull(it.bytes).readBytes(it.length.toInt())
    }
    private fun write(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, atomically = true))
    }
    private fun waitFor(condition: () -> Boolean) {
        repeat(200) { if (!condition()) NSRunLoop.currentRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.02)) }
    }
}
