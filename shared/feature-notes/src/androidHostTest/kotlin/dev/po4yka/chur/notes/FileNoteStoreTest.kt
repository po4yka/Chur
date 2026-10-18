package dev.po4yka.chur.notes

import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun a_failed_update_keeps_the_saved_state_and_the_same_value_can_be_retried() = runTest {
        val original = Note("a", "Saved", "original", 1)
        FileNoteStore(path).put(original)
        val writer = FailingWriter()
        val store = FileNoteStore(path, ::readNoteFile, writer::write)
        val changed = original.copy(body = "# Unsaved\n\n- [x] exact source  \n", updatedMs = 2)

        writer.failNext = true
        assertFailsWith<IOException> { store.put(changed) }
        assertEquals(listOf(original), store.all())
        assertEquals(listOf(original), FileNoteStore(path).all())

        store.put(changed)
        assertEquals(listOf(changed), store.all())
        assertEquals(listOf(changed), FileNoteStore(path).all())
    }

    @Test
    fun a_failed_delete_keeps_the_note_and_retry_persists_its_removal() = runTest {
        val note = Note("a", "Saved", "body", 1)
        FileNoteStore(path).put(note)
        val writer = FailingWriter()
        val store = FileNoteStore(path, ::readNoteFile, writer::write)

        writer.failNext = true
        assertFailsWith<IOException> { store.remove(note.id) }
        assertEquals(listOf(note), store.all())
        assertEquals(listOf(note), FileNoteStore(path).all())

        store.remove(note.id)
        assertEquals(emptyList(), store.all())
        assertEquals(emptyList(), FileNoteStore(path).all())
    }

    @Test
    fun a_failed_disclosure_write_does_not_acknowledge_and_retry_survives_reopen() = runTest {
        val writer = FailingWriter()
        val store = FileNoteStore(path, ::readNoteFile, writer::write)

        writer.failNext = true
        assertFailsWith<IOException> { store.acknowledgeDisclosure() }
        assertFalse(store.disclosureAcknowledged())
        assertFalse(FileNoteStore(path).disclosureAcknowledged())

        store.acknowledgeDisclosure()
        assertTrue(store.disclosureAcknowledged())
        assertTrue(FileNoteStore(path).disclosureAcknowledged())
    }

    @Test
    fun version_one_is_migrated_without_reinterpreting_its_plain_text() = runTest {
        File(path).writeText(
            """{"version":1,"notes":[{"id":"old","title":"Legacy","body":"# literal heading","updatedMs":1}],"disclosureAcknowledged":true}""",
        )
        val store = FileNoteStore(path)
        val legacy = store.all().single()
        assertEquals(NoteFormat.PLAIN_TEXT, legacy.format)
        assertEquals("# literal heading", legacy.body)
        assertTrue(store.disclosureAcknowledged())

        val markdown = Note("new", "Markdown", "# Heading\n\n| a | b |\n| - | - |\n| 1 | 2 |\n", 2)
        store.put(markdown)

        val reopened = FileNoteStore(path).all()
        assertEquals(listOf(markdown, legacy), reopened)
        assertTrue(File(path).readText().contains("\"version\":2"))
        assertTrue(File(path).readText().contains("\"format\":\"PLAIN_TEXT\""))
        assertTrue(File(path).readText().contains("\"format\":\"MARKDOWN\""))
    }

    @Test
    fun an_unsupported_version_cannot_be_read_or_overwritten() = runTest {
        for (version in listOf(0, 3, 999)) {
            val contents = """{"version":$version,"notes":[],"disclosureAcknowledged":false}"""
            File(path).writeText(contents)
            val store = FileNoteStore(path)

            assertFailsWith<IllegalStateException> { store.all() }
            assertFailsWith<IllegalStateException> { store.put(Note("a", "", "body", 1)) }
            assertFailsWith<IllegalStateException> { store.remove("a") }
            assertFailsWith<IllegalStateException> { store.acknowledgeDisclosure() }
            assertEquals(contents, File(path).readText())
        }
    }

    private class FailingWriter {
        var failNext = false

        fun write(path: String, contents: String) {
            if (failNext) {
                failNext = false
                throw IOException("injected atomic-write failure")
            }
            writeNoteFile(path, contents)
        }
    }

}
