package dev.po4yka.chur.app.vault

import androidx.compose.runtime.Composable
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val VIDEO_TIMELINE_COLUMNS = 4
const val VIDEO_TIMELINE_ROWS = 3
const val VIDEO_TIMELINE_CELL_WIDTH = 160
const val VIDEO_TIMELINE_CELL_HEIGHT = 90
const val MAX_VIDEO_TIMELINE_BYTES = 2 * 1024 * 1024

data class VideoTimelineBytes(val bytes: ByteArray, val width: Int, val height: Int)

interface VideoTimelineGenerator {
    suspend fun generate(source: PlaybackSource, cancelRequested: () -> Boolean): VideoTimelineBytes
}

@Composable
expect fun rememberVideoTimelineGenerator(): VideoTimelineGenerator

/** Slot centers are part of the generator profile, not provider metadata. */
fun videoTimelineSlot(positionMs: Long, durationMs: Long): Int =
    if (durationMs <= 0) 0 else ((positionMs.coerceIn(0, durationMs) * 12) / durationMs).toInt().coerceIn(0, 11)

suspend fun createVideoTimeline(source: PlaybackSource, generator: VideoTimelineGenerator,
    cancelRequested: () -> Boolean = { false }): ByteArray {
    require(source.kind == StreamKind.ORIGINAL && source.offset == 0L && source.length == source.plaintextSize)
    val generation = (source.vault.state.value as? VaultState.Unlocked)?.generation
        ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the video session has ended")
    val output = generator.generate(source, cancelRequested)
    try {
        if (cancelRequested()) throw CancellationException("Timeline preview cancelled")
        if (output.bytes.isEmpty() || output.bytes.size > MAX_VIDEO_TIMELINE_BYTES || output.width != 640 || output.height != 270) {
            throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "the timeline preview exceeds its bounds")
        }
        withContext(Dispatchers.Default) {
            if (cancelRequested()) throw CancellationException("Timeline preview cancelled")
            source.vault.putDerived(source.objectId, StreamKind.VIDEO_STORYBOARD, output.width, output.height,
                output.bytes, expectedGeneration = generation)
        }
        return output.bytes.copyOf()
    } finally { output.bytes.fill(0) }
}
