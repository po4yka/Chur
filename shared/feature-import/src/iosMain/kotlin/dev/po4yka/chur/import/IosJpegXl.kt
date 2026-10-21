@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.JpegXlDecoder
import dev.po4yka.chur.ffi.JpegXlFrame
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.UIKit.UIImage
import platform.posix.pread

/** Read a bounded prefix without changing the descriptor's shared file position. */
internal fun jpegXlSignature(descriptor: Int): Boolean {
    val prefix = ByteArray(12)
    return try {
        val length = prefix.usePinned { pread(descriptor, it.addressOf(0), prefix.size.toULong(), 0) }.toInt()
        (length >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0x0a.toByte()) ||
            (length == 12 && prefix.contentEquals(byteArrayOf(0, 0, 0, 12, 0x4a, 0x58, 0x4c, 0x20, 13, 10, 0x87.toByte(), 10)))
    } finally { prefix.fill(0) }
}

internal inline fun <T> withJpegXl(descriptor: Int, block: (JpegXlDecoder) -> T): T? = try {
    val decoder = JpegXlDecoder.open(descriptor)
    try { block(decoder) } finally { decoder.close() }
} catch (_: Exception) { null }

/** UIImage owns a copy of the decoded pixels; it never retains a frame's native allocation. */
internal fun jpegXlImage(frame: JpegXlFrame): UIImage? {
    val data = CFDataCreate(null, frame.pixels.pointer.reinterpret(), frame.pixels.capacityBytes.toLong()) ?: return null
    val provider = try { CGDataProviderCreateWithCFData(data) } finally { CFRelease(data) } ?: return null
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
    if (space == null) { CGDataProviderRelease(provider); return null }
    val image = try {
        CGImageCreate(
            frame.width.toULong(), frame.height.toULong(), 8u, 32u, (frame.width * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaLast.value or kCGBitmapByteOrder32Big,
            provider, null, false, CGColorRenderingIntent.kCGRenderingIntentDefault,
        )
    } finally { CGColorSpaceRelease(space); CGDataProviderRelease(provider) } ?: return null
    return try { UIImage.imageWithCGImage(image) } finally { CGImageRelease(image) }
}
