@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.toKString
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVAssetReaderStatusCompleted
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.AVMetadataCommonKeyArtwork
import platform.AVFoundation.AVMetadataItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetReferenceRestrictionsKey
import platform.AVFoundation.AVAssetReferenceRestrictionForbidAll
import platform.AVFoundation.cancelLoading
import platform.AVFoundation.commonKey
import platform.AVFoundation.commonMetadata
import platform.AVFoundation.creationDate
import platform.AVFoundation.dataValue
import platform.AVFoundation.dateValue
import platform.AVFoundation.duration
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.Foundation.CFBridgingRetain
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetTypeID
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFDictionaryGetTypeID
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectApplyAffineTransform
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetType
import platform.UniformTypeIdentifiers.UTType
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyDNGDictionary
import platform.ImageIO.kCGImagePropertyDNGDefaultCropSize
import platform.ImageIO.kCGImagePropertyExifDictionary
import platform.ImageIO.kCGImagePropertyExifDateTimeOriginal
import platform.ImageIO.kCGImagePropertyExifOffsetTimeOriginal
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * The iOS codec side of `MEDIA_PIPELINE.md` §1.
 *
 * A `PHPickerViewController` result is a file URL, so the source is a path
 * rather than a content URI. Every decode reopens from the URL rather than
 * reusing the descriptor the import is reading, for the reason the Android side
 * gives: two readers of one descriptor share a file position, and the
 * descriptor belongs to the import.
 */
class IosMediaCodec : MediaCodec {

    /**
     * Opens a picked file URL, §3.
     *
     * The descriptor comes from `open(2)` rather than from an `NSFileHandle`,
     * because the handle owns what it opened and closing it is its own
     * business; §13 of `docs/interop/FFI_CONTRACT.md` has the caller close the
     * descriptor on its own schedule, which is what [PickedMedia.close] does.
     *
     * [name] is the item's own name, which the picker gave, and it is what the
     * catalog records and a search finds. The file at [url] is a scratch copy
     * with a random name, because `IOS.md` §12 keeps a file name from telling
     * the filename, so its last path component means nothing to the user.
     */
    fun open(url: NSURL, name: String?): PickedMedia? {
        val path = url.path ?: return null
        val descriptor = platform.posix.open(path, platform.posix.O_RDONLY)
        if (descriptor < 0) return null
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue
        val bundle = try { readLivePhotoBundle(descriptor, size, validatePayloads = false) } catch (failure: Exception) { platform.posix.close(descriptor); throw failure }
        val type = if (bundle != null) LivePhotoBundle.CONTENT_TYPE else detectedType(url, name)
        return PickedMedia(
            descriptor = descriptor,
            seekable = true,
            knownLength = size,
            contentTypeHint = type,
            originalFilename = name,
            captureTimeMs = bundle?.captureTimeMs ?: embeddedCaptureTime(url, type),
            livePhoto = bundle,
            platformHandle = url,
            close = { platform.posix.close(descriptor) },
        )
    }

    /** Uses a scoped, coordinated source owned by the document picker host. */
    fun openDocument(
        descriptor: Int,
        name: String?,
        contentType: String,
        knownLength: Long,
        release: () -> Unit,
    ): PickedMedia? {
        if (descriptor < 0 || knownLength < 0) { release(); return null }
        return PickedMedia(
            descriptor = descriptor,
            seekable = true,
            knownLength = knownLength,
            contentTypeHint = contentType,
            originalFilename = name,
            captureTimeMs = null,
            platformHandle = null,
            close = release,
            importAsFile = true,
        )
    }

    /** Keeps the host's security-scoped URL valid until import releases its lease. */
    fun openMediaDocument(
        url: NSURL,
        descriptor: Int,
        name: String?,
        contentType: String?,
        knownLength: Long,
        release: () -> Unit,
    ): PickedMedia? {
        if (descriptor < 0 || knownLength < 0) { release(); return null }
        val bundle = try { readLivePhotoBundle(descriptor, knownLength, validatePayloads = false) } catch (failure: Exception) { release(); throw failure }
        val type = if (bundle != null) LivePhotoBundle.CONTENT_TYPE else detectedType(url, name).takeUnless { it == "application/octet-stream" }
            ?: normalizedFileContentType(contentType.orEmpty())
        return PickedMedia(
            descriptor = descriptor,
            seekable = true,
            knownLength = knownLength,
            contentTypeHint = type,
            originalFilename = name,
            captureTimeMs = bundle?.captureTimeMs ?: embeddedCaptureTime(url, type),
            livePhoto = bundle,
            platformHandle = url,
            close = release,
        )
    }

