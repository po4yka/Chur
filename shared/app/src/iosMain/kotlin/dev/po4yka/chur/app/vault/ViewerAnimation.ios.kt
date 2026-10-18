@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.po4yka.chur.ffi.ChurBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryGetTypeID
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.kCGImagePropertyGIFDictionary
import platform.ImageIO.kCGImagePropertyPNGDictionary
import platform.ImageIO.kCGImagePropertyWebPDictionary
import platform.ImageIO.kCGImagePropertyGIFLoopCount
import platform.ImageIO.kCGImagePropertyGIFCanvasPixelWidth
import platform.ImageIO.kCGImagePropertyGIFCanvasPixelHeight
import platform.ImageIO.kCGImagePropertyGIFDelayTime
import platform.ImageIO.kCGImagePropertyGIFUnclampedDelayTime
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.CGImageSourceCopyProperties
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithDataProvider
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.CGImageSourceRemoveCacheAtIndex
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIImage
import platform.UIKit.accessibilityLabel
import platform.UIKit.isAccessibilityElement
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode.UIViewContentModeScaleAspectFit

internal actual fun openViewerAnimation(original: ChurBuffer): ViewerAnimationDecoder? {
    val provider = CGDataProviderCreateWithData(null, original.pointer, original.capacityBytes.toULong(), null) ?: return null
    val options = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    if (options == null) { CGDataProviderRelease(provider); return null }
    val source = try {
        CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
        CGImageSourceCreateWithDataProvider(provider, options)
    } finally { CFRelease(options); CGDataProviderRelease(provider) } ?: return null
    try {
        val type = CGImageSourceGetType(source)?.let(::animationTypeString)
        if (type !in setOf("com.compuserve.gif", "public.png", "org.webmproject.webp")) return null
        val count = CGImageSourceGetCount(source).toInt()
        if (count !in 2..1_000) return null
        val metadata = CGImageSourceCopyProperties(source, null)
        val rawLoop = if (metadata != null) try {
            val root = animationProperties(metadata)
            val canvasWidth = animationNumber(root, kCGImagePropertyGIFCanvasPixelWidth)
                ?: animationNumber(metadata, kCGImagePropertyPixelWidth)
            val canvasHeight = animationNumber(root, kCGImagePropertyGIFCanvasPixelHeight)
                ?: animationNumber(metadata, kCGImagePropertyPixelHeight)
            if (canvasWidth != null && canvasWidth !in 1.0..ANIMATION_SOURCE_EDGE_MAX.toDouble()) return null
            if (canvasHeight != null && canvasHeight !in 1.0..ANIMATION_SOURCE_EDGE_MAX.toDouble()) return null
            animationNumber(root, kCGImagePropertyGIFLoopCount)?.toInt()
        } finally { CFRelease(metadata) } else null
        val loops = when {
            rawLoop == null || rawLoop < 0 -> 1
            rawLoop == 0 -> 0
            type == "com.compuserve.gif" -> rawLoop.coerceAtMost(Int.MAX_VALUE - 1) + 1
            else -> rawLoop.coerceAtLeast(1)
        }
        // Decode the first frame before transferring ownership to the UI.
        val first = animationFrame(source, 0) ?: return null
        return IosViewerAnimation(source, count, loops, first).also { CFRetain(source) }
    } finally { CFRelease(source) }
}

