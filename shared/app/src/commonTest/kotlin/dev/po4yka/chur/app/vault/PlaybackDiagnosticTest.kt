package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiagnosticTest {
    @Test
    fun typed_reader_failures_win_over_track_diagnostics_and_integrity_wins_over_availability() {
        val audio = PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_AUDIO, audio = PlaybackAudioFormat.DTS)
        assertEquals(PlaybackProblem.INTEGRITY, playbackDiagnostic(PlaybackProblem.INTEGRITY, audio)?.problem)
        assertEquals(PlaybackProblem.UNAVAILABLE, playbackDiagnostic(PlaybackProblem.UNAVAILABLE, audio)?.problem)
        assertEquals(PlaybackProblem.INTEGRITY,
            playbackDiagnostic(PlaybackProblem.UNAVAILABLE, PlaybackDiagnostic(PlaybackProblem.INTEGRITY))?.problem)
    }

    @Test
    fun codec_labels_and_dimensions_do_not_echo_untrusted_metadata() {
        val private = "/private/export/secret-title.mp4"
        val unknown = playbackVideoDetails(private, private, Int.MAX_VALUE, -1)
        val message = playbackDiagnosticMessage(PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_VIDEO, video = unknown))
        assertFalse(message.contains(private))
        assertFalse(message.contains(Int.MAX_VALUE.toString()))
        assertEquals(PlaybackVideoFormat.UNKNOWN, unknown.format)
        val main10 = playbackVideoDetails("video/hevc", "hvc1.2.4.L153.B0", 3840, 2160)
        assertEquals(PlaybackVideoFormat.HEVC_MAIN10, main10.format)
        assertTrue(playbackDiagnosticMessage(PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_VIDEO, video = main10))
            .contains("HEVC Main10, 3840 × 2160"))
        assertEquals(PlaybackVideoFormat.HEVC_10_BIT, playbackVideoDetails("video/hevc", bitDepth = 10).format)
        assertEquals(PlaybackVideoFormat.HEVC, playbackVideoDetails("video/hevc").format)
        assertEquals(PlaybackAudioFormat.UNKNOWN, playbackAudioFormat(private))
    }

    @Test
    fun actions_cannot_retry_or_make_a_viewing_copy_after_integrity_failure() {
        val integrity = PlaybackDiagnostic(PlaybackProblem.INTEGRITY)
        assertFalse(integrity.canRetry)
        assertFalse(integrity.canViewCopy)
        assertFalse(integrity.canExportOriginal)
        val unavailable = PlaybackDiagnostic(PlaybackProblem.UNAVAILABLE)
        assertTrue(unavailable.canRetry)
        assertFalse(unavailable.canViewCopy)
        val unsupported = PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_AUDIO, audio = PlaybackAudioFormat.DTS)
        assertTrue(unsupported.canViewCopy)
        assertTrue(unsupported.canExportOriginal)
        assertFalse(unsupported.canRetry)
        val invalid = PlaybackDiagnostic(PlaybackProblem.INVALID_MEDIA)
        assertTrue(invalid.canExportOriginal)
        assertFalse(invalid.canRetry)
        assertFalse(invalid.canViewCopy)
        assertTrue(playbackDiagnosticMessage(invalid).contains("not an integrity-check failure"))
        val container = PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_CONTAINER)
        assertTrue(container.canExportOriginal)
        assertFalse(container.canRetry)
        assertFalse(container.canViewCopy)
        assertTrue(playbackDiagnosticMessage(container).contains("does not recognize this media container"))
    }
}
