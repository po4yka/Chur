package dev.po4yka.chur.app.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
actual fun rememberVideoTimelineGenerator(): VideoTimelineGenerator {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidVideoTimelineGenerator(context) }
}

/** Twelve SDR frames sampled from authenticated ranges; no plaintext scratch or player side effect. */
class AndroidVideoTimelineGenerator(@Suppress("UNUSED_PARAMETER") context: Context) : VideoTimelineGenerator {
    override suspend fun generate(source: PlaybackSource, cancelRequested: () -> Boolean): VideoTimelineBytes =
        withContext(Dispatchers.Default) {
            val coroutine = currentCoroutineContext()
            val started = System.nanoTime()
            val generation = (source.vault.state.value as? VaultState.Unlocked)?.generation
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "video timeline session")
            val callbackFailure = AtomicReference<Throwable?>(null)
            fun checkActive() {
                coroutine.ensureActive()
                if (System.nanoTime() - started > 30_000_000_000L) {
                    throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "video timeline runtime")
                }
                if (cancelRequested()) throw CancellationException("Video timeline cancelled")
                if ((source.vault.state.value as? VaultState.Unlocked)?.generation != generation) {
                    throw ChurFailure(ChurStatus.SESSION_EXPIRED, "video timeline session")
                }
                callbackFailure.get()?.let { throw it }
            }
            checkActive()
            val reader = source.vault.leaseReader(source.objectId, source.kind, generation)
            val retriever = MediaMetadataRetriever()
            val extractor = MediaExtractor()
            var atlas: Bitmap? = null
            // Native frame seeking makes many small reads from the same
            // authenticated chunks. Keep one bounded private plaintext window
            // rather than decrypting that chunk again for every parser read.
            var cachedOffset = -1L
            var cached = ByteArray(0)
            val cacheLock = Any()
            fun input() = object : MediaDataSource() {
                override fun close() = Unit
                override fun getSize() = source.length
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    try {
                        checkActive()
                        if (position < 0) throw IOException("Invalid video timeline range")
                        if (position >= source.length) return -1
                        return synchronized(cacheLock) {
                            var copied = 0
                            val wanted = minOf(size.toLong(), source.length - position).toInt()
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
                                val count = minOf(wanted - copied, cached.size - index)
                                cached.copyInto(buffer, offset + copied, index, index + count)
                                copied += count
                            }
                            if (copied == 0) -1 else copied
                        }
                    } catch (failure: Exception) {
                        callbackFailure.compareAndSet(null, failure)
                        throw IOException("Video timeline input unavailable", failure)
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
                } ?: throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, "video timeline track")
                val videoFormat = extractor.getTrackFormat(videoTrack)
                val hdr = videoFormat.isTimelineHdr()
                if (hdr && Build.VERSION.SDK_INT < 30) {
                    throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, "video timeline HDR preview")
                }
                retriever.setDataSource(input())
                checkActive()
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, "video timeline duration")
                if (durationMs !in 1L..14_400_000L) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "video timeline duration")
                val image = Bitmap.createBitmap(640, 270, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
                atlas = image
                val canvas = Canvas(image)
                canvas.drawColor(Color.BLACK)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                if (!hdr) {
                    extractor.selectTrack(videoTrack)
                    sequentialTimeline(extractor, videoFormat, durationMs * 1000L, canvas, ::checkActive)
                } else for (slot in 0 until 12) {
                    checkActive()
                    val timeUs = durationMs * 1000L * (2 * slot + 1) / 24
                    val frame = if (Build.VERSION.SDK_INT >= 30) retriever.getScaledFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 160, 90,
                        MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 },
                    ) else retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 160, 90)
                    if (frame == null) {
                        checkActive()
                        throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, "video timeline frame")
                    }
                    try {
                        checkActive()
                        val color = frame.colorSpace
                        if (color == null || (Build.VERSION.SDK_INT >= 34 &&
                                color in listOf(ColorSpace.get(ColorSpace.Named.BT2020_PQ), ColorSpace.get(ColorSpace.Named.BT2020_HLG)))) {
                            throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, "video timeline SDR frame")
                        }
                        val scale = min(160f / frame.width, 90f / frame.height)
                        val left = (slot % 4) * 160 + (160 - frame.width * scale) / 2
                        val top = (slot / 4) * 90 + (90 - frame.height * scale) / 2
                        canvas.drawBitmap(frame, null, RectF(left, top, left + frame.width * scale, top + frame.height * scale), paint)
                    } finally { frame.recycle() }
                }
                checkActive()
                WipedJpegOutput().use { encoded ->
                    check(image.compress(Bitmap.CompressFormat.JPEG, 82, encoded))
                    if (encoded.size() > 2 * 1024 * 1024) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "video timeline bytes")
                    val bytes = encoded.toByteArray()
                    try { checkActive(); VideoTimelineBytes(bytes, 640, 270) }
                    catch (failure: Throwable) { bytes.fill(0); throw failure }
                }
            } catch (failure: Throwable) {
                callbackFailure.get()?.let { throw it }
                throw failure
            } finally {
                runCatching { atlas?.let { it.eraseColor(Color.BLACK); it.recycle() } }
                runCatching { retriever.release() }
                runCatching { extractor.release() }
                synchronized(cacheLock) { cached.fill(0); cached = ByteArray(0) }
                source.vault.releaseReader(reader)
            }
        }
}

