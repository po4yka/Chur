@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.OperationProgress
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.IosMediaCodec
import dev.po4yka.chur.imports.livePhotoDigest
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.CoreVideo.*
import platform.Foundation.*
import platform.posix.*
import kotlin.test.*

/** Import, real native video decode, encrypted seeks and byte-exact native export. No decoder skips. */
class HevcMain10PlayerIosTest {
    @Test fun sdr10_hvc1_survives_import_play_seek_and_export() = checkRoundTrip("sdr10-hvc1.mp4")
    @Test fun sdr10_hev1_keeps_original_export_and_reports_unsupported_video() = checkRoundTrip("sdr10-hev1.mp4", playable = false)
    @Test fun hdr10_hvc1_survives_import_play_seek_and_export() = checkRoundTrip("hdr10-hvc1.mp4")
    @Test fun hdr10_hev1_keeps_original_export_and_reports_unsupported_video() = checkRoundTrip("hdr10-hev1.mp4", playable = false)
    @Test fun hlg_hvc1_survives_import_play_seek_and_export() = checkRoundTrip("hlg-hvc1.mp4")
    @Test fun hlg_hev1_keeps_original_export_and_reports_unsupported_video() = checkRoundTrip("hlg-hev1.mp4", playable = false)

    private fun checkRoundTrip(name: String, playable: Boolean = true): Unit = runBlocking {
        val fixtures = assertNotNull(getenv("CHUR_HEVC_MAIN10_FIXTURES")?.toKString(), "HEVC fixture directory is required")
        val original = read("$fixtures/$name")
        val root = NSTemporaryDirectory() + "chur-hevc-main10-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val path = "$root/source.mp4"
        val input = original.usePinned { NSData.create(bytes = it.addressOf(0), length = original.size.toULong()) }
        assertTrue(input.writeToFile(path, atomically = true))
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        var loader: ChurResourceLoader? = null
        var player: AVPlayer? = null
        try {
            vault.start()
            vault.create("HEVC-Main10-native-test".encodeToByteArray(), false)
            val codec = IosMediaCodec()
            val imported = assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(vault,
                assertNotNull(codec.open(NSURL.fileURLWithPath(path), name))), name)
            assertFalse(imported.storedAsFile, name)
            assertFalse(imported.previewsSkipped, name)
            if (playable) {
                assertNull(imported.previewWarning, "$name preview decode")
                assertTrue(imported.derivatives >= 2, "$name thumbnail and poster")
                assertTrue(vault.readDerived(imported.objectId, StreamKind.THUMBNAIL).isNotEmpty())
                assertTrue(vault.readDerived(imported.objectId, StreamKind.VIDEO_POSTER).isNotEmpty())
            } else {
                assertEquals(MediaImporter.PreviewWarning.DECODING_FAILED, imported.previewWarning, name)
                assertEquals(0, imported.derivatives, "$name must not claim a video preview")
            }
            assertEquals(0, unlink(path), "Playback must use the encrypted object after picker scratch is removed")
            assertContentEquals(original, vault.readRange(imported.objectId, 0, original.size), name)

            // Export through the native export operation, not by writing readRange bytes.
            val exportPath = "$root/export.mp4"
            val output = open(exportPath, O_WRONLY or O_CREAT or O_EXCL, 384)
            assertTrue(output >= 0)
            try {
                val operation = vault.beginExport(imported.objectId, output)
                val result = try { complete(vault, operation) } finally { vault.closeOperation(operation) }
                assertEquals(0, result.status, "$name native export")
            } finally { close(output) }
            val exported = read(exportPath)
            assertContentEquals(original, exported, "$name original bytes")
            assertContentEquals(sha256(original), sha256(exported), "$name original SHA-256")

            val lease = vault.leaseReader(imported.objectId)
            val info = try { vault.readerContentInfo(lease) } finally { vault.releaseReader(lease) }
            assertEquals("video/mp4", info.contentType)
            val nativeLoader = ChurResourceLoader(PlaybackSource(vault, imported.objectId, info.contentType, info.plaintextSize))
                .also { loader = it }
            val item = privatePlayerItem(nativeLoader)
            val video = AVPlayerItemVideoOutput(pixelBufferAttributes = null)
            item.addOutput(video)
            val nativePlayer = privatePlayer(item).also { player = it; it.muted = true }
            waitFor { item.status == AVPlayerItemStatusReadyToPlay || item.error != null }
            assertNull(item.error, "$name AVPlayer load")
            assertEquals(AVPlayerItemStatusReadyToPlay, item.status, name)
            if (playable) {
                assertTrue(item.asset.playable, name)
                assertNull(iosVideoPlaybackSupportProblem(item), "$name must pass native video capability")
                nativePlayer.play()
                waitFor { CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12 || item.error != null }
                assertNull(item.error, "$name play")
                assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12, "$name playback must advance")
                // A video frame proves HEVC decoding; an advancing AAC clock alone does not.
                var decoded: CVPixelBufferRef? = null
                waitFor {
                    decoded = video.copyPixelBufferForItemTime(nativePlayer.currentTime(), null)
                    decoded != null || item.error != null
                }
                val frame = assertNotNull(decoded, "$name must decode an actual HEVC Main10 frame: ${item.error}")
                try {
                    assertEquals(192, CVPixelBufferGetWidth(frame).toInt())
                    assertEquals(96, CVPixelBufferGetHeight(frame).toInt())
                } finally { CVPixelBufferRelease(frame) }
                nativePlayer.pause()
                nativePlayer.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
                waitFor { CMTimeGetSeconds(nativePlayer.currentTime()) < 0.05 || item.error != null }
                assertNull(item.error, "$name seek")
                assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) < 0.05, "$name seek to beginning")
                nativePlayer.play()
                waitFor { CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12 || item.error != null }
                assertNull(item.error, "$name resume")
                assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12, "$name playback after seek")
            } else {
                // AVPlayer accepts the AAC track even though the video's native
                // sample entry is unplayable. The same guard used by the UI must
                // reject this before it starts misleading audio-only playback.
                assertFalse(item.asset.playable, name)
                val problem = assertNotNull(iosVideoPlaybackSupportProblem(item))
                assertEquals(PlaybackProblem.UNSUPPORTED, problem, name)
                assertEquals("This device cannot play this file. You can export it to use a compatible player.", playbackProblemMessage(problem))
                assertEquals(0f, nativePlayer.rate, "$name must remain paused")
            }
            assertFalse(nativePlayer.allowsExternalPlayback)
            assertNull(nativeLoader.problem.value)
            // Match viewer disposal before lock. A separately held original
            // reader must also become invalid when the vault closes the session.
            val heldReader = vault.leaseReader(imported.objectId)
            nativePlayer.pause()
            nativePlayer.replaceCurrentItemWithPlayerItem(null)
            nativeLoader.release()
            player = null
            loader = null
            vault.lock(LockReason.USER)
            assertNull(assertIs<VaultState.Locked>(vault.state.value).lastFailure, "$name vault lock")
            val expired = assertFailsWith<ChurFailure>("$name reader must fail after lock") {
                vault.readLeased(heldReader, 0, 16)
            }
            assertEquals(ChurStatus.SESSION_EXPIRED, expired.status, "$name reader invalidation")
            vault.releaseReader(heldReader)
        } finally {
            player?.pause()
            player?.replaceCurrentItemWithPlayerItem(null)
            loader?.release()
            runCatching { vault.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private suspend fun complete(vault: VaultRepository, operation: Long): OperationProgress = withTimeout(60_000) {
        var progress = vault.poll(operation)
        while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
        progress
    }

    private fun read(path: String): ByteArray = assertNotNull(NSData.dataWithContentsOfFile(path), path).let {
        assertNotNull(it.bytes).readBytes(it.length.toInt())
    }

    private fun sha256(bytes: ByteArray): ByteArray = livePhotoDigest().apply { update(bytes) }.finish()

    private fun waitFor(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            NSRunLoop.currentRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.02))
        }
    }
}
