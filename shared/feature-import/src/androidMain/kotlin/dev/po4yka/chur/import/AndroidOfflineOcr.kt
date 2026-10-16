package dev.po4yka.chur.imports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import dev.po4yka.chur.ffi.StreamKind
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/** Runs only in the disposable decoder process, which the parent owns through completion or cancellation. */
internal class AndroidOfflineOcr(private val context: Context) {
    fun recognize(source: ParcelFileDescriptor, contentType: String): Derivative? {
        val models = prepareModels()
        val api = TessBaseAPI()
        try {
            if (!api.init(models.absolutePath, "eng+rus", TessBaseAPI.OEM_LSTM_ONLY)) return null
            api.setVariable("debug_file", "/dev/null")
            api.setVariable("tessedit_write_images", "0")
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            return if (contentType == "application/pdf") pdf(source, api) else image(source, contentType, api)
        } finally { api.recycle() }
    }

    private fun image(source: ParcelFileDescriptor, contentType: String, api: TessBaseAPI): Derivative? {
        val media = PickedMedia(source.fd, true, null, contentType, null, null, Uri.EMPTY, {})
        val codec = AndroidMediaCodec(context.contentResolver, source)
        val probe = codec.probe(media) ?: return null
        if (probe.mediaClass != MediaBounds.CLASS_IMAGE || MediaBounds.check(probe) != null) return null
        val preview = codec.derive(media, probe, StreamKind.SCREEN_PREVIEW) ?: return null
        try {
            val bitmap = BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size,
                BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
            try {
                if (bitmap.width > 2048 || bitmap.height > 2048 || bitmap.width.toLong() * bitmap.height > 4_194_304L) return null
                api.setImage(bitmap)
                val text = api.getUTF8Text().orEmpty()
                val encoded = text.toByteArray(Charsets.UTF_8)
                try {
                    val length = utf8End(encoded, OCR_RESULT_MAX)
                    return Derivative(StreamKind.ORIGINAL, encoded.copyOf(length), 1, if (length < encoded.size) 1 else 0)
                } finally { encoded.fill(0); api.clear() }
            } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
        } finally { preview.bytes.fill(0) }
    }

    private fun pdf(source: ParcelFileDescriptor, api: TessBaseAPI): Derivative? {
        // The duplicate is owned by PdfRenderer; the Binder source stays owned by the service.
        val duplicate = ParcelFileDescriptor.dup(source.fileDescriptor)
        val renderer = try { PdfRenderer(duplicate) } catch (failure: Throwable) { duplicate.close(); throw failure }
        renderer.use { document ->
            if (document.pageCount <= 0) return null
            val pages = minOf(document.pageCount, 100)
            val output = ByteArray(OCR_RESULT_MAX)
            var used = 0
            var completed = 0
            var partial = document.pageCount > pages
            try {
                for (number in 0 until pages) {
                    document.openPage(number).use { page ->
                        if (page.width <= 0 || page.height <= 0) return null
                        val scale = minOf(2048.0 / maxOf(page.width, page.height),
                            sqrt(4_000_000.0 / (page.width.toDouble() * page.height)))
                        val width = (page.width * scale).toInt().coerceIn(1, 2048)
                        val height = (page.height * scale).toInt().coerceIn(1, 2048)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            api.setImage(bitmap)
                            val text = api.getUTF8Text().orEmpty().toByteArray(Charsets.UTF_8)
                            try {
                                val length = utf8End(text, OCR_RESULT_MAX - used)
                                text.copyInto(output, used, 0, length)
                                used += length
                                partial = partial || length < text.size
                            } finally { text.fill(0); api.clear() }
                        } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
                    }
                    completed = number + 1
                    if (used >= OCR_RESULT_MAX - 4) { partial = true; break }
                    output[used++] = '\n'.code.toByte()
                }
                return Derivative(StreamKind.ORIGINAL, output.copyOf(used), completed, if (partial) 1 else 0)
            } finally { output.fill(0) }
        }
    }

    /** Only public, pinned language models are written. Originals and recognized text stay in memory. */
    private fun prepareModels(): File {
        val root = File(context.noBackupFilesDir, "ocr")
        val directory = File(root, "tessdata")
        check(directory.mkdirs() || directory.isDirectory)
        for ((language, expected) in MODEL_DIGESTS) {
            val target = File(directory, "$language.traineddata")
            if (target.isFile && digest(target) == expected) continue
            val temporary = File(directory, "$language.installing")
            try {
                context.assets.open("ocr/$language.traineddata").use { from -> temporary.outputStream().use { from.copyTo(it) } }
                check(digest(temporary) == expected)
                check(temporary.renameTo(target))
            } finally { temporary.delete() }
        }
        return root
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            try { while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) } }
            finally { bytes.fill(0) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun utf8End(bytes: ByteArray, limit: Int): Int {
        if (bytes.size <= limit) return bytes.size
        var end = limit
        while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
        return end
    }

    companion object {
        // tessdata_fast at 87416418657359cb625c412a48b6e1d6d41c29bd (Apache-2.0).
        private val MODEL_DIGESTS = mapOf(
            "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            "rus" to "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225",
        )
    }
}
