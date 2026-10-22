package dev.po4yka.chur.app.vault

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackDiagnosticsHostTest {
    @Test
    fun unsupported_video_and_audio_are_separate_even_when_the_other_track_is_selected() {
        val video = androidPlaybackDiagnostic(null, tracks(C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_HANDLED))!!
        assertEquals(PlaybackProblem.UNSUPPORTED_VIDEO, video.problem)
        assertEquals(PlaybackVideoFormat.HEVC_MAIN10, video.video?.format)
        val audio = androidPlaybackDiagnostic(null, tracks(C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE))!!
        assertEquals(PlaybackProblem.UNSUPPORTED_AUDIO, audio.problem)
        assertEquals(PlaybackAudioFormat.DTS, audio.audio)
        assertNull(androidPlaybackDiagnostic(null, tracks(C.FORMAT_HANDLED, C.FORMAT_HANDLED)))
        assertNull(androidPlaybackDiagnostic(null, tracks(C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_HANDLED)))
    }

    @Test
    fun parser_errors_are_not_authentication_failures_and_generic_decoder_errors_are_not_invalid_media() {
        val malformed = error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackProblem.INVALID_MEDIA, androidPlaybackDiagnostic(malformed)?.problem)
        assertEquals(PlaybackProblem.UNSUPPORTED_CONTAINER,
            androidPlaybackDiagnostic(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))?.problem)
        assertEquals(PlaybackProblem.UNAVAILABLE,
            androidPlaybackDiagnostic(error(PlaybackException.ERROR_CODE_DECODING_FAILED))?.problem)
        assertFalse(playbackDiagnosticMessage(androidPlaybackDiagnostic(malformed)!!).contains("secret"))
    }

    @Test
    fun a_decoder_failure_on_an_exceeds_capability_track_is_an_unsupported_format() {
        val hevc = Format.Builder().setSampleMimeType("video/hevc").setCodecs("hvc1.2.4.L120.B0").build()
        val exceeded = renderer(hevc, C.FORMAT_EXCEEDS_CAPABILITIES, PlaybackException.ERROR_CODE_DECODING_FAILED)
        val diagnostic = androidPlaybackDiagnostic(exceeded)!!
        assertEquals(PlaybackProblem.UNSUPPORTED_VIDEO, diagnostic.problem)
        assertEquals(PlaybackVideoFormat.HEVC_MAIN10, diagnostic.video?.format)
        // A failure on a handled format can be a reclaimed or busy decoder.
        assertEquals(PlaybackProblem.UNAVAILABLE, androidPlaybackDiagnostic(
            renderer(hevc, C.FORMAT_HANDLED, PlaybackException.ERROR_CODE_DECODING_FAILED))?.problem)
    }

    @Test
    fun authenticated_reader_integrity_and_availability_take_priority_over_media_errors() {
        val tracks = tracks(C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_UNSUPPORTED_TYPE)
        val integrity = PlaybackException("secret", IllegalStateException("secret", ChurFailure(ChurStatus.OBJECT_CORRUPT, "secret")),
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackProblem.INTEGRITY, androidPlaybackDiagnostic(integrity, tracks)?.problem)
        val missing = PlaybackException("secret", ChurFailure(ChurStatus.OBJECT_INCOMPLETE, "secret"),
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackProblem.UNAVAILABLE, androidPlaybackDiagnostic(missing, tracks)?.problem)
    }

    private fun error(code: Int) = PlaybackException("/private/secret.mp4", null, code)

    private fun renderer(format: Format, support: Int, code: Int) = ExoPlaybackException.createForRenderer(
        IllegalStateException("secret"), "secret", 0, format, support, false, code)

    private fun tracks(video: Int, audio: Int) = Tracks(listOf(
        group(Format.Builder().setSampleMimeType("video/hevc").setCodecs("hvc1.2.4.L120.B0").build(), video),
        group(Format.Builder().setSampleMimeType("audio/vnd.dts").build(), audio),
    ))

    private fun group(format: Format, support: Int) = Tracks.Group(TrackGroup(format), false,
        intArrayOf(support), booleanArrayOf(support == C.FORMAT_HANDLED))
}
