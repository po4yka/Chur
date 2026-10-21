package dev.po4yka.chur.app.vault

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultState
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
actual fun rememberVideoCompatibilityEncoder(): VideoCompatibilityEncoder {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidVideoCompatibilityEncoder(context) }
}

/** Explicit viewing copy. Input is authenticated ranges; output is a bounded RAM-backed descriptor. */
class AndroidVideoCompatibilityEncoder(private val context: Context) : VideoCompatibilityEncoder {
    override suspend fun encode(
        source: PlaybackSource,
        cancelRequested: () -> Boolean,
        onProgress: (Double) -> Unit,
    ): CompatibleVideoBytes = withContext(Dispatchers.Default) {
        val coroutine = currentCoroutineContext()
        val operationStarted = System.nanoTime()
        val generation = (source.vault.state.value as? VaultState.Unlocked)?.generation
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "compatible video session")
        val callbackFailure = AtomicReference<Throwable?>(null)
        fun checkActive() {
            coroutine.ensureActive()
            if (System.nanoTime() - operationStarted > 300_000_000_000L) {
                throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video runtime")
            }
            if (cancelRequested()) throw CancellationException("Compatible video cancelled")
            if ((source.vault.state.value as? VaultState.Unlocked)?.generation != generation) {
                throw ChurFailure(ChurStatus.SESSION_EXPIRED, "compatible video session")
            }
            callbackFailure.get()?.let { throw it }
        }
        checkActive()
        val reader = source.vault.leaseReader(source.objectId, source.kind, generation)
        val worker = HandlerThread("chur-compatible-video-io").apply { start() }
        var descriptor: ParcelFileDescriptor? = null
        var decoder: MediaCodec? = null
        var fallbackRetriever: MediaMetadataRetriever? = null
        var encoder: MediaCodec? = null
        var bridge: EncoderSurfaceBridge? = null
        var encoderSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val extractor = MediaExtractor()
        val output = BoundedVideoOutput(MAX_COMPATIBLE_VIDEO_BYTES, callbackFailure, ::checkActive)
        val cacheLock = Any()
        var cachedOffset = -1L
        var cached = ByteArray(0)
        fun input() = object : MediaDataSource() {
            override fun getSize(): Long = source.length
            override fun close() = Unit // The operation, not an extractor, owns the lease.
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                try {
                    checkActive()
                    if (position < 0) throw IOException("Invalid compatible video range")
                    if (position >= source.length) return -1
                    return synchronized(cacheLock) {
                        val wanted = min(size.toLong(), source.length - position).toInt()
                        var copied = 0
                        while (copied < wanted) {
                            checkActive()
                            val at = position + copied
                            if (cachedOffset < 0 || at < cachedOffset || at >= cachedOffset + cached.size) {
                                cached.fill(0)
                                cachedOffset = at / 1_048_576 * 1_048_576
                                cached = source.vault.readLeased(reader, source.offset + cachedOffset,
                                    minOf(1_048_576L, source.length - cachedOffset).toInt())
                                if (cached.isEmpty()) break
                            }
                            val index = (at - cachedOffset).toInt()
                            val count = min(wanted - copied, cached.size - index)
                            cached.copyInto(buffer, offset + copied, index, index + count)
                            copied += count
                        }
                        if (copied == 0) -1 else copied
                    }
                } catch (failure: Exception) {
                    callbackFailure.compareAndSet(null, failure)
                    throw IOException("Compatible video input unavailable", failure)
                }
            }
        }
        try {
            val info = source.vault.readerContentInfo(reader)
            require(info.complete && info.byteRangeSupported && source.offset >= 0 && source.length > 0 &&
                source.offset <= info.plaintextSize && source.length <= info.plaintextSize - source.offset)
            extractor.setDataSource(input())
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: unsupported("compatible video track")
            val video = extractor.getTrackFormat(videoTrack)
            val audioTracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (audioTracks.any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC }) {
                unsupported("compatible video audio codec")
            }
            val width = video.getInteger(MediaFormat.KEY_WIDTH)
            val height = video.getInteger(MediaFormat.KEY_HEIGHT)
            if (width <= 0 || height <= 0 || width > 7680 || height > 7680) unsupported("compatible video dimensions")
            val durationUs = video.getLong(MediaFormat.KEY_DURATION)
            if (durationUs !in 1_000L..14_400_000_000L) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video duration")
            val rotation = if (video.containsKey(MediaFormat.KEY_ROTATION))
                ((video.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360 else 0
            if (rotation !in listOf(0, 90, 180, 270)) unsupported("compatible video rotation")
            val scale = min(1.0, min(1280.0 / maxOf(width, height), 720.0 / minOf(width, height)))
            val outputWidth = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val outputHeight = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val hdr = video.integerOrNull(MediaFormat.KEY_COLOR_TRANSFER) in
                listOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)
            if (hdr && Build.VERSION.SDK_INT < 31) unsupported("compatible video HDR tone mapping")
            val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(outputFormat)
                ?: unsupported("compatible video AVC encoder")
            val videoEncoder = MediaCodec.createByCodecName(encoderName)
            encoder = videoEncoder
            videoEncoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = videoEncoder.createInputSurface()
            videoEncoder.start()
            val surfaceBridge = EncoderSurfaceBridge(requireNotNull(encoderSurface), outputWidth, outputHeight, Handler(worker.looper), ::checkActive)
            bridge = surfaceBridge
            // Preserve the original display matrix once, in the MP4 track.
            video.setInteger(MediaFormat.KEY_ROTATION, 0)
            if (hdr) video.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            val videoDecoder = if (hdr) {
                // Tone mapping is optional and findDecoderForFormat selects only
                // by coded format. Try each native component, keeping the one
                // that acknowledges this operation's actual request.
                var accepted: MediaCodec? = null
                val mime = requireNotNull(video.getString(MediaFormat.KEY_MIME))
                val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                    !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
                }
                for (candidate in candidates) {
                    checkActive()
                    var codec: MediaCodec? = null
                    var started = false
                    try {
                        codec = MediaCodec.createByCodecName(candidate.name)
                        codec.configure(video, surfaceBridge.surface, null, 0)
                        if (codec.inputFormat.integerOrNull(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
                            codec.start()
                            started = true
                            accepted = codec
                            break
                        }
                    } catch (_: MediaCodec.CodecException) {
                        // A native component may reject the coded profile.
                    } catch (_: IllegalArgumentException) {
                        // Try the next component without changing the request.
                    } finally {
                        if (codec !== accepted) {
                            if (started) runCatching { codec?.stop() }
                            runCatching { codec?.release() }
                        }
                    }
                }
                accepted
            } else {
                val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(video)
                    ?: unsupported("compatible video decoder")
                MediaCodec.createByCodecName(decoderName).also { codec ->
                    decoder = codec
                    codec.configure(video, surfaceBridge.surface, null, 0)
                    codec.start()
                }
            }
            decoder = videoDecoder
            descriptor = requireNotNull(context.getSystemService(StorageManager::class.java)).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_WRITE, output, Handler(worker.looper),
            )
            val videoMuxer = MediaMuxer(requireNotNull(descriptor).fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = videoMuxer
            videoMuxer.setOrientationHint(rotation)
            val muxedAudio = audioTracks.associateWith { videoMuxer.addTrack(extractor.getTrackFormat(it)) }
            extractor.selectTrack(videoTrack)
            val fallbackTimes = if (videoDecoder == null) {
                // Use the platform retriever's native SDR capture path when
                // codecs cannot acknowledge optional surface tone mapping.
                // Each selected source PTS stays on its original timeline.
                val times = mutableListOf<Long>()
                while (extractor.sampleTrackIndex >= 0) {
                    checkActive()
                    if (times.size >= 1_000_000) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video frame table")
                    times += extractor.sampleTime
                    extractor.advance()
                }
                times.sort()
                var previous = Long.MIN_VALUE
                val selected = times.filter { time ->
                    if (time < 0 || (previous != Long.MIN_VALUE && time - previous < 33_333)) false
                    else { previous = time; true }
                }
                if (selected.isEmpty()) unsupported("compatible video frames")
                fallbackRetriever = MediaMetadataRetriever().also { it.setDataSource(input()) }
                selected
            } else emptyList()
            var fallbackIndex = 0
            var videoOutputTrack = -1
            var inputEnded = false
            var decoderEnded = false
            var encoderEnded = false
            var lastRenderedUs = Long.MIN_VALUE
            var renderedFrames = 0
            var outputChecked = false
            val decodedInfo = MediaCodec.BufferInfo()
            val encodedInfo = MediaCodec.BufferInfo()
            var lastWork = System.nanoTime()
            val started = lastWork
            while (!encoderEnded) {
                checkActive()
                var worked = false
                while (true) {
                    val index = videoEncoder.dequeueOutputBuffer(encodedInfo, 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    worked = true
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val encoded = videoEncoder.outputFormat
                        requireSdr709(encoded)
                        if (encoded.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_AVC) unsupported("compatible video AVC output")
                        videoOutputTrack = videoMuxer.addTrack(encoded)
                        videoMuxer.start()
                        muxerStarted = true
                    } else if (index >= 0) {
                        try {
                            if (encodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encodedInfo.size > 0) {
                                check(muxerStarted && videoOutputTrack >= 0)
                                videoMuxer.writeSampleData(videoOutputTrack, requireNotNull(videoEncoder.getOutputBuffer(index)), encodedInfo)
                            }
                            if (encodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEnded = true
                        } finally { videoEncoder.releaseOutputBuffer(index, false) }
                    }
                }
                if (!inputEnded && videoDecoder != null) {
                    val index = videoDecoder.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(videoDecoder.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            videoDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            videoDecoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        worked = true
                    }
                }
                if (!decoderEnded && videoDecoder == null) {
                    val time = fallbackTimes[fallbackIndex++]
                    val frame = fallbackRetriever!!.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST,
                        if (rotation in listOf(90, 270)) outputHeight else outputWidth,
                        if (rotation in listOf(90, 270)) outputWidth else outputHeight,
                        MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 },
                    ) ?: unsupported("compatible video native SDR frame")
                    try {
                        checkActive()
                        // Native capture can retain ten-bit storage even
                        // after tone mapping to SDR sRGB. Canvas below does
                        // the actual eight-bit raster conversion.
                        val nativeSdrConfig = frame.config == Bitmap.Config.ARGB_8888 ||
                            (Build.VERSION.SDK_INT >= 33 && frame.config == Bitmap.Config.RGBA_1010102 && frame.colorSpace?.isSrgb == true)
                        if (!nativeSdrConfig || frame.colorSpace == null ||
                            (Build.VERSION.SDK_INT >= 34 && frame.colorSpace in listOf(
                                ColorSpace.get(ColorSpace.Named.BT2020_PQ), ColorSpace.get(ColorSpace.Named.BT2020_HLG)))) {
                            unsupported("compatible video native SDR pixels")
                        }
                        val sdr = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888, false,
                            ColorSpace.get(ColorSpace.Named.SRGB))
                        try {
                            // Retriever applies the display rotation. Restore
                            // coded orientation here; the muxer retains it once.
                            val transform = Matrix().apply { setRotate(-rotation.toFloat()) }
                            val bounds = RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
                            transform.mapRect(bounds)
                            transform.postTranslate(-bounds.left, -bounds.top)
                            transform.postScale(outputWidth / bounds.width(), outputHeight / bounds.height())
                            Canvas(sdr).drawBitmap(frame, transform, Paint(Paint.FILTER_BITMAP_FLAG))
                            check(sdr.config == Bitmap.Config.ARGB_8888 && sdr.colorSpace?.isSrgb == true)
                            surfaceBridge.drawBitmap(sdr, time)
                        } finally { sdr.eraseColor(android.graphics.Color.BLACK); sdr.recycle() }
                    } finally { frame.recycle() }
                    renderedFrames++
                    lastRenderedUs = time
                    onProgress((time.toDouble() / durationUs).coerceIn(0.0, 0.95))
                    if (fallbackIndex == fallbackTimes.size) {
                        decoderEnded = true
                        videoEncoder.signalEndOfInputStream()
                    }
                    worked = true
                }
                if (!decoderEnded && videoDecoder != null) {
                    val index = videoDecoder.dequeueOutputBuffer(decodedInfo, 1_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        requireSdr709(videoDecoder.outputFormat)
                        outputChecked = true
                        worked = true
                    } else if (index >= 0) {
                        check(outputChecked) { "Decoder output color format unavailable" }
                        val render = decodedInfo.size > 0 && decodedInfo.presentationTimeUs >= 0 &&
                            (lastRenderedUs == Long.MIN_VALUE || decodedInfo.presentationTimeUs - lastRenderedUs >= 33_333)
                        videoDecoder.releaseOutputBuffer(index, render)
                        if (render) {
                            surfaceBridge.draw(decodedInfo.presentationTimeUs)
                            lastRenderedUs = decodedInfo.presentationTimeUs
                            renderedFrames++
                            onProgress((lastRenderedUs.toDouble() / durationUs.coerceAtLeast(1)).coerceIn(0.0, 0.95))
                        }
                        if (decodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderEnded = true
                            videoEncoder.signalEndOfInputStream()
                        }
                        worked = true
                    }
                }
                if (worked) lastWork = System.nanoTime()
                if (System.nanoTime() - lastWork > 15_000_000_000L) throw IOException("Compatible video codec stalled")
                if (System.nanoTime() - started > 300_000_000_000L) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video runtime")
            }
            if (renderedFrames == 0) unsupported("compatible video frames")
            // AAC packets retain their original timeline, including any offset
            // relative to video. A source with no audio remains silent.
            if (audioTracks.isNotEmpty()) {
                val audio = MediaExtractor()
                val bytes = ByteBuffer.allocateDirect(1024 * 1024)
                try {
                    audio.setDataSource(input())
                    audioTracks.forEach(audio::selectTrack)
                    val sample = MediaCodec.BufferInfo()
                    while (audio.sampleTrackIndex >= 0) {
                        checkActive()
                        if (audio.sampleSize > bytes.capacity()) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video audio packet")
                        bytes.clear()
                        val count = audio.readSampleData(bytes, 0)
                        if (count < 0) break
                        sample.set(0, count, audio.sampleTime, if (audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        videoMuxer.writeSampleData(muxedAudio.getValue(audio.sampleTrackIndex), bytes, sample)
                        audio.advance()
                    }
                } finally {
                    bytes.clear()
                    while (bytes.hasRemaining()) bytes.put(0)
                    audio.release()
                }
            }
            checkActive()
            videoMuxer.stop()
            muxerStarted = false
            videoMuxer.release()
            muxer = null
            checkActive()
            val bytes = output.copyBytes()
            try {
                checkActive()
                onProgress(1.0)
                checkActive()
                CompatibleVideoBytes(bytes, if (rotation in listOf(90, 270)) outputHeight else outputWidth,
                    if (rotation in listOf(90, 270)) outputWidth else outputHeight, durationUs / 1000)
            } catch (failure: Throwable) { bytes.fill(0); throw failure }
        } catch (failure: Throwable) {
            callbackFailure.get()?.let { throw it }
            throw failure
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { fallbackRetriever?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { bridge?.close() }
            runCatching { encoderSurface?.release() }
            runCatching { extractor.release() }
            synchronized(cacheLock) { cached.fill(0); cached = ByteArray(0) }
            runCatching { descriptor?.close() }
            output.clear()
            worker.quitSafely()
            try { worker.join(2_000) } finally { source.vault.releaseReader(reader) }
        }
    }
}

