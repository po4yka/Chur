package dev.po4yka.chur.app.notes

import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

internal actual fun decodeNoteAttachmentImage(bytes: ByteArray): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        require(info.size.width in 1..16384 && info.size.height in 1..16384 &&
            info.size.width.toLong() * info.size.height <= 64_000_000L)
        val edge = maxOf(info.size.width, info.size.height)
        if (edge > 384) decoder.setTargetSize(
            (info.size.width.toLong() * 384 / edge).toInt().coerceAtLeast(1),
            (info.size.height.toLong() * 384 / edge).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
}.getOrNull()
