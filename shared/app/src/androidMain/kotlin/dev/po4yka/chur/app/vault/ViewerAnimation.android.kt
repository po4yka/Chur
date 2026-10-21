package dev.po4yka.chur.app.vault

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.imports.jpegXlSignature

/** Android's native animated decoder supports GIF and WebP; APNG keeps its still fallback. */
internal actual fun openViewerAnimation(original: ChurBuffer): ViewerAnimationDecoder? {
    if (jpegXlSignature(original.buffer)) return openJpegXlViewerAnimation(original)
    return runCatching {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(original.buffer.asReadOnlyBuffer())) { decoder, info, _ ->
            require(info.isAnimated && info.mimeType in setOf("image/gif", "image/webp"))
            val width = info.size.width
            val height = info.size.height
            require(width in 1..ANIMATION_SOURCE_EDGE_MAX && height in 1..ANIMATION_SOURCE_EDGE_MAX)
            val edge = maxOf(width, height)
            if (edge > ANIMATION_RENDER_EDGE_MAX) decoder.setTargetSize(
                (width * ANIMATION_RENDER_EDGE_MAX / edge).coerceAtLeast(1),
                (height * ANIMATION_RENDER_EDGE_MAX / edge).coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        } as? AnimatedImageDrawable ?: return@runCatching null
        AndroidViewerAnimation(drawable)
    }.getOrNull()
}

internal class AndroidViewerAnimation(internal var drawable: AnimatedImageDrawable?) : ViewerAnimationDecoder {
    private var view: ImageView? = null

    @Composable
    override fun Content(modifier: Modifier, playing: Boolean) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ImageView(context).apply {
                    // A software canvas decodes synchronously on the UI thread.
                    // RenderThread decoding could still read the native source after stop().
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Animated image"
                    setImageDrawable(drawable)
                    view = this
                }
            },
            update = { image ->
                image.setImageDrawable(drawable)
                if (playing) drawable?.start() else drawable?.stop()
            },
        )
    }

    override fun close() {
        drawable?.stop()
        view?.setImageDrawable(null)
        view = null
        drawable?.callback = null
        drawable = null
    }
}
