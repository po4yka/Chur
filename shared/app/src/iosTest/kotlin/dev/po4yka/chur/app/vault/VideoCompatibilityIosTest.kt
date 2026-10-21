@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.*
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.*
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.*
import platform.CoreMedia.*
import platform.CoreVideo.*
import platform.Foundation.*
import platform.posix.getenv
import kotlin.test.*

class VideoCompatibilityIosTest {
    @Test fun pq_converts_to_encrypted_h264_aac_sdr_and_keeps_original_export() = roundTrip(
        IosVideoCase("hdr10-hvc1.mp4", 192, 96, durationMs = 1_200L..1_300L, seekSeconds = listOf(0.5), fixtureEnvironment = "CHUR_HEVC_MAIN10_FIXTURES"), grayStrip = true)
    @Test fun sdr_converts_to_encrypted_h264_aac_sdr_and_keeps_original_export() = roundTrip(
        IosVideoCase("sdr10-hvc1.mp4", 192, 96, durationMs = 1_200L..1_300L, seekSeconds = listOf(0.5), fixtureEnvironment = "CHUR_HEVC_MAIN10_FIXTURES"), grayStrip = true)
    @Test fun portrait_copy_is_upright_and_keeps_original_export() = roundTrip(iosPortraitVideo)
    @Test fun vfr_copy_keeps_duration_and_distant_seek_timestamps() = roundTrip(iosLongGopVfrVideo)

    @Test fun unsupported_declared_dts_audio_cannot_be_silently_removed_from_a_viewing_copy(): Unit = runBlocking {
        val case = IosVideoCase("hevc-dts.mp4", 192, 96, durationMs = 1_200L..1_300L, seekSeconds = emptyList(),
            fixtureEnvironment = "CHUR_VIDEO_DIAGNOSTICS_FIXTURES")
        withIosImportedVideo(case) { session, originalSha256 ->
            val failure = assertFailsWith<ChurFailure> {
                IosVideoCompatibilityEncoder().encode(originalSource(session), { false }, {})
            }
            assertEquals(ChurStatus.IO_FAILURE, failure.status)
            assertNoCopy(session)
            session.verifyExportAndLock(originalSha256)
        }
    }

