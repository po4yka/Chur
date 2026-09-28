@file:OptIn(ExperimentalForeignApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
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
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.creationDate
import platform.AVFoundation.dateValue
import platform.AVFoundation.duration
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIImage
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
        val type = typeOf(path)
        return PickedMedia(
            descriptor = descriptor,
            seekable = true,
            knownLength = size,
            contentTypeHint = type,
            originalFilename = name,
            captureTimeMs = embeddedCaptureTime(url, type),
            platformHandle = url,
            close = { platform.posix.close(descriptor) },
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
        type.startsWith("image/") ->
            (CIImage.imageWithContentsOfURL(url)?.properties?.get("{Exif}") as? Map<*, *>)?.let { exif ->
                exifCaptureTimeMs(
                    exif["DateTimeOriginal"] as? String,
                    exif["OffsetTimeOriginal"] as? String,
                    NSTimeZone.localTimeZone,
                )
            }
        // A container with no date stores zero seconds since 1904.
        type.startsWith("video/") -> AVURLAsset(url, options = null).creationDate?.dateValue
            ?.let { (it.timeIntervalSince1970 * 1_000).toLong() }
            ?.takeIf { it >= 0 }
        else -> null
    }

    override fun probe(media: PickedMedia): ProbedMedia? {
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
        val image = loadImage(url) ?: return null
        val width = image.size.useContents { width }
        val height = image.size.useContents { height }
        if (width <= 0.0 || height <= 0.0) return null
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_IMAGE,
            width = width.toInt(),
            height = height.toInt(),
            durationMs = 0,
            contentType = type,
        )
    }

    /**
     * A video's duration and normalized dimensions.
     *
     * The dimensions come from the poster frame rather than from the track's
     * natural size, and deliberately: §11 requires orientation normalization,
     * the poster generator already applies the preferred track transform, and a
     * portrait recording's natural size is landscape. Taking both from the same
     * transform is what keeps the probe and the derivative agreeing.
     */
    private fun probeVideo(url: NSURL, type: String): ProbedMedia {
        val seconds = CMTimeGetSeconds(AVURLAsset(url, options = null).duration)
        val poster = posterFrame(url)
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_VIDEO,
            width = poster?.size?.useContents { width.toInt() } ?: 0,
            height = poster?.size?.useContents { height.toInt() } ?: 0,
            durationMs = if (seconds.isNaN()) 0 else (seconds * 1000).toLong(),
            contentType = type,
        )
    }

    private fun probeAudio(url: NSURL, type: String): ProbedMedia {
        val seconds = CMTimeGetSeconds(AVURLAsset(url, options = null).duration)
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_AUDIO,
            width = 0,
            height = 0,
            durationMs = if (seconds.isNaN()) 0 else (seconds * 1000).toLong(),
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
        val target = MediaBounds.targetSize(kind, probe.width, probe.height) ?: return null
        val (width, height) = target
        val image = when (probe.mediaClass) {
            MediaBounds.CLASS_IMAGE -> loadImage(url)
            MediaBounds.CLASS_VIDEO -> posterFrame(url)
            else -> null
        } ?: return null

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
        val data = scaled?.let { UIImageJPEGRepresentation(it, MediaBounds.quality(kind) / 100.0) }
            ?: return null
        return Derivative(kind, data.toByteArray(), width, height)
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
        val asset = AVURLAsset(url, options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
            ?: return null
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatLinearPCM),
            AVLinearPCMBitDepthKey to NSNumber(int = 16),
            AVLinearPCMIsBigEndianKey to NSNumber(bool = false),
            AVLinearPCMIsFloatKey to NSNumber(bool = false),
            AVLinearPCMIsNonInterleaved to NSNumber(bool = false),
        )
        val reader = AVAssetReader(asset, null) ?: return null
        val output = AVAssetReaderTrackOutput(track, settings)
        if (!reader.canAddOutput(output)) return null
        reader.addOutput(output)
        if (!reader.startReading()) return null

        // Two bytes per sample; the frame count is only used to spread buckets,
        // and the accumulator clamps an index a decoder's own count contradicts.
        val samples = probe.durationMs * SAMPLE_RATE_HINT / 1_000L
        val accumulator = Waveform.Accumulator(samples)
        while (true) {
            if (cancelRequested()) {
                reader.cancelReading()
                return null
            }
            val buffer = output.copyNextSampleBuffer() ?: break
            val block = CMSampleBufferGetDataBuffer(buffer)
            if (block != null) {
                val length = CMBlockBufferGetDataLength(block).toInt()
                if (length > 0) {
                    val pcm = ByteArray(length)
                    pcm.usePinned { pinned ->
                        CMBlockBufferCopyDataBytes(block, 0u, length.toULong(), pinned.addressOf(0))
                    }
                    accumulator.addAll(pcm)
                }
            }
            CFRelease(buffer)
        }
        if (accumulator.sampleCount == 0L) return null
        return Derivative(
            kind = StreamKind.AUDIO_WAVEFORM,
            bytes = accumulator.encode(probe.durationMs),
            width = 0,
            height = 0,
        )
    }

    private fun loadImage(url: NSURL): UIImage? = url.path?.let { UIImage.imageWithContentsOfFile(it) }

    /**
     * The first frame of a video, §6's poster frame.
     *
     * `appliesPreferredTrackTransform` is what §11 calls orientation
     * normalization: without it a portrait recording produces a landscape
     * poster with the picture on its side.
     */
    private fun posterFrame(url: NSURL): UIImage? {
        val asset: AVAsset = AVURLAsset(url, options = null)
        val generator = AVAssetImageGenerator(asset)
        generator.appliesPreferredTrackTransform = true
        val frame = generator.copyCGImageAtTime(CMTimeMake(0, 1), null, null) ?: return null
        return UIImage.imageWithCGImage(frame)
    }

    private fun urlOf(media: PickedMedia): NSURL? = media.platformHandle as? NSURL

    /**
     * The IANA type a path's extension implies.
     *
     * §3 calls the type a hint, so a coarse mapping is enough: Rust validates
     * the shape and the catalog stores what it was told, and no cryptographic
     * decision depends on it.
     */
    private companion object {
        /**
         * The sample rate a waveform's bucket spread assumes.
         *
         * The value only decides how frames map onto buckets, and the
         * accumulator clamps an index the decoder's own count contradicts, so a
         * recording at another rate draws correctly either way. 44100 is the
         * rate the platform resamples most inputs to.
         */
        const val SAMPLE_RATE_HINT = 44_100L
    }

    private fun typeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic", "heif" -> "image/heic"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
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
