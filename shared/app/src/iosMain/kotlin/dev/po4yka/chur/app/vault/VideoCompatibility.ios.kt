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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.CoreAudioTypes.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreMedia.*
import platform.CoreVideo.*
import platform.Foundation.*
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

@Composable
actual fun rememberVideoCompatibilityEncoder(): VideoCompatibilityEncoder = remember { IosVideoCompatibilityEncoder() }

/** Reads authenticated ranges and emits fragmented MP4 directly into bounded memory. */
class IosVideoCompatibilityEncoder : VideoCompatibilityEncoder {
    override suspend fun encode(source: PlaybackSource, cancelRequested: () -> Boolean, onProgress: (Double) -> Unit): CompatibleVideoBytes =
        withContext(Dispatchers.Default) {
            val encodeContext = currentCoroutineContext()
            val started = TimeSource.Monotonic.markNow()
            val generation = (source.vault.state.value as? VaultState.Unlocked)?.generation
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "compatible video")
            fun active() {
                encodeContext.ensureActive()
                if (cancelRequested()) throw CancellationException("Compatible video cancelled")
                if ((source.vault.state.value as? VaultState.Unlocked)?.generation != generation) {
                    throw ChurFailure(ChurStatus.SESSION_EXPIRED, "compatible video")
                }
                if (started.elapsedNow().inWholeMilliseconds >= 300_000) {
                    throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video time limit")
                }
            }
            active()
            val loader = ChurResourceLoader(source)
            // Reuse the player's private asset setup, including forbidden media
            // references and its serial authenticated resource-loader queue.
            val item = privatePlayerItem(loader)
            val asset = item.asset
            var reader: AVAssetReader? = null
            var writer: AVAssetWriter? = null
            val segments = CompatibleVideoSegments()
            try {
                active()
                // Native demuxing can omit an unsupported declared audio track.
                // Reject that input instead of silently producing a silent copy.
                val declared = inspectPlaybackContainer(source, generation)
                active()
                if (iosAssetUnsupportedDiagnostic(asset, declared) != null) compatibilityFailure("unsupported native track")
                val video = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
                    ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "compatible video track")
                val duration = CMTimeGetSeconds(asset.duration)
                if (!duration.isFinite() || duration <= 0) throw ChurFailure(ChurStatus.INVALID_INPUT, "compatible video duration")
                val originalSize = AVVideoComposition.videoCompositionWithPropertiesOfAsset(asset).renderSize.useContents { width to height }
                val scale = min(1.0, min(1280.0 / max(originalSize.first, originalSize.second), 720.0 / min(originalSize.first, originalSize.second)))
                val width = max(2, floor(originalSize.first * scale / 2).toInt() * 2)
                val height = max(2, floor(originalSize.second * scale / 2).toInt() * 2)
                val composition = iosSdrVideoComposition(asset, video, width, height, scale)