private fun MediaFormat.isTimelineHdr(): Boolean = containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
    getInteger(MediaFormat.KEY_COLOR_TRANSFER) in listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)

/** Decode each GOP once. Adjacent presentation timestamps select the closest VFR frame. */
private fun sequentialTimeline(extractor: MediaExtractor, format: MediaFormat, durationUs: Long,
    canvas: Canvas, checkActive: () -> Unit) {
    val width = format.getInteger(MediaFormat.KEY_WIDTH)
    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
    if (width !in 1..7680 || height !in 1..7680) throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "video timeline dimensions")
    val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360 else 0
    if (rotation !in listOf(0, 90, 180, 270)) throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, "video timeline rotation")
    val worker = HandlerThread("chur-timeline-frame").apply { start() }
    var bridge: TimelineSurface? = null
    var decoder: MediaCodec? = null
    var decoderStarted = false
    try {
        val frames = TimelineSurface(width, height, rotation, Handler(worker.looper), checkActive)
        bridge = frames
        // Apply the source display rotation once, in the bounded render target.
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
        val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        decoder = codec
        codec.configure(format, frames.surface, null, 0)
        codec.start()
        decoderStarted = true
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var heldIndex = -1
        var heldTime = 0L
        var slot = 0
        fun target() = durationUs * (2 * slot + 1) / 24
        fun drawHeld(throughTime: Long) {
            if (heldIndex < 0 || slot >= 12 || target() > throughTime) return
            val index = heldIndex
            heldIndex = -1
            codec.releaseOutputBuffer(index, true)
            val bitmap = frames.readFrame()
            try {
                while (slot < 12 && target() <= throughTime) {
                    canvas.drawBitmap(bitmap, (slot % 4 * 160).toFloat(), (slot / 4 * 90).toFloat(), null)
                    slot++
                }
            } finally { bitmap.eraseColor(Color.BLACK); bitmap.recycle() }
        }
        while (!outputEnded && slot < 12) {
            checkActive()
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(inputIndex))
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, 1_000)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (codec.outputFormat.isTimelineHdr()) throw ChurFailure(ChurStatus.UNSUPPORTED_VERSION, "video timeline SDR decode")
                frames.updateDimensions(codec.outputFormat)
            } else if (outputIndex >= 0) {
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    if (heldIndex >= 0) {
                        if (info.presentationTimeUs < heldTime) throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline frame order")
                        drawHeld(heldTime + (info.presentationTimeUs - heldTime) / 2)
                        if (heldIndex >= 0) codec.releaseOutputBuffer(heldIndex, false)
                    }
                    heldIndex = outputIndex
                    heldTime = info.presentationTimeUs
                } else codec.releaseOutputBuffer(outputIndex, false)
            }
        }
        if (outputEnded) drawHeld(durationUs)
        if (heldIndex >= 0) codec.releaseOutputBuffer(heldIndex, false)
        checkActive()
        if (slot != 12) throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline incomplete frames")
    } finally {
        if (decoderStarted) runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { bridge?.close() }
        worker.quitSafely()
        worker.join(1_000)
    }
}

