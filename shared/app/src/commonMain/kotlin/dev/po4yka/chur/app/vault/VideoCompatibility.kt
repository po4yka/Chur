package dev.po4yka.chur.app.vault

import androidx.compose.runtime.Composable
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The existing derivative boundary also bounds a complete viewing copy. */
const val MAX_COMPATIBLE_VIDEO_BYTES: Int = 32 * 1024 * 1024

/** A complete native MP4. The caller wipes [bytes] after encryption. */
data class CompatibleVideoBytes(val bytes: ByteArray, val width: Int, val height: Int, val durationMs: Long)

interface VideoCompatibilityEncoder {
    /** Decode from authenticated ranges; never write a plaintext output file. */
    suspend fun encode(
        source: PlaybackSource,
        cancelRequested: () -> Boolean,
        onProgress: (Double) -> Unit,
    ): CompatibleVideoBytes
}

@Composable
expect fun rememberVideoCompatibilityEncoder(): VideoCompatibilityEncoder

/** An explicit operation. Neither import nor playback starts a transcode. */
suspend fun createCompatibleVideoCopy(
    source: PlaybackSource,
    encoder: VideoCompatibilityEncoder,
    cancelRequested: () -> Boolean = { false },
    onProgress: (Double) -> Unit = {},
): PlaybackSource {
    require(source.kind == StreamKind.ORIGINAL && source.offset == 0L && source.length == source.plaintextSize)
    require(source.contentType.startsWith("video/"))
    val generation = (source.vault.state.value as? VaultState.Unlocked)?.generation
        ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the video session has ended")
    val output = encoder.encode(source, cancelRequested, onProgress)
    try {
        if (cancelRequested()) throw CancellationException("Viewing copy cancelled")
        if (output.bytes.isEmpty() || output.bytes.size > MAX_COMPATIBLE_VIDEO_BYTES ||
            output.width !in 1..1280 || output.height !in 1..1280 || minOf(output.width, output.height) > 720 ||
            output.durationMs !in 1L..14_400_000L) {
            throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "the viewing copy exceeds its bounds")
        }
        withContext(Dispatchers.Default) {
            if (cancelRequested()) throw CancellationException("Viewing copy cancelled")
            source.vault.putDerived(source.objectId, StreamKind.COMPATIBLE_VIDEO, output.width, output.height,
                output.bytes, expectedGeneration = generation)
        }
    } finally { output.bytes.fill(0) }
    return compatibleVideoSource(source, generation)
}

/** A saved copy is played through the same authenticated range reader. */
suspend fun compatibleVideoSource(original: PlaybackSource, generation: Long? = null): PlaybackSource {
    val reader = original.vault.leaseReader(original.objectId, StreamKind.COMPATIBLE_VIDEO, generation)
    val info = try { original.vault.readerContentInfo(reader) } finally { original.vault.releaseReader(reader) }
    check(info.complete && info.byteRangeSupported && info.contentType == "video/mp4")
    return PlaybackSource(original.vault, original.objectId, info.contentType, info.plaintextSize,
        kind = StreamKind.COMPATIBLE_VIDEO, onVerify = original.onVerify, onExport = original.onExport)
}
