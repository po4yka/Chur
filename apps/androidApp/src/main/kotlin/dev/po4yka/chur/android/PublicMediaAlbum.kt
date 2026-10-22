package dev.po4yka.chur.android

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import dev.po4yka.chur.app.publicshell.PublicCameraPhoto
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Public camera files, independent of vault imports and sessions. Existing UUID JPEGs stay valid. */
internal class PublicMediaAlbum(context: Context) {
    private val root = File(context.filesDir.canonicalFile, "public/camera")
    init { validateRoot(create = true) }
    private fun validateRoot(create: Boolean = false) {
        check(root.canonicalFile == root.absoluteFile) { "Album path is unavailable" }
        if (create) check(root.isDirectory || root.mkdirs())
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
    }
    fun availableBytes(): Long { validateRoot(); return (root.usableSpace - RESERVE).coerceAtLeast(0L) }
    fun pending(video: Boolean): File {
        validateRoot()
        check(availableBytes() > RESERVE) { "Not enough storage space" }
        return File(root, ".pending-${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}")
    }
    fun commit(file: File): String {
        validateRoot()
        require(file.parentFile == root && PENDING.matches(file.name) && !Files.isSymbolicLink(file.toPath()))
        require(file.isFile && file.length() > 0)
        if (file.extension == "jpg") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768)
        } else MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.path)
            require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0)
        }
        FileOutputStream(file, true).use { it.fd.sync() }
        val target = File(root, file.name.removePrefix(".pending-"))
        check(!target.exists() && file.renameTo(target)) { "File could not be saved" }
        return target.name
    }
    fun discard(file: File) {
        validateRoot()
        require(file.parentFile == root && PENDING.matches(file.name) && !Files.isSymbolicLink(file.toPath()))
        file.delete()
    }
    /** Valid finalized recordings left by process death become visible; incomplete files stay recoverable. */
    fun recover(): Int {
        validateRoot()
        var recovered = 0
        root.listFiles().orEmpty().filter { PENDING.matches(it.name) && !Files.isSymbolicLink(it.toPath()) }.forEach {
            if (runCatching { commit(it) }.isSuccess) recovered++
        }
        return recovered
    }
    fun pendingRecovery(): File? {
        validateRoot()
        return root.listFiles().orEmpty().firstOrNull { PENDING.matches(it.name) && it.isFile && !Files.isSymbolicLink(it.toPath()) }
    }
    fun list(): List<PublicCameraPhoto> {
        validateRoot()
        return root.listFiles().orEmpty().filter { MEDIA.matches(it.name) && !Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }.map {
                PublicCameraPhoto(it.name, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.lastModified())), it.extension == "mp4")
            }
    }
    fun file(id: String): File {
        validateRoot()
        require(MEDIA.matches(id))
        return File(root, id).also { require(it.isFile && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root) }
    }
    fun delete(id: String) { check(file(id).delete()) }
    fun thumbnail(id: String, edge: Int = 512): Bitmap? {
        val source = file(id)
        if (source.extension == "mp4") return MediaMetadataRetriever().use {
            it.setDataSource(source.path)
            it.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
        } }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    fun exportToLibrary(context: Context, ids: List<String>) {
        ids.forEach { id ->
            val source = file(id)
            val video = source.extension == "mp4"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Export unavailable")
            try {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> source.inputStream().use { it.copyTo(output) } }
                check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            } catch (error: Exception) { context.contentResolver.delete(uri, null, null); throw error }
        }
    }
    companion object {
        private val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val MEDIA = Regex("$UUID_PATTERN\\.(jpg|mp4)")
        private val PENDING = Regex("\\.pending-$UUID_PATTERN\\.(jpg|mp4)")
        private const val RESERVE = 16L * 1024 * 1024
    }
}
