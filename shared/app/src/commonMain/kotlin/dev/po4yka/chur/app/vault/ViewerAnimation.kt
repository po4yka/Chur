package dev.po4yka.chur.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.LocalChurMotion
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val ANIMATION_BYTES_MAX = 32L * 1024 * 1024
internal const val ANIMATION_SOURCE_EDGE_MAX = 2_048
internal const val ANIMATION_RENDER_EDGE_MAX = 1_024

internal fun animationCandidate(projection: ObjectProjection): Boolean =
    projection.mediaKind == MEDIA_CLASS_IMAGE && projection.plaintextSize in 1..ANIMATION_BYTES_MAX &&
        projection.width in 1..ANIMATION_SOURCE_EDGE_MAX && projection.height in 1..ANIMATION_SOURCE_EDGE_MAX &&
        PresentedState.of(projection) != PresentedState.QUARANTINED

internal fun animationContentType(contentType: String?): Boolean =
    contentType?.substringBefore(';')?.trim()?.lowercase() in setOf("image/gif", "image/webp", "image/png", "image/apng")

/** Only the settled viewer page calls this. The source never enters a heap array or a file. */
@Composable
fun rememberViewerAnimation(
    vault: VaultRepository,
    projection: ObjectProjection,
    generation: Long,
    contentType: String? = null,
): (@Composable (Modifier) -> Unit)? {
    val owner = remember(vault, projection.id, generation, contentType) { ViewerAnimationOwner() }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(owner) {
        if (!animationCandidate(projection) || !animationContentType(contentType)) return@LaunchedEffect
        var original: ChurBuffer? = null
        try {
            withContext(Dispatchers.Default) {
                val reader = vault.leaseReader(projection.objectId)
                try {
                    val info = vault.readerContentInfo(reader)
                    if (!info.complete || info.plaintextSize !in 1..ANIMATION_BYTES_MAX) return@withContext
                    currentCoroutineContext().ensureActive()
                    val buffer = ChurBuffer(info.plaintextSize.toInt())
                    // Assign before any cancellable return to retain ownership on cancellation.
                    original = buffer
                    vault.readLeasedInto(reader, buffer)
                } finally {
                    vault.releaseReader(reader)
                }
            }
            currentCoroutineContext().ensureActive()
            if (owner.closed || (vault.state.value as? VaultState.Unlocked)?.generation != generation) return@LaunchedEffect
            val buffer = original ?: return@LaunchedEffect
            owner.decoder = openViewerAnimation(buffer)
            if (owner.decoder != null) vault.state.first { (it as? VaultState.Unlocked)?.generation != generation }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The still remains available for unsupported, incomplete, or corrupt animations.
        } finally {
            owner.close()
            // No native image, view, or decoder may still refer to these bytes.
            withContext(NonCancellable + Dispatchers.Default) {
                original?.let { it.clear(); it.release() }
                original = null
            }
        }
    }
    val decoder = owner.decoder ?: return null
    return { modifier -> ViewerAnimationContent(decoder, modifier) }
}

private class ViewerAnimationOwner {
    var decoder by mutableStateOf<ViewerAnimationDecoder?>(null)
    var closed = false
    fun close() {
        if (closed) return
        closed = true
        decoder?.close()
        decoder = null
    }
}

@Composable
private fun ViewerAnimationContent(decoder: ViewerAnimationDecoder, modifier: Modifier) {
    val reducedMotion = LocalChurMotion.current.reducedMotion
    var playing by remember(decoder) { mutableStateOf(!reducedMotion) }
    LaunchedEffect(reducedMotion) { if (reducedMotion) playing = false }
    Box(Modifier.fillMaxSize()) {
        decoder.Content(modifier.fillMaxSize(), playing)
        TextButton(
            onClick = { playing = !playing },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
                .background(ViewerColors.chromeScrim, CircleShape),
            colors = ButtonDefaults.textButtonColors(contentColor = ViewerColors.content),
        ) { Text(if (playing) "Pause animation" else "Play animation") }
    }
}

internal interface ViewerAnimationDecoder {
    @Composable fun Content(modifier: Modifier, playing: Boolean)
    /** Called on the UI thread before the original buffer is cleared. Idempotent. */
    fun close()
}

internal expect fun openViewerAnimation(original: ChurBuffer): ViewerAnimationDecoder?
