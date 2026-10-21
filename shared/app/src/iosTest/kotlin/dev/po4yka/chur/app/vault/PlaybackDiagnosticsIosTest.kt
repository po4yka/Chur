package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorFileFailedToParse
import platform.AVFoundation.AVErrorInvalidSourceMedia
import platform.AVFoundation.AVErrorMediaTypeKey
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSUnderlyingErrorKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackDiagnosticsIosTest {
    @Test
    fun native_decoder_errors_use_the_reported_media_type_and_safe_format_facts() {
        val video = iosPlaybackDiagnostic(NSError(AVFoundationErrorDomain, AVErrorDecoderNotFound,
            mapOf(AVErrorMediaTypeKey to AVMediaTypeVideo)), PlaybackVideoDetails(PlaybackVideoFormat.HEVC_MAIN10))
        assertEquals(PlaybackProblem.UNSUPPORTED_VIDEO, video.problem)
        assertEquals(PlaybackVideoFormat.HEVC_MAIN10, video.video?.format)
        val audio = iosPlaybackDiagnostic(NSError(AVFoundationErrorDomain, AVErrorDecoderNotFound,
            mapOf(AVErrorMediaTypeKey to AVMediaTypeAudio)), audio = PlaybackAudioFormat.DTS)
        assertEquals(PlaybackProblem.UNSUPPORTED_AUDIO, audio.problem)
        assertEquals(PlaybackAudioFormat.DTS, audio.audio)
        assertEquals(PlaybackProblem.UNSUPPORTED,
            iosPlaybackDiagnostic(NSError(AVFoundationErrorDomain, AVErrorDecoderNotFound, null)).problem)
    }

    @Test
    fun invalid_container_decode_failure_and_authenticated_integrity_remain_distinct() {
        for (code in listOf(AVErrorInvalidSourceMedia, AVErrorFileFailedToParse)) {
            val diagnostic = iosPlaybackDiagnostic(NSError(AVFoundationErrorDomain, code,
                mapOf(NSLocalizedDescriptionKey to "/private/secret.mov")))
            assertEquals(PlaybackProblem.INVALID_MEDIA, diagnostic.problem)
            assertFalse(playbackDiagnosticMessage(diagnostic).contains("secret"))
        }
        assertEquals(PlaybackProblem.UNSUPPORTED_CONTAINER,
            iosPlaybackDiagnostic(NSError(AVFoundationErrorDomain, AVErrorFileFormatNotRecognized, null)).problem)
        assertEquals(PlaybackProblem.UNAVAILABLE,
            iosPlaybackDiagnostic(NSError(AVFoundationErrorDomain, AVErrorDecodeFailed, null)).problem)
        val integrity = iosReaderPlaybackError(ChurStatus.OBJECT_CORRUPT)
        val wrapped = NSError(AVFoundationErrorDomain, AVErrorDecodeFailed, mapOf(NSUnderlyingErrorKey to integrity))
        assertEquals(PlaybackProblem.INTEGRITY, iosPlaybackDiagnostic(wrapped).problem)
        assertEquals(PlaybackProblem.UNAVAILABLE,
            iosPlaybackDiagnostic(NSError(AVFoundationErrorDomain, AVErrorDecoderNotFound, null),
                readerProblem = PlaybackProblem.UNAVAILABLE).problem)
    }

    @Test
    fun hevc_main10_is_reported_only_when_the_actual_configuration_provides_its_profile() {
        val configuration = ByteArray(23).apply { this[0] = 1; this[1] = 2; this[17] = 2 }
        assertEquals(PlaybackVideoFormat.HEVC_MAIN10, iosPlaybackVideoDetails(0x68766331u, configuration).format)
        assertEquals(PlaybackVideoFormat.HEVC, iosPlaybackVideoDetails(0x68766331u).format)
        assertEquals(PlaybackVideoFormat.UNKNOWN, iosPlaybackVideoDetails(0u, configuration).format)
    }
}
