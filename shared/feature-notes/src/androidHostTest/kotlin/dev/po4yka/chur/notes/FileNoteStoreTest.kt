package dev.po4yka.chur.notes

import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch

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
        assertTrue(File(path).readText().contains("\"version\":3"))
        assertTrue(File(path).readText().contains("\"format\":\"PLAIN_TEXT\""))
        assertTrue(File(path).readText().contains("\"format\":\"MARKDOWN\""))
    }

    @Test
    fun an_unsupported_version_cannot_be_read_or_overwritten() = runTest {
        for (version in listOf(0, 4, 999)) {
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

    @Test
    fun attachments_survive_restart_and_removal_deletes_only_after_metadata_save() = runTest {
        val note = Note("a", "Files", "source", 1)
        val store = FileNoteStore(path)
        store.put(note)
        val bytes = byteArrayOf(0, 1, -1)
        val attachment = store.addAttachment(note.id, "example.bin", bytes)
        val reopened = FileNoteStore(path)
        assertEquals(listOf(attachment), reopened.all().single().attachments)
        assertContentEquals(bytes, reopened.readAttachment(note.id, attachment.id))

        val writer = FailingWriter()
        val failing = FileNoteStore(path, ::readNoteFile, writer::write)
        writer.failNext = true
        assertFailsWith<IOException> { failing.removeAttachment(note.id, attachment.id) }
        assertContentEquals(bytes, failing.readAttachment(note.id, attachment.id))
        assertContentEquals(bytes, FileNoteStore(path).readAttachment(note.id, attachment.id))

        failing.removeAttachment(note.id, attachment.id)
        assertTrue(FileNoteStore(path).all().single().attachments.isEmpty())
        assertFalse(File("$path.attachments/${attachment.storageId}").exists())
    }

    @Test
    fun attachment_metadata_is_never_published_after_a_failed_write() = runTest {
        val note = Note("a", "Files", "source", 1)
        FileNoteStore(path).put(note)
        val writer = FailingWriter()
        val store = FileNoteStore(path, ::readNoteFile, writer::write)
        writer.failNext = true
        assertFailsWith<IOException> { store.addAttachment(note.id, "example.bin", byteArrayOf(1)) }
        assertEquals(note, store.all().single())
        assertEquals(note, FileNoteStore(path).all().single())
        assertTrue(File("$path.attachments").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun package_import_is_atomic_and_equal_reference_ids_do_not_overwrite_other_notes() = runTest {
        val id = "0123456789abcdef0123456789abcdef"
        fun content(byte: Byte) = NotePackages.encode(NotePackage("Imported", "[file](attachment:$id)  ", NoteFormat.MARKDOWN,
            listOf(NotePackageAttachment(id, "file.bin", "application/octet-stream", byteArrayOf(byte)))))
        val store = FileNoteStore(path)
        val first = store.importPackage(content(1), "first", 1)
        val second = store.importPackage(content(2), "second", 2)
        assertEquals(first.body, second.body)
        assertContentEquals(byteArrayOf(1), store.readAttachment("first", id))
        assertContentEquals(byteArrayOf(2), store.readAttachment("second", id))
        assertContentEquals(byteArrayOf(1), NotePackages.decode(store.exportPackage("first")).attachments.single().data)

        val writer = FailingWriter()
        val failing = FileNoteStore(path, ::readNoteFile, writer::write)
        writer.failNext = true
        assertFailsWith<IOException> { failing.importPackage(content(3), "failed", 3) }
        assertEquals(setOf("first", "second"), FileNoteStore(path).all().map { it.id }.toSet())
        assertEquals(2, File("$path.attachments").listFiles().orEmpty().size)
    }

    @Test
    fun forged_or_stale_metadata_and_oversized_blob_are_rejected() = runTest {
        val store = FileNoteStore(path)
        val note = Note("a", "Files", "source", 1)
        store.put(note)
        val attachment = store.addAttachment(note.id, "example.bin", byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { store.put(note.copy(title = "stale")) }
        assertFailsWith<IllegalArgumentException> {
            store.put(store.all().single().copy(attachments = listOf(attachment.copy(name = "changed"))))
        }
        File("$path.attachments/${attachment.storageId}").writeBytes(ByteArray(NoteAttachments.MAX_FILE_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { store.readAttachment(note.id, attachment.id) }
        assertFailsWith<IllegalStateException> { store.readAttachment(note.id, "../notes.json") }
    }

    @Test
    fun cancellation_during_metadata_commit_keeps_blob_metadata_and_cache_consistent() = runTest {
        FileNoteStore(path).put(Note("a", "Files", "body", 1))
        var duringWrite: () -> Unit = {}
        val store = FileNoteStore(path, ::readNoteFile) { file, contents ->
            duringWrite()
            writeNoteFile(file, contents)
        }
        val operation = launch { store.addAttachment("a", "file.bin", byteArrayOf(1, 2, 3)) }
        duringWrite = { operation.cancel() }
        operation.join()
        val attachment = store.all().single().attachments.single()
        assertContentEquals(byteArrayOf(1, 2, 3), store.readAttachment("a", attachment.id))
        assertEquals(store.all(), FileNoteStore(path).all())
        assertContentEquals(byteArrayOf(1, 2, 3), FileNoteStore(path).readAttachment("a", attachment.id))
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
