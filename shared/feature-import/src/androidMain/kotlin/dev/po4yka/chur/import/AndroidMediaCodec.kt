package dev.po4yka.chur.imports

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.media.AudioFormat
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.po4yka.chur.ffi.ChurBuffer
import dev.po4yka.chur.ffi.JpegXlDecoder
import dev.po4yka.chur.ffi.StreamKind
import java.io.InputStream
import java.io.FilterInputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * The Android codec side of `MEDIA_PIPELINE.md` §1.
 *
 * Every decode opens its own stream from the content URI rather than reusing
 * the descriptor the import is reading. Two readers of one descriptor share a
 * file position, so a probe would leave the import to start from wherever the
 * probe stopped, and the descriptor belongs to the import: closing or seeking
 * it here would be reaching into another operation's resource.
 */
class AndroidMediaCodec private constructor(
    private val resolver: ContentResolver,
    private val decoderContext: Context?,
    private val sourceDescriptor: ParcelFileDescriptor?,
    private val sourceOffset: Long = 0,
    private val sourceLength: Long? = null,
) : MediaCodec {
    constructor(resolver: ContentResolver) : this(resolver, null, null)
    constructor(context: Context) : this(context.contentResolver, context.applicationContext, null)
    internal constructor(resolver: ContentResolver, source: ParcelFileDescriptor, offset: Long = 0, length: Long? = null) : this(resolver, null, source, offset, length)

    override suspend fun deriveBounded(
        media: PickedMedia,
        probe: ProbedMedia,
        kind: StreamKind,
        cancelRequested: () -> Boolean,
    ): Derivative? {
        val context = decoderContext ?: return derive(media, probe, kind, cancelRequested)
        return AndroidDerivativeClient(context).derive(media, probe, kind, cancelRequested)
    }

    /**
     * Opens a picked content URI, §3.
     *
     * The provider's length and type are hints. `OpenableColumns` is where a
     * Storage Access Framework provider publishes them, and a provider that
     * publishes neither is ordinary rather than an error: §3 makes the length
     * optional and Rust re-derives the authenticated size from the final commit
     * record.
     */
    fun open(uri: Uri, asFile: Boolean = false): PickedMedia? {
        val descriptor: ParcelFileDescriptor = try {
            resolver.openFileDescriptor(uri, "r") ?: return null
        } catch (_: Exception) {
            return null
        }
        try {
            val seekable = try {
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
                true
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.ESPIPE) false else throw failure
            }
            var name: String? = null
            var size: Long? = null
            var taken: Long? = null
            // Metadata is optional. A provider can allow byte reads while refusing a query.
            runCatching { resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                        name = cursor.getString(nameColumn)
                    }
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                    }
                    // The photo picker and the media store publish the time
                    // an item was taken under one column name.
                    val takenColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (takenColumn >= 0 && !cursor.isNull(takenColumn)) {
                        taken = cursor.getLong(takenColumn).takeIf { it > 0 }
                    }
                }
            } }
            val livePhoto = if (seekable) readLivePhotoBundle(descriptor.fd, size, validatePayloads = false) else null
            val type = if (livePhoto != null) LivePhotoBundle.CONTENT_TYPE else sourceType(uri, name, runCatching { resolver.getType(uri) }.getOrNull())
            return PickedMedia(
                descriptor = descriptor.fd,
                seekable = seekable,
                knownLength = size,
                contentTypeHint = type,
                originalFilename = name,
                captureTimeMs = taken ?: if (asFile) null else embeddedCaptureTime(uri, normalizeType(type)),
                platformHandle = uri,
                close = { descriptor.close() },
                importAsFile = asFile && livePhoto == null,
                livePhoto = livePhoto,
            )
        } catch (_: Exception) {
            descriptor.close()
            return null
        }
    }

    /**
     * The capture time the source itself records, for a provider that
     * publishes none, §8.1 of the catalog.
     *
     * A document provider has no taken-time column, so the time is read
     * from the file: a photo's EXIF original time, and a video container's
     * creation date. Each read opens its own stream, as every decode here
     * does. The value is a hint, `MEDIA_PIPELINE.md` §3, and Rust stores a
     * substituted time for an absent one.
     */
    private fun embeddedCaptureTime(uri: Uri, type: String): Long? = when {
        type.startsWith("image/") -> read(uri) { stream ->
            val exif = ExifInterface(stream)
            // The offset tag is API 30. The name is a constant the compiler
            // inlines, and an API 29 reader that does not know the tag
            // returns null, so the time is read in the device's zone.
            exifCaptureTimeMs(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                ZoneId.systemDefault(),
            )
        }
        type.startsWith("video/") -> withRetriever(uri) { retriever ->
            containerCaptureTimeMs(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
        }
        else -> null
    }

    override fun probe(media: PickedMedia): ProbedMedia? {
        media.livePhoto?.let { bundle ->
            return withPhotoCodec(media, bundle.displayPhoto) { codec, photo -> codec.probe(photo) }
        }
        val type = normalizeType(media.contentTypeHint)
        val uri = uriOf(media) ?: return ProbedMedia(MediaBounds.CLASS_OPAQUE, 0, 0, 0, type)
        // Provider MIME and file names can be absent or wrong. A native decoder
        // or extractor must confirm media from the bytes before classification.
        probeImage(uri, type)?.let { return it }
        withRetriever(uri) { retriever ->
            val nativeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.let(::normalizeType) ?: type
            when {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" ->
                    videoProbe(retriever, nativeType)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" ->
                    ProbedMedia(MediaBounds.CLASS_AUDIO, 0, 0,
                        retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION), nativeType)
                else -> null
            }
        }?.let { return it }
        return if (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")) null
        else ProbedMedia(MediaBounds.CLASS_OPAQUE, 0, 0, 0, type)
    }

    /**
     * Reads an image's dimensions without decoding its pixels.
     *
     * `inJustDecodeBounds` is what makes §12's "rejected before decode" true:
     * the header is read, the bounds are checked, and a source above them never
     * reaches the decoder.
     */
    private fun probeImage(uri: Uri, type: String): ProbedMedia? {
        if (isJpegXl(uri)) return withJpegXl(uri) { decoder ->
            ProbedMedia(MediaBounds.CLASS_IMAGE, decoder.width, decoder.height, 0, "image/jxl")
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(uri) { stream -> BitmapFactory.decodeStream(stream, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return probeRawThumbnail(uri, type)
        val swapped = swapsDimensions(imageOrientation(uri))
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_IMAGE,
            width = if (swapped) options.outHeight else options.outWidth,
            height = if (swapped) options.outWidth else options.outHeight,
            durationMs = 0,
            contentType = normalizeType(options.outMimeType ?: type),
        )
    }

    /** A RAW preview is optional; the original RAW bytes remain the source. */
    private fun probeRawThumbnail(uri: Uri, type: String): ProbedMedia? {
        if (type !in RAW_TYPES) return null
        return read(uri) { stream ->
            val exif = ExifInterface(stream)
            if (!exif.hasThumbnail()) return@read null
            val width = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (width <= 0 || height <= 0) return@read null
            val swapped = swapsDimensions(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            ProbedMedia(MediaBounds.CLASS_IMAGE, if (swapped) height else width, if (swapped) width else height, 0, type)
        }
    }

    private fun videoProbe(retriever: MediaMetadataRetriever, type: String): ProbedMedia {
        val width = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rotation = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val swapped = ((rotation % 360) + 360) % 360 in listOf(90, 270)
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_VIDEO,
            width = if (swapped) height else width,
            height = if (swapped) width else height,
            durationMs = retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION),
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
        media.livePhoto?.let { bundle ->
            return withPhotoCodec(media, bundle.displayPhoto) { codec, photo -> codec.derive(photo, probe, kind, cancelRequested) }
        }
        val uri = uriOf(media) ?: return null
        if (kind == StreamKind.AUDIO_WAVEFORM) return deriveWaveform(uri, probe, cancelRequested)
        if (probe.mediaClass == MediaBounds.CLASS_AUDIO) return deriveArtwork(uri, kind, cancelRequested)
        val target = MediaBounds.targetSize(kind, probe.width, probe.height) ?: return null
        val (targetWidth, targetHeight) = target
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        var srgb: Bitmap? = null
        try {
            bitmap = when (probe.mediaClass) {
                MediaBounds.CLASS_IMAGE -> if (probe.contentType == "image/jxl") {
                    withJpegXl(uri, cancelRequested) { decoder ->
                        val frame = decoder.renderFrame(0, maxOf(targetWidth, targetHeight))
                        try {
                            if (cancelRequested()) null else jpegXlBitmap(frame)
                        } finally { frame.close() }
                    }
                } else decodeScaled(uri, targetWidth, targetHeight, cancelRequested)
                MediaBounds.CLASS_VIDEO -> videoFrame(uri, targetWidth, targetHeight, cancelRequested)
                else -> null
            } ?: return null
            if (cancelRequested()) return null
            scaled = if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            }
            if (cancelRequested()) return null
            // The native retriever maps HDR video to SDR on API 30+. This
            // separate canvas converts SDR gamut (including Display P3) to
            // sRGB; it must not retag HDR pixels as if they were already SDR.
            val output = if (probe.mediaClass == MediaBounds.CLASS_VIDEO) {
                val targetBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888,
                    false, ColorSpace.get(ColorSpace.Named.SRGB))
                srgb = targetBitmap
                Canvas(targetBitmap).drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                targetBitmap
            } else if (probe.contentType == "image/jxl" && scaled.hasAlpha()) {
                // JPEG has no alpha. Composite JXL pixels over white.
                val targetBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888,
                    false, ColorSpace.get(ColorSpace.Named.SRGB))
                srgb = targetBitmap
                Canvas(targetBitmap).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawBitmap(scaled, 0f, 0f, null)
                }
                targetBitmap
            } else scaled
            val out = ClearingByteArrayOutputStream()
            try {
                // The source orientation is now in the pixels. No EXIF
                // orientation is copied into this baseline JPEG.
                val encoded = output.compress(Bitmap.CompressFormat.JPEG, MediaBounds.quality(kind), out)
                if (!encoded || cancelRequested()) return null
                val bytes = out.toByteArray()
                if (cancelRequested()) { bytes.fill(0); return null }
                return Derivative(kind, bytes, targetWidth, targetHeight)
            } finally { out.close() }
        } finally {
            srgb?.let { if (it.isMutable) it.eraseColor(android.graphics.Color.TRANSPARENT); it.recycle() }
            if (scaled !== bitmap) scaled?.let { if (it.isMutable) it.eraseColor(android.graphics.Color.TRANSPARENT); it.recycle() }
            bitmap?.let { if (it.isMutable) it.eraseColor(android.graphics.Color.TRANSPARENT); it.recycle() }
        }
    }

    private fun videoFrame(uri: Uri, width: Int, height: Int, cancelRequested: () -> Boolean): Bitmap? {
        // Android 10's retriever lacks the native HDR capture/tone-map path.
        // Keep that original playable/exportable, with an unavailable preview.
        if (Build.VERSION.SDK_INT < 30 && videoColorTransfer(uri) in
            listOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)) return null
        val frame = withRetriever(uri) {
            if (cancelRequested()) null
            else if (Build.VERSION.SDK_INT >= 30) it.getScaledFrameAtTime(
                -1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height,
                MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 },
            ) else it.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
        } ?: return null
        val color = frame.colorSpace
        if (color == null || (Build.VERSION.SDK_INT >= 34 &&
                (color == ColorSpace.get(ColorSpace.Named.BT2020_HLG) || color == ColorSpace.get(ColorSpace.Named.BT2020_PQ)))) {
            frame.recycle()
            return null
        }
        return frame
    }

    private fun videoColorTransfer(uri: Uri): Int? {
        val descriptor = openDecodeDescriptor(uri) ?: return null
        val extractor = MediaExtractor()
        return try {
            descriptor.use {
                if (sourceLength == null) extractor.setDataSource(it.fileDescriptor)
                else extractor.setDataSource(it.fileDescriptor, sourceOffset, sourceLength)
                (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { format -> format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?.let { format -> if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null }
            }
        } finally { extractor.release() }
    }

    /** Optional embedded artwork never changes the audio's dimensions or import outcome. */
    private fun deriveArtwork(uri: Uri, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? = try {
        withRetriever(uri) { retriever ->
            if (cancelRequested()) return@withRetriever null
            // Android returns a complete byte array; its native allocation cannot
            // be bounded through this API. Reject it before any pixel decode.
            val bytes = retriever.embeddedPicture ?: return@withRetriever null
            try {
                if (cancelRequested() || bytes.size > MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES) {
                    return@withRetriever null
                }
                decodeArtwork(bytes, kind, cancelRequested)
            } finally {
                bytes.fill(0)
            }
        }
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        // Artwork is optional, including when the platform cannot allocate it.
        null
    }

    private fun decodeArtwork(bytes: ByteArray, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val orientation = runCatching {
            bytes.inputStream().use { ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            ) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val swapped = swapsDimensions(orientation)
        val width = if (swapped) bounds.outHeight else bounds.outWidth
        val height = if (swapped) bounds.outWidth else bounds.outHeight
        val (targetWidth, targetHeight) = MediaBounds.artworkTargetSize(kind, width, height, bytes.size.toLong())
            ?: return null
        if (cancelRequested()) return null
        val rawTargetWidth = if (swapped) targetHeight else targetWidth
        val rawTargetHeight = if (swapped) targetWidth else targetHeight
        var sample = 1
        // Round up the sample so even a source just over a target never
        // allocates a full source raster. The final resize restores exact size.
        while ((bounds.outWidth + sample - 1) / sample > rawTargetWidth ||
            (bounds.outHeight + sample - 1) / sample > rawTargetHeight
        ) sample *= 2
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return null
            if (cancelRequested() || decoded.width > 2048 || decoded.height > 2048) return null
            val matrix = orientationMatrix(orientation)
            oriented = if (matrix == null) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true,
            )
            if (cancelRequested()) return null
            scaled = if (oriented.width == targetWidth && oriented.height == targetHeight) oriented else
                Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true)
            return ClearingByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, MediaBounds.quality(kind), out) || cancelRequested()) return null
                val encoded = out.toByteArray()
                if (cancelRequested()) { encoded.fill(0); return null }
                Derivative(kind, encoded, targetWidth, targetHeight)
            }
        } finally {
            listOfNotNull(scaled, oriented, decoded).distinct().forEach { bitmap ->
                if (bitmap.isMutable) bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                bitmap.recycle()
            }
        }
    }

    /**
     * Decodes the audio track to PCM and folds it into the §6.1 waveform record.
     *
     * `MediaExtractor` plus `MediaCodec` is the only way to reach samples on
     * Android; there is no framework call that returns an envelope. The decode
     * runs synchronously and never holds more than one output buffer, so a
     * four-hour recording costs the same memory as a four-second one, which is
     * what §12 requires of every import buffer.
     *
     * A failure at any stage returns `null`. §13 keeps an unsupported codec a
     * distinct outcome from a malformed source, and the caller decides whether
     * to import the object without this derivative.
     */
    private fun deriveWaveform(
        uri: Uri,
        probe: ProbedMedia,
        cancelRequested: () -> Boolean,
    ): Derivative? {
        val descriptor = openDecodeDescriptor(uri) ?: return null
        val extractor = MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val accumulator = if (mime == "audio/raw") {
                // PCM WAVE has no compressed frames and needs no MediaCodec.
                readPcm(extractor, format, probe.durationMs, cancelRequested)
            } else {
                codec = android.media.MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                drain(codec, extractor, format, probe.durationMs, cancelRequested)
            } ?: return null
            if (cancelRequested() || accumulator.sampleCount == 0L) return null
            return Derivative(
                kind = StreamKind.AUDIO_WAVEFORM,
                bytes = accumulator.encode(probe.durationMs),
                width = 0,
                height = 0,
            )
        } catch (_: RuntimeException) {
            return null
        } catch (_: java.io.IOException) {
            return null
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
            descriptor.close()
        }
    }

    /**
     * Runs one synchronous decode loop. Only output EOS confirms completion.
     *
     * The loop is bounded twice: it stops at the output end of stream, and it
     * stops after [DECODE_STALL_LIMIT] consecutive iterations that move no
     * buffer, so a decoder that stops producing cannot spin here forever. §12
     * gives a derivative generation 30 seconds, and the caller applies that; the
     * stall limit is the inner guard that makes the outer one reachable.
     */
    private fun drain(
        codec: android.media.MediaCodec,
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        durationMs: Long,
        cancelRequested: () -> Boolean,
    ): Waveform.Accumulator? {
        val info = android.media.MediaCodec.BufferInfo()
        var outputFormat = inputFormat
        var into: Waveform.Accumulator? = null
        var inputDone = false
        var stalls = 0
        while (stalls < DECODE_STALL_LIMIT) {
            if (cancelRequested()) return null
            var moved = false
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(BUFFER_TIMEOUT_US)
                if (index >= 0) {
                    moved = true
                    val buffer = codec.getInputBuffer(index)
                    val read = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(
                            index,
                            0,
                            0,
                            0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, BUFFER_TIMEOUT_US)
            if (output == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val next = codec.outputFormat
                // A channel/rate change after samples would change their time
                // mapping. Refuse the optional derivative instead of lying.
                if (into != null && (next.getInteger(MediaFormat.KEY_SAMPLE_RATE) != outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) ||
                        next.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))) return null
                outputFormat = next
                moved = true
            } else if (output >= 0) {
                moved = true
                try {
                    if (info.size > 0 && info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = codec.getOutputBuffer(output) ?: return null
                        buffer.limit(info.offset + info.size)
                        buffer.position(info.offset)
                        val accumulator = into ?: accumulator(outputFormat, durationMs).also { into = it }
                        if (!addPcm(buffer.slice().order(ByteOrder.nativeOrder()), outputFormat, accumulator)) return null
                    }
                } finally {
                    codec.releaseOutputBuffer(output, false)
                }
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return into
            }
            stalls = if (moved) 0 else stalls + 1
        }
        return null
    }

    private fun accumulator(format: MediaFormat, durationMs: Long): Waveform.Accumulator =
        Waveform.Accumulator(durationMs * format.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1_000L *
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))

    private fun readPcm(extractor: MediaExtractor, format: MediaFormat, durationMs: Long,
        cancelRequested: () -> Boolean): Waveform.Accumulator? {
        val into = accumulator(format, durationMs)
        val buffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder())
        while (!cancelRequested()) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return into
            if (size == 0 || size > buffer.capacity()) return null
            buffer.position(0)
            buffer.limit(size)
            if (!addPcm(buffer, format, into)) return null
            if (!extractor.advance()) return into
        }
        return null
    }

    private fun addPcm(buffer: ByteBuffer, format: MediaFormat, into: Waveform.Accumulator): Boolean {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        return addAndroidPcm(buffer, encoding, into)
    }

    /**
     * Decodes at the smallest power-of-two sample that still covers the target.
     *
     * §12 bounds the decode buffer, and decoding a 48-megapixel photograph at
     * full size to produce a 320px thumbnail is how that bound is exceeded.
     */
    private fun decodeScaled(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        cancelRequested: () -> Boolean,
    ): Bitmap? {
        val orientation = imageOrientation(uri, cancelRequested)
        if (cancelRequested()) return null
        val swapped = swapsDimensions(orientation)
        val rawWidth = if (swapped) targetHeight else targetWidth
        val rawHeight = if (swapped) targetWidth else targetHeight
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(uri, cancelRequested) { BitmapFactory.decodeStream(it, null, bounds) }
        if (cancelRequested()) return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val thumbnailBytes = read(uri, cancelRequested) { ExifInterface(it).thumbnail } ?: return null
            val thumbnail = try {
                if (thumbnailBytes.size > MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES) return null
                val thumbnailBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size, thumbnailBounds)
                if (thumbnailBounds.outWidth <= 0 || thumbnailBounds.outHeight <= 0 ||
                    thumbnailBounds.outWidth.toLong() * thumbnailBounds.outHeight > MediaBounds.IMAGE_AREA_MAX) return null
                var thumbnailSample = 1
                while (thumbnailBounds.outWidth / (thumbnailSample * 2) >= rawWidth &&
                    thumbnailBounds.outHeight / (thumbnailSample * 2) >= rawHeight) thumbnailSample *= 2
                BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size,
                    BitmapFactory.Options().apply { inSampleSize = thumbnailSample }) ?: return null
            } finally { thumbnailBytes.fill(0) }
            val matrix = orientationMatrix(orientation) ?: return thumbnail
            return try {
                Bitmap.createBitmap(thumbnail, 0, 0, thumbnail.width, thumbnail.height, matrix, true).also {
                    if (it !== thumbnail) thumbnail.recycle()
                }
            } catch (failure: Exception) { thumbnail.recycle(); throw failure }
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= rawWidth &&
            bounds.outHeight / (sample * 2) >= rawHeight
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = read(uri, cancelRequested) { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        try {
            if (cancelRequested()) return null
            val matrix = orientationMatrix(orientation) ?: return decoded
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (oriented !== decoded) decoded.recycle()
            if (cancelRequested()) { oriented.recycle(); return null }
            return oriented
        } catch (failure: Exception) {
            decoded.recycle()
            throw failure
        } finally {
            if (cancelRequested() && !decoded.isRecycled) decoded.recycle()
        }
    }

    private fun imageOrientation(uri: Uri, cancelRequested: () -> Boolean = { false }): Int =
        read(uri, cancelRequested) {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

    private fun swapsDimensions(orientation: Int): Boolean = orientation in 5..8

    private fun orientationMatrix(orientation: Int): Matrix? {
        val axes = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, 0f, -1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 1f, 0f)
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, 1f, 0f)
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, -1f, 0f)
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, -1f, 0f)
            else -> return null
        }
        return Matrix().apply {
            setValues(floatArrayOf(axes[0], axes[1], 0f, axes[2], axes[3], 0f, 0f, 0f, 1f))
        }
    }

    private fun <T> read(uri: Uri, cancelRequested: () -> Boolean, body: (InputStream) -> T): T? =
        read(uri) { source ->
            body(object : FilterInputStream(source) {
                private fun checkCancellation() {
                    if (cancelRequested()) throw InterruptedIOException("Derivative cancelled")
                }
                override fun read(): Int { checkCancellation(); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    checkCancellation()
                    return `in`.read(bytes, offset, length)
                }
                override fun skip(length: Long): Long { checkCancellation(); return super.skip(length) }
            })
        }

    private fun <T> read(uri: Uri, body: (InputStream) -> T): T? = try {
        if (sourceDescriptor == null) resolver.openInputStream(uri)?.use(body)
        else openDecodeDescriptor(uri)?.let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val length = sourceLength
                if (length == null) body(input) else body(object : FilterInputStream(input) {
                    private var remaining: Long = requireNotNull(length)
                    override fun read(): Int {
                        if (remaining == 0L) return -1
                        val value = `in`.read()
                        if (value >= 0) remaining--
                        return value
                    }
                    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                        if (remaining == 0L) return -1
                        val read = `in`.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
                        if (read > 0) remaining -= read
                        return read
                    }
                    override fun skip(count: Long): Long = `in`.skip(minOf(count, remaining)).also { remaining -= it }
                    override fun available(): Int = minOf(`in`.available().toLong(), remaining).toInt()
                })
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun openDecodeDescriptor(uri: Uri): ParcelFileDescriptor? {
        val source = sourceDescriptor ?: return resolver.openFileDescriptor(uri, "r")
        val copy = ParcelFileDescriptor.dup(source.fileDescriptor)
        return try {
            // The service owns this source independently of the import reader.
            Os.lseek(copy.fileDescriptor, sourceOffset, OsConstants.SEEK_SET)
            copy
        } catch (_: ErrnoException) {
            copy.close()
            // A pipe must be reopened for each pass. URI grants are shared by
            // the application's private processes.
            resolver.openFileDescriptor(uri, "r")
        }
    }

    private fun <T> withRetriever(uri: Uri, body: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            openDecodeDescriptor(uri)?.use { descriptor ->
                if (sourceLength != null) retriever.setDataSource(descriptor.fileDescriptor, sourceOffset, sourceLength)
                else retriever.setDataSource(descriptor.fileDescriptor)
                body(retriever)
            }
        } catch (_: Exception) {
            // §13: an unsupported codec is a distinct outcome, and the caller
            // decides whether to import the object as opaque.
            null
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.metadataInt(key: Int): Int =
        extractMetadata(key)?.toIntOrNull() ?: 0

    private fun MediaMetadataRetriever.metadataLong(key: Int): Long =
        extractMetadata(key)?.toLongOrNull() ?: 0

    private fun <T> withPhotoCodec(media: PickedMedia, photo: LivePhotoResource, body: (AndroidMediaCodec, PickedMedia) -> T): T =
        ParcelFileDescriptor.fromFd(media.descriptor).use { descriptor ->
            val position = Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
            try {
                body(AndroidMediaCodec(resolver, descriptor, photo.offset, photo.byteLength),
                    media.copy(livePhoto = null, contentTypeHint = photo.contentType, originalFilename = photo.originalFilename,
                        knownLength = photo.byteLength))
            } finally { Os.lseek(descriptor.fileDescriptor, position, OsConstants.SEEK_SET) }
        }

    private fun isJpegXl(uri: Uri): Boolean = read(uri) { source ->
        val header = ByteArray(12)
        try {
            var size = 0
            while (size < header.size) {
                val count = source.read(header, size, header.size - size)
                if (count <= 0) break
                size += count
            }
            jpegXlSignature(header, size)
        } finally { header.fill(0) }
    } ?: false

    private fun <T> withJpegXl(uri: Uri, cancelRequested: () -> Boolean = { false }, body: (JpegXlDecoder) -> T): T? =
        try {
            if (cancelRequested()) null else openDecodeDescriptor(uri)?.use { source ->
                val seekable = try {
                    Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_CUR)
                    true
                } catch (failure: ErrnoException) {
                    if (failure.errno == OsConstants.ESPIPE) false else throw failure
                }
                if (seekable) {
                    val decoder = JpegXlDecoder.open(source.fd)
                    try { if (cancelRequested()) null else body(decoder) } finally { decoder.close() }
                } else withJpegXlPipe(source, cancelRequested, body)
            }
        } catch (_: Exception) { null }

    /** A provider pipe has no positional reads. Only this independently opened FD is consumed. */
    private fun <T> withJpegXlPipe(
        source: ParcelFileDescriptor,
        cancelRequested: () -> Boolean,
        body: (JpegXlDecoder) -> T,
    ): T? {
        val chunk = ByteArray(64 * 1024)
        var encoded = ChurBuffer(chunk.size)
        var length = 0
        try {
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                while (true) {
                    if (cancelRequested()) return null
                    // At the cap, read one byte only to distinguish EOF from an
                    // oversized source. Provider length metadata is not trusted.
                    val count = input.read(chunk, 0, minOf(chunk.size, JPEG_XL_ENCODED_MAX_BYTES - length + 1))
                    if (count < 0) break
                    if (count == 0) continue
                    if (count > JPEG_XL_ENCODED_MAX_BYTES - length) return null
                    if (length + count > encoded.capacityBytes) {
                        val grown = ChurBuffer(minOf(JPEG_XL_ENCODED_MAX_BYTES, encoded.capacityBytes * 2))
                        try {
                            grown.buffer.put(encoded.buffer.duplicate().apply { position(0); limit(length) })
                        } catch (failure: Throwable) {
                            grown.clear(); grown.release(); throw failure
                        }
                        encoded.clear()
                        encoded.release()
                        encoded = grown
                    }
                    encoded.buffer.position(length)
                    encoded.buffer.put(chunk, 0, count)
                    length += count
                    chunk.fill(0, 0, count)
                }
            }
            if (cancelRequested() || length == 0) return null
            val decoder = JpegXlDecoder.open(encoded, length)
            try { return if (cancelRequested()) null else body(decoder) } finally { decoder.close() }
        } finally {
            chunk.fill(0)
            encoded.clear()
            encoded.release()
        }
    }

    private fun uriOf(media: PickedMedia): Uri? = media.platformHandle as? Uri

    /**
     * A lowercase IANA type with no parameter, `FFI_CONTRACT.md` §6.1.
     *
     * A provider may report `image/jpeg; charset=binary`, and the catalog
     * refuses a type carrying a parameter, so the parameter is dropped here
     * rather than making the import fail.
     */
    private fun normalizeType(hint: String): String =
        hint.substringBefore(';').trim().lowercase()

    private fun sourceType(uri: Uri, name: String?, hint: String?): String {
        // The byte signature takes priority even when a provider supplies a
        // specific but incorrect type. No filename is required.
        if (isJpegXl(uri)) return "image/jxl"
        val normalized = hint?.let(::normalizeType)
        if (normalized != null && normalized !in setOf("application/octet-stream", "binary/octet-stream", "application/ogg")) {
            return normalized
        }
        // Native probing below confirms these hints. The signature check also
        // recognises unnamed TIFF/DNG sources whose decoder is unavailable.
        val header = read(uri) { stream ->
            val buffer = ByteArray(64)
            var size = 0
            while (size < buffer.size) {
                val count = stream.read(buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        if (header != null) {
            val type = when {
                header.size >= 12 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) &&
                    (8..header.size - 4 step 4).any { index ->
                        String(header, index, 4, Charsets.US_ASCII) in setOf("avif", "avis")
                    } -> "image/avif"
                header.size >= 4 && ((header[0] == 73.toByte() && header[1] == 73.toByte() && header[2] == 42.toByte() && header[3] == 0.toByte()) ||
                    (header[0] == 77.toByte() && header[1] == 77.toByte() && header[2] == 0.toByte() && header[3] == 42.toByte())) ->
                    if (name?.substringAfterLast('.')?.lowercase() == "dng") "image/x-adobe-dng" else "image/tiff"
                else -> null
            }
            if (type != null) return type
        }
        return when (name?.substringAfterLast('.')?.lowercase()) {
            "jxl" -> "image/jxl"
            "avif" -> "image/avif"
            "dng" -> "image/x-adobe-dng"
            "tif", "tiff" -> "image/tiff"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "apng", "png" -> "image/png"
            "ogg", "oga", "opus" -> "audio/ogg"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> normalized ?: "application/octet-stream"
        }
    }

    private companion object {
        val RAW_TYPES = setOf("image/x-adobe-dng", "image/dng")
        /** How long one dequeue waits before the loop tries the other side. */
        const val BUFFER_TIMEOUT_US = 10_000L

        /** How many buffer-less iterations end a decode that has stopped. */
        const val DECODE_STALL_LIMIT = 200
    }
}

/** Fold native Android PCM without allocating a second decoded buffer. */
internal fun addAndroidPcm(buffer: ByteBuffer, encoding: Int, into: Waveform.Accumulator): Boolean {
    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> return false
    }
    if (buffer.remaining() % bytesPerSample != 0) return false
    while (buffer.hasRemaining()) {
        val sample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) shl 8
            AudioFormat.ENCODING_PCM_16BIT -> buffer.short.toInt()
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val value = buffer.float
                if (!value.isFinite()) return false
                (value.coerceIn(-1f, 1f) * 32767f).toInt()
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                // Android PCM output uses native byte order (little endian).
                buffer.get()
                (buffer.get().toInt() and 255) or (buffer.get().toInt() shl 8)
            }
            else -> buffer.int shr 16
        }
        into.add(sample.toShort())
    }
    return true
}