    /**
     * The capture time the file records, `MEDIA_PIPELINE.md` §4.
     *
     * The picker hands over a copy of the file and no date: the asset's own
     * date needs photo library access, which an import does not ask for,
     * `IOS.md` §15.1. So the copy is read, as Android reads a file whose
     * provider publishes no date: a photo's EXIF original time, and a video
     * container's creation date. The value is a hint, §3, and Rust stores a
     * substituted time for an absent one, §8.1 of the catalog.
     */
    private fun embeddedCaptureTime(url: NSURL, type: String): Long? = when {
        // The ImageIO names are kCGImagePropertyExifDictionary and its
        // DateTimeOriginal and OffsetTimeOriginal keys.
        type.startsWith("image/") -> imageCaptureTime(url)
        // A container with no date stores zero seconds since 1904.
        type.startsWith("video/") -> mediaAsset(url).creationDate?.dateValue
            ?.let { (it.timeIntervalSince1970 * 1_000).toLong() }
            ?.takeIf { it >= 0 }
        else -> null
    }

    override fun probe(media: PickedMedia): ProbedMedia? {
        if (media.livePhoto != null) {
            val dimensions = withLivePhotoImage(media) { imageDimensions(it) }
            return ProbedMedia(MediaBounds.CLASS_IMAGE, dimensions?.first ?: 0, dimensions?.second ?: 0, 0, LivePhotoBundle.CONTENT_TYPE)
        }
        val url = urlOf(media)
            ?: return ProbedMedia(MediaBounds.CLASS_OPAQUE, 0, 0, 0, media.contentTypeHint)
        val type = media.contentTypeHint
        return when {
            type.startsWith("image/") -> probeImage(url, type)
            type.startsWith("video/") -> probeVideo(url, type)
            type.startsWith("audio/") -> probeAudio(url, type)
            else -> ProbedMedia(MediaBounds.CLASS_OPAQUE, 0, 0, 0, type)
        }
    }

    private fun probeImage(url: NSURL, type: String): ProbedMedia? {
        val dimensions = withImageSource(url) { imageDimensions(it) } ?: return null
        return ProbedMedia(MediaBounds.CLASS_IMAGE, dimensions.first, dimensions.second, 0, type)
    }