                val nativeReader = memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    error.value = null
                    AVAssetReader(asset, error.ptr)
                }
                reader = nativeReader
                val pixelKey = compatibleCfString(requireNotNull(kCVPixelBufferPixelFormatTypeKey))
                val videoOutput = AVAssetReaderVideoCompositionOutput(listOf(video), mapOf(pixelKey to NSNumber(unsignedInt = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)))
                videoOutput.videoComposition = composition
                videoOutput.alwaysCopiesSampleData = false
                if (!nativeReader.canAddOutput(videoOutput)) compatibilityFailure("video reader")
                nativeReader.addOutput(videoOutput)

                val nativeWriter = AVAssetWriter(contentType = requireNotNull(UTType.typeWithIdentifier("public.mpeg-4")))
                writer = nativeWriter
                nativeWriter.delegate = segments
                nativeWriter.outputFileTypeProfile = AVFileTypeProfileMPEG4AppleHLS
                nativeWriter.preferredOutputSegmentInterval = CMTimeMake(1, 1)
                nativeWriter.initialSegmentStartTime = kCMTimeZero.readValue()
                val videoInput = AVAssetWriterInput(AVMediaTypeVideo, mapOf(
                    AVVideoCodecKey to AVVideoCodecTypeH264, AVVideoWidthKey to width, AVVideoHeightKey to height,
                    AVVideoColorPropertiesKey to mapOf(AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_709_2,
                        AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2, AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_709_2),
                    AVVideoCompressionPropertiesKey to mapOf(AVVideoAverageBitRateKey to 2_000_000,
                        AVVideoProfileLevelKey to AVVideoProfileLevelH264MainAutoLevel, AVVideoMaxKeyFrameIntervalDurationKey to 2.0,
                        AVVideoExpectedSourceFrameRateKey to 30),
                ))
                videoInput.expectsMediaDataInRealTime = false
                if (!nativeWriter.canAddInput(videoInput)) compatibilityFailure("video writer")
                nativeWriter.addInput(videoInput)

                val audio = asset.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
                val audioOutput = audio?.let { AVAssetReaderTrackOutput(it, mapOf(AVFormatIDKey to kAudioFormatLinearPCM,
                    AVSampleRateKey to 48_000, AVNumberOfChannelsKey to 2, AVLinearPCMBitDepthKey to 16,
                    AVLinearPCMIsFloatKey to false, AVLinearPCMIsBigEndianKey to false, AVLinearPCMIsNonInterleaved to false)) }
                val audioInput = audio?.let { AVAssetWriterInput(AVMediaTypeAudio, mapOf(AVFormatIDKey to kAudioFormatMPEG4AAC,
                    AVSampleRateKey to 48_000, AVNumberOfChannelsKey to 2, AVEncoderBitRateKey to 128_000)) }
                if (audioOutput != null && audioInput != null) {
                    audioOutput.alwaysCopiesSampleData = false
                    audioInput.expectsMediaDataInRealTime = false
                    if (!nativeReader.canAddOutput(audioOutput) || !nativeWriter.canAddInput(audioInput)) compatibilityFailure("audio codec")
                    nativeReader.addOutput(audioOutput); nativeWriter.addInput(audioInput)
                }
                active()
                if (!nativeWriter.startWriting() || !nativeReader.startReading()) compatibilityFailure("codec start")
                nativeWriter.startSessionAtSourceTime(kCMTimeZero.readValue())
                var videoDone = false
                var audioDone = audioOutput == null
                var lastVideoSeconds = Double.NEGATIVE_INFINITY
                var progressed = TimeSource.Monotonic.markNow()
                while (!videoDone || !audioDone) {
                    active(); segments.checkLimit()
                    if (progressed.elapsedNow().inWholeMilliseconds >= 15_000) compatibilityFailure("codec stalled")
                    if (nativeWriter.status == AVAssetWriterStatusFailed || nativeReader.status == AVAssetReaderStatusFailed) compatibilityFailure("codec processing")
                    var advanced = false
                    if (!videoDone && videoInput.readyForMoreMediaData) {
                        val sample = videoOutput.copyNextSampleBuffer()
                        if (sample == null) { videoInput.markAsFinished(); videoDone = true } else try {
                            val seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                            // Keep source timestamps and gaps; only discard frames
                            // that would exceed the compatible copy's 30 fps cap.
                            if (seconds - lastVideoSeconds >= 1.0 / 30.0 - 0.000_01) {
                                if (!videoInput.appendSampleBuffer(sample)) compatibilityFailure("video encode")
                                lastVideoSeconds = seconds
                            }
                            onProgress((seconds / duration).coerceIn(0.0, 0.99))
                        } finally { CFRelease(sample) }
                        advanced = true
                    }
                    if (!audioDone && audioInput != null && audioOutput != null && audioInput.readyForMoreMediaData) {
                        val sample = audioOutput.copyNextSampleBuffer()
                        if (sample == null) { audioInput.markAsFinished(); audioDone = true }
                        else try { if (!audioInput.appendSampleBuffer(sample)) compatibilityFailure("audio encode") }
                        finally { CFRelease(sample) }
                        advanced = true
                    }
                    if (advanced) progressed = TimeSource.Monotonic.markNow() else delay(5)
                }
                if (nativeReader.status != AVAssetReaderStatusCompleted) {
                    val error = nativeReader.error
                    val domain = when (error?.domain) {
                        AVFoundationErrorDomain -> "AVFoundation"
                        NSOSStatusErrorDomain -> "OSStatus"
                        null -> "none"
                        else -> "other"
                    }
                    compatibilityFailure("incomplete decode reader=${nativeReader.status} writer=${nativeWriter.status} native=$domain/${error?.code ?: 0}")
                }
                nativeWriter.endSessionAtSourceTime(asset.duration)
                val finished = MutableStateFlow(false)
                progressed = TimeSource.Monotonic.markNow()
                nativeWriter.finishWritingWithCompletionHandler { finished.value = true }
                while (!finished.value) {
                    active(); segments.checkLimit()
                    if (nativeWriter.status == AVAssetWriterStatusFailed) compatibilityFailure("encode finalization")
                    if (progressed.elapsedNow().inWholeMilliseconds >= 15_000) compatibilityFailure("finalization stalled")
                    delay(5)
                }
                active(); segments.checkLimit()
                if (nativeWriter.status != AVAssetWriterStatusCompleted) compatibilityFailure("incomplete encode")
                val bytes = segments.finish()
                try { active(); onProgress(1.0); active() } catch (failure: Throwable) { bytes.fill(0); throw failure }
                CompatibleVideoBytes(bytes, width, height, (duration * 1_000).toLong())
            } finally {
                // The delegate is weak. Its setter is illegal after writing
                // starts, including after completion; the closed segment sink
                // rejects late callbacks without changing writer properties.
                segments.wipe()
                if (reader?.status == AVAssetReaderStatusReading) reader?.cancelReading()
                if (writer?.status == AVAssetWriterStatusWriting) writer?.cancelWriting()
                asset.cancelLoading()
                loader.release()
            }
        }
}

