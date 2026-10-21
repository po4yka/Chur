package dev.po4yka.chur.app.vault

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackTracksHostTest {
    @Test
    fun emptyAndSupportedTracksDoNotReportAProblem() {
        assertNull(playbackProblem(null, Tracks.EMPTY))
        assertNull(playbackProblem(null, tracks(C.FORMAT_HANDLED, C.FORMAT_HANDLED)))
    }

    @Test
    fun unsupportedVideoIsReportedEvenWhenAudioIsSelected() {
        assertEquals(PlaybackProblem.UNSUPPORTED,
            playbackProblem(null, tracks(C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_HANDLED)))
    }

    @Test
    fun unsupportedAudioIsReportedEvenWhenVideoIsSelected() {
        assertEquals(PlaybackProblem.UNSUPPORTED,
            playbackProblem(null, tracks(C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE)))
    }

    @Test
    fun decoderMayTryTracksWhichExceedItsReportedCapabilities() {
        assertNull(playbackProblem(null, tracks(C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_HANDLED)))
    }

    @Test
    fun readerFailureTakesPriorityOverUnsupportedTracks() {
        val unsupported = tracks(C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_HANDLED)
        val integrity = PlaybackException("redacted", ChurFailure(ChurStatus.OBJECT_CORRUPT, "redacted"),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(PlaybackProblem.INTEGRITY, playbackProblem(integrity, unsupported))
        val unavailable = PlaybackException("redacted", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(PlaybackProblem.UNAVAILABLE, playbackProblem(unavailable, unsupported))
    }

    private fun tracks(videoSupport: Int, audioSupport: Int) = Tracks(listOf(
        group("video/hevc", videoSupport), group("audio/mp4a-latm", audioSupport),
    ))

    private fun group(mime: String, support: Int) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).build()), false,
        intArrayOf(support), booleanArrayOf(support == C.FORMAT_HANDLED),
    )
}
