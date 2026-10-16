@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.PrivateNoteAction
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Actual iOS C ABI and SQLCipher; public notes use a different, unchanged file. */
class PrivateNotesIosTest {
    @Test
    fun native_notes_preserve_UTF8_and_durable_revisions_without_entering_the_public_store(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "chur-private-notes-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val publicPath = "$root/public-notes.json"
        val public = Note("public", "Public fixture", "Public shell content", 1)
        val password = "Private-notes-iOS-fixture".encodeToByteArray()
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        var reopened: VaultRepository? = null
        try {
            FileNoteStore(publicPath).put(public)
            vault.start(); vault.create(password, false)
            val generation = (vault.state.value as VaultState.Unlocked).generation
            val saved = vault.savePrivateNote(generation, title = "Личная заметка 🔐", body = "Приватный UTF-8\nSecond line")
            val changed = vault.savePrivateNote(generation, saved.noteId, saved.revision, saved.title, "Updated UTF-8 🔐")
            assertFailsWith<ChurFailure> {
                vault.savePrivateNote(generation, saved.noteId, saved.revision, "Stale", "must not commit")
            }
            assertEquals(changed.revision, vault.privateNote(generation, saved.noteId).revision)
            assertEquals(listOf(public), FileNoteStore(publicPath).all())
            vault.lock(LockReason.PANIC)
            assertFailsWith<ChurFailure> { vault.privateNote(generation, saved.noteId) }
            vault.shutdown()
            val next = VaultRepository("$root/vault", { 1_700_000_000_001L }).also { reopened = it }
            next.start(); next.unlock(password)
            val nextGeneration = (next.state.value as VaultState.Unlocked).generation
            val durable = next.privateNote(nextGeneration, saved.noteId)
            assertEquals("Updated UTF-8 🔐", durable.body)
            val trashed = requireNotNull(next.changePrivateNote(nextGeneration, durable.noteId, durable.revision, PrivateNoteAction.TRASH))
            assertTrue(next.privateNotes(nextGeneration).notes.isEmpty())
            assertEquals(listOf(durable.id), next.privateNotes(nextGeneration, trashed = true).notes.map { it.id })
            next.changePrivateNote(nextGeneration, durable.noteId, trashed.revision, PrivateNoteAction.RESTORE)
            assertEquals("Updated UTF-8 🔐", next.privateNote(nextGeneration, durable.noteId).body)
            assertEquals(listOf(public), FileNoteStore(publicPath).all())
        } finally {
            runCatching { vault.shutdown() }
            runCatching { reopened?.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }
}
