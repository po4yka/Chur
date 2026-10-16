package dev.po4yka.chur.android

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Source identity must survive resolution before Android can offer to delete it. */
@RunWith(AndroidJUnit4::class)
class OriginalDeletionTest {
    private val localRow = Uri.parse("content://media/external/images/media/42")

    @Test
    fun externalDocumentCannotDeleteALocalFileWithTheSameNameAndSize() {
        val source = Uri.parse("content://example.documents/document/42")
        val provider = SourceProvider(source)

        assertNull(originalRow(ContentResolver.wrap(provider), source))
        assertTrue("an external source must not search local rows", provider.mediaQueries.isEmpty())
    }

    @Test
    fun cloudPickerCannotDeleteALocalFileWithTheSameNameAndSize() {
        val source = Uri.parse("content://media/picker/0/example.cloud.provider/media/42")
        val provider = SourceProvider(source)

        assertNull(originalRow(ContentResolver.wrap(provider), source))
        assertTrue(provider.mediaQueries.isEmpty())
    }

    @Test
    fun localPickerResolvesTheActualMediaStoreId() {
        val source = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/42")
        val provider = SourceProvider(source)

        assertEquals(localRow, originalRow(ContentResolver.wrap(provider), source))
        assertEquals(listOf("_id = ?" to listOf("42")), provider.mediaQueries)
    }

    @Test
    fun mediaStoreOriginalKeepsItsVolume() {
        val source = Uri.parse("content://media/external_primary/images/media/42")
        val provider = SourceProvider(source)

        assertEquals(source, originalRow(ContentResolver.wrap(provider), source))
    }

    @Test
    fun differentSizeDoesNotAuthorizeDeletion() {
        val source = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/42")
        assertNull(originalRow(ContentResolver.wrap(SourceProvider(source, sourceSize = 101)), source))
    }

    @Test
    fun arbitraryMediaPathCannotBorrowALocalId() {
        val source = Uri.parse("content://media/other/42")
        assertNull(originalRow(ContentResolver.wrap(SourceProvider(source)), source))
    }

    @Test
    fun localPickerFromAnotherProfileCannotDeleteTheCurrentProfilesId() {
        val source = Uri.parse("content://media/picker/10/com.android.providers.media.photopicker/media/42")
        assertNull(originalRow(ContentResolver.wrap(SourceProvider(source)), source))
    }

    @Test
    fun inaccessibleOriginalIsNotADeletionTarget() {
        val source = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/42")
        assertNull(originalRow(ContentResolver.wrap(SourceProvider(source, visible = false)), source))
    }

    /** The external source A and unrelated local B deliberately have equal metadata. */
    private class SourceProvider(
        private val source: Uri,
        private val sourceSize: Long = 100,
        private val visible: Boolean = true,
    ) : ContentProvider() {
        val mediaQueries = mutableListOf<Pair<String?, List<String>>>()

        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/jpeg"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = requireNotNull(projection)
            val sourceMetadata = !columns.contains(MediaStore.MediaColumns._ID)
            val cursor = MatrixCursor(columns)
            if (sourceMetadata) {
                assertEquals(source, uri)
                cursor.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) "same.jpg" else sourceSize })
            } else {
                mediaQueries.add(selection to selectionArgs.orEmpty().toList())
                if (visible) cursor.addRow(columns.map { if (it == MediaStore.MediaColumns._ID) 42L else 100L })
            }
            return cursor
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri = error("unexpected insert")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("unexpected delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            error("unexpected update")
    }
}