    /** Read track metadata before any frame decode; preserve full upright dimensions. */
    private fun probeVideo(url: NSURL, type: String): ProbedMedia {
        val asset = mediaAsset(url)
        val seconds = CMTimeGetSeconds(asset.duration)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
        val dimensions = track?.naturalSize?.useContents {
            CGRectApplyAffineTransform(CGRectMake(0.0, 0.0, width, height), track.preferredTransform)
                .useContents { size.width.toInt() to size.height.toInt() }
        }
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_VIDEO,
            width = dimensions?.first ?: 0,
            height = dimensions?.second ?: 0,
            durationMs = durationMillis(seconds),
            contentType = type,
        )
    }

    private fun probeAudio(url: NSURL, type: String): ProbedMedia {
        val seconds = CMTimeGetSeconds(mediaAsset(url).duration)
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_AUDIO,
            width = 0,
            height = 0,
            durationMs = durationMillis(seconds),
            contentType = type,
        )
    }

    override fun derive(
        media: PickedMedia,
        probe: ProbedMedia,
        kind: StreamKind,
        cancelRequested: () -> Boolean,
    ): Derivative? {
        if (cancelRequested()) return null
        val url = urlOf(media) ?: return null
        if (kind == StreamKind.AUDIO_WAVEFORM) return deriveWaveform(url, probe, cancelRequested)
        if (probe.mediaClass == MediaBounds.CLASS_AUDIO) return deriveAudioArtwork(url, kind, cancelRequested)
        val target = MediaBounds.targetSize(kind, probe.width, probe.height) ?: return null
        var (width, height) = target
        val image = when (probe.mediaClass) {
            MediaBounds.CLASS_IMAGE -> if (media.livePhoto == null) loadImage(url, maxOf(width, height)) else withLivePhotoImage(media) { source ->
                val frame = boundedThumbnail(source, maxOf(width, height)) ?: return@withLivePhotoImage null
                try { UIImage.imageWithCGImage(frame) } finally { CGImageRelease(frame) }
            }
            MediaBounds.CLASS_VIDEO -> posterFrame(url, maxOf(width, height))
            else -> null
        } ?: return null
        val decoded = image.size.useContents { this.width.toInt() to this.height.toInt() }
        // Some RAW decoders expose only an embedded preview. Keep its actual
        // pixels; a preview must not be enlarged to the RAW image dimensions.
        if (decoded.first < width || decoded.second < height) {
            val bounded = MediaBounds.targetSize(kind, decoded.first, decoded.second) ?: return null
            width = bounded.first
            height = bounded.second
        }

        // A scale of 1.0 keeps the pixel size the target names rather than
        // multiplying it by the screen scale: §11 makes the derivative's size a
        // format decision and not a display one.
        UIGraphicsBeginImageContextWithOptions(
            CGSizeMake(width.toDouble(), height.toDouble()),
            opaque = true,
            scale = 1.0,
        )
        image.drawInRect(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        val scaled = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        // §12: baseline JPEG, at the quality the kind names.
        if (cancelRequested()) return null
        val data = scaled?.let { UIImageJPEGRepresentation(it, MediaBounds.quality(kind) / 100.0) }
            ?: return null
        val bytes = data.toByteArray()
        if (cancelRequested()) { bytes.fill(0); return null }
        return Derivative(kind, bytes, width, height)
    }

    /** Native tags are optional: an absent, oversized, or invalid image adds no visual asset. */
    private fun deriveAudioArtwork(url: NSURL, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? = autoreleasepool {
        if (MediaBounds.longEdge(kind) == null || kind == StreamKind.VIDEO_POSTER || cancelRequested()) return@autoreleasepool null
        val asset = mediaAsset(url)
        try {
            // commonMetadata normalizes supported ID3 APIC and iTunes covr tags.
            // AVFoundation owns its initial NSData allocation; app checks happen before image decode.
            for (value in asset.commonMetadata.take(256)) {
                if (cancelRequested()) return@autoreleasepool null
                val item = value as? AVMetadataItem ?: continue
                if (item.commonKey != AVMetadataCommonKeyArtwork) continue
                val data = item.dataValue ?: continue
                if (cancelRequested()) return@autoreleasepool null
                val result = audioArtworkDerivative(data, kind, cancelRequested)
                if (result != null) return@autoreleasepool result
            }
            null
        } finally { asset.cancelLoading() }
    }

    /**
     * Reads the audio track as PCM and folds it into the §6.1 waveform record.
     *
     * `AVAssetReader` yields one sample buffer at a time and the accumulator
     * holds one bucket array whatever the recording's length, so a four-hour
     * import costs the same memory as a four-second one. That is the bound §12
     * puts on every other import buffer.
     *
     * The output is asked for as 16-bit signed little-endian linear PCM, which
     * `AVAssetReaderAudioMixOutput` produces for every input format the platform
     * decodes, so the folding code is the same on both hosts.
     */
    private fun deriveWaveform(
        url: NSURL,
        probe: ProbedMedia,
        cancelRequested: () -> Boolean,
    ): Derivative? {
        val asset = mediaAsset(url)
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
            ?: return null
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatLinearPCM),
            AVLinearPCMBitDepthKey to NSNumber(int = 16),
            AVLinearPCMIsBigEndianKey to NSNumber(bool = false),
            AVLinearPCMIsFloatKey to NSNumber(bool = false),
            AVLinearPCMIsNonInterleaved to NSNumber(bool = false),
        )
        val reader = AVAssetReader(asset, null)
        val output = AVAssetReaderTrackOutput(track, settings)
        if (!reader.canAddOutput(output)) return null
        reader.addOutput(output)
        try {
            if (!reader.startReading()) return null

            // The reader preserves the track rate and channel count. Count interleaved
            // samples, since the common accumulator advances once per channel sample.
            var accumulator: Waveform.Accumulator? = null
            while (true) {
                if (cancelRequested()) { reader.cancelReading(); return null }
                val buffer = output.copyNextSampleBuffer() ?: break
                try {
                    if (accumulator == null) {
                        val description = CMSampleBufferGetFormatDescription(buffer) ?: return null
                        val format = CMAudioFormatDescriptionGetStreamBasicDescription(description) ?: return null
                        val expected = format.pointed.let {
                            waveformExpectedSamples(probe.durationMs, it.mSampleRate, it.mChannelsPerFrame.toInt())
                        } ?: return null
                    accumulator = Waveform.Accumulator(expected)
                    }
                    val block = CMSampleBufferGetDataBuffer(buffer) ?: return null
                    val length = CMBlockBufferGetDataLength(block)
                    if (length > 16uL * 1024uL * 1024uL || length % 2uL != 0uL) return null
                    if (length > 0uL) {
                        val pcm = ByteArray(length.toInt())
                        try {
                            val status = pcm.usePinned { pinned ->
                                CMBlockBufferCopyDataBytes(block, 0u, length, pinned.addressOf(0))
                            }
                            if (status != 0) return null
                            accumulator.addAll(pcm)
                        } finally { pcm.fill(0) }
                    }
                } finally { CFRelease(buffer) }
            }
            val complete = accumulator ?: return null
            if (reader.status != AVAssetReaderStatusCompleted || cancelRequested() || complete.sampleCount == 0L) return null
            return Derivative(
                kind = StreamKind.AUDIO_WAVEFORM,
                bytes = complete.encode(probe.durationMs),
                width = 0,
                height = 0,
            )
        } finally {
            if (reader.status != AVAssetReaderStatusCompleted) reader.cancelReading()
            asset.cancelLoading()
        }
    }

    private fun loadImage(url: NSURL, maxEdge: Int): UIImage? = withImageSource(url) { source ->
        // ImageIO applies orientation and can use native RAW thumbnails. Unsupported
        // RAW files remain original files with no invented preview.
        val frame = boundedThumbnail(source, maxEdge) ?: return@withImageSource null
        try { UIImage.imageWithCGImage(frame) } finally { CGImageRelease(frame) }
    }

    private fun posterFrame(url: NSURL, maxEdge: Int): UIImage? {
        val asset: AVAsset = mediaAsset(url)
        val generator = AVAssetImageGenerator(asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSizeMake(maxEdge.toDouble(), maxEdge.toDouble())
        val frame = generator.copyCGImageAtTime(CMTimeMake(0, 1), null, null) ?: return null
        return try { UIImage.imageWithCGImage(frame) } finally { CGImageRelease(frame) }
    }

    private fun urlOf(media: PickedMedia): NSURL? = media.platformHandle as? NSURL

    /** Prefer decoded image type, then the platform's registered extension type. */
    private fun detectedType(url: NSURL, name: String?): String {
        val imageType = withImageSource(url) { source ->
            CGImageSourceGetType(source)?.let(::cfStringValue)
        }
        if (imageType != null) {
            UTType.typeWithIdentifier(imageType)?.preferredMIMEType?.let { return it }
        }
        val extension = (name ?: url.path.orEmpty()).substringAfterLast('.', "").lowercase()
        val native = UTType.typeWithFilenameExtension(extension)
        return native?.preferredMIMEType ?: "application/octet-stream"
    }

}

