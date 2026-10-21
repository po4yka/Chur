@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.*
import dev.po4yka.chur.imports.livePhotoDigest
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.UIImage
import kotlin.test.*

class VideoTimelineIosTest {
    @Test fun pq_timeline_uses_native_sdr_conversion_and_keeps_gray_order(): Unit = runBlocking {
        val case = IosVideoCase("hdr10-hvc1.mp4", 192, 96, durationMs = 1_200L..1_300L,
            seekSeconds = emptyList(), fixtureEnvironment = "CHUR_HEVC_MAIN10_FIXTURES")
        withIosImportedVideo(case) { session, originalSha256 ->
            val atlas = IosVideoTimelineGenerator().generate(source(session), { false })
            try {
                val pixels = decode(atlas.bytes)
                try {
                    val gray = (0..5).map { bar ->
                        val x = ((16 + 32 * bar) * 160.0 / 192.0).toInt()
                        val offset = (16 * 640 + x) * 4
                        val rgb = (0..2).map { pixels[offset + it].toInt() and 255 }
                        assertTrue(rgb.max() - rgb.min() <= 12, "PQ timeline gray must remain neutral: $rgb")
                        rgb.sum() / 3
                    }
                    assertTrue(gray.zipWithNext().all { (first, next) -> next >= first - 2 }, "PQ timeline gray order: $gray")
                    assertTrue(gray.last() - gray.first() > 96, "PQ timeline black/white separation: $gray")
                } finally { pixels.fill(0) }
            } finally { atlas.bytes.fill(0) }
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun real_vfr_timeline_has_twelve_slots_and_encrypted_bytes_without_changing_original(): Unit = runBlocking {
        withIosImportedVideo(iosLongGopVfrVideo) { session, originalSha256 ->
            val generation = assertIs<VaultState.Unlocked>(session.vault.state.value).generation
            val atlas = IosVideoTimelineGenerator().generate(source(session), { false })
            try {
                assertEquals(640 to 270, atlas.width to atlas.height)
                assertTrue(atlas.bytes.isNotEmpty() && atlas.bytes.size <= 2 * 1024 * 1024)
                val pixels = decode(atlas.bytes)
                val cellHashes = (0 until 12).map { index ->
                    val digest = livePhotoDigest()
                    for (row in 0 until 90) {
                        digest.update(pixels, ((index / 4 * 90 + row) * 640 + index % 4 * 160) * 4, 160 * 4)
                    }
                    digest.finish().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                }
                assertTrue(cellHashes.distinct().size > 1, "Different source timestamps must not all display the same frame")
                session.vault.putDerived(session.objectId, StreamKind.VIDEO_STORYBOARD, atlas.width, atlas.height, atlas.bytes, generation)
                assertContentEquals(atlas.bytes, session.vault.readDerived(session.objectId, StreamKind.VIDEO_STORYBOARD))
                pixels.fill(0)
            } finally { atlas.bytes.fill(0) }
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun portrait_slots_keep_their_aspect_ratio_with_black_letterboxes(): Unit = runBlocking {
        withIosImportedVideo(iosPortraitVideo) { session, originalSha256 ->
            val atlas = IosVideoTimelineGenerator().generate(source(session), { false })
            try {
                val pixels = decode(atlas.bytes)
                try {
                    for (x in listOf(5, 155)) {
                        val offset = (45 * 640 + x) * 4
                        assertTrue((0..2).all { (pixels[offset + it].toInt() and 255) <= 8 }, "Portrait cell sides must stay black")
                    }
                    assertTrue((10 until 80).any { row ->
                        val offset = (row * 640 + 80) * 4
                        (0..2).any { (pixels[offset + it].toInt() and 255) > 32 }
                    }, "Portrait image content must appear in the center")
                } finally { pixels.fill(0) }
            } finally { atlas.bytes.fill(0) }
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun cancellation_during_frame_generation_produces_no_partial_storyboard(): Unit = runBlocking {
        withIosImportedVideo(iosLongGopVfrVideo) { session, originalSha256 ->
            var checks = 0
            assertFailsWith<CancellationException> { IosVideoTimelineGenerator().generate(source(session), { ++checks >= 4 }) }
            assertNoAtlas(session)
            session.verifyExportAndLock(originalSha256)
        }
    }

    @Test fun lock_during_frame_generation_rejects_old_generation(): Unit = runBlocking {
        withIosImportedVideo(iosLongGopVfrVideo) { session, originalSha256 ->
            var checks = 0
            val failure = assertFailsWith<ChurFailure> {
                IosVideoTimelineGenerator().generate(source(session), {
                    if (++checks == 4) runBlocking { session.vault.lock(LockReason.USER) }
                    false
                })
            }
            assertEquals(ChurStatus.SESSION_EXPIRED, failure.status)
            session.vault.unlock("Video-acceptance-native-test".encodeToByteArray())
            assertNoAtlas(session)
            session.verifyExportAndLock(originalSha256)
        }
    }

    private suspend fun source(session: IosVideoSession): PlaybackSource {
        val reader = session.vault.leaseReader(session.objectId)
        val info = try { session.vault.readerContentInfo(reader) } finally { session.vault.releaseReader(reader) }
        return PlaybackSource(session.vault, session.objectId, info.contentType, info.plaintextSize)
    }

    private suspend fun assertNoAtlas(session: IosVideoSession) {
        assertEquals(ChurStatus.NOT_FOUND, assertFailsWith<ChurFailure> { session.vault.readDerived(session.objectId, StreamKind.VIDEO_STORYBOARD) }.status)
    }

    private fun decode(bytes: ByteArray): ByteArray {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        val image = assertNotNull(UIImage.imageWithData(data)?.CGImage)
        assertEquals(640, CGImageGetWidth(image).toInt()); assertEquals(270, CGImageGetHeight(image).toInt())
        val pixels = ByteArray(640 * 270 * 4)
        pixels.usePinned { pinned ->
            val space = assertNotNull(CGColorSpaceCreateWithName(kCGColorSpaceSRGB))
            val bitmap = try { assertNotNull(CGBitmapContextCreate(pinned.addressOf(0), 640uL, 270uL, 8uL, 2560uL, space,
                CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value or kCGBitmapByteOrder32Big)) } finally { CGColorSpaceRelease(space) }
            try { CGContextDrawImage(bitmap, CGRectMake(0.0, 0.0, 640.0, 270.0), image) } finally { CGContextRelease(bitmap) }
        }
        return pixels
    }
}
