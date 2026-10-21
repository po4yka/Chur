package dev.po4yka.chur.android

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** A partial MediaStore export is not left in shared storage after the process dies. */
@RunWith(AndroidJUnit4::class)
class MediaExportCleanupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    @Test
    fun aNewProcessDeletesThePendingRowsThatAnEarlierProcessLeft() {
        // An earlier process inserted the row and died before publish or discard.
        val abandoned = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "chur-abandoned-${System.nanoTime()}.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(abandoned)!!.use { it.write(ByteArray(64) { 7 }) }
            assertNotNull(pendingRow(abandoned))
            ExportDestinations(context, {}, {})
            assertEquals(null, pendingRow(abandoned))
        } finally {
            resolver.delete(abandoned, null, null)
        }
    }

    private fun pendingRow(uri: Uri): Long? = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_EXPIRES),
        Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }, null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }
}
