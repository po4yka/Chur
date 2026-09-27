package dev.po4yka.chur.app.vault

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.po4yka.chur.ffi.ChurBuffer

internal actual fun decodeThumbnail(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/**
 * `ImageDecoder` applies the EXIF orientation, which `BitmapFactory` does not,
 * and scales an oversized image down as it decodes rather than after.
 *
 * It reads the direct buffer in place, without a copy onto the heap, and
 * `decodeBitmap` is done with it when it returns. It throws a runtime exception
 * as well as an `IOException` for some malformed files, so every failure is
 * caught, as on iOS, and the viewer falls back to a derivative.
 */
internal actual fun decodeForDisplay(original: ChurBuffer, maxEdgePx: Int): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(original.buffer)) { decoder, info, _ ->
        val edge = maxOf(info.size.width, info.size.height)
        if (edge > maxEdgePx) {
            decoder.setTargetSize(
                (info.size.width * maxEdgePx / edge).coerceAtLeast(1),
                (info.size.height * maxEdgePx / edge).coerceAtLeast(1),
            )
        }
    }.asImageBitmap()
}.getOrNull()
