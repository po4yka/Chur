@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.Foundation.NSURL
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import kotlin.test.*

class PlaybackAssetDetailsIosTest {
    @Test fun real_truncated_mp4_is_rejected_by_native_playback_without_an_integrity_claim() {
        val fixtures = assertNotNull(getenv("CHUR_VIDEO_DIAGNOSTICS_FIXTURES")?.toKString())
        val asset = privatePlaybackAsset(NSURL.fileURLWithPath("$fixtures/truncated-container.mp4"))
        val item = AVPlayerItem(asset, automaticallyLoadedAssetKeys = listOf("playable"))
        val player = privatePlayer(item)
        try {
            assertFalse(player.allowsExternalPlayback)
            player.play()
            iosVideoWait { item.status == AVPlayerItemStatusFailed || item.error != null }
            assertEquals(AVPlayerItemStatusFailed, item.status)
            val error = assertNotNull(item.error, "Native rejection must provide an actual NSError")
            val diagnostic = iosPlaybackDiagnostic(error)
            assertTrue(diagnostic.problem in listOf(PlaybackProblem.INVALID_MEDIA, PlaybackProblem.UNSUPPORTED_CONTAINER),
                "Native ${error.domain}/${error.code} must classify as invalid media or unsupported container")
            assertNotEquals(PlaybackProblem.INTEGRITY, diagnostic.problem)
        } finally {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            asset.cancelLoading()
        }
    }

    @Test fun actual_hevc_main10_configuration_and_coded_size_reach_safe_diagnostics() {
        val fixtures = assertNotNull(getenv("CHUR_HEVC_MAIN10_FIXTURES")?.toKString())
        for (name in listOf("sdr10-hvc1.mp4", "hdr10-hvc1.mp4", "sdr10-hev1.mp4")) {
            val asset = privatePlaybackAsset(NSURL.fileURLWithPath("$fixtures/$name"))
            val details = assertNotNull(iosAssetVideoDetails(asset), name)
            assertEquals(PlaybackVideoFormat.HEVC_MAIN10, details.format, name)
            assertEquals(192, details.width, name)
            assertEquals(96, details.height, name)
            assertEquals(PlaybackAudioFormat.AAC, iosAssetAudioFormat(asset), name)
        }
    }

    @Test fun an_asset_without_native_track_descriptions_has_no_invented_format() {
        val asset = AVMutableComposition()
        assertNull(iosAssetVideoDetails(asset))
        assertNull(iosAssetAudioFormat(asset))
    }

    @Test fun actual_native_track_capability_separates_unplayable_hev1_from_supported_hvc1() {
        val fixtures = assertNotNull(getenv("CHUR_HEVC_MAIN10_FIXTURES")?.toKString())
        val supported = privatePlaybackAsset(NSURL.fileURLWithPath("$fixtures/sdr10-hvc1.mp4"))
        assertTrue((supported.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack).decodable)
        assertNull(iosAssetUnsupportedDiagnostic(supported))
        val unsupported = privatePlaybackAsset(NSURL.fileURLWithPath("$fixtures/sdr10-hev1.mp4"))
        assertFalse(unsupported.playable)
        assertFalse((unsupported.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack).decodable)
        val diagnostic = assertNotNull(iosAssetUnsupportedDiagnostic(unsupported))
        assertEquals(PlaybackProblem.UNSUPPORTED_VIDEO, diagnostic.problem)
        assertEquals(PlaybackVideoFormat.HEVC_MAIN10, diagnostic.video?.format)
    }

    @Test fun actual_dts_audio_rejection_does_not_blame_a_decodable_hevc_main10_video_track() {
        val fixtures = assertNotNull(getenv("CHUR_VIDEO_DIAGNOSTICS_FIXTURES")?.toKString())
        val path = "$fixtures/hevc-dts.mp4"
        val asset = privatePlaybackAsset(NSURL.fileURLWithPath(path))
        val data = assertNotNull(NSData.dataWithContentsOfFile(path))
        val bytes = assertNotNull(data.bytes).readBytes(data.length.toInt())
        val declared = try {
            inspectPlaybackContainerRanges(bytes.size.toLong()) { offset, count -> bytes.copyOfRange(offset.toInt(), offset.toInt() + count) }
        } finally { bytes.fill(0) }
        assertTrue(declared.complete)
        assertEquals(listOf(PlaybackAudioFormat.DTS), declared.audio)
        assertEquals(PlaybackVideoFormat.HEVC_MAIN10, declared.video.single().format)
        assertTrue((asset.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack).decodable)
        assertTrue(asset.tracksWithMediaType(AVMediaTypeAudio).filterIsInstance<AVAssetTrack>().none { it.decodable },
            "DTS is either omitted by native demuxing or exposed as undecodable")
        val diagnostic = assertNotNull(iosAssetUnsupportedDiagnostic(asset, declared))
        assertEquals(PlaybackProblem.UNSUPPORTED_AUDIO, diagnostic.problem)
        assertEquals(PlaybackAudioFormat.DTS, diagnostic.audio)
        assertNull(diagnostic.video)
    }
}
