package dev.po4yka.chur.android

import android.content.Intent
import android.content.ContextWrapper
import android.system.Os
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses public synthetic photos, the actual scoped provider, and the local public album. */
@RunWith(AndroidJUnit4::class)
class PublicCameraStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = File(context.cacheDir, "public-camera")
    private val album get() = File(context.filesDir, "public/camera")

    @Test
    fun captureNormalizesOrientationAndExplicitlySavesToLocalPublicAlbum() {
        val storage = PublicCameraStorage(context)
        var savedId: String? = null
        try {
            val capture = storage.beginCapture()
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, capture.action)
            val uri = output(capture)
            assertEquals("${context.packageName}.public-camera", uri.authority)
            assertEquals(uri, capture.clipData?.getItemAt(0)?.uri)
            assertTrue(capture.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            writeJpeg(uri, 96, 48)
            val source = directory.listFiles()!!.single()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1")
                saveAttributes()
            }
            val before = storage.photos().map { it.id }.toSet()
            val mediaRows = publicRows()
            val preview = requireNotNull(storage.finishCapture(true))
            assertEquals(48, preview.width)
            assertEquals(96, preview.height)
            preview.recycle()
            assertEquals(before, storage.photos().map { it.id }.toSet())
            val normalized = directory.listFiles()!!.single()
            val exif = ExifInterface(normalized)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) in listOf(0, 1))
            assertTrue(storage.save())
            val added = storage.photos().map { it.id }.toSet() - before
            assertEquals(1, added.size)
            val newId = added.single()
            savedId = newId
            assertTrue(File(album, "$newId.jpg").isFile)
            storage.openPhoto(newId).also {
                assertEquals(48, it.width)
                assertEquals(96, it.height)
                it.recycle()
            }
            assertEquals(mediaRows, publicRows()) // Local Save must not publish to system Photos.
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertFalse(storage.save())
            storage.close()
            PublicCameraStorage(context).use { reopened ->
                assertTrue(reopened.photos().any { it.id == savedId })
                reopened.deletePhoto(newId)
                assertFalse(reopened.photos().any { it.id == savedId })
            }
        } finally {
            savedId?.let { File(album, "$it.jpg").delete() }
            storage.close()
        }
    }

    @Test
    fun albumCountAndByteLimitsPreserveReviewAndExistingPhotos() {
        PublicCameraStorage(context).use { storage ->
            val created = mutableListOf<File>()
            try {
                repeat(PublicCameraStorage.MAX_PHOTOS - album.listFiles().orEmpty().size) {
                    File(album, "${UUID.randomUUID()}.jpg").also { it.writeBytes(byteArrayOf(1)); created += it }
                }
                writeJpeg(output(storage.beginCapture()), 32, 32)
                storage.finishCapture(true)?.recycle()
                assertTrue(runCatching { storage.save() }.isFailure)
                assertEquals(1, directory.listFiles().orEmpty().size)
                assertTrue(created.all { it.exists() })
                created.forEach { it.delete() }
                created.clear()
                repeat(8) {
                    File(album, "${UUID.randomUUID()}.jpg").also { file ->
                        java.io.RandomAccessFile(file, "rw").use { it.setLength(PublicCameraStorage.MAX_CAPTURE_BYTES) }
                        created += file
                    }
                }
                assertTrue(runCatching { storage.save() }.isFailure)
                assertEquals(1, directory.listFiles().orEmpty().size)
                assertTrue(created.all { it.exists() })
                created.forEach { it.delete() }
                created.clear()
                val before = storage.photos().map { it.id }.toSet()
                assertTrue(storage.save()) // A retry still owns the reviewed photo.
                (storage.photos().map { it.id }.toSet() - before).forEach { storage.deletePhoto(it) }
                assertTrue(runCatching { storage.deletePhoto("../sentinel") }.isFailure)
                assertTrue(runCatching { storage.openPhoto("../sentinel") }.isFailure)
                assertFalse(album.listFiles().orEmpty().any { it.name.startsWith(".pending-") })
            } finally { created.forEach { it.delete() } }
        }
    }

    @Test
    fun cancelledCorruptAndOversizedCapturesLeaveNoScratchAndClosedOwnerCannotSave() {
        val storage = PublicCameraStorage(context)
        try {
            writeJpeg(output(storage.beginCapture()), 32, 32)
            assertNull(storage.finishCapture(false))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            output(storage.beginCapture()).let { uri ->
                context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3)) }
            }
            assertTrue(runCatching { storage.finishCapture(true) }.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            storage.beginCapture()
            java.io.RandomAccessFile(directory.listFiles()!!.single(), "rw").use {
                it.setLength(PublicCameraStorage.MAX_CAPTURE_BYTES + 1)
            }
            assertTrue(runCatching { storage.finishCapture(true) }.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            writeJpeg(output(storage.beginCapture()), 32, 32)
            storage.finishCapture(true)?.recycle()
            storage.close()
            assertFalse(storage.save())
            assertTrue(runCatching { storage.beginCapture() }.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { storage.close() }
    }

    @Test
    fun recreatingOwnerDiscardsAbandonedPublicScratchAndProviderCannotReachVault() {
        directory.mkdirs()
        File(directory, "abandoned.jpg").writeBytes(byteArrayOf(1))
        PublicCameraStorage(context).use { storage ->
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            val uri = output(storage.beginCapture())
            assertNotNull(context.contentResolver.openFileDescriptor(uri, "rw")?.use { it.statSize })
            assertTrue(runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.public-camera", File(context.filesDir, "vault/secret"))
            }.isFailure)
            storage.discard()
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun restoredAlbumParentAndScratchLinksAreRejectedBeforeCleanup() {
        for (kind in listOf("album", "parent", "scratch")) {
            val root = File(context.cacheDir, "public-camera-boundary-${UUID.randomUUID()}")
            val files = File(root, "files").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val victim = File(root, "victim").apply { mkdirs() }
            val marker = File(victim, ".pending-${UUID.randomUUID()}").apply { writeText("keep") }
            val link = when (kind) {
                "album" -> File(files, "public/camera").also { it.parentFile!!.mkdirs() }
                "parent" -> File(files, "public")
                else -> File(cache, "public-camera")
            }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir(): File = files
                override fun getCacheDir(): File = cache
            }
            try {
                Os.symlink(victim.absolutePath, link.absolutePath)
                assertTrue("$kind link must be rejected", runCatching { PublicCameraStorage(isolated) }.isFailure)
                assertTrue("$kind cleanup must not delete a foreign pending file", marker.isFile)
                assertEquals("keep", marker.readText())
            } finally {
                if (link.exists()) check(link.delete())
                root.deleteRecursively()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun output(intent: Intent): Uri = requireNotNull(intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT))

    private fun writeJpeg(uri: Uri, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try { context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) } }
        finally { bitmap.recycle() }
    }

    private fun publicRows(): Set<Uri> {
        val rows = mutableSetOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Pictures/Chur/"), null,
        )!!.use { cursor ->
            while (cursor.moveToNext()) rows += android.content.ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0),
            )
        }
        return rows
    }
}
