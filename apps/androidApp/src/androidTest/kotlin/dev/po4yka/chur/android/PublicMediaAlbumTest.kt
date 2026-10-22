package dev.po4yka.chur.android

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicMediaAlbumTest {
    @Test fun existingJpegsAndMoreThanOneHundredNewCapturesSurviveReopen() = isolated { context ->
        val album = PublicMediaAlbum(context)
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            repeat(101) {
                val pending = album.pending(false)
                pending.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
                assertTrue(album.list().none { it.id == pending.name })
                album.commit(pending)
            }
            val reopened = PublicMediaAlbum(context)
            assertEquals(101, reopened.list().size)
            val first = reopened.list().first()
            assertFalse(first.video)
            assertNotNull(reopened.thumbnail(first.id))
            reopened.delete(first.id)
            assertEquals(100, album.list().size)
        } finally { bitmap.recycle() }
    }
    @Test fun recoveryIsAtomicAndRejectsMalformedMediaAndLinks() = isolated { context ->
        val album = PublicMediaAlbum(context)
        val pending = album.pending(false)
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try { pending.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } } finally { bitmap.recycle() }
        val invalid = album.pending(true).apply { writeText("not a video") }
        assertTrue(runCatching { album.commit(invalid) }.isFailure)
        assertTrue(invalid.exists())
        assertEquals(1, album.recover())
        assertFalse(pending.exists())
        assertEquals(1, album.list().size)
        assertTrue(runCatching { album.file("../outside.jpg") }.isFailure)
        val target = File(context.filesDir, "outside").apply { writeText("untouched") }
        val link = File(context.filesDir, "public/camera/${UUID.randomUUID()}.jpg")
        Os.symlink(target.path, link.path)
        assertEquals(1, album.list().size)
        assertTrue(runCatching { album.delete(link.name) }.isFailure)
        assertEquals("untouched", target.readText())
    }
    private fun isolated(body: (android.content.Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "public-media-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try { body(context) } finally { root.deleteRecursively() }
    }
}