/** The GPU samples native SDR output into one 160×90 sRGB cell with black letterboxes. */
private class TimelineSurface(private var width: Int, private var height: Int, private val rotation: Int,
    handler: Handler, private val checkActive: () -> Unit) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var image: SurfaceTexture? = null
    private var input: Surface? = null
    val surface: Surface get() = requireNotNull(input)
    private val frameLock = Object()
    private var available = false
    private val transform = FloatArray(16)
    private val vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coordinates = floats(when (rotation) {
        90 -> floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
        180 -> floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f)
        270 -> floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        else -> floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    })
    private val pixels = ByteBuffer.allocateDirect(160 * 90 * 4)
    private val colors = IntArray(160 * 90)
    init {
        try {
            check(EGL14.eglInitialize(display, null, 0, null, 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 160, EGL14.EGL_HEIGHT, 90, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            image = SurfaceTexture(texture).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({ synchronized(frameLock) { available = true; frameLock.notifyAll() } }, handler)
            }
            input = Surface(requireNotNull(image))
            program = GLES20.glCreateProgram()
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition; attribute vec2 aCoordinate; uniform mat4 uTransform; varying vec2 vCoordinate; void main(){gl_Position=aPosition;vCoordinate=(uTransform*vec4(aCoordinate,0.0,1.0)).xy;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vCoordinate; uniform samplerExternalOES uImage; void main(){gl_FragColor=texture2D(uImage,vCoordinate);}")
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE)
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun updateDimensions(format: MediaFormat) {
        width = if (format.containsKey("crop-right") && format.containsKey("crop-left"))
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else format.getInteger(MediaFormat.KEY_WIDTH)
        height = if (format.containsKey("crop-bottom") && format.containsKey("crop-top"))
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else format.getInteger(MediaFormat.KEY_HEIGHT)
        require(width in 1..7680 && height in 1..7680)
    }
    fun readFrame(): Bitmap {
        val deadline = System.nanoTime() + 5_000_000_000L
        synchronized(frameLock) {
            while (!available) {
                checkActive()
                if (System.nanoTime() >= deadline) throw ChurFailure(ChurStatus.IO_FAILURE, "video timeline surface stalled")
                frameLock.wait(20)
            }
            available = false
        }
        checkActive()
        requireNotNull(image).updateTexImage()
        requireNotNull(image).getTransformMatrix(transform)
        GLES20.glViewport(0, 0, 160, 90)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val uprightWidth = if (rotation == 90 || rotation == 270) height else width
        val uprightHeight = if (rotation == 90 || rotation == 270) width else height
        val scale = min(160f / uprightWidth, 90f / uprightHeight)
        val renderWidth = (uprightWidth * scale).roundToInt().coerceIn(1, 160)
        val renderHeight = (uprightHeight * scale).roundToInt().coerceIn(1, 90)
        GLES20.glViewport((160 - renderWidth) / 2, (90 - renderHeight) / 2, renderWidth, renderHeight)
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
        pixels.position(0)
        GLES20.glReadPixels(0, 0, 160, 90, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        for (y in 0 until 90) for (x in 0 until 160) {
            val offset = ((89 - y) * 160 + x) * 4
            colors[y * 160 + x] = Color.rgb(pixels.get(offset).toInt() and 255,
                pixels.get(offset + 1).toInt() and 255, pixels.get(offset + 2).toInt() and 255)
        }
        return Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB)).apply {
            setPixels(colors, 0, 160, 0, 0, 160, 90)
        }
    }
    override fun close() {
        input?.release()
        image?.release()
        colors.fill(0)
        pixels.clear()
        while (pixels.hasRemaining()) pixels.put(0.toByte())
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
    private fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, source)
        GLES20.glCompileShader(it)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE)
    }
    private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}

private class WipedJpegOutput : ByteArrayOutputStream() {
    override fun close() { buf.fill(0); reset(); super.close() }
}
