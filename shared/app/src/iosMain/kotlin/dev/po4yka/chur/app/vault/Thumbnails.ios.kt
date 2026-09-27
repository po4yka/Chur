package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.po4yka.chur.ffi.ChurBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithDataProvider
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

internal actual fun decodeThumbnail(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/**
 * ImageIO reads the native buffer in place through a data provider, so the
 * original never becomes a Swift `Data` or a Kotlin `ByteArray`, `IOS.md` §31.
 * Skia's own decoder takes only a `ByteArray`. ImageIO also applies the EXIF
 * orientation and scales an oversized image down as it decodes, as
 * `ImageDecoder` does on Android, and it reads every format the import accepts,
 * HEIC among them.
 *
 * The upright image is drawn into the pixels of a Skia bitmap, which Compose
 * then shows, and every ImageIO and Core Graphics object is released before
 * this returns, so nothing refers to the buffer once the caller clears it,
 * `PLAINTEXT_LIFECYCLE.md` §9.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeForDisplay(original: ChurBuffer, maxEdgePx: Int): ImageBitmap? {
    val provider = CGDataProviderCreateWithData(
        null,
        original.pointer,
        original.capacityBytes.toULong(),
        null,
    ) ?: return null
    val source = CGImageSourceCreateWithDataProvider(provider, null)
    CGDataProviderRelease(provider)
    if (source == null) return null
    val upright = try {
        uprightThumbnail(source, maxEdgePx)
    } finally {
        CFRelease(source)
    } ?: return null
    return try {
        rasterOf(upright)
    } finally {
        CGImageRelease(upright)
    }
}

/**
 * The whole image, turned upright and no larger than [maxEdgePx], decoded now.
 *
 * "From image always" keeps ImageIO from returning the small thumbnail a camera
 * embeds in the file, and "cache immediately" decodes before this returns
 * rather than when the image is first drawn.
 */
@OptIn(ExperimentalForeignApi::class)
private fun uprightThumbnail(source: CGImageSourceRef, maxEdgePx: Int): CGImageRef? = memScoped {
    val edge = alloc<IntVar> { value = maxEdgePx }
    val maxPixelSize = CFNumberCreate(null, kCFNumberIntType, edge.ptr)
    val options = CFDictionaryCreateMutable(
        null,
        4,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
    CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
    CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maxPixelSize)
    CFRelease(maxPixelSize)
    try {
        CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
    } finally {
        CFRelease(options)
    }
}

/** Draws [image] into a new Skia bitmap of the same size, RGBA premultiplied. */
@OptIn(ExperimentalForeignApi::class)
private fun rasterOf(image: CGImageRef): ImageBitmap? {
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    val bitmap = Bitmap()
    val pixels = if (bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) {
        bitmap.peekPixels()
    } else {
        null
    }
    if (pixels == null) {
        bitmap.close()
        return null
    }
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
    val context = CGBitmapContextCreate(
        interpretCPointer<ByteVar>(pixels.addr),
        width.toULong(),
        height.toULong(),
        8u,
        bitmap.rowBytes.toULong(),
        space,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
    )
    CGColorSpaceRelease(space)
    pixels.close()
    if (context == null) {
        bitmap.close()
        return null
    }
    CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
    CGContextRelease(context)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