/** The built-in compositor converts HDR into 8-bit SDR; encoder tags alone cannot do that. */
internal fun iosSdrVideoComposition(asset: AVAsset, video: AVAssetTrack, width: Int, height: Int, scale: Double): AVMutableVideoComposition =
    AVMutableVideoComposition().apply {
        setRenderSize(CGSizeMake(width.toDouble(), height.toDouble()))
        setSourceTrackIDForFrameTiming(video.trackID)
        setFrameDuration(CMTimeMake(1, 30))
        setColorPrimaries(AVVideoColorPrimaries_ITU_R_709_2)
        setColorTransferFunction(AVVideoTransferFunction_ITU_R_709_2)
        setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_709_2)
        val layer = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(video)
        layer.setTransform(CGAffineTransformConcat(video.preferredTransform, CGAffineTransformMakeScale(scale, scale)), kCMTimeZero.readValue())
        val instruction = AVMutableVideoCompositionInstruction()
        instruction.setTimeRange(CMTimeRangeMake(kCMTimeZero.readValue(), asset.duration))
        instruction.setLayerInstructions(listOf(layer))
        setInstructions(listOf(instruction))
    }

/** Segment callbacks can run off-thread; closing forbids any late plaintext append. */
private class CompatibleVideoSegments : NSObject(), AVAssetWriterDelegateProtocol {
    private val lock = NSLock()
    private val chunks = mutableListOf<ByteArray>()
    private var length = 0
    private var limitExceeded = false
    private var closed = false
    override fun assetWriter(writer: AVAssetWriter, didOutputSegmentData: NSData, segmentType: AVAssetSegmentType) {
        lock.lock()
        try {
            if (closed || limitExceeded) return
            if (didOutputSegmentData.length > (MAX_COMPATIBLE_VIDEO_BYTES - length).toULong()) { limitExceeded = true; return }
            val count = didOutputSegmentData.length.toInt()
            if (count > 0) { chunks += requireNotNull(didOutputSegmentData.bytes).readBytes(count); length += count }
        } finally { lock.unlock() }
    }
    fun checkLimit() {
        lock.lock()
        try { if (limitExceeded) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video size") }
        finally { lock.unlock() }
    }
    fun finish(): ByteArray {
        lock.lock()
        try {
            if (limitExceeded) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video size")
            if (length == 0) compatibilityFailure("empty encode")
            val result = ByteArray(length)
            var offset = 0
            chunks.forEach { it.copyInto(result, offset); offset += it.size; it.fill(0) }
            chunks.clear(); closed = true; length = 0
            return result
        } finally { lock.unlock() }
    }
    fun wipe() {
        lock.lock()
        try { closed = true; chunks.forEach { it.fill(0) }; chunks.clear(); length = 0 }
        finally { lock.unlock() }
    }
}

private fun compatibleCfString(value: CFStringRef): String = memScoped {
    val capacity = CFStringGetLength(value) * 4 + 1
    val bytes = allocArray<ByteVar>(capacity.toInt())
    if (!CFStringGetCString(value, bytes, capacity, kCFStringEncodingUTF8)) compatibilityFailure("pixel format")
    bytes.toKString()
}

private fun compatibilityFailure(stage: String): Nothing = throw ChurFailure(ChurStatus.IO_FAILURE, "compatible video $stage")