/**
 * An EXIF `DateTimeOriginal`, "2019:05:01 12:00:00", in milliseconds since
 * the epoch.
 *
 * EXIF records the time on the camera's clock. `OffsetTimeOriginal`,
 * "+02:00", says which zone that clock was in; a time with no offset is read
 * in [zone], the device's zone at import, `MEDIA_PIPELINE.md` §4. A value
 * that does not parse, as the "0000:00:00 00:00:00" of a camera whose clock
 * was never set, is no capture time.
 */
internal fun exifCaptureTimeMs(dateTime: String?, offset: String?, zone: ZoneId): Long? {
    val local = try {
        LocalDateTime.parse(dateTime?.trim() ?: return null, EXIF_DATE_TIME)
    } catch (_: DateTimeException) {
        return null
    }
    val at = try {
        offset?.trim()?.let(ZoneOffset::of)
    } catch (_: DateTimeException) {
        null
    }
    return local.atZone(at ?: zone).toInstant().toEpochMilli().takeIf { it >= 0 }
}

/**
 * A video container's creation date as `MediaMetadataRetriever` reports it,
 * "20190501T120000.000Z", in milliseconds since the epoch.
 *
 * The container stores UTC. A container with no date stores zero seconds
 * since 1904, which comes back as a time before 1970 and is no capture time.
 */
internal fun containerCaptureTimeMs(date: String?): Long? = try {
    LocalDateTime.parse(date?.trim() ?: return null, CONTAINER_DATE)
        .toInstant(ZoneOffset.UTC).toEpochMilli().takeIf { it >= 0 }
} catch (_: DateTimeException) {
    null
}

private val EXIF_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

private val CONTAINER_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss[.SSS]'Z'").withResolverStyle(ResolverStyle.STRICT)

private const val JPEG_XL_ENCODED_MAX_BYTES = 64 * 1024 * 1024