private fun unsupported(where: String): Nothing = throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, where)
private fun MediaFormat.integerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
private fun requireSdr709(format: MediaFormat) {
    if (format.integerOrNull(MediaFormat.KEY_COLOR_TRANSFER) != MediaFormat.COLOR_TRANSFER_SDR_VIDEO ||
        format.integerOrNull(MediaFormat.KEY_COLOR_STANDARD) != MediaFormat.COLOR_STANDARD_BT709) {
        unsupported("compatible video SDR output color")
    }
}

/** FUSE callbacks reject growth before allocation or a write, including muxer headers. */
private class BoundedVideoOutput(
    private val limit: Int,
    private val failure: AtomicReference<Throwable?>,
    private val checkActive: () -> Unit,
) : ProxyFileDescriptorCallback() {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0
    private var closed = false
    @Synchronized override fun onGetSize(): Long = size.toLong()
    @Synchronized override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (closed || offset < 0) throw ErrnoException("compatible video read", OsConstants.EBADF)
        val count = min(size.toLong(), (this.size.toLong() - offset).coerceAtLeast(0)).toInt()
        var copied = 0
        while (copied < count) {
            val position = offset.toInt() + copied
            val length = min(count - copied, CHUNK - position % CHUNK)
            chunks[position / CHUNK].copyInto(data, copied, position % CHUNK, position % CHUNK + length)
            copied += length
        }
        return count
    }
    @Synchronized override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
        try {
            checkActive()
            if (closed || offset < 0 || offset > limit || size < 0 || size > data.size || size > limit - offset) {
                throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "compatible video bytes")
            }
            val end = offset.toInt() + size
            while (chunks.size * CHUNK < end) chunks += ByteArray(CHUNK)
            var copied = 0
            while (copied < size) {
                val position = offset.toInt() + copied
                val count = min(size - copied, CHUNK - position % CHUNK)
                data.copyInto(chunks[position / CHUNK], position % CHUNK, copied, copied + count)
                copied += count
            }
            this.size = maxOf(this.size, end)
            return size
        } catch (problem: Exception) {
            failure.compareAndSet(null, problem)
            throw ErrnoException("compatible video write", if (problem is ChurFailure && problem.status == ChurStatus.RESOURCE_LIMIT_EXCEEDED) OsConstants.ENOSPC else OsConstants.EIO)
        }
    }
    override fun onFsync() = Unit
    override fun onRelease() = Unit
    @Synchronized fun copyBytes(): ByteArray = ByteArray(size).also { result ->
        var copied = 0
        for (chunk in chunks) {
            val count = min(chunk.size, size - copied)
            chunk.copyInto(result, copied, 0, count)
            copied += count
        }
    }
    @Synchronized fun clear() { closed = true; chunks.forEach { it.fill(0) }; chunks.clear(); size = 0 }
    private companion object { const val CHUNK = 256 * 1024 }
}

