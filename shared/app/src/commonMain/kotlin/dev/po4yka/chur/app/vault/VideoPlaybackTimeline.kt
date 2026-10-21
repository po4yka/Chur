package dev.po4yka.chur.app.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** Always pass the full original, including when a viewing copy is selected. */
@Composable
internal fun rememberVideoPlaybackTimeline(source: PlaybackSource): VideoPlaybackTimeline {
    val state by source.vault.state.collectAsState()
    val generation = (state as? VaultState.Unlocked)?.generation
    val scope = rememberCoroutineScope()
    val generator = rememberVideoTimelineGenerator()
    val timeline = remember(source.vault, source.objectId.toList(), source.kind, source.offset, source.length, generation) {
        VideoPlaybackTimeline(source, generation, generator, scope)
    }
    LaunchedEffect(timeline) { timeline.loadPosition() }
    DisposableEffect(timeline) { onDispose { timeline.close() } }
    return timeline
}

/** One generation owns its resume checkpoint, decoded atlas, and pending work. */
internal class VideoPlaybackTimeline(
    private val source: PlaybackSource,
    private val generation: Long?,
    private val generator: VideoTimelineGenerator,
    parentScope: CoroutineScope,
) {
    val enabled = generation != null && source.kind == StreamKind.ORIGINAL && source.offset == 0L &&
        source.length == source.plaintextSize && source.contentType.startsWith("video/")
    var initialPositionMs: Long? by mutableStateOf(if (enabled) null else 0L)
        private set
    internal var atlas: ImageBitmap? by mutableStateOf(null)
        private set
    internal var previewActive by mutableStateOf(false)
        private set
    internal var previewPositionMs by mutableStateOf(0L)
        private set
    internal var previewDurationMs by mutableStateOf(0L)
        private set
    internal var previewMessage by mutableStateOf("Loading preview…")
        private set
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val saveMutex = Mutex()
    private var disposed = false
    private var previewRequested = false
    private var pendingPosition: Pair<Long, Long>? = null
    private var savedPosition: Pair<Long, Long>? = null
    private var checkpoint = TimeSource.Monotonic.markNow()

    private fun current() = !disposed && enabled &&
        (source.vault.state.value as? VaultState.Unlocked)?.generation == generation

    internal suspend fun loadPosition() {
        if (!current()) return
        val position = try { withContext(Dispatchers.Default) { source.vault.playbackPosition(source.objectId, requireNotNull(generation)) } }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { 0L }
        if (current()) initialPositionMs = position.coerceAtLeast(0L)
    }

    /** Keep the latest position when the wrapper replaces its native player. */
    fun resumePositionMs(): Long? = if (current()) pendingPosition?.first ?: initialPositionMs else null

    /** The player calls this only after its initial seek has completed. */
    fun sample(positionMs: Long, durationMs: Long) {
        if (!current() || initialPositionMs == null || durationMs !in 1L..14_400_000L) return
        pendingPosition = positionMs.coerceIn(0L, durationMs) to durationMs
        if (checkpoint.elapsedNow().inWholeMilliseconds >= 5_000) flush()
    }

    /** Pause and explicit player teardown can request an immediate checkpoint. */
    fun flush() {
        if (!current() || pendingPosition == null) return
        checkpoint = TimeSource.Monotonic.markNow()
        scope.launch {
            saveMutex.withLock {
                val value = pendingPosition ?: return@withLock
                if (!current() || savedPosition == value) return@withLock
                try {
                    withContext(Dispatchers.Default) {
                        source.vault.savePlaybackPosition(source.objectId, videoResumePosition(value.first, value.second), value.second, requireNotNull(generation))
                    }
                    if (current()) savedPosition = value
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { /* Playback remains usable when a checkpoint cannot be saved. */ }
            }
        }
    }

    fun scrub(positionMs: Long, durationMs: Long, active: Boolean) {
        previewActive = active && current() && durationMs > 0
        if (!previewActive) return
        previewDurationMs = durationMs
        previewPositionMs = positionMs.coerceIn(0L, durationMs)
        if (previewRequested) return
        previewRequested = true
        scope.launch {
            try {
                val bytes = try { withContext(Dispatchers.Default) { source.vault.readDerived(source.objectId, StreamKind.VIDEO_STORYBOARD, generation) } }
                catch (failure: ChurFailure) {
                    if (failure.status != ChurStatus.NOT_FOUND) throw failure
                    if (!current()) return@launch
                    createVideoTimeline(source, generator, cancelRequested = { !current() })
                }
                try {
                    if (!current()) return@launch
                    require(bytes.size <= MAX_VIDEO_TIMELINE_BYTES && timelineJpegHasExpectedSize(bytes))
                    val image = withContext(Dispatchers.Default) { decodeThumbnail(bytes) }
                    require(image != null && image.width == 640 && image.height == 270)
                    if (current()) atlas = image
                } finally { bytes.fill(0) }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { if (current()) previewMessage = "Preview is not available on this device." }
        }
    }

    internal fun close() {
        disposed = true
        lifetime.cancel()
        atlas = null
        previewActive = false
        initialPositionMs = null
        pendingPosition = null
        savedPosition = null
    }
}

/** Apply after the native player has loaded its actual duration. */
internal fun videoResumePosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs <= 0 || positionMs >= (durationMs - 2_000L).coerceAtLeast(0L)) 0L
    else positionMs.coerceIn(0L, durationMs)

@Composable
internal fun TimelinePreview(timeline: VideoPlaybackTimeline, modifier: Modifier = Modifier) {
    if (!timeline.previewActive) return
    Column(modifier.testTag("video-seek-preview").semantics { contentDescription = "Seek preview" }
        .background(ViewerColors.chromeScrim).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val image = timeline.atlas
        if (image != null) {
            val slot = videoTimelineSlot(timeline.previewPositionMs, timeline.previewDurationMs)
            Canvas(Modifier.size(160.dp, 90.dp).testTag("video-seek-preview-image")) {
                drawImage(image, srcOffset = IntOffset(slot % 4 * 160, slot / 4 * 90), srcSize = IntSize(160, 90),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
        } else Text(timeline.previewMessage, color = ViewerColors.content)
        val seconds = timeline.previewPositionMs / 1_000L
        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = ViewerColors.content)
    }
}

/** Reject an oversized or malformed JPEG before the platform allocates a bitmap. */
internal fun timelineJpegHasExpectedSize(bytes: ByteArray): Boolean {
    fun byte(index: Int) = bytes[index].toInt() and 255
    if (bytes.size < 4 || byte(0) != 255 || byte(1) != 216) return false
    var offset = 2
    while (offset + 3 < bytes.size) {
        if (byte(offset++) != 255) return false
        while (offset < bytes.size && byte(offset) == 255) offset++
        if (offset >= bytes.size) return false
        val marker = byte(offset++)
        if (marker == 218 || marker == 217 || marker == 0) return false
        if (marker == 1 || marker in 208..215) continue
        if (offset + 1 >= bytes.size) return false
        val length = byte(offset) * 256 + byte(offset + 1)
        if (length < 2 || offset + length > bytes.size) return false
        if (marker in listOf(192, 193, 194)) {
            return length >= 8 && byte(offset + 2) == 8 && byte(offset + 3) * 256 + byte(offset + 4) == 270 &&
                byte(offset + 5) * 256 + byte(offset + 6) == 640
        }
        offset += length
    }
    return false
}