/** Read CF strings through UTF-8 rather than an untyped Foundation bridge cast. */
private fun cfStringValue(value: CFStringRef): String? = memScoped {
    val length = CFStringGetLength(value)
    if (length !in 0..1024) return@memScoped null
    val capacity = length * 4 + 1
    val bytes = allocArray<ByteVar>(capacity.toInt())
    if (CFStringGetCString(value, bytes, capacity, kCFStringEncodingUTF8)) bytes.toKString() else null
}

internal fun waveformExpectedSamples(durationMs: Long, sampleRate: Double, channels: Int): Long? {
    if (!sampleRate.isFinite() || sampleRate <= 0 || channels !in 1..64) return null
    val samples = durationMs.coerceAtLeast(0).toDouble() * sampleRate * channels / 1000.0
    return if (samples < Long.MAX_VALUE.toDouble()) samples.toLong() else null
}

/** Import must not follow media references to files or network resources. */
private fun mediaAsset(url: NSURL): AVURLAsset = AVURLAsset(
    url,
    options = mapOf(AVURLAssetReferenceRestrictionsKey to NSNumber(unsignedLongLong = AVAssetReferenceRestrictionForbidAll)),
)

private fun durationMillis(seconds: Double): Long =
    if (seconds.isFinite() && seconds > 0 && seconds < Long.MAX_VALUE / 1000.0) (seconds * 1000).toLong() else 0

private inline fun <T> withImageSource(url: NSURL, block: (CGImageSourceRef) -> T): T? {
    val retained = CFBridgingRetain(url) ?: return null
    try {
        val options = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: return null
        val source = try {
            CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
            CGImageSourceCreateWithURL(retained.reinterpret(), options)
        } finally { CFRelease(options) } ?: return null
        try { return block(source) } finally { CFRelease(source) }
    } finally { CFRelease(retained) }
}