/** Samples only acknowledged SDR decoder frames; this shader does no HDR retagging or tone map. */
private class EncoderSurfaceBridge(
    encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    handler: Handler,
    private val checkActive: () -> Unit,
) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var eglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var bitmapTexture = 0
    private var bitmapProgram = 0
    private val transform = FloatArray(16)
    private val frameLock = Object()
    private var frameAvailable = false
    private val vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coordinates = floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private var image: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    val surface: Surface get() = requireNotNull(inputSurface)
    init {
        try {
            check(EGL14.eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = requireNotNull(configs[0])
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            window = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, window, window, eglContext))
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            image = SurfaceTexture(texture).apply {
                setOnFrameAvailableListener({ synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() } }, handler)
            }
            inputSurface = Surface(requireNotNull(image))
            program = GLES20.glCreateProgram()
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition; attribute vec2 aCoordinate; uniform mat4 uTransform; varying vec2 vCoordinate; void main(){gl_Position=aPosition;vCoordinate=(uTransform*vec4(aCoordinate,0.0,1.0)).xy;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vCoordinate; uniform samplerExternalOES uImage; void main(){gl_FragColor=texture2D(uImage,vCoordinate);}")
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun draw(timeUs: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        synchronized(frameLock) {
            while (!frameAvailable) {
                checkActive()
                if (System.nanoTime() >= deadline) throw IOException("Compatible video surface stalled")
                frameLock.wait(40)
            }
            frameAvailable = false
        }
        requireNotNull(image).updateTexImage()
        requireNotNull(image).getTransformMatrix(transform)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uImage"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(program, "aCoordinate")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        check(EGLExt.eglPresentationTimeANDROID(display, window, timeUs * 1000))
        check(EGL14.eglSwapBuffers(display, window))
    }
    fun drawBitmap(bitmap: Bitmap, timeUs: Long) {
        checkActive()
        if (bitmapProgram == 0) {
            val textureIds = IntArray(1)
            GLES20.glGenTextures(1, textureIds, 0)
            bitmapTexture = textureIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            bitmapProgram = GLES20.glCreateProgram()
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition; attribute vec2 aCoordinate; varying vec2 vCoordinate; void main(){gl_Position=aPosition;vCoordinate=vec2(aCoordinate.x,1.0-aCoordinate.y);}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vCoordinate; uniform sampler2D uImage; void main(){gl_FragColor=texture2D(uImage,vCoordinate);}")
            GLES20.glAttachShader(bitmapProgram, vertex)
            GLES20.glAttachShader(bitmapProgram, fragment)
            GLES20.glLinkProgram(bitmapProgram)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(bitmapProgram, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            check(linked[0] == GLES20.GL_TRUE)
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(bitmapProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bitmapProgram, "uImage"), 0)
        val position = GLES20.glGetAttribLocation(bitmapProgram, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(bitmapProgram, "aCoordinate")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        check(EGLExt.eglPresentationTimeANDROID(display, window, timeUs * 1000))
        check(EGL14.eglSwapBuffers(display, window))
    }
    override fun close() {
        inputSurface?.release()
        image?.release()
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteProgram(bitmapProgram)
        GLES20.glDeleteTextures(1, intArrayOf(bitmapTexture), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, window)
        EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
    private fun shader(type: Int, text: String): Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, text)
        GLES20.glCompileShader(it)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE)
    }
    private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
