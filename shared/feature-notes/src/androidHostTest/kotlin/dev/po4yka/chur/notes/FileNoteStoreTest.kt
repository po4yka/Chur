package dev.po4yka.chur.notes

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The store keeps notes across instances, which is the whole point of it: a
 * shell that forgets is the decoy `DISCREET_MODE.md` forbids.
 */
class FileNoteStoreTest {
    private val directory: File = File.createTempFile("chur-notes", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }

    private val path: String = File(directory, "notes.json").path

    @AfterTest
    fun clean() {
        directory.deleteRecursively()
    }

    @Test
    fun a_note_survives_a_new_store() = runTest {
        FileNoteStore(path).put(Note(id = "a", title = "Groceries", body = "milk", updatedMs = 7))

        val reopened = FileNoteStore(path).all()

        assertEquals(1, reopened.size)
        assertEquals("Groceries", reopened.first().title)
        assertEquals("milk", reopened.first().body)
    }

    @Test
    fun a_body_with_quotes_and_newlines_round_trips() = runTest {
        val body = "line one\n\"quoted\", \\escaped\\\ttabbed"
        FileNoteStore(path).put(Note(id = "a", title = "", body = body, updatedMs = 1))

        assertEquals(body, FileNoteStore(path).all().first().body)
    }

    @Test
    fun removal_persists() = runTest {
        val store = FileNoteStore(path)
        store.put(Note(id = "a", title = "one", body = "", updatedMs = 1))
        store.put(Note(id = "b", title = "two", body = "", updatedMs = 2))
        store.remove("a")

        assertEquals(listOf("b"), FileNoteStore(path).all().map { it.id })
    }

    @Test
    fun an_absent_file_is_an_empty_store() = runTest {
        assertEquals(emptyList(), FileNoteStore(path).all())
        assertTrue(!File(path).exists())
    }

    /**
     * An unreadable file must not be replaced by the next write. Reporting the
     * failure keeps whatever is in the file; answering "empty" would delete it
     * on the next keystroke.
     */
    @Test
    fun an_unreadable_file_is_reported_rather_than_replaced() = runTest {
        File(path).writeText("{ this is not the note file")

        assertFailsWith<IllegalStateException> { FileNoteStore(path).all() }
        assertEquals("{ this is not the note file", File(path).readText())
    }
}
