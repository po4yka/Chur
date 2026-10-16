package dev.po4yka.chur.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class PublicCameraEntry(val id: String, val label: String)

/** One public capture transaction. Its paths never refer to vault storage. */
internal class PublicCameraStorage(private val context: Context) : AutoCloseable {
    private val directory = ownedDirectory(context.cacheDir, "public-camera")
    private val album = ownedDirectory(context.filesDir, "public/camera")
    private val active = AtomicBoolean(true)
    private var source: File? = null
    private var reviewed: File? = null
    private var granted: Uri? = null

    val discardedPreviousCapture: Boolean

    init {
        // A new route owns no previous capture. This also removes process-death leftovers.
        validateDirectory(directory)
        validateDirectory(album)
        discardedPreviousCapture = directory.listFiles().orEmpty().isNotEmpty()
        directory.listFiles()?.forEach { it.delete() }
        album.listFiles()?.filter { PENDING_NAME.matches(it.name) }?.forEach { it.delete() }
    }

    @Synchronized
    fun beginCapture(): Intent {
        check(active.get())
        validateDirectory(directory)
        discardFiles()
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        check(file.createNewFile())
        source = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.public-camera", file)
        granted = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** Decode only bounded JPEG input and write a normalized, metadata-free public image. */
    @Synchronized
    fun finishCapture(success: Boolean): Bitmap? {
        revoke()
        if (!active.get()) return null
        validateDirectory(directory)
        val input = source ?: return null
        source = null
        if (!success) {
            validateDirectory(directory)
            input.delete()
            return null
        }
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var preview: Bitmap? = null
        val output = File(directory, "${UUID.randomUUID()}.jpg")
        try {
            require(input.length() in 1..MAX_CAPTURE_BYTES) { "The camera photo is too large or empty." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.path, bounds)
            require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..MAX_SOURCE_EDGE &&
                bounds.outHeight in 1..MAX_SOURCE_EDGE &&
                bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) {
                "The camera did not return a supported JPEG photo."
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
            decoded = requireNotNull(BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply {
                inSampleSize = sample
            })) { "The camera photo could not be read." }
            val orientation = ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            val matrix = orientationMatrix(orientation)
            oriented = if (matrix == null) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true,
            )
            output.outputStream().use { check(oriented.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            require(output.length() in 1..MAX_CAPTURE_BYTES)
            val scale = minOf(1.0, PREVIEW_EDGE.toDouble() / maxOf(oriented.width, oriented.height))
            preview = Bitmap.createScaledBitmap(
                oriented, maxOf(1, (oriented.width * scale).toInt()),
                maxOf(1, (oriented.height * scale).toInt()), true,
            )
            check(active.get()) { "The camera session has ended." }
            reviewed?.delete()
            reviewed = output
            return preview
        } catch (failure: Exception) {
            if (runCatching { validateDirectory(directory) }.isSuccess) output.delete()
            throw failure
        } finally {
            if (runCatching { validateDirectory(directory) }.isSuccess) input.delete()
            if (decoded !== preview && decoded !== oriented) decoded?.recycle()
            if (oriented !== preview) oriented?.recycle()
        }
    }

    /** Commit to the local public album only after the explicit Save action. */
    @Synchronized
    fun save(): Boolean {
        if (!active.get()) return false
        validateDirectory(directory)
        validateDirectory(album)
        val file = reviewed ?: return false
        require(file.length() in 1..MAX_CAPTURE_BYTES)
        check(album.canonicalFile == album.absoluteFile) { "The public album path is not available." }
        val entries = album.listFiles().orEmpty()
        check(entries.count { PHOTO_NAME.matches(it.name) } < MAX_PHOTOS) { "The public album is full (100 photos)." }
        check(entries.sumOf { it.length() } + file.length() <= MAX_ALBUM_BYTES) { "The public album is full (256 MiB)." }
        val id = UUID.randomUUID().toString()
        val destination = File(album, "$id.jpg")
        val pending = File(album, ".pending-$id")
        check(!destination.exists() && pending.createNewFile())
        try {
            FileOutputStream(pending).use { output ->
                file.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                output.fd.sync()
            }
            check(pending.length() == file.length() && pending.length() <= MAX_CAPTURE_BYTES)
            check(active.get()) { "The camera session has ended." }
            check(!destination.exists() && pending.renameTo(destination)) { "The photo could not be saved." }
            reviewed = null
            file.delete()
            return true
        } finally {
            if (runCatching { validateDirectory(album) }.isSuccess) pending.delete()
        }
    }

    @Synchronized
    fun photos(): List<PublicCameraEntry> {
        if (!active.get()) return emptyList()
        validateDirectory(album)
        return album.listFiles().orEmpty().filter {
            PHOTO_NAME.matches(it.name) && it.isFile && it.canonicalFile.parentFile == album.absoluteFile
        }.sortedByDescending { it.lastModified() }.take(MAX_PHOTOS).map {
            PublicCameraEntry(
                it.name.removeSuffix(".jpg"),
                "Photo · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.lastModified()))}",
            )
        }
    }

    @Synchronized
    fun openPhoto(id: String): Bitmap {
        check(active.get())
        val file = albumPhoto(id)
        require(file.length() in 1..MAX_CAPTURE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..MAX_IMAGE_EDGE &&
            bounds.outHeight in 1..MAX_IMAGE_EDGE) { "The photo could not be read." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_EDGE) sample *= 2
        return requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
        })) { "The photo could not be read." }
    }

    @Synchronized
    fun deletePhoto(id: String) {
        check(active.get())
        check(albumPhoto(id).delete()) { "The photo could not be deleted." }
    }

    private fun albumPhoto(id: String): File {
        require(PHOTO_ID.matches(id)) { "Invalid public photo." }
        return File(album, "$id.jpg").also {
            check(album.canonicalFile == album.absoluteFile && it.canonicalFile.parentFile == album.absoluteFile)
            check(it.isFile) { "The photo no longer exists." }
        }
    }

    @Synchronized
    fun discard() { if (active.get()) discardFiles() }

    override fun close() {
        active.set(false)
        synchronized(this) {
            // A replaced path is no longer ours to clean. Revoke the grant in all cases.
            revoke()
            runCatching { discardFiles() }
        }
    }

    private fun discardFiles() {
        revoke()
        validateDirectory(directory)
        source?.delete()
        reviewed?.delete()
        source = null
        reviewed = null
    }

    private fun revoke() {
        granted?.let {
            runCatching {
                context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        granted = null
    }

    companion object {
        const val MAX_CAPTURE_BYTES = 32L * 1024 * 1024
        const val MAX_PHOTOS = 100
        const val MAX_ALBUM_BYTES = 256L * 1024 * 1024
        private val PHOTO_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val PHOTO_NAME = Regex("${PHOTO_ID.pattern}\\.jpg")
        private val PENDING_NAME = Regex("\\.pending-${PHOTO_ID.pattern}")
        private const val MAX_SOURCE_EDGE = 16_384
        private const val MAX_SOURCE_PIXELS = 67_108_864L
        private const val MAX_IMAGE_EDGE = 4096
        private const val PREVIEW_EDGE = 1280

        private fun ownedDirectory(base: File, relative: String): File {
            val owned = File(base.canonicalFile, relative).absoluteFile
            // Check before mkdir/list/delete, including a link in an existing parent.
            check(owned.canonicalFile == owned) { "Public camera storage is not available." }
            check(owned.isDirectory || owned.mkdirs()) { "Public camera storage is not available." }
            validateDirectory(owned)
            return owned
        }

        private fun validateDirectory(directory: File) {
            check(directory.canonicalFile == directory.absoluteFile && directory.isDirectory) {
                "Public camera storage is not available."
            }
        }

        private fun orientationMatrix(orientation: Int): Matrix? {
            val values = when (orientation) {
                2 -> floatArrayOf(-1f, 0f, 0f, 1f)
                3 -> floatArrayOf(-1f, 0f, 0f, -1f)
                4 -> floatArrayOf(1f, 0f, 0f, -1f)
                5 -> floatArrayOf(0f, 1f, 1f, 0f)
                6 -> floatArrayOf(0f, -1f, 1f, 0f)
                7 -> floatArrayOf(0f, -1f, -1f, 0f)
                8 -> floatArrayOf(0f, 1f, -1f, 0f)
                else -> return null
            }
            return Matrix().apply { setValues(floatArrayOf(values[0], values[1], 0f, values[2], values[3], 0f, 0f, 0f, 1f)) }
        }
    }
}
