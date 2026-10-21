package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorFileFailedToParse
import platform.AVFoundation.AVErrorInvalidSourceMedia
import platform.AVFoundation.AVErrorMediaSubTypeKey
import platform.AVFoundation.AVErrorMediaTypeKey
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUnderlyingErrorKey

/** Keep native descriptions and source URLs out of both the result and UI. */
internal fun iosPlaybackDiagnostic(
    error: NSError?,
    video: PlaybackVideoDetails? = null,
    audio: PlaybackAudioFormat? = null,
    readerProblem: PlaybackProblem? = null,
): PlaybackDiagnostic {
    if (readerProblem == PlaybackProblem.INTEGRITY) return PlaybackDiagnostic(readerProblem)
    var current = error
    var reader: PlaybackProblem? = readerProblem
    repeat(8) {
        val item = current
        if (item?.domain == "dev.po4yka.chur.playback") {
            val problem = playbackProblem(ChurStatus.fromValue(item.code.toInt()))
            if (problem == PlaybackProblem.INTEGRITY) return PlaybackDiagnostic(problem)
            if (reader == null) reader = problem
        }
        current = item?.userInfo?.get(NSUnderlyingErrorKey) as? NSError
    }
    reader?.let { return PlaybackDiagnostic(it) }
    if (error == null || error.domain != AVFoundationErrorDomain) return PlaybackDiagnostic(PlaybackProblem.UNAVAILABLE)
    return when (error.code) {
        AVErrorInvalidSourceMedia, AVErrorFileFailedToParse -> PlaybackDiagnostic(PlaybackProblem.INVALID_MEDIA)
        AVErrorFileFormatNotRecognized -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_CONTAINER)
        AVErrorDecoderNotFound -> {
            val mediaType = error.userInfo[AVErrorMediaTypeKey]
            val subtypes = error.userInfo[AVErrorMediaSubTypeKey] as? List<*>
            val subtype = (subtypes?.firstOrNull() as? NSNumber)?.unsignedIntValue
            when {
                mediaType == AVMediaTypeAudio -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_AUDIO,
                    audio = audio ?: iosPlaybackAudioFormat(subtype))
                mediaType == AVMediaTypeVideo -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_VIDEO,
                    video = video ?: iosPlaybackVideoDetails(subtype))
                video != null && audio == null -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_VIDEO, video = video)
                audio != null && video == null -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_AUDIO, audio = audio)
                else -> PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED)
            }
        }
        // DecodeFailed is not evidence of failed authenticated integrity or malformed bytes.
        else -> PlaybackDiagnostic(PlaybackProblem.UNAVAILABLE)
    }
}

/** Parse only bounded ISO HEVC configuration facts supplied by the native format description. */
internal fun iosPlaybackVideoDetails(
    subtype: UInt?, hevcConfiguration: ByteArray? = null, width: Int? = null, height: Int? = null,
): PlaybackVideoDetails {
    val mime = when (subtype) {
        0x68766331u, 0x68657631u -> "video/hevc" // hvc1, hev1
        0x64766831u, 0x64766865u -> "video/dolby-vision" // dvh1, dvhe
        0x61766331u, 0x61766333u -> "video/avc"
        0x61763031u -> "video/av01"
        0x76703039u -> "video/x-vnd.on2.vp9"
        else -> null
    }
    val configuration = hevcConfiguration?.takeIf { it.size in 23..4096 && it[0].toInt() == 1 }
    val profile = configuration?.get(1)?.toInt()?.and(31)
    val depth = configuration?.get(17)?.toInt()?.and(7)?.plus(8)
    return playbackVideoDetails(mime, profile?.let { "hvc1.$it" }, width, height, depth)
}

private fun iosPlaybackAudioFormat(subtype: UInt?): PlaybackAudioFormat = when (subtype) {
    0x6d703461u, 0x61616320u -> PlaybackAudioFormat.AAC
    0x61632d33u -> PlaybackAudioFormat.AC3
    0x65632d33u -> PlaybackAudioFormat.EAC3
    0x64747363u, 0x64747368u, 0x6474736cu, 0x64747365u -> PlaybackAudioFormat.DTS
    0x4d4c5041u -> PlaybackAudioFormat.TRUEHD
    0x664c6143u -> PlaybackAudioFormat.FLAC
    0x4f707573u, 0x6f707573u -> PlaybackAudioFormat.OPUS
    0x6c70636du, 0x736f7774u, 0x74776f73u -> PlaybackAudioFormat.PCM
    else -> PlaybackAudioFormat.UNKNOWN
}
