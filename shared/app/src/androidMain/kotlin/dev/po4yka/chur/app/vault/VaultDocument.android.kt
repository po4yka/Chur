package dev.po4yka.chur.app.vault

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
actual fun VaultDocument(vault: VaultRepository, generation: Long, projection: ObjectProjection,
    detail: ObjectDetail?, modifier: Modifier, contentPadding: PaddingValues) {
    val state by vault.state.collectAsState()
    if ((state as? VaultState.Unlocked)?.generation != generation) return
    when {
        PresentedState.of(projection) == PresentedState.QUARANTINED -> DocumentFallback(projection, detail,
            "This file is quarantined. Check its integrity before opening a preview.", modifier, contentPadding)
        detail == null -> DocumentFallback(projection, detail, "Opening file details…", modifier, contentPadding)
        documentPreviewKind(detail.contentType) == DocumentPreviewKind.TEXT ->
            ReadOnlyTextDocument(vault, generation, projection, detail, modifier, contentPadding)
        documentPreviewKind(detail.contentType) == DocumentPreviewKind.PDF &&
            detail.plaintextSize in 1..DOCUMENT_PDF_PREVIEW_MAX_BYTES ->
            PdfDocument(vault, generation, projection, detail, modifier, contentPadding)
        documentPreviewKind(detail.contentType) == DocumentPreviewKind.PDF -> DocumentFallback(projection, detail,
            "This PDF is outside the 32 MiB preview limit. Its complete original can still be exported.", modifier, contentPadding)
        else -> DocumentFallback(projection, detail,
            "There is no internal preview for this file type.", modifier, contentPadding)
    }
}

@Composable
private fun PdfDocument(vault: VaultRepository, generation: Long, projection: ObjectProjection,
    detail: ObjectDetail, modifier: Modifier, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val owner = remember(vault, generation, projection.id) { AtomicReference<PdfPreviewDocument?>() }
    var document by remember(owner) { mutableStateOf<PdfPreviewDocument?>(null) }
    var message by remember(owner) { mutableStateOf<String?>(null) }
    var index by remember(owner) { mutableStateOf(0) }
    var bitmap by remember(owner) { mutableStateOf<Bitmap?>(null) }
    var zoom by remember(owner, index) { mutableStateOf(1f) }
    var offset by remember(owner, index) { mutableStateOf(Offset.Zero) }
    DisposableEffect(owner) { onDispose { owner.get()?.invalidate() } }
    LaunchedEffect(owner) {
        try {
            withContext(Dispatchers.Default) {
                val reader = vault.leaseReader(projection.objectId)
                try {
                    val info = vault.readerContentInfo(reader)
                    check(info.complete && info.plaintextSize in 1..DOCUMENT_PDF_PREVIEW_MAX_BYTES)
                    owner.set(PdfPreviewDocument.open(context, info.plaintextSize,
                        read = { position, length -> vault.readLeased(reader, position, length) },
                        canRead = { (vault.state.value as? VaultState.Unlocked)?.generation == generation },
                        onRelease = { vault.releaseReader(reader) }))
                } catch (failure: Exception) { vault.releaseReader(reader); throw failure }
            }
            document = owner.get()
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SecurityException) { message = "This PDF needs a password or uses unsupported security. Export it to open it elsewhere."
        } catch (_: Exception) { message = "This PDF preview is unavailable or could not be verified. Its original remains in the vault."
        } finally {
            val opened = owner.getAndSet(null)
            opened?.invalidate()
            withContext(NonCancellable + Dispatchers.Default) { runCatching { opened?.close() } }
            document = null
        }
    }
    LaunchedEffect(document, index) {
        bitmap = null
        val opened = document ?: return@LaunchedEffect
        var rendered: Bitmap? = null
        try {
            withContext(Dispatchers.Default) { rendered = opened.render(index) }
            bitmap = rendered
            rendered = null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { message = "This page could not be rendered or verified. The original can still be exported."
        } finally { rendered?.let { it.eraseColor(android.graphics.Color.TRANSPARENT); it.recycle() } }
    }
    val current = bitmap
    DisposableEffect(current) { onDispose { current?.let { it.eraseColor(android.graphics.Color.TRANSPARENT); it.recycle() } } }
    val opened = document
    if (message != null || opened == null) {
        DocumentFallback(projection, detail, message ?: "Opening PDF preview…", modifier, contentPadding)
        return
    }
    Column(modifier.fillMaxSize().padding(contentPadding).padding(horizontal = ChurSpacing.two)) {
        Text("Page ${index + 1} of ${opened.pageCount}", color = ViewerColors.content)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = index > 0, onClick = { index-- }) { Text("Previous", color = ViewerColors.content) }
            TextButton(enabled = index + 1 < opened.pageCount, onClick = { index++ }) { Text("Next", color = ViewerColors.content) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(4f) }) { Text("Zoom", color = ViewerColors.content) }
            TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("Fit", color = ViewerColors.content) }
        }
        val transform = rememberTransformableState { factor, pan, _ ->
            zoom = (zoom * factor).coerceIn(1f, 4f)
            offset = if (zoom == 1f) Offset.Zero else offset + pan
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().transformable(transform)) {
            current?.let {
                Image(it.asImageBitmap(), "PDF page ${index + 1}", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                    })
            }
        }
    }
}
