package dev.po4yka.chur.app.vault

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import dev.po4yka.chur.ffi.ChurFailure

/** Classifies typed failures only. Error messages, renderer names and paths are never displayed. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun androidPlaybackDiagnostic(error: PlaybackException?, tracks: Tracks = Tracks.EMPTY): PlaybackDiagnostic? {
    var cause: Throwable? = error
    var readerProblem: PlaybackProblem? = null
    repeat(16) {
        val current = cause
        if (current is ChurFailure) {
            val problem = playbackProblem(current.status)
            if (problem == PlaybackProblem.INTEGRITY) return PlaybackDiagnostic(problem)
            if (readerProblem == null) readerProblem = problem
        }
        cause = current?.cause
    }
    readerProblem?.let { return PlaybackDiagnostic(it) }
    if (error != null) {
        if (error.errorCode in 2000..2999) return PlaybackDiagnostic(PlaybackProblem.UNAVAILABLE)
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> return PlaybackDiagnostic(PlaybackProblem.INVALID_MEDIA)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> return PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_CONTAINER)
        }
        val renderer = error as? ExoPlaybackException
        val unsupportedFormat = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            renderer?.rendererFormatSupport in listOf(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_UNSUPPORTED_SUBTYPE)
        if (unsupportedFormat) {
            return unsupportedDiagnostic(renderer?.rendererFormat) ?: unsupportedTrackDiagnostic(tracks)
                ?: PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED)
        }
        // A decoder can fail because resources were reclaimed or are temporarily busy.
        // It does not prove invalid input, an unsupported profile, or failed authentication.
        return PlaybackDiagnostic(PlaybackProblem.UNAVAILABLE)
    }
    return unsupportedTrackDiagnostic(tracks)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun unsupportedTrackDiagnostic(tracks: Tracks): PlaybackDiagnostic? {
    for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
        if (tracks.containsType(type) && !tracks.isTypeSupported(type, /* allowExceedsCapabilities = */ true)) {
            val format = tracks.groups.firstOrNull { it.type == type && it.length > 0 }?.getTrackFormat(0)
            return unsupportedDiagnostic(format) ?: PlaybackDiagnostic(
                if (type == C.TRACK_TYPE_VIDEO) PlaybackProblem.UNSUPPORTED_VIDEO else PlaybackProblem.UNSUPPORTED_AUDIO,
            )
        }
    }
    return null
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun unsupportedDiagnostic(format: Format?): PlaybackDiagnostic? {
    val mime = format?.sampleMimeType ?: return null
    return when {
        mime.startsWith("video/") -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_VIDEO,
            video = playbackVideoDetails(mime, format.codecs, format.width, format.height, format.colorInfo?.lumaBitdepth))
        mime.startsWith("audio/") -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_AUDIO, audio = playbackAudioFormat(mime))
        else -> null
    }
}