private fun imageCaptureTime(url: NSURL): Long? = withImageSource(url) { source ->
    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return@withImageSource null
    try {
        val exif = CFDictionaryGetValue(properties, kCGImagePropertyExifDictionary) ?: return@withImageSource null
        if (CFGetTypeID(exif) != CFDictionaryGetTypeID()) return@withImageSource null
        fun text(key: CFStringRef?): String? {
            val value = CFDictionaryGetValue(exif.reinterpret(), key) ?: return null
            if (CFGetTypeID(value) != CFStringGetTypeID()) return null
            return cfStringValue(value.reinterpret())
        }
        exifCaptureTimeMs(text(kCGImagePropertyExifDateTimeOriginal), text(kCGImagePropertyExifOffsetTimeOriginal), NSTimeZone.localTimeZone)
    } finally { CFRelease(properties) }
}

private fun imageDimensions(source: CGImageSourceRef): Pair<Int, Int>? {
    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return null
    try {
        // iOS may report an embedded preview as PixelWidth/Height for RAW.
        // DNG DefaultCropSize names the actual image, even in that fallback.
        val rawCrop = if (CGImageSourceGetType(source)?.let(::cfStringValue) == "com.adobe.raw-image") dngCropDimensions(properties) else null
        val width = rawCrop?.first ?: imageInteger(properties, kCGImagePropertyPixelWidth) ?: return null
        val height = rawCrop?.second ?: imageInteger(properties, kCGImagePropertyPixelHeight) ?: return null
        val orientation = imageInteger(properties, kCGImagePropertyOrientation) ?: 1L
        if (width !in 1..Int.MAX_VALUE.toLong() || height !in 1..Int.MAX_VALUE.toLong() || orientation !in 1..8) return null
        return if (orientation in 5..8) height.toInt() to width.toInt() else width.toInt() to height.toInt()
    } finally { CFRelease(properties) }
}

private fun dngCropDimensions(properties: CFDictionaryRef): Pair<Long, Long>? {
    val dng = CFDictionaryGetValue(properties, kCGImagePropertyDNGDictionary) ?: return null
    if (CFGetTypeID(dng) != CFDictionaryGetTypeID()) return null
    val crop = CFDictionaryGetValue(dng.reinterpret(), kCGImagePropertyDNGDefaultCropSize) ?: return null
    if (CFGetTypeID(crop) != CFArrayGetTypeID() || CFArrayGetCount(crop.reinterpret()) != 2L) return null
    fun dimension(index: Long): Long? = memScoped {
        val number = CFArrayGetValueAtIndex(crop.reinterpret(), index) ?: return@memScoped null
        if (CFGetTypeID(number) != CFNumberGetTypeID()) return@memScoped null
        val value = alloc<LongVar>()
        if (CFNumberGetValue(number.reinterpret(), kCFNumberSInt64Type, value.ptr) && value.value in 1..Int.MAX_VALUE.toLong()) value.value else null
    }
    return (dimension(0) ?: return null) to (dimension(1) ?: return null)
}

