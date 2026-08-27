package dev.po4yka.chur.imports

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.po4yka.chur.ffi.StreamKind
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The Android codec side of `MEDIA_PIPELINE.md` §1.
 *
 * Every decode opens its own stream from the content URI rather than reusing
 * the descriptor the import is reading. Two readers of one descriptor share a
 * file position, so a probe would leave the import to start from wherever the
 * probe stopped, and the descriptor belongs to the import: closing or seeking
 * it here would be reaching into another operation's resource.
 */
class AndroidMediaCodec(private val resolver: ContentResolver) : MediaCodec {

    /**
     * Opens a picked content URI, §3.
     *
     * The provider's length and type are hints. `OpenableColumns` is where a
     * Storage Access Framework provider publishes them, and a provider that
     * publishes neither is ordinary rather than an error: §3 makes the length
     * optional and Rust re-derives the authenticated size from the final commit
     * record.
     */
    fun open(uri: Uri): PickedMedia? {
        val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    name = cursor.getString(nameColumn)
                }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn)
                }
            }
        }
        return PickedMedia(
            descriptor = descriptor.fd,
            seekable = true,
            knownLength = size,
            contentTypeHint = resolver.getType(uri) ?: "application/octet-stream",
            originalFilename = name,
            // §8.1 of the catalog: the capture time comes from provider
            // metadata, and a plain content URI publishes none, so it is absent
            // and the row records that it was substituted rather than carrying
            // a guess.
            captureTimeMs = null,
            platformHandle = uri,
            close = { descriptor.close() },
        )
    }

    override fun probe(media: PickedMedia): ProbedMedia? {
        val type = normalizeType(media.contentTypeHint)
        val uri = uriOf(media) ?: return ProbedMedia(MediaBounds.CLASS_OPAQUE, 0, 0, 0, type)
        return when {
            type.startsWith("image/") -> probeImage(uri, type)
            type.startsWith("video/") -> probeVideo(uri, type)
            type.startsWith("audio/") -> probeAudio(uri, type)
            else -> ProbedMedia(MediaBounds.CLASS_OPAQUE, 0, 0, 0, type)
        }
    }

    /**
     * Reads an image's dimensions without decoding its pixels.
     *
     * `inJustDecodeBounds` is what makes §12's "rejected before decode" true:
     * the header is read, the bounds are checked, and a source above them never
     * reaches the decoder.
     */
    private fun probeImage(uri: Uri, type: String): ProbedMedia? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(uri) { stream -> BitmapFactory.decodeStream(stream, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ProbedMedia(
            mediaClass = MediaBounds.CLASS_IMAGE,
            width = options.outWidth,
            height = options.outHeight,
            durationMs = 0,
            contentType = normalizeType(options.outMimeType ?: type),
        )
    }

    private fun probeVideo(uri: Uri, type: String): ProbedMedia? = withRetriever(uri) { retriever ->
        ProbedMedia(
            mediaClass = MediaBounds.CLASS_VIDEO,
            width = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
            height = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            durationMs = retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION),
            contentType = type,
        )
    }

    private fun probeAudio(uri: Uri, type: String): ProbedMedia? = withRetriever(uri) { retriever ->
        ProbedMedia(
            mediaClass = MediaBounds.CLASS_AUDIO,
            width = 0,
            height = 0,
            durationMs = retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION),
            contentType = type,
        )
    }

    override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind): Derivative? {
        val uri = uriOf(media) ?: return null
        val target = MediaBounds.targetSize(kind, probe.width, probe.height) ?: return null
        val (targetWidth, targetHeight) = target
        val bitmap = when (probe.mediaClass) {
            MediaBounds.CLASS_IMAGE -> decodeScaled(uri, targetWidth, targetHeight)
            MediaBounds.CLASS_VIDEO -> withRetriever(uri) { it.frameAtTime }
            else -> null
        } ?: return null
        val scaled = if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
        val out = ByteArrayOutputStream()
        // §12: baseline JPEG with 4:2:0 chroma, which this encoder produces, at
        // the quality the kind names.
        val encoded = scaled.compress(Bitmap.CompressFormat.JPEG, MediaBounds.quality(kind), out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        if (!encoded) return null
        return Derivative(kind, out.toByteArray(), targetWidth, targetHeight)
    }

    /**
     * Decodes at the smallest power-of-two sample that still covers the target.
     *
     * §12 bounds the decode buffer, and decoding a 48-megapixel photograph at
     * full size to produce a 320px thumbnail is how that bound is exceeded.
     */
    private fun decodeScaled(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(uri) { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return read(uri) { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun <T> read(uri: Uri, body: (InputStream) -> T): T? =
        resolver.openInputStream(uri)?.use(body)

    private fun <T> withRetriever(uri: Uri, body: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(resolver.openFileDescriptor(uri, "r")?.fileDescriptor ?: return null)
            body(retriever)
        } catch (_: RuntimeException) {
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
}
