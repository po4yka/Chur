package dev.po4yka.chur.imports

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.security.MessageDigest

/**
 * Runs only in the OCR child, which the parent owns through completion or cancellation.
 *
 * It receives decoded pixels only. Image and PDF parsing stay in the isolated
 * decoder; Tesseract reads only the two pinned public models.
 */
internal class AndroidOfflineOcr(private val context: Context) : AutoCloseable {
    private var api: TessBaseAPI? = null

    /** Returns UTF-8 text, or null when the engine cannot start. The caller clears it. */
    fun recognize(bitmap: Bitmap): ByteArray? {
        val engine = api ?: start() ?: return null
        try {
            engine.setImage(bitmap)
            return engine.getUTF8Text().orEmpty().toByteArray(Charsets.UTF_8)
        } finally { engine.clear() }
    }

    private fun start(): TessBaseAPI? {
        val models = prepareModels()
        val engine = TessBaseAPI()
        if (!engine.init(models.absolutePath, "eng+rus", TessBaseAPI.OEM_LSTM_ONLY)) {
            engine.recycle()
            return null
        }
        engine.setVariable("debug_file", "/dev/null")
        engine.setVariable("tessedit_write_images", "0")
        engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = engine
        return engine
    }

    override fun close() {
        api?.recycle()
        api = null
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

    companion object {
        // tessdata_fast at 87416418657359cb625c412a48b6e1d6d41c29bd (Apache-2.0).
        private val MODEL_DIGESTS = mapOf(
            "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            "rus" to "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225",
        )
    }
}