/** ImageIO composites GIF/APNG/WebP frames, including disposal, without a retained frame array. */
private class IosViewerAnimation(
    private var source: CGImageSourceRef?,
    private val count: Int,
    private val loops: Int,
    private var current: UIImage?,
) : ViewerAnimationDecoder {
    private var view: UIImageView? = null
    private var index = 0
    private var completedLoops = 0

    @Composable
    override fun Content(modifier: Modifier, playing: Boolean) {
        UIKitView(
            modifier = modifier,
            factory = {
                UIImageView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).also {
                    it.contentMode = UIViewContentModeScaleAspectFit
                    it.isAccessibilityElement = true
                    it.accessibilityLabel = "Animated image"
                    it.image = current
                    view = it
                }
            },
            update = { it.image = current },
        )
        LaunchedEffect(this, playing) {
            if (!playing) return@LaunchedEffect
            if (loops > 0 && completedLoops >= loops) {
                completedLoops = 0
                index = 0
                source?.let { animationFrame(it, 0) }?.let { current = it; view?.image = it }
            }
            while (source != null) {
                val active = source ?: break
                delay(animationDelayMillis(active, index))
                if (source == null) break
                var next = index + 1
                if (next >= count) {
                    completedLoops += 1
                    if (loops > 0 && completedLoops >= loops) break
                    next = 0
                }
                // Decode on the UI thread: close cannot race a native decoder.
                val frame = animationFrame(active, next) ?: break
                current = frame
                view?.image = frame
                CGImageSourceRemoveCacheAtIndex(active, index.toULong())
                index = next
            }
        }
    }

    override fun close() {
        view?.image = null
        view = null
        current = null
        source?.let { CFRelease(it) }
        source = null
    }
}

/** Read Core Foundation values directly: CFBridgingRelease does not produce Kotlin String/Map values. */
private fun animationTypeString(value: CFStringRef): String? = memScoped {
    val bytes = allocArray<ByteVar>(256)
    if (CFStringGetCString(value, bytes, 256, kCFStringEncodingUTF8)) bytes.toKString() else null
}

private fun animationProperties(properties: CFDictionaryRef): CFDictionaryRef? =
    listOf(kCGImagePropertyGIFDictionary, kCGImagePropertyPNGDictionary, kCGImagePropertyWebPDictionary)
        .firstNotNullOfOrNull { key ->
            val value = CFDictionaryGetValue(properties, key)
            if (value != null && CFGetTypeID(value) == CFDictionaryGetTypeID()) value.reinterpret() else null
        }

private fun animationNumber(properties: CFDictionaryRef?, key: CFStringRef?): Double? = memScoped {
    if (properties == null || key == null) return@memScoped null
    val value = CFDictionaryGetValue(properties, key) ?: return@memScoped null
    if (CFGetTypeID(value) != CFNumberGetTypeID()) return@memScoped null
    val number = alloc<DoubleVar>()
    if (CFNumberGetValue(value.reinterpret(), kCFNumberDoubleType, number.ptr)) number.value else null
}

internal fun animationDelayMillis(source: CGImageSourceRef, index: Int): Long {
    val properties = CGImageSourceCopyPropertiesAtIndex(source, index.toULong(), null) ?: return 100
    val seconds = try {
        val frame = animationProperties(properties)
        animationNumber(frame, kCGImagePropertyGIFUnclampedDelayTime)
            ?: animationNumber(frame, kCGImagePropertyGIFDelayTime) ?: 0.1
    } finally { CFRelease(properties) }
    return if (seconds.isFinite() && seconds > 0.0) (seconds * 1_000).toLong().coerceIn(20, 60_000) else 100
}

internal fun animationFrame(source: CGImageSourceRef, index: Int): UIImage? = memScoped {
    val properties = CGImageSourceCopyPropertiesAtIndex(source, index.toULong(), null) ?: return@memScoped null
    val bounded = try {
        val width = animationNumber(properties, kCGImagePropertyPixelWidth)
        val height = animationNumber(properties, kCGImagePropertyPixelHeight)
        width != null && height != null && width in 1.0..ANIMATION_SOURCE_EDGE_MAX.toDouble() && height in 1.0..ANIMATION_SOURCE_EDGE_MAX.toDouble()
    } finally { CFRelease(properties) }
    if (!bounded) return@memScoped null
    val edge = alloc<IntVar> { value = ANIMATION_RENDER_EDGE_MAX }
    val number = CFNumberCreate(null, kCFNumberIntType, edge.ptr) ?: return@memScoped null
    val options = CFDictionaryCreateMutable(null, 5, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    if (options == null) { CFRelease(number); return@memScoped null }
    val image = try {
        CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
        CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
        CGImageSourceCreateThumbnailAtIndex(source, index.toULong(), options)
    } finally { CFRelease(options); CFRelease(number) } ?: return@memScoped null
    try { UIImage.imageWithCGImage(image) } finally { CGImageRelease(image) }
}
