@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.OperationProgress
import dev.po4yka.chur.ffi.PrivateNoteSelection
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.read
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual iOS C ABI, native streaming, SQLCipher, and restore. Only public fixtures. */
class SelectiveArchiveIosTest {
    @Test
    fun native_archive_restore_and_existing_vault_import_keep_separate_ids_credentials_and_content(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "chur-selective-ios-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val source = VaultRepository("$root/source", { NOW })
        val restored = VaultRepository("$root/restored", { NOW })
        val target = VaultRepository("$root/target", { NOW })
        try {
            source.start(); source.create(SOURCE_PASSWORD, false)
            val document = "Selected iOS UTF-8 document 🔐\n".encodeToByteArray()
            val original = importObject(source, "$root/input", document, "selected.txt")
            val hidden = importObject(source, "$root/hidden-input", byteArrayOf(9, 8, 7), "UNSELECTED.bin")
            val parent = source.createAlbum("Selected parent")
            val child = source.createAlbum("Selected child")
            source.moveAlbum(child, parent, null)
            source.setAlbumMembership(child, original, true)
            source.createAlbum("UNSELECTED album")
            val note = source.savePrivateNote(generation(source), title = "é".repeat(128), body = "😀".repeat(65_536), pinned = true)
            source.savePrivateNote(generation(source), title = "UNSELECTED note", body = "UNSELECTED body")
            val beforeGeneration = source.page(ObjectQuery()).catalogGeneration
            val archive = "$root/selected.chur"
            withOutput(archive) { fd ->
                finish(source, source.beginSelectiveExport(generation(source), listOf(original, original), listOf(parent, child),
                    listOf(PrivateNoteSelection(note.noteId, note.revision), PrivateNoteSelection(note.noteId, note.revision)), ARCHIVE_PASSWORD, fd))
            }
            assertEquals(beforeGeneration, source.page(ObjectQuery()).catalogGeneration)
            assertEquals(2, source.page(ObjectQuery()).objects.size)
            assertContentEquals(byteArrayOf(9, 8, 7), source.readRange(hidden, 0, 3))
            assertEquals(2, source.privateNotes(generation(source)).notes.size)
            restored.start()
            withInput(archive) { fd -> assertNotEquals(0, terminal(restored, restored.beginRestore(fd, SOURCE_PASSWORD)).status) }
            assertTrue(restored.state.value is VaultState.NoVault)
            withInput(archive) { fd -> finish(restored, restored.beginRestore(fd, ARCHIVE_PASSWORD)) }
            restored.unlock(ARCHIVE_PASSWORD)
            val item = restored.page(ObjectQuery()).objects.single()
            assertFalse(item.objectId.contentEquals(original))
            assertEquals("selected.txt", restored.detail(item.objectId).filename)
            assertContentEquals(document, restored.readRange(item.objectId, 0, document.size))
            assertEquals(setOf("Selected parent", "Selected child"), restored.albums().map { it.name }.toSet())
            val restoredParent = restored.albums().single { it.name == "Selected parent" }
            val restoredChild = restored.albums().single { it.name == "Selected child" }
            assertContentEquals(restoredParent.albumId, restoredChild.parentId)
            assertEquals(1, restored.page(ObjectQuery(QueryScope.ALBUM, scopeId = restoredChild.albumId)).objects.size)
            val summary = restored.privateNotes(generation(restored)).notes.single()
            val restoredNote = restored.privateNote(generation(restored), summary.noteId)
            assertFalse(restoredNote.noteId.contentEquals(note.noteId))
            assertEquals(note.title, restoredNote.title)
            assertEquals(note.body, restoredNote.body)
            assertEquals(262_402L, restoredNote.exportSize)
            assertTrue(restoredNote.pinned)
            assertEquals(listOf(1), restored.slots().map { it.slotType })
            assertEquals(ChurStatus.INVALID_INPUT, assertFailsWith<ChurFailure> { restored.syncHeads() }.status)

            target.start(); target.create(TARGET_PASSWORD, false)
            val keptObject = importObject(target, "$root/target-input", byteArrayOf(1, 2, 3), "selected.txt")
            val keptNote = target.savePrivateNote(generation(target), title = note.title, body = "Existing target note")
            withInput(archive) { fd -> finish(target, target.beginSelectiveImport(generation(target), fd, ARCHIVE_PASSWORD)) }
            assertEquals(2, target.page(ObjectQuery()).objects.size)
            assertContentEquals(byteArrayOf(1, 2, 3), target.readRange(keptObject, 0, 3))
            val imported = target.page(ObjectQuery()).objects.single { !it.objectId.contentEquals(keptObject) }
            assertFalse(imported.objectId.contentEquals(item.objectId))
            assertContentEquals(document, target.readRange(imported.objectId, 0, document.size))
            assertEquals("Existing target note", target.privateNote(generation(target), keptNote.noteId).body)
            assertEquals(2, target.privateNotes(generation(target)).notes.size)
            target.lock(LockReason.USER)
            assertEquals(ChurStatus.AUTHENTICATION_FAILED, assertFailsWith<ChurFailure> { target.unlock(ARCHIVE_PASSWORD) }.status)
            target.unlock(TARGET_PASSWORD)
        } finally {
            runCatching { source.shutdown() }; runCatching { restored.shutdown() }; runCatching { target.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    @Test
    fun native_import_rejects_wrong_password_corruption_and_truncation_before_target_changes(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "chur-selective-invalid-ios-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val source = VaultRepository("$root/source", { NOW })
        val target = VaultRepository("$root/target", { NOW })
        try {
            source.start(); source.create(SOURCE_PASSWORD, false)
            val note = source.savePrivateNote(generation(source), title = "Selected fixture", body = "Selected body")
            val archive = "$root/selected.chur"
            withOutput(archive) { fd -> finish(source, source.beginSelectiveExport(generation(source), emptyList(), emptyList(),
                listOf(PrivateNoteSelection(note.noteId, note.revision)), ARCHIVE_PASSWORD, fd)) }
            val bytes = readFile(archive)
            val manifest = backupPayload(bytes, 1)
            val catalog = backupPayload(bytes, 3)
            writeFile("$root/corrupt-manifest.chur", bytes.copyOf().apply {
                val offset = manifest.first + 24 + 16
                assertTrue(offset in manifest)
                this[offset] = (this[offset].toInt() xor 1).toByte()
            })
            writeFile("$root/corrupt.chur", bytes.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() })
            writeFile("$root/truncated.chur", bytes.copyOf(bytes.size - 17))
            target.start(); target.create(TARGET_PASSWORD, false)
            val kept = target.savePrivateNote(generation(target), title = "Selected fixture", body = "Existing content")
            val before = target.page(ObjectQuery()).catalogGeneration
            val cases = listOf(
                Triple("wrong password", archive, SOURCE_PASSWORD),
                Triple("encrypted manifest", "$root/corrupt-manifest.chur", ARCHIVE_PASSWORD),
                Triple("truncated final commit", "$root/truncated.chur", ARCHIVE_PASSWORD),
                Triple("middle bitflip at ${bytes.size / 2}, catalog=${catalog.first}..${catalog.last}", "$root/corrupt.chur", ARCHIVE_PASSWORD),
            )
            for ((label, path, password) in cases) {
                withInput(path) { fd ->
                    val status = try { terminal(target, target.beginSelectiveImport(generation(target), fd, password)).status }
                    catch (failure: ChurFailure) { failure.status.value }
                    assertNotEquals(0, status, label)
                }
                assertEquals(before, target.page(ObjectQuery()).catalogGeneration, label)
                assertEquals(kept.id, target.privateNotes(generation(target)).notes.single().id)
                assertEquals("Existing content", target.privateNote(generation(target), kept.noteId).body)
                assertTrue(target.page(ObjectQuery()).objects.isEmpty())
            }
        } finally {
            runCatching { source.shutdown() }; runCatching { target.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private suspend fun importObject(vault: VaultRepository, path: String, bytes: ByteArray, name: String): ByteArray {
        writeFile(path, bytes)
        return withInput(path) { fd ->
            val result = terminal(vault, vault.beginImport(fd, ImportRequest("text/plain", true, 4,
                knownLength = bytes.size.toLong(), originalFilename = name)))
            assertEquals(0, result.status)
            assertNotNull(result.importedObjectId())
        }
    }
    private suspend fun finish(vault: VaultRepository, operation: Long) { assertEquals(0, terminal(vault, operation).status) }
    private suspend fun terminal(vault: VaultRepository, operation: Long): OperationProgress = try {
        withTimeout(60_000) {
            var result = vault.poll(operation)
            while (!result.terminal) { delay(10); result = vault.poll(operation) }
            result
        }
    } finally { vault.closeOperation(operation) }
    private inline fun <T> withInput(path: String, block: (Int) -> T): T {
        val fd = open(path, O_RDONLY)
        assertTrue(fd >= 0)
        return try { block(fd) } finally { close(fd) }
    }
    private inline fun <T> withOutput(path: String, block: (Int) -> T): T {
        val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, 384)
        assertTrue(fd >= 0)
        return try { block(fd) } finally { close(fd) }
    }
    private fun writeFile(path: String, bytes: ByteArray) = withOutput(path) { fd ->
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                assertTrue(count > 0)
                offset += count
            }
        }
    }
    private fun readFile(path: String): ByteArray = withInput(path) { fd ->
        val size = lseek(fd, 0, SEEK_END)
        assertTrue(size in 1..Int.MAX_VALUE.toLong())
        assertEquals(0L, lseek(fd, 0, SEEK_SET))
        ByteArray(size.toInt()).also { bytes -> bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = read(fd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                assertTrue(count > 0)
                offset += count
            }
        } }
    }
    private fun generation(vault: VaultRepository) = (vault.state.value as VaultState.Unlocked).generation
    private fun backupPayload(bytes: ByteArray, type: Int): IntRange {
        var offset = 32
        while (offset + 12 <= bytes.size) {
            val length = (offset + 4 until offset + 12).fold(0L) { value, index ->
                (value shl 8) or (bytes[index].toLong() and 255)
            }
            assertTrue(length in 0..(bytes.size - offset - 12).toLong())
            val start = offset + 12
            val end = start + length.toInt()
            if (bytes[offset].toInt() and 255 == type) return start until end
            offset = end
        }
        error("fixture has no backup record of type $type")
    }
    private companion object {
        const val NOW = 1_700_000_000_000L
        val SOURCE_PASSWORD = "selective-ios-source-fixture".encodeToByteArray()
        val ARCHIVE_PASSWORD = "selective-ios-archive-fixture".encodeToByteArray()
        val TARGET_PASSWORD = "selective-ios-target-fixture".encodeToByteArray()
    }
}
