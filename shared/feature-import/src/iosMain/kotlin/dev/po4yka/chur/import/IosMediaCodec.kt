@file:OptIn(ExperimentalForeignApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
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
     */
    fun open(url: NSURL): PickedMedia? {
        val path = url.path ?: return null
        val descriptor = platform.posix.open(path, platform.posix.O_RDONLY)
        if (descriptor < 0) return null
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue
        return PickedMedia(
            descriptor = descriptor,
            seekable = true,
            knownLength = size,
            contentTypeHint = typeOf(path),
            originalFilename = url.lastPathComponent,
            // §8.1 of the catalog: a picker result carries no capture time on
            // this path, so it is absent and the row records the substitution
            // rather than carrying a guess.
            captureTimeMs = null,
            platformHandle = url,
            close = { platform.posix.close(descriptor) },
        )
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

    override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind): Derivative? {
        val url = urlOf(media) ?: return null
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

/** Copies an `NSData` into a Kotlin array. */
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) {
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return out
}