    private fun roundTrip(case: IosVideoCase, grayStrip: Boolean = false): Unit = runBlocking {
        withIosImportedVideo(case) { session, originalSha256 ->
            val generation = assertIs<VaultState.Unlocked>(session.vault.state.value).generation
            val source = originalSource(session)
            val encoded = IosVideoCompatibilityEncoder().encode(source, { false }, {})
            try {
                // Public procedural fixtures only: retain the actual native
                // output beside the acceptance report for independent probing.
                if (getenv("CHUR_IOS_CAPTURE_COMPATIBLE_FIXTURES")?.toKString() == "1") {
                    val reports = assertNotNull(getenv("CHUR_VIDEO_ACCEPTANCE_REPORTS")?.toKString())
                    assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(reports, true, null, null))
                    encoded.bytes.usePinned { pinned ->
                        val output = NSData.create(bytes = pinned.addressOf(0), length = encoded.bytes.size.toULong())
                        assertTrue(output.writeToFile("$reports/compatible-${case.name}", atomically = true))
                    }
                }
                assertTrue(encoded.bytes.isNotEmpty() && encoded.bytes.size <= MAX_COMPATIBLE_VIDEO_BYTES)
                assertTrue(maxOf(encoded.width, encoded.height) <= 1280 && minOf(encoded.width, encoded.height) <= 720)
                assertTrue(encoded.durationMs in case.durationMs)
                session.vault.putDerived(session.objectId, StreamKind.COMPATIBLE_VIDEO, encoded.width, encoded.height, encoded.bytes, generation)
                val stored = session.vault.leaseReader(session.objectId, StreamKind.COMPATIBLE_VIDEO, generation)
                try {
                    assertEquals(encoded.bytes.size.toLong(), session.vault.readerContentInfo(stored).plaintextSize)
                    var offset = 0
                    while (offset < encoded.bytes.size) {
                        val bytes = session.vault.readLeased(stored, offset.toLong(), minOf(65_536, encoded.bytes.size - offset))
                        try {
                            assertTrue(bytes.isNotEmpty())
                            assertTrue(bytes.indices.all { bytes[it] == encoded.bytes[offset + it] }, "Stored copy differs at range $offset")
                            offset += bytes.size
                        } finally { bytes.fill(0) }
                    }
                } finally { session.vault.releaseReader(stored) }
                val copySource = PlaybackSource(session.vault, session.objectId, "video/mp4", encoded.bytes.size.toLong(), StreamKind.COMPATIBLE_VIDEO)
                val loader = ChurResourceLoader(copySource)
                val native = IosTestVideoPlayer(loader, privatePlayerItem(loader), mapOf<Any?, Any>(
                    cfString(assertNotNull(kCVPixelBufferPixelFormatTypeKey)) to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA)))
                val copyCase = case.copy(codedWidth = encoded.width, codedHeight = encoded.height)
                try {
                    native.ready()
                    val video = assertIs<AVAssetTrack>(native.item.asset.tracksWithMediaType(AVMediaTypeVideo).single())
                    val audio = assertIs<AVAssetTrack>(native.item.asset.tracksWithMediaType(AVMediaTypeAudio).single())
                    assertEquals(kCMVideoCodecType_H264.toInt(), codecType(assertNotNull(video.formatDescriptions.firstOrNull())))
                    assertEquals(kAudioFormatMPEG4AAC.toInt(), codecType(assertNotNull(audio.formatDescriptions.firstOrNull())))
                    assertTrue(video.nominalFrameRate <= 30.1f)
                    val actualDurationMs = CMTimeGetSeconds(native.item.asset.duration) * 1_000
                    assertTrue(kotlin.math.abs(actualDurationMs - encoded.durationMs) <= 100,
                        "Compatible fragmented MP4 duration $actualDurationMs must match source ${encoded.durationMs}")
                    native.player.play()
                    val frame = native.frame(copyCase)
                    try {
                        assertEquals(kCVPixelFormatType_32BGRA.toInt(), CVPixelBufferGetPixelFormatType(frame).toInt())
                        val transfer = assertNotNull(CVBufferCopyAttachment(frame, assertNotNull(kCVImageBufferTransferFunctionKey), null))
                        try { assertEquals(cfString(assertNotNull(kCVImageBufferTransferFunction_ITU_R_709_2)), cfString(transfer.reinterpret())) }
                        finally { CFRelease(transfer) }
                        if (grayStrip) assertGrayStrip(frame)
                    } finally { CVPixelBufferRelease(frame) }
                    case.seekSeconds.forEach { native.seek(copyCase, it) }
                    assertNull(native.loader.problem.value)
                } finally { native.close() }
            } finally { encoded.bytes.fill(0) }
            // The default native export still opens ORIGINAL, even after the
            // encrypted compatible view stream has been stored and played.
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun cancellation_before_decode_keeps_original_and_creates_no_view_stream(): Unit = runBlocking {
        withIosImportedVideo(iosPortraitVideo) { session, originalSha256 ->
            assertFailsWith<CancellationException> { IosVideoCompatibilityEncoder().encode(originalSource(session), { true }, {}) }
            assertNoCopy(session)
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun completion_callback_cancellation_and_lock_cannot_return_plaintext_output(): Unit = runBlocking {
        val case = IosVideoCase("sdr10-hvc1.mp4", 192, 96, durationMs = 1_200L..1_300L, seekSeconds = emptyList(),
            fixtureEnvironment = "CHUR_HEVC_MAIN10_FIXTURES")
        withIosImportedVideo(case) { session, originalSha256 ->
            var cancelled = false
            assertFailsWith<CancellationException> {
                IosVideoCompatibilityEncoder().encode(originalSource(session), { cancelled }, { if (it == 1.0) cancelled = true })
            }
            assertTrue(cancelled)
            assertNoCopy(session)
            var locked = false
            val failure = assertFailsWith<ChurFailure> {
                IosVideoCompatibilityEncoder().encode(originalSource(session), { false }, {
                    if (it == 1.0) { locked = true; runBlocking { session.vault.lock(LockReason.USER) } }
                })
            }
            assertTrue(locked)
            assertEquals(ChurStatus.SESSION_EXPIRED, failure.status)
            session.vault.unlock("Video-acceptance-native-test".encodeToByteArray())
            assertNoCopy(session)
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun cancellation_during_native_encode_returns_no_partial_view_stream(): Unit = runBlocking {
        withIosImportedVideo(iosLongGopVfrVideo) { session, originalSha256 ->
            var cancelled = false
            assertFailsWith<CancellationException> {
                IosVideoCompatibilityEncoder().encode(originalSource(session), { cancelled }, { cancelled = true })
            }
            assertTrue(cancelled)
            assertNoCopy(session)
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun lock_during_native_encode_rejects_old_generation_and_keeps_original(): Unit = runBlocking {
        withIosImportedVideo(iosLongGopVfrVideo) { session, originalSha256 ->
            var locked = false
            val failure = assertFailsWith<ChurFailure> {
                IosVideoCompatibilityEncoder().encode(originalSource(session), { false }, {
                    if (!locked) { locked = true; runBlocking { session.vault.lock(LockReason.USER) } }
                })
            }
            assertTrue(locked)
            assertEquals(ChurStatus.SESSION_EXPIRED, failure.status)
            session.vault.unlock("Video-acceptance-native-test".encodeToByteArray())
            assertNoCopy(session)
            session.verifyExportAndLock(originalSha256)
        }
    }

    private suspend fun originalSource(session: IosVideoSession): PlaybackSource {
        val reader = session.vault.leaseReader(session.objectId)
        val info = try { session.vault.readerContentInfo(reader) } finally { session.vault.releaseReader(reader) }
        return PlaybackSource(session.vault, session.objectId, info.contentType, info.plaintextSize)
    }

    private suspend fun assertNoCopy(session: IosVideoSession) {
        val failure = assertFailsWith<ChurFailure> { session.vault.readDerived(session.objectId, StreamKind.COMPATIBLE_VIDEO) }
        assertEquals(ChurStatus.NOT_FOUND, failure.status)
    }

    private fun codecType(value: Any): Int {
        val retained = assertNotNull(CFBridgingRetain(value))
        try { return CMFormatDescriptionGetMediaSubType(retained.reinterpret()).toInt() }
        finally { CFRelease(retained) }
    }

    private fun assertGrayStrip(frame: CVPixelBufferRef) {
        assertEquals(kCVReturnSuccess, CVPixelBufferLockBaseAddress(frame, 0uL))
        try {
            val pixels = assertNotNull(CVPixelBufferGetBaseAddress(frame)).reinterpret<ByteVar>()
            val rowBytes = CVPixelBufferGetBytesPerRow(frame).toInt()
            val gray = (0..5).map { bar ->
                val offset = 16 * rowBytes + (16 + bar * 32) * 4
                val rgb = (0..2).map { pixels[offset + it].toInt() and 255 }
                assertTrue(rgb.max() - rgb.min() <= 12, "SDR gray must remain neutral: $rgb")
                rgb.sum() / 3
            }
            assertTrue(gray.zipWithNext().all { (first, next) -> next >= first - 2 }, "SDR gray order: $gray")
            assertTrue(gray.last() - gray.first() > 96, "SDR black/white separation: $gray")
        } finally { CVPixelBufferUnlockBaseAddress(frame, 0uL) }
    }

    private fun cfString(value: CFStringRef): String = memScoped {
        val capacity = CFStringGetLength(value) * 4 + 1
        val bytes = allocArray<ByteVar>(capacity.toInt())
        assertTrue(CFStringGetCString(value, bytes, capacity, kCFStringEncodingUTF8))
        bytes.toKString()
    }
}
