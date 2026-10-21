package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.core.model.ChurStatus

/** Safe user-facing categories. Native diagnostic strings never reach the viewer. */
enum class PlaybackProblem {
    INTEGRITY,
    UNAVAILABLE,
    UNSUPPORTED,
    UNSUPPORTED_VIDEO,
    UNSUPPORTED_AUDIO,
    UNSUPPORTED_CONTAINER,
    INVALID_MEDIA,
}

/** Only allowlisted media facts can reach the error panel. */
enum class PlaybackVideoFormat(val label: String) {
    HEVC("HEVC"), HEVC_MAIN("HEVC Main"), HEVC_MAIN10("HEVC Main10"), HEVC_10_BIT("HEVC 10-bit"),
    DOLBY_VISION("Dolby Vision"), H264("H.264"), AV1("AV1"), VP9("VP9"), UNKNOWN("video"),
}

enum class PlaybackAudioFormat(val label: String) {
    AAC("AAC"), AC3("AC-3"), EAC3("E-AC-3"), DTS("DTS"), TRUEHD("TrueHD"),
    FLAC("FLAC"), OPUS("Opus"), VORBIS("Vorbis"), PCM("PCM"), UNKNOWN("audio"),
}

data class PlaybackVideoDetails(
    val format: PlaybackVideoFormat,
    val width: Int? = null,
    val height: Int? = null,
) {
    internal val label: String
        get() = if (width != null && height != null && width in 1..7680 && height in 1..7680) "${format.label}, $width × $height" else format.label
}

data class PlaybackDiagnostic(
    val problem: PlaybackProblem,
    val video: PlaybackVideoDetails? = null,
    val audio: PlaybackAudioFormat? = null,
) {
    val canRetry: Boolean get() = problem == PlaybackProblem.UNAVAILABLE
    val canViewCopy: Boolean get() = problem in setOf(PlaybackProblem.UNSUPPORTED_VIDEO, PlaybackProblem.UNSUPPORTED_AUDIO, PlaybackProblem.UNSUPPORTED)
    val canExportOriginal: Boolean get() = problem != PlaybackProblem.INTEGRITY
}

/** Native strings are inputs to a closed vocabulary, never interpolated into UI. */
internal fun playbackVideoDetails(
    mime: String?, codecs: String? = null, width: Int? = null, height: Int? = null, bitDepth: Int? = null,
): PlaybackVideoDetails {
    val format = when (mime) {
        "video/dolby-vision" -> PlaybackVideoFormat.DOLBY_VISION
        "video/hevc" -> {
            val parts = codecs?.takeIf { it.length <= 128 }?.split('.')
            val profile = if (parts?.firstOrNull() in listOf("hvc1", "hev1"))
                parts?.getOrNull(1)?.trimStart('A', 'B', 'C')?.toIntOrNull() else null
            when (profile) {
                1 -> PlaybackVideoFormat.HEVC_MAIN
                2 -> PlaybackVideoFormat.HEVC_MAIN10
                else -> if (bitDepth == 10) PlaybackVideoFormat.HEVC_10_BIT else PlaybackVideoFormat.HEVC
            }
        }
        "video/avc" -> PlaybackVideoFormat.H264
        "video/av01" -> PlaybackVideoFormat.AV1
        "video/x-vnd.on2.vp9" -> PlaybackVideoFormat.VP9
        else -> PlaybackVideoFormat.UNKNOWN
    }
    return PlaybackVideoDetails(format, width?.takeIf { it in 1..7680 }, height?.takeIf { it in 1..7680 })
}

internal fun playbackAudioFormat(mime: String?): PlaybackAudioFormat = when (mime) {
    "audio/mp4a-latm" -> PlaybackAudioFormat.AAC
    "audio/ac3" -> PlaybackAudioFormat.AC3
    "audio/eac3", "audio/eac3-joc" -> PlaybackAudioFormat.EAC3
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr", "audio/vnd.dts.uhd;profile=p2" -> PlaybackAudioFormat.DTS
    "audio/true-hd" -> PlaybackAudioFormat.TRUEHD
    "audio/flac" -> PlaybackAudioFormat.FLAC
    "audio/opus" -> PlaybackAudioFormat.OPUS
    "audio/vorbis" -> PlaybackAudioFormat.VORBIS
    "audio/raw" -> PlaybackAudioFormat.PCM
    else -> PlaybackAudioFormat.UNKNOWN
}

