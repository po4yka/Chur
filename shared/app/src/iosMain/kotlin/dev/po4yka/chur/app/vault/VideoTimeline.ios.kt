@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.CoreFoundation.*
import platform.CoreMedia.*
import platform.CoreVideo.*
import platform.VideoToolbox.VTCreateCGImageFromCVPixelBuffer
import platform.Foundation.*
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.min
import kotlin.math.floor
import kotlin.time.TimeSource

@Composable
actual fun rememberVideoTimelineGenerator(): VideoTimelineGenerator = remember { IosVideoTimelineGenerator() }

/** Twelve bounded native frames, laid out in a fixed atlas without plaintext files. */
class IosVideoTimelineGenerator : VideoTimelineGenerator {
    override suspend fun generate(source: PlaybackSource, cancelRequested: () -> Boolean): VideoTimelineBytes = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        val started = TimeSource.Monotonic.markNow()
        val generation = (source.vault.state.value as? VaultState.Unlocked)?.generation
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "video timeline")
        fun active() {
            context.ensureActive()
            if (cancelRequested()) throw CancellationException("Video timeline cancelled")
            if ((source.vault.state.value as? VaultState.Unlocked)?.generation != generation) {
                throw ChurFailure(ChurStatus.SESSION_EXPIRED, "video timeline")
            }
            if (started.elapsedNow().inWholeMilliseconds >= 30_000) {
                throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "video timeline time limit")
            }
        }
        active()
        val loader = ChurResourceLoader(source)
        val asset = privatePlayerItem(loader).asset
        val atlas = ByteArray(640 * 270 * 4)
        var reader: AVAssetReader? = null
        var held: CMSampleBufferRef? = null
        try {
            val duration = CMTimeGetSeconds(asset.duration)
            if (!duration.isFinite() || duration <= 0) throw ChurFailure(ChurStatus.INVALID_INPUT, "video timeline duration")
            val video = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
                ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "video timeline track")
            val size = AVVideoComposition.videoCompositionWithPropertiesOfAsset(asset).renderSize.useContents { width to height }
            val scale = min(1.0, min(160.0 / size.first, 90.0 / size.second))
            val width = maxOf(2, floor(size.first * scale / 2).toInt() * 2)
            val height = maxOf(2, floor(size.second * scale / 2).toInt() * 2)
            val output = AVAssetReaderVideoCompositionOutput(listOf(video), mapOf(
                timelineCfString(requireNotNull(kCVPixelBufferPixelFormatTypeKey)) to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA)))
            output.videoComposition = iosSdrVideoComposition(asset, video, width, height, scale)
            output.alwaysCopiesSampleData = false
            val nativeReader = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                AVAssetReader(asset, error.ptr)
            }
            reader = nativeReader
            if (!nativeReader.canAddOutput(output)) throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline reader output")
            nativeReader.addOutput(output)
            active()
            if (!nativeReader.startReading()) throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline reader start")
            var index = 0
            var heldSeconds = 0.0
            fun target() = duration * (index + 0.5) / 12.0
            fun drawHeld(throughSeconds: Double) {
                if (index >= 12 || target() > throughSeconds) return
                val sample = held ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline missing frame")
                val cell = timelineSdrCell(sample)
                try {
                    while (index < 12 && target() <= throughSeconds) {
                        for (row in 0 until 90) {
                            val destination = ((index / 4 * 90 + row) * 640 + index % 4 * 160) * 4
                            cell.copyInto(atlas, destination, row * 160 * 4, (row + 1) * 160 * 4)
                        }
                        index++
                    }
                } finally { cell.fill(0) }
            }
            while (index < 12) {
                active()
                val sample = output.copyNextSampleBuffer()
                if (sample == null) {
                    active()
                    if (nativeReader.status != AVAssetReaderStatusCompleted) {
                        throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline native decode")
                    }
                    drawHeld(duration)
                    break
                }
                try {
                    active()
                    val seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                    if (!seconds.isFinite() || (held != null && seconds < heldSeconds)) {
                        throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline frame order")
                    }
                    if (held != null) drawHeld((heldSeconds + seconds) / 2.0)
                    held?.let { CFRelease(it) }
                    held = sample
                    heldSeconds = seconds
                } catch (failure: Throwable) { CFRelease(sample); throw failure }
            }
            if (index != 12) throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline incomplete frames")
            active()
            val encoded = timelineJpeg(atlas)
            try { active() } catch (failure: Throwable) { encoded.fill(0); throw failure }
            VideoTimelineBytes(encoded, 640, 270)
        } finally {
            held?.let { CFRelease(it) }
            if (reader?.status == AVAssetReaderStatusReading) reader?.cancelReading()
            atlas.fill(0)
            asset.cancelLoading()
            loader.release()
        }
    }
}

