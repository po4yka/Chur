@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.po4yka.chur.app.notes

import androidx.compose.ui.graphics.ImageBitmap
import dev.po4yka.chur.app.vault.uprightThumbnail
import dev.po4yka.chur.app.vault.rasterOf
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImagePropertyPixelHeight
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageRelease
import platform.ImageIO.CGImageSourceCreateWithDataProvider

internal actual fun decodeNoteAttachmentImage(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return bytes.usePinned { pinned ->
        val provider = CGDataProviderCreateWithData(null, pinned.addressOf(0), bytes.size.toULong(), null) ?: return@usePinned null
        val source = CGImageSourceCreateWithDataProvider(provider, null)
        CGDataProviderRelease(provider)
        if (source == null) return@usePinned null
        val image = try {
            val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return@usePinned null
            val bounded = try {
                val width = attachmentImageDimension(properties, kCGImagePropertyPixelWidth)
                val height = attachmentImageDimension(properties, kCGImagePropertyPixelHeight)
                width != null && height != null && width in 1..16384 && height in 1..16384 && width * height <= 64_000_000L
            } finally { CFRelease(properties) }
            if (bounded) uprightThumbnail(source, 384) else null
        } finally { CFRelease(source) } ?: return@usePinned null
        try { rasterOf(image) } finally { CGImageRelease(image) }
    }
}

private fun attachmentImageDimension(properties: CFDictionaryRef, key: CFStringRef?): Long? = memScoped {
    val number = CFDictionaryGetValue(properties, key) ?: return@memScoped null
    if (CFGetTypeID(number) != CFNumberGetTypeID()) return@memScoped null
    val value = alloc<LongVar>()
    if (CFNumberGetValue(number.reinterpret(), kCFNumberSInt64Type, value.ptr)) value.value else null
}
