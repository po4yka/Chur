package dev.po4yka.chur.imports

import android.graphics.Bitmap
import dev.po4yka.chur.ffi.JpegXlFrame
import java.nio.ByteBuffer

private val JXL_CONTAINER_SIGNATURE = byteArrayOf(0, 0, 0, 12, 0x4a, 0x58, 0x4c, 0x20, 0x0d, 0x0a, 0x87.toByte(), 0x0a)

/** JPEG XL codestream and container signatures. Provider MIME is only a hint. */
fun jpegXlSignature(bytes: ByteArray, length: Int = bytes.size): Boolean =
    length in 2..bytes.size && bytes[0] == 0xff.toByte() && bytes[1] == 0x0a.toByte() ||
        length in 12..bytes.size && JXL_CONTAINER_SIGNATURE.indices.all { bytes[it] == JXL_CONTAINER_SIGNATURE[it] }

/** Checks a direct source without copying encoded plaintext onto the heap. */
fun jpegXlSignature(bytes: ByteBuffer): Boolean =
    bytes.limit() >= 2 && bytes.get(0) == 0xff.toByte() && bytes.get(1) == 0x0a.toByte() ||
        bytes.limit() >= 12 && JXL_CONTAINER_SIGNATURE.indices.all { bytes.get(it) == JXL_CONTAINER_SIGNATURE[it] }

/** Transfers owned RGBA8 sRGB pixels into a software bitmap. The caller closes the frame. */
fun jpegXlBitmap(frame: JpegXlFrame): Bitmap {
    val pixels = frame.pixels.buffer.duplicate()
    pixels.position(0)
    // Android's raster uses premultiplied RGBA. The decoder returns straight
    // alpha; transform the owned frame before its final wipe.
    for (offset in 0 until frame.width * frame.height * 4 step 4) {
        val alpha = pixels.get(offset + 3).toInt() and 255
        if (alpha != 255) for (channel in 0..2) {
            val value = pixels.get(offset + channel).toInt() and 255
            pixels.put(offset + channel, ((value * alpha + 127) / 255).toByte())
        }
    }
    val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.copyPixelsFromBuffer(pixels)
        return bitmap
    } catch (failure: Throwable) {
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        bitmap.recycle()
        throw failure
    }
}