private fun boundedThumbnail(source: CGImageSourceRef, maxEdge: Int) = memScoped {
    val edge = alloc<LongVar> { value = maxEdge.toLong() }
    val number = CFNumberCreate(null, kCFNumberSInt64Type, edge.ptr) ?: return@memScoped null
    val options = CFDictionaryCreateMutable(null, 5, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    if (options == null) { CFRelease(number); return@memScoped null }
    try {
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
        CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
    } finally { CFRelease(options); CFRelease(number) }
}

/** Decode only a bounded upright thumbnail, then write a new metadata-free JPEG. */
internal fun audioArtworkDerivative(data: NSData, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? {
    if (cancelRequested() || data.length == 0uL || data.length > MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES.toULong()) return null
    // A retained CFData copy gives ImageIO deterministic ownership without a borrowed NSData pointer.
    val encoded = CFDataCreate(null, data.bytes?.reinterpret(), data.length.toLong()) ?: return null
    try {
        val options = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: return null
        val source = try {
            CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
            CGImageSourceCreateWithData(encoded, options)
        } finally { CFRelease(options) } ?: return null
        try {
            if (cancelRequested()) return null
            val target = artworkSize(source, kind, data.length.toLong()) ?: return null
            if (cancelRequested()) return null
            val frame = memScoped {
                val edge = alloc<LongVar> { value = maxOf(target.first, target.second).toLong() }
                val number = CFNumberCreate(null, kCFNumberSInt64Type, edge.ptr) ?: return@memScoped null
                val thumbnailOptions = CFDictionaryCreateMutable(null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                if (thumbnailOptions == null) { CFRelease(number); return@memScoped null }
                try {
                    CFDictionarySetValue(thumbnailOptions, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                    CFDictionarySetValue(thumbnailOptions, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                    CFDictionarySetValue(thumbnailOptions, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
                    CFDictionarySetValue(thumbnailOptions, kCGImageSourceThumbnailMaxPixelSize, number)
                    CGImageSourceCreateThumbnailAtIndex(source, 0u, thumbnailOptions)
                } finally { CFRelease(thumbnailOptions); CFRelease(number) }
            } ?: return null
            try {
                if (cancelRequested()) return null
                val edge = maxOf(target.first, target.second).toULong()
                if (CGImageGetWidth(frame) > edge || CGImageGetHeight(frame) > edge) return null
                val image = UIImage.imageWithCGImage(frame)
                UIGraphicsBeginImageContextWithOptions(CGSizeMake(target.first.toDouble(), target.second.toDouble()), true, 1.0)
                val jpeg = try {
                    image.drawInRect(CGRectMake(0.0, 0.0, target.first.toDouble(), target.second.toDouble()))
                    if (cancelRequested()) return null
                    UIGraphicsGetImageFromCurrentImageContext()?.let { UIImageJPEGRepresentation(it, MediaBounds.quality(kind) / 100.0) }
                } finally { UIGraphicsEndImageContext() } ?: return null
                if (cancelRequested() || jpeg.length == 0uL || jpeg.length > MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES.toULong()) return null
                val bytes = jpeg.toByteArray()
                if (cancelRequested()) { bytes.fill(0); return null }
                return Derivative(kind, bytes, target.first, target.second)
            } finally { CGImageRelease(frame) }
        } finally { CFRelease(source) }
    } finally { CFRelease(encoded) }
}

private fun artworkSize(source: CGImageSourceRef, kind: StreamKind, encodedSize: Long): Pair<Int, Int>? {
    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return null
    try {
        val width = imageInteger(properties, kCGImagePropertyPixelWidth) ?: return null
        val height = imageInteger(properties, kCGImagePropertyPixelHeight) ?: return null
        if (width !in 1..Int.MAX_VALUE.toLong() || height !in 1..Int.MAX_VALUE.toLong()) return null
        val orientation = imageInteger(properties, kCGImagePropertyOrientation) ?: 1L
        if (orientation !in 1..8) return null
        return if (orientation in 5..8) MediaBounds.artworkTargetSize(kind, height.toInt(), width.toInt(), encodedSize)
        else MediaBounds.artworkTargetSize(kind, width.toInt(), height.toInt(), encodedSize)
    } finally { CFRelease(properties) }
}

private fun imageInteger(properties: CFDictionaryRef, key: CFStringRef?): Long? = memScoped {
    val number = CFDictionaryGetValue(properties, key) ?: return@memScoped null
    if (CFGetTypeID(number) != CFNumberGetTypeID()) return@memScoped null
    val value = alloc<LongVar>()
    if (CFNumberGetValue(number.reinterpret(), kCFNumberSInt64Type, value.ptr)) value.value else null
}

/**
 * An EXIF `DateTimeOriginal`, "2019:05:01 12:00:00", in milliseconds since
 * the epoch, as the Android codec reads it.
 *
 * EXIF records the time on the camera's clock. `OffsetTimeOriginal`,
 * "+02:00", says which zone that clock was in; a time with no offset, or with
 * one that does not parse, is read in [zone], the device's zone at import,
 * `MEDIA_PIPELINE.md` §4. A value that does not parse, as the
 * "0000:00:00 00:00:00" of a camera whose clock was never set, is no capture
 * time.
 */
internal fun exifCaptureTimeMs(dateTime: String?, offset: String?, zone: NSTimeZone): Long? {
    val local = dateTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val formatter = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        timeZone = zone
        lenient = false
    }
    val date = offset?.trim()?.takeIf { it.isNotEmpty() }?.let {
        formatter.dateFormat = "yyyy:MM:dd HH:mm:ssZZZZZ"
        formatter.dateFromString(local + it)
    } ?: formatter.run {
        dateFormat = "yyyy:MM:dd HH:mm:ss"
        dateFromString(local)
    }
    return date?.let { (it.timeIntervalSince1970 * 1_000).toLong() }?.takeIf { it >= 0 }
}

/** Copies an `NSData` into a Kotlin array. */
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) {
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return out
}
