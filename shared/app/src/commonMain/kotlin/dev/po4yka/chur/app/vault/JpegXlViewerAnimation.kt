package dev.po4yka.chur.app.vault

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.JpegXlDecoder
import dev.po4yka.chur.ffi.JpegXlFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Copies the frame's straight-alpha sRGB pixels before the native frame is cleared. */
internal expect fun jpegXlFrameBitmap(frame: JpegXlFrame): ImageBitmap?

/** Owns one bounded decoder; the UI thread serializes frame decode and disposal. */
internal fun openJpegXlViewerAnimation(original: ChurBuffer): ViewerAnimationDecoder? {
    val decoder = try { JpegXlDecoder.open(original) } catch (_: Exception) { return null }
    try {
        if (decoder.frameCount !in 2..1_000 || decoder.width !in 1..ANIMATION_SOURCE_EDGE_MAX ||
            decoder.height !in 1..ANIMATION_SOURCE_EDGE_MAX) {
            decoder.close()
            return null
        }
        return JpegXlViewerAnimation(decoder)
    } catch (_: Exception) {
        decoder.close()
        return null
    }
}

internal class JpegXlViewerAnimation(private var decoder: JpegXlDecoder?) : ViewerAnimationDecoder {
    internal var current by mutableStateOf<ImageBitmap?>(null)
        private set
    internal var frameIndex = 0
        private set
    internal var completedLoops = 0
        private set
    internal var frameDurationMs = 100L
        private set
    private var finished = false

    init { check(loadFrame(0)) { "JPEG XL frame is unavailable" } }

    private fun loadFrame(index: Int): Boolean {
        val active = decoder ?: return false
        val frame = active.renderFrame(index, ANIMATION_RENDER_EDGE_MAX)
        try {
            val image = jpegXlFrameBitmap(frame) ?: return false
            current = image
            frameIndex = index
            frameDurationMs = frame.durationMs.toLong().coerceIn(20, 60_000)
            return true
        } finally { frame.close() }
    }

    /** The same transition drives playback and native lifecycle tests. */
    internal fun advanceFrame(): Boolean {
        val active = decoder ?: return false
        if (finished) return false
        val next = frameIndex + 1
        if (next < active.frameCount) return loadFrame(next)
        completedLoops += 1
        if (active.loopCount > 0 && completedLoops >= active.loopCount) {
            finished = true
            return false
        }
        return loadFrame(0)
    }

    internal fun restartPlayback(): Boolean {
        if (decoder == null) return false
        completedLoops = 0
        finished = false
        return loadFrame(0)
    }

    @Composable
    override fun Content(modifier: Modifier, playing: Boolean) {
        current?.let { Image(it, "Animated image", modifier, contentScale = ContentScale.Fit) }
        LaunchedEffect(this, playing) {
            if (!playing) return@LaunchedEffect
            try {
                if (finished && !restartPlayback()) return@LaunchedEffect
                while (decoder != null && !finished) {
                    delay(frameDurationMs)
                    if (!advanceFrame()) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed animation retains its last valid frame and the original.
                finished = true
            }
        }
    }

    override fun close() {
        current = null
        val active = decoder
        decoder = null
        finished = true
        active?.close()
    }
}
