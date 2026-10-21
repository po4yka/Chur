@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.CoreFoundation.*
import platform.CoreMedia.*
import platform.Foundation.CFBridgingRetain

/** Asset-level rejection alone does not identify the failed media track. */
internal fun iosAssetUnsupportedDiagnostic(asset: AVAsset, declared: PlaybackContainerFacts? = null): PlaybackDiagnostic? {
    val video = asset.tracksWithMediaType(AVMediaTypeVideo).filterIsInstance<AVAssetTrack>()
    val complete = declared?.takeIf { it.complete }
    if ((video.isNotEmpty() || !complete?.video.isNullOrEmpty()) && video.none { it.decodable }) {
        return PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_VIDEO,
            video = iosAssetVideoDetails(asset) ?: complete?.video?.firstOrNull())
    }
    val audio = asset.tracksWithMediaType(AVMediaTypeAudio).filterIsInstance<AVAssetTrack>()
    // AVFoundation can omit a declared but unsupported MP4 audio track entirely.
    // Authenticated container declarations establish the missing group; they do
    // not override a decodable native alternate track in that group.
    if ((audio.isNotEmpty() || !complete?.audio.isNullOrEmpty()) && audio.none { it.decodable }) {
        return PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED_AUDIO,
            audio = iosAssetAudioFormat(asset) ?: complete?.audio?.firstOrNull())
    }
    return if (!asset.playable) PlaybackDiagnostic(PlaybackProblem.UNSUPPORTED) else null
}

/** Read only allowlisted, bounded format facts; never return native descriptions or source URLs. */
internal fun iosAssetVideoDetails(asset: AVAsset): PlaybackVideoDetails? {
    val retained = retainFormatDescription(asset, AVMediaTypeVideo) ?: return null
    try {
        val format: CMVideoFormatDescriptionRef = retained.reinterpret()
        val dimensions = CMVideoFormatDescriptionGetDimensions(format).useContents { width to height }
        val configuration = boundedHevcConfiguration(format)
        try { return iosPlaybackVideoDetails(CMFormatDescriptionGetMediaSubType(format), configuration, dimensions.first, dimensions.second) }
        finally { configuration?.fill(0) }
    } finally { CFRelease(retained) }
}

internal fun iosAssetAudioFormat(asset: AVAsset): PlaybackAudioFormat? {
    val retained = retainFormatDescription(asset, AVMediaTypeAudio) ?: return null
    try {
        val description: CMAudioFormatDescriptionRef = retained.reinterpret()
        val subtype = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointed?.mFormatID
            ?: CMFormatDescriptionGetMediaSubType(description)
        return when (subtype) {
            // mp4a is a sample-entry family and alone is not proof of AAC.
            0x61616320u, 0x61616368u, 0x6161636cu, 0x61616365u, 0x61616366u, 0x61616367u, 0x61616370u, 0x61616373u -> PlaybackAudioFormat.AAC
            0x61632d33u -> PlaybackAudioFormat.AC3
            0x65632d33u -> PlaybackAudioFormat.EAC3
            0x64747363u, 0x64747368u, 0x6474736cu, 0x64747365u -> PlaybackAudioFormat.DTS
            0x4d4c5041u -> PlaybackAudioFormat.TRUEHD
            0x664c6143u, 0x666c6163u -> PlaybackAudioFormat.FLAC
            0x4f707573u, 0x6f707573u -> PlaybackAudioFormat.OPUS
            0x6c70636du, 0x736f7774u, 0x74776f73u -> PlaybackAudioFormat.PCM
            else -> PlaybackAudioFormat.UNKNOWN
        }
    } finally { CFRelease(retained) }
}

private fun retainFormatDescription(asset: AVAsset, mediaType: String?): CFTypeRef? {
    val type = mediaType ?: return null
    val track = asset.tracksWithMediaType(type).firstOrNull() as? AVAssetTrack ?: return null
    val description = track.formatDescriptions.firstOrNull() ?: return null
    return CFBridgingRetain(description)
}

private fun boundedHevcConfiguration(format: CMVideoFormatDescriptionRef): ByteArray? {
    val atoms = CMFormatDescriptionGetExtension(format, kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms) ?: return null
    if (CFGetTypeID(atoms) != CFDictionaryGetTypeID()) return null
    val key = CFBridgingRetain("hvcC") ?: return null
    val value = try { CFDictionaryGetValue(atoms.reinterpret(), key) } finally { CFRelease(key) } ?: return null
    if (CFGetTypeID(value) != CFDataGetTypeID()) return null
    val data: CFDataRef = value.reinterpret()
    val length = CFDataGetLength(data)
    if (length !in 23L..4096L) return null
    return CFDataGetBytePtr(data)?.readBytes(length.toInt())
}
