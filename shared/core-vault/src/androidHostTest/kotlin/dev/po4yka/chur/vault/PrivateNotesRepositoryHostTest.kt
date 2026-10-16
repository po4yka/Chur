package dev.po4yka.chur.vault

import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.PrivateNoteAction
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real host JNI, SQLCipher persistence, generation checks, and encrypted backup. */
class PrivateNotesRepositoryHostTest {
    private val roots = mutableListOf<File>()
    private val repositories = mutableListOf<VaultRepository>()
    private var now = 1_700_000_000_000L

    @AfterTest
    fun cleanUp(): Unit = runBlocking {
        repositories.forEach { runCatching { it.shutdown() } }
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun committed_notes_search_pin_trash_restore_and_survive_reopen_without_plaintext_files(): Unit = runBlocking {
        val root = root()
        var vault = repository(root)
        vault.start(); vault.create(PASSWORD, false)
        var generation = generation(vault)
        val note = vault.savePrivateNote(generation, title = "Приватная заметка 🔐", body = CANARY)
        val other = vault.savePrivateNote(generation, title = "Second note", body = "ordinary fixture")
        assertEquals(CANARY, vault.privateNote(generation, note.noteId).body)
        assertEquals(listOf(note.id), vault.privateNotes(generation, terms = "Приватная").notes.map { it.id })
        val pinned = requireNotNull(vault.changePrivateNote(generation, other.noteId, other.revision, PrivateNoteAction.PIN))
        assertTrue(pinned.pinned)
        assertEquals(other.id, vault.privateNotes(generation).notes.first().id)
        val trash = requireNotNull(vault.changePrivateNote(generation, note.noteId, note.revision, PrivateNoteAction.TRASH))
        assertFalse(vault.privateNotes(generation).notes.any { it.id == note.id })
        assertEquals(listOf(note.id), vault.privateNotes(generation, trashed = true).notes.map { it.id })
        val restored = requireNotNull(vault.changePrivateNote(generation, note.noteId, trash.revision, PrivateNoteAction.RESTORE))
        assertEquals(CANARY, restored.body)
        vault.shutdown()
        assertNoPlaintext(root, CANARY)
        vault = repository(root)
        vault.start(); vault.unlock(PASSWORD)
        generation = generation(vault)
        assertEquals(CANARY, vault.privateNote(generation, note.noteId).body)
        assertEquals(2, vault.privateNotes(generation).notes.size)
        val current = vault.privateNote(generation, note.noteId)
        val trashed = requireNotNull(vault.changePrivateNote(generation, note.noteId, current.revision, PrivateNoteAction.TRASH))
        vault.changePrivateNote(generation, note.noteId, trashed.revision, PrivateNoteAction.PURGE)
        assertFalse(vault.privateNotes(generation, trashed = true).notes.any { it.id == note.id })
    }

    @Test
    fun failed_CAS_and_UTF8_bounds_keep_the_previous_committed_revision(): Unit = runBlocking {
        val vault = repository(root())
        vault.start(); vault.create(PASSWORD, false)
        val generation = generation(vault)
        val original = vault.savePrivateNote(generation, title = "Original", body = CANARY)
        val saved = vault.savePrivateNote(generation, original.noteId, original.revision, "Saved", "Saved body")
        assertFailsWith<ChurFailure> {
            vault.savePrivateNote(generation, original.noteId, original.revision, "Stale", "must not replace saved text")
        }
        assertFailsWith<ChurFailure> {
            vault.savePrivateNote(generation, original.noteId, saved.revision, "é".repeat(129), "must not commit")
        }
        assertFailsWith<ChurFailure> {
            vault.savePrivateNote(generation, original.noteId, saved.revision, "Valid title", "x".repeat(262_145))
        }
        val unchanged = vault.privateNote(generation, original.noteId)
        assertEquals(saved.revision, unchanged.revision)
        assertEquals("Saved", unchanged.title)
        assertEquals("Saved body", unchanged.body)

        val maxTitle = "é".repeat(128)
        val maxBody = "😀".repeat(65_536)
        assertEquals(256, maxTitle.encodeToByteArray().size)
        assertEquals(262_144, maxBody.encodeToByteArray().size)
        val maxNote = vault.savePrivateNote(generation, original.noteId, saved.revision, maxTitle, maxBody)
        val roundTrip = vault.privateNote(generation, maxNote.noteId)
        assertEquals(saved.revision + 1, roundTrip.revision)
        assertEquals(maxTitle, roundTrip.title)
        assertEquals(maxBody, roundTrip.body)
        val exportSize = 256L + 2L + 262_144L
        assertEquals(exportSize, maxNote.exportSize)
        assertEquals(exportSize, roundTrip.exportSize)
        assertEquals(exportSize, vault.privateNotes(generation).notes.single().exportSize)
    }

    @Test
    fun lock_and_sibling_identity_reject_old_generation_and_sibling_identifiers(): Unit = runBlocking {
        val vault = repository(root())
        vault.start(); vault.create(PASSWORD, false)
        val oldGeneration = generation(vault)
        val first = vault.savePrivateNote(oldGeneration, title = "First identity", body = CANARY)
        vault.create(OTHER_PASSWORD, false)
        val siblingGeneration = generation(vault)
        assertTrue(vault.privateNotes(siblingGeneration).notes.isEmpty())
        assertFailsWith<ChurFailure> { vault.privateNotes(oldGeneration) }
        assertFailsWith<ChurFailure> { vault.privateNote(siblingGeneration, first.noteId) }
        assertFailsWith<ChurFailure> { vault.changePrivateNote(siblingGeneration, first.noteId, first.revision, PrivateNoteAction.TRASH) }
        vault.savePrivateNote(siblingGeneration, title = "Second identity", body = "separate content")
        vault.lock(LockReason.PANIC)
        assertFailsWith<ChurFailure> { vault.privateNotes(siblingGeneration) }
        assertFailsWith<ChurFailure> { vault.savePrivateNote(siblingGeneration, title = "after lock", body = "refused") }
        vault.unlock(PASSWORD)
        val reopened = generation(vault)
        assertFailsWith<ChurFailure> { vault.privateNotes(oldGeneration) }
        assertEquals(listOf(first.id), vault.privateNotes(reopened).notes.map { it.id })
        assertEquals(CANARY, vault.privateNote(reopened, first.noteId).body)
    }

    @Test
    fun byte_exact_UTF8_export_is_revision_checked_and_backup_restores_only_selected_identity(): Unit = runBlocking {
        val root = root()
        val source = repository(root)
        source.start(); source.create(OTHER_PASSWORD, false)
        val sibling = source.savePrivateNote(generation(source), title = "Unselected identity", body = "sibling-only fixture")
        source.create(PASSWORD, false)
        val generation = generation(source)
        val title = "Заголовок 🔐"
        val body = "Первая строка\nSecond line\n"
        val note = source.savePrivateNote(generation, title = title, body = body)
        val exported = File(root, "explicit-export.txt")
        FileOutputStream(exported).use { output ->
            finish(source, source.beginPrivateNoteExport(generation, note.noteId, note.revision, descriptor(output.fd)))
        }
        assertEquals("$title\n\n$body", exported.readText())
        assertEquals(exported.length(), note.exportSize)
        val staleOutput = File(root, "stale-export.txt")
        FileOutputStream(staleOutput).use { output ->
            assertFailsWith<ChurFailure> {
                source.beginPrivateNoteExport(generation, note.noteId, note.revision + 1, descriptor(output.fd))
            }
        }
        assertEquals(0L, staleOutput.length())
        exported.delete(); staleOutput.delete()
        val packageFile = File(root, "encrypted-backup.chur")
        FileOutputStream(packageFile).use { output -> finish(source, source.beginBackup(descriptor(output.fd))) }
        assertNoPlaintext(packageFile, body)
        val destination = repository(root())
        destination.start()
        FileInputStream(packageFile).use { input -> finish(destination, destination.beginRestore(descriptor(input.fd), PASSWORD)) }
        destination.unlock(PASSWORD)
        val restoredGeneration = generation(destination)
        assertEquals(body, destination.privateNote(restoredGeneration, note.noteId).body)
        assertEquals(listOf(note.id), destination.privateNotes(restoredGeneration).notes.map { it.id })
        assertFailsWith<ChurFailure> { destination.privateNote(restoredGeneration, sibling.noteId) }
    }

    private suspend fun finish(vault: VaultRepository, operation: Long) {
        try {
            withTimeout(30_000) {
                while (true) {
                    val progress = vault.poll(operation)
                    if (progress.terminal) { assertEquals(0, progress.status); break }
                    delay(10)
                }
            }
        } finally { vault.closeOperation(operation) }
    }

    private fun root(): File = File(System.getProperty("java.io.tmpdir"), "chur-private-notes-${System.nanoTime()}")
        .apply { mkdirs(); roots.add(this) }
    private fun repository(root: File) = VaultRepository(root.absolutePath, { now }).also { repositories.add(it) }
    private fun generation(vault: VaultRepository) = (vault.state.value as VaultState.Unlocked).generation
    private fun descriptor(fd: FileDescriptor): Int = fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(fd)
    private fun assertNoPlaintext(path: File, text: String) {
        val needle = text.encodeToByteArray()
        val files = if (path.isDirectory) path.walkTopDown().filter { it.isFile }.toList() else listOf(path)
        for (file in files) {
            val bytes = file.readBytes()
            assertFalse((0..bytes.size - needle.size).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } },
                "private note content must not appear in a persistent file")
        }
    }

    private companion object {
        val PASSWORD = "private-notes-test-password".encodeToByteArray()
        val OTHER_PASSWORD = "private-notes-other-identity".encodeToByteArray()
        const val CANARY = "Private-note plaintext canary 95a702ae. Не сохранять вне зашифрованного каталога."
    }
}
