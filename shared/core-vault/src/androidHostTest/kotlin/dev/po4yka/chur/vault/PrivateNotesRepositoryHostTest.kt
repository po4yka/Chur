package dev.po4yka.chur.vault

import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.PrivateNoteAttachmentData
import dev.po4yka.chur.ffi.PrivateNoteFormat
import dev.po4yka.chur.ffi.PrivateNoteSelection
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

    @Test
    fun markdown_source_format_survives_export_backup_restore_and_selective_archive(): Unit = runBlocking {
        val sourceRoot = root()
        val source = repository(sourceRoot)
        source.start(); source.create(PASSWORD, false)
        val generation = generation(source)
        val body = "# Заголовок\n\n- [x] Выполнено\n\n```kotlin\nval answer = 42\n```\n"
        val note = source.savePrivateNote(generation, title = "Separate title", body = body, format = PrivateNoteFormat.MARKDOWN)
        assertEquals(PrivateNoteFormat.MARKDOWN, source.privateNotes(generation).notes.single().format)
        val outputFile = File(sourceRoot, "note.md")
        FileOutputStream(outputFile).use { output -> finish(source, source.beginPrivateNoteExport(generation, note.noteId, note.revision, descriptor(output.fd))) }
        assertEquals(body, outputFile.readText())
        assertEquals(body.encodeToByteArray().size.toLong(), note.exportSize)
        outputFile.delete()
        val backup = File(sourceRoot, "backup.chur")
        FileOutputStream(backup).use { output -> finish(source, source.beginBackup(descriptor(output.fd))) }
        val restored = repository(root()); restored.start()
        FileInputStream(backup).use { input -> finish(restored, restored.beginRestore(descriptor(input.fd), PASSWORD)) }
        restored.unlock(PASSWORD)
        val roundTrip = restored.privateNote(generation(restored), note.noteId)
        assertEquals(body, roundTrip.body)
        assertEquals(PrivateNoteFormat.MARKDOWN, roundTrip.format)
        val archive = File(sourceRoot, "selection.chur")
        FileOutputStream(archive).use { output -> finish(source, source.beginSelectiveExport(generation, emptyList(), emptyList(), listOf(PrivateNoteSelection(note.noteId, note.revision)), OTHER_PASSWORD, descriptor(output.fd))) }
        assertNoPlaintext(archive, body)
        val target = repository(root()); target.start(); target.create(PASSWORD, false)
        FileInputStream(archive).use { input -> finish(target, target.beginSelectiveImport(generation(target), descriptor(input.fd), OTHER_PASSWORD)) }
        val selected = target.privateNotes(generation(target)).notes.single()
        assertFalse(selected.noteId.contentEquals(note.noteId))
        val imported = target.privateNote(generation(target), selected.noteId)
        assertEquals(body, imported.body)
        assertEquals(PrivateNoteFormat.MARKDOWN, imported.format)
    }

    @Test
    fun attachment_bytes_survive_encrypted_backup_selective_archive_and_revision_checked_export(): Unit = runBlocking {
        val sourceRoot=root();val source=repository(sourceRoot);source.start();source.create(PASSWORD,false)
        val generation=generation(source);val attachmentId=ByteArray(16) { (it+1).toByte() }
        val data=("%PDF-1.7\n"+CANARY).encodeToByteArray()
        val body="[Document](attachment:0102030405060708090a0b0c0d0e0f10)\n"
        val note=source.importPrivateNotePackage(generation,"Package",body,PrivateNoteFormat.MARKDOWN,listOf(PrivateNoteAttachmentData(attachmentId,"document.pdf","application/pdf",data)))
        assertEquals(1,note.attachmentCount);assertEquals(1,source.privateNoteAttachments(generation,note.noteId).size)
        assertTrue(data.contentEquals(source.readPrivateNoteAttachment(generation,note.noteId,attachmentId)))
        assertFailsWith<ChurFailure> { source.removePrivateNoteAttachment(generation,note.noteId,note.revision+1,attachmentId) }
        val fileOutput=File(sourceRoot,"explicit.pdf")
        FileOutputStream(fileOutput).use { finish(source,source.beginPrivateNoteAttachmentExport(generation,note.noteId,note.revision,attachmentId,descriptor(it.fd))) }
        assertTrue(data.contentEquals(fileOutput.readBytes()));fileOutput.delete()
        val staleFile=File(sourceRoot,"stale.pdf")
        FileOutputStream(staleFile).use { output -> assertFailsWith<ChurFailure> { source.beginPrivateNoteAttachmentExport(generation,note.noteId,note.revision+1,attachmentId,descriptor(output.fd)) } }
        assertEquals(0L,staleFile.length());staleFile.delete()
        val output=File(sourceRoot,"explicit.churnote");FileOutputStream(output).use { finish(source,source.beginPrivateNoteExport(generation,note.noteId,note.revision,descriptor(it.fd))) }
        assertEquals(note.exportSize,output.length());assertTrue(output.readText().contains("\"mimeType\":\"application/pdf\""));assertTrue(output.readText().contains(body.trim()))
        output.delete();assertNoPlaintext(sourceRoot,CANARY)
        val backup=File(sourceRoot,"backup.chur");FileOutputStream(backup).use { finish(source,source.beginBackup(descriptor(it.fd))) };assertNoPlaintext(backup,CANARY)
        val restored=repository(root());restored.start();FileInputStream(backup).use { finish(restored,restored.beginRestore(descriptor(it.fd),PASSWORD)) };restored.unlock(PASSWORD)
        assertTrue(data.contentEquals(restored.readPrivateNoteAttachment(generation(restored),note.noteId,attachmentId)))
        val archive=File(sourceRoot,"selected.chur");FileOutputStream(archive).use { finish(source,source.beginSelectiveExport(generation,emptyList(),emptyList(),listOf(PrivateNoteSelection(note.noteId,note.revision)),OTHER_PASSWORD,descriptor(it.fd))) }
        val target=repository(root());target.start();target.create(PASSWORD,false);FileInputStream(archive).use { finish(target,target.beginSelectiveImport(generation(target),descriptor(it.fd),OTHER_PASSWORD)) }
        val imported=target.privateNotes(generation(target)).notes.single();assertEquals(1,imported.attachmentCount);assertTrue(data.contentEquals(target.readPrivateNoteAttachment(generation(target),imported.noteId,attachmentId)))
        val trash=requireNotNull(source.changePrivateNote(generation,note.noteId,note.revision,PrivateNoteAction.TRASH));assertTrue(data.contentEquals(source.readPrivateNoteAttachment(generation,note.noteId,attachmentId)))
        source.changePrivateNote(generation,note.noteId,trash.revision,PrivateNoteAction.PURGE);assertFailsWith<ChurFailure> { source.readPrivateNoteAttachment(generation,note.noteId,attachmentId) }
        source.lock(LockReason.BACKGROUND);assertFailsWith<ChurFailure> { source.readPrivateNoteAttachment(generation,note.noteId,attachmentId) };data.fill(0)
    }

    @Test
    fun workspace_filters_and_encrypted_history_restore_original_files_through_real_JNI_and_archives(): Unit = runBlocking {
        val sourceRoot = root()
        val source = repository(sourceRoot); source.start(); source.create(PASSWORD, false)
        val generation = generation(source)
        val fileId = ByteArray(16) { (it + 1).toByte() }
        val data = ("%PDF-1.7\n" + CANARY).encodeToByteArray()
        val initial = source.importPrivateNotePackage(generation, "History", CANARY, PrivateNoteFormat.MARKDOWN,
            listOf(PrivateNoteAttachmentData(fileId, "history.pdf", "application/pdf", data)))
        val organization = dev.po4yka.chur.ffi.PrivateNoteOrganization("Проекты", listOf("Plan, do", "важное"))
        val organized = source.organizePrivateNote(generation, initial.noteId, initial.revision, organization)
        val changed = source.savePrivateNote(generation, initial.noteId, organized.revision, "Changed", "new text", format = PrivateNoteFormat.MARKDOWN)
        val removed = source.removePrivateNoteAttachment(generation, initial.noteId, changed.revision, fileId)
        val page = source.privateNoteWorkspace(generation, "", false, 1, null, "Проекты", "важное")
        assertEquals(listOf(initial.id), page.page.notes.map { it.id })
        assertEquals(organization, page.organization[initial.id])
        assertTrue(source.privateNoteWorkspace(generation, "", false, 1, null, "other", "").page.notes.isEmpty())
        val detail = source.privateNoteRevision(generation, initial.noteId, organized.revision)
        assertEquals(CANARY, detail.note.body)
        assertEquals(organization, detail.organization)
        assertEquals(1, detail.attachments.size)
        val recovered = source.restorePrivateNoteRevision(generation, initial.noteId, removed.revision, organized.revision)
        assertTrue(recovered.revision > removed.revision)
        assertEquals(CANARY, recovered.body)
        assertTrue(data.contentEquals(source.readPrivateNoteAttachment(generation, initial.noteId, fileId)))
        assertTrue(source.privateNoteHistory(generation, initial.noteId).any { it.revision == removed.revision })
        assertFailsWith<ChurFailure> { source.restorePrivateNoteRevision(generation, initial.noteId, removed.revision, organized.revision) }
        val backup = File(sourceRoot, "workspace-backup.chur")
        FileOutputStream(backup).use { finish(source, source.beginBackup(descriptor(it.fd))) }
        val restored = repository(root()); restored.start()
        FileInputStream(backup).use { finish(restored, restored.beginRestore(descriptor(it.fd), PASSWORD)) }
        restored.unlock(PASSWORD)
        assertEquals(organization, restored.privateNoteOrganization(generation(restored), initial.noteId))
        assertEquals(CANARY, restored.privateNoteRevision(generation(restored), initial.noteId, organized.revision).note.body)
        val archive = File(sourceRoot, "workspace-selected.chur")
        FileOutputStream(archive).use { finish(source, source.beginSelectiveExport(generation, emptyList(), emptyList(),
            listOf(PrivateNoteSelection(initial.noteId, recovered.revision)), OTHER_PASSWORD, descriptor(it.fd))) }
        val target = repository(root()); target.start(); target.create(PASSWORD, false)
        FileInputStream(archive).use { finish(target, target.beginSelectiveImport(generation(target), descriptor(it.fd), OTHER_PASSWORD)) }
        val selected = target.privateNotes(generation(target)).notes.single()
        assertEquals(organization, target.privateNoteOrganization(generation(target), selected.noteId))
        val old = target.privateNoteHistory(generation(target), selected.noteId).first { it.attachmentCount == 0 }
        val withoutFile = target.restorePrivateNoteRevision(generation(target), selected.noteId, selected.revision, old.revision)
        assertEquals(0, withoutFile.attachmentCount)
        val original = target.privateNoteHistory(generation(target), selected.noteId).first { it.attachmentCount == 1 }
        target.restorePrivateNoteRevision(generation(target), selected.noteId, withoutFile.revision, original.revision)
        assertTrue(data.contentEquals(target.readPrivateNoteAttachment(generation(target), selected.noteId, fileId)))
        source.lock(LockReason.PANIC)
        assertFailsWith<ChurFailure> { source.privateNoteHistory(generation, initial.noteId) }
        assertFailsWith<ChurFailure> { source.privateNoteWorkspace(generation, "", false, 1, null, "", "") }
        assertNoPlaintext(sourceRoot, CANARY)
        data.fill(0)
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