/** Verify actual 8-bit SDR output before the native color-managed image conversion. */
private fun timelineSdrCell(sample: CMSampleBufferRef): ByteArray = memScoped {
    val pixels = CMSampleBufferGetImageBuffer(sample)
        ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline pixel buffer")
    if (CVPixelBufferGetPixelFormatType(pixels) != kCVPixelFormatType_32BGRA) {
        throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline 8-bit output")
    }
    val transfer = CVBufferCopyAttachment(pixels, requireNotNull(kCVImageBufferTransferFunctionKey), null)
        ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline SDR transfer")
    try {
        if (timelineCfString(transfer.reinterpret()) != timelineCfString(requireNotNull(kCVImageBufferTransferFunction_ITU_R_709_2))) {
            throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline SDR transfer")
        }
    } finally { CFRelease(transfer) }
    val image = alloc<CGImageRefVar>()
    image.value = null
    if (VTCreateCGImageFromCVPixelBuffer(pixels, null, image.ptr) != 0 || image.value == null) {
        throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline image conversion")
    }
    val frame = requireNotNull(image.value)
    try { timelineCell(frame) } finally { CGImageRelease(frame) }
}

private fun timelineCfString(value: CFStringRef): String = memScoped {
    val capacity = CFStringGetLength(value) * 4 + 1
    val bytes = allocArray<ByteVar>(capacity.toInt())
    if (!CFStringGetCString(value, bytes, capacity, kCFStringEncodingUTF8)) {
        throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline color name")
    }
    bytes.toKString()
}

/** An opaque SDR sRGB context invokes native HDR tone mapping and leaves black letterboxes. */
private fun timelineCell(frame: CGImageRef): ByteArray {
    val pixels = ByteArray(160 * 90 * 4)
    try {
        pixels.usePinned { pinned ->
            val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline color")
            val bitmap = try {
                CGBitmapContextCreate(pinned.addressOf(0), 160uL, 90uL, 8uL, 640uL, space,
                    CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value or kCGBitmapByteOrder32Big)
            } finally { CGColorSpaceRelease(space) }
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline bitmap")
            try {
                val width = CGImageGetWidth(frame).toDouble()
                val height = CGImageGetHeight(frame).toDouble()
                val scale = min(160.0 / width, 90.0 / height)
                CGContextDrawImage(bitmap, CGRectMake((160.0 - width * scale) / 2, (90.0 - height * scale) / 2,
                    width * scale, height * scale), frame)
            } finally { CGContextRelease(bitmap) }
        }
        return pixels
    } catch (failure: Throwable) { pixels.fill(0); throw failure }
}

private fun timelineJpeg(pixels: ByteArray): ByteArray = pixels.usePinned { pinned ->
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline color")
    val bitmap = try {
        CGBitmapContextCreate(pinned.addressOf(0), 640uL, 270uL, 8uL, 2560uL, space,
            CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value or kCGBitmapByteOrder32Big)
    } finally { CGColorSpaceRelease(space) }
        ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline bitmap")
    try {
        val image = CGBitmapContextCreateImage(bitmap)
            ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline image")
        try {
            val data = UIImageJPEGRepresentation(UIImage.imageWithCGImage(image), 0.8)
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline encode")
            if (data.length > 2uL * 1024uL * 1024uL) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "video timeline size")
            requireNotNull(data.bytes).readBytes(data.length.toInt())
        } finally { CGImageRelease(image) }
    } finally { CGContextRelease(bitmap) }
}