/** A reader failure is authoritative even when the native player also rejects a track. */
internal fun playbackDiagnostic(readerProblem: PlaybackProblem?, native: PlaybackDiagnostic?): PlaybackDiagnostic? =
    if (readerProblem == PlaybackProblem.INTEGRITY || native?.problem == PlaybackProblem.INTEGRITY)
        PlaybackDiagnostic(PlaybackProblem.INTEGRITY) else readerProblem?.let { PlaybackDiagnostic(it) } ?: native

internal fun playbackProblem(status: ChurStatus): PlaybackProblem = when (status) {
    ChurStatus.OBJECT_CORRUPT, ChurStatus.VAULT_CORRUPT, ChurStatus.CATALOG_CORRUPT -> PlaybackProblem.INTEGRITY
    ChurStatus.UNSUPPORTED_VERSION, ChurStatus.UNSUPPORTED_SUITE -> PlaybackProblem.UNSUPPORTED
    else -> PlaybackProblem.UNAVAILABLE
}

internal fun playbackProblemMessage(problem: PlaybackProblem): String = when (problem) {
    PlaybackProblem.INTEGRITY -> "Playback stopped because this file failed its integrity check."
    PlaybackProblem.UNAVAILABLE -> "This file is not available for playback. Verify the library to check its status."
    PlaybackProblem.UNSUPPORTED -> "This device cannot play this file. You can export it to use a compatible player."
    PlaybackProblem.UNSUPPORTED_VIDEO -> "This device cannot decode the video track. You can export the original to use a compatible player."
    PlaybackProblem.UNSUPPORTED_AUDIO -> "This device cannot decode the audio track. You can export the original to use a compatible player."
    PlaybackProblem.UNSUPPORTED_CONTAINER -> "This device does not recognize this media container. You can export the original to use a compatible player."
    PlaybackProblem.INVALID_MEDIA -> "The media container is invalid or is not recognized by this device. This is not an integrity-check failure."
}

internal fun playbackDiagnosticMessage(diagnostic: PlaybackDiagnostic): String = when (diagnostic.problem) {
    PlaybackProblem.UNSUPPORTED_VIDEO -> "This device cannot decode the ${diagnostic.video?.label ?: "video"} track. You can export the original to use a compatible player."
    PlaybackProblem.UNSUPPORTED_AUDIO -> "This device cannot decode the ${diagnostic.audio?.label ?: "audio"} track. You can export the original to use a compatible player."
    else -> playbackProblemMessage(diagnostic.problem)
}

@Composable
internal fun PlaybackProblemPanel(
    problem: PlaybackProblem,
    onVerify: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    PlaybackProblemPanel(PlaybackDiagnostic(problem), onVerify, modifier)
}

@Composable
internal fun PlaybackProblemPanel(
    diagnostic: PlaybackDiagnostic,
    onVerify: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onExport: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onViewCopy: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(diagnostic.problem) {
        if (diagnostic.problem == PlaybackProblem.INTEGRITY) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }
    Column(
        modifier.background(ViewerColors.chromeScrim).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(playbackDiagnosticMessage(diagnostic), color = ViewerColors.content)
        if (onVerify != null) {
            TextButton(onClick = onVerify) { Text("Verify library", color = ViewerColors.content) }
        }
        if (onExport != null && diagnostic.canExportOriginal) {
            TextButton(onClick = onExport) { Text("Export original", color = ViewerColors.content) }
        }
        if (onRetry != null && diagnostic.canRetry) {
            TextButton(onClick = onRetry) { Text("Retry playback", color = ViewerColors.content) }
        }
        if (onViewCopy != null && diagnostic.canViewCopy) {
            TextButton(onClick = onViewCopy) { Text("View compatible copy", color = ViewerColors.content) }
        }
    }
}
