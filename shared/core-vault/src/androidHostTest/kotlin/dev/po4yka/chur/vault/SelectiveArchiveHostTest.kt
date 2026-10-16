package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.OperationProgress
import dev.po4yka.chur.ffi.PrivateNoteAction
import dev.po4yka.chur.ffi.PrivateNoteSelection
import dev.po4yka.chur.ffi.QueryScope
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual JNI and SQLCipher. All content and credentials are public test fixtures. */
class SelectiveArchiveHostTest {
    private val roots = mutableListOf<File>()
    private val repositories = mutableListOf<VaultRepository>()
    private var now = 1_700_000_000_000L

    @AfterTest
    fun cleanUp(): Unit = runBlocking {
        repositories.forEach { runCatching { it.shutdown() } }
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun selected_subtree_and_note_restore_with_new_credentials_and_import_without_overwriting(): Unit = runBlocking {
        val source = repository()
        source.start()
        val recovery = assertNotNull(source.create(SOURCE_PASSWORD, true))
        assertTrue(source.confirmRecoveryPhrase())
        val sourceIdentity = assertNotNull(source.syncIdentity())
        val document = "Selected UTF-8 document 🔐\n".encodeToByteArray()
        val binary = ByteArray(70_001) { (it % 251).toByte() }
        val picture = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1sAAAAASUVORK5CYII=")
        val docId = importObject(source, document, "selected.txt", "text/plain")
        val binaryId = importObject(source, binary, "selected.bin", "application/octet-stream")
        val imageId = importObject(source, picture, "selected.png", "image/png", mediaClass = 1)
        val hiddenId = importObject(source, "Unselected object fixture".encodeToByteArray(), "UNSELECTED.bin", "application/octet-stream")
        val parent = source.createAlbum("Selected parent")
        val child = source.createAlbum("Selected child")
        val empty = source.createAlbum("Selected empty child")
        source.moveAlbum(child, parent, null)
        source.moveAlbum(empty, child, null)
        source.setAlbumMembership(parent, docId, true)
        source.setAlbumMembership(child, binaryId, true)
        source.setAlbumMembership(child, imageId, true)
        val hiddenAlbum = source.createAlbum("UNSELECTED album")
        source.setAlbumMembership(hiddenAlbum, hiddenId, true)
        // Membership in an unselected album must not pull its name into the archive.
        source.setAlbumMembership(hiddenAlbum, docId, true)
        val hiddenTag = source.createTag("UNSELECTED tag")
        source.setObjectTag(hiddenTag, hiddenId, true)
        val note = source.savePrivateNote(generation(source), title = MAX_TITLE, body = MAX_BODY, pinned = true)
        source.savePrivateNote(generation(source), title = "UNSELECTED note", body = "UNSELECTED note body")
        val before = snapshot(source)
        val archive = file("selected.chur")
        FileOutputStream(archive).use { output ->
            finish(source, source.beginSelectiveExport(
                generation(source), listOf(docId, docId), listOf(parent, child, parent),
                listOf(PrivateNoteSelection(note.noteId, note.revision), PrivateNoteSelection(note.noteId, note.revision)),
                ARCHIVE_PASSWORD, descriptor(output.fd),
            ))
        }
        assertEquals(before, snapshot(source), "export must leave source catalog and content unchanged")
        val bytes = archive.readBytes()
        assertContentEquals("CHURBAK1".encodeToByteArray(), bytes.copyOfRange(0, 8))
        assertFalse(contains(bytes, document))
        assertFalse(contains(bytes, "UNSELECTED note body".encodeToByteArray()))

        val restored = repository()
        restored.start()
        for (credential in listOf(SOURCE_PASSWORD, recovery.encodeToByteArray())) {
            FileInputStream(archive).use { input ->
                assertNotEquals(0, terminal(restored, restored.beginRestore(descriptor(input.fd), credential)).status)
            }
            assertTrue(restored.state.value is VaultState.NoVault)
        }
        FileInputStream(archive).use { input -> finish(restored, restored.beginRestore(descriptor(input.fd), ARCHIVE_PASSWORD)) }
        restored.unlock(ARCHIVE_PASSWORD)
        assertEquals(listOf(1), restored.slots().map { it.slotType }, "source recovery/device slots must not travel")
        assertEquals(ChurStatus.INVALID_INPUT, assertFailsWith<ChurFailure> { restored.syncHeads() }.status,
            "the independent archive must have no provisioned source sync membership")
        assertSelected(restored, document, binary, picture)
        assertTrue(restored.page(ObjectQuery()).objects.none { item ->
            listOf(docId, binaryId, imageId, hiddenId).any { it.contentEquals(item.objectId) }
        })
        assertFalse(restored.privateNotes(generation(restored)).notes.single().noteId.contentEquals(note.noteId))
        val restoredIdentity = assertNotNull(restored.syncIdentity())
        assertFalse(restoredIdentity.vaultId.contentEquals(sourceIdentity.vaultId))
        assertFalse(restoredIdentity.deviceId.contentEquals(sourceIdentity.deviceId))
        restored.lock(LockReason.USER)
        assertEquals(ChurStatus.AUTHENTICATION_FAILED, assertFailsWith<ChurFailure> { restored.unlock(SOURCE_PASSWORD) }.status)
        restored.unlock(ARCHIVE_PASSWORD)
        source.lock(LockReason.USER)
        assertEquals(ChurStatus.AUTHENTICATION_FAILED, assertFailsWith<ChurFailure> { source.unlock(ARCHIVE_PASSWORD) }.status)
        source.unlock(SOURCE_PASSWORD)

        val target = repository()
        target.start(); target.create(TARGET_PASSWORD, false)
        val kept = importObject(target, "Existing target object".encodeToByteArray(), "selected.txt", "text/plain")
        val keptNote = target.savePrivateNote(generation(target), title = MAX_TITLE, body = "Existing target note")
        val keptAlbum = target.createAlbum("Selected parent")
        val targetIdentity = assertNotNull(target.syncIdentity())
        FileInputStream(archive).use { input -> finish(target, target.beginSelectiveImport(generation(target), descriptor(input.fd), ARCHIVE_PASSWORD)) }
        assertEquals(4, target.page(ObjectQuery()).objects.size)
        assertContentEquals("Existing target object".encodeToByteArray(), target.readRange(kept, 0, 22))
        assertEquals("Existing target note", target.privateNote(generation(target), keptNote.noteId).body)
        assertEquals(2, target.privateNotes(generation(target)).notes.size)
        assertEquals(2, target.albums().count { it.name == "Selected parent" })
        assertTrue(target.albums().any { it.albumId.contentEquals(keptAlbum) })
        assertContentEquals(targetIdentity.vaultId, assertNotNull(target.syncIdentity()).vaultId)
        assertEquals(1, target.slots().size)
        assertTrue(target.page(ObjectQuery()).objects.none { imported ->
            restored.page(ObjectQuery()).objects.any { it.objectId.contentEquals(imported.objectId) }
        })
    }

    @Test
    fun missing_stale_and_trashed_selections_fail_without_source_mutation(): Unit = runBlocking {
        val source = created()
        val id = importObject(source, byteArrayOf(1, 2, 3), "kept.bin", "application/octet-stream")
        val trashedId = importObject(source, byteArrayOf(4, 5), "trashed.bin", "application/octet-stream")
        source.delete(trashedId)
        val note = source.savePrivateNote(generation(source), title = "Current", body = "Current body")
        val old = source.savePrivateNote(generation(source), title = "Trashed", body = "Trashed body")
        val trash = assertNotNull(source.changePrivateNote(generation(source), old.noteId, old.revision, PrivateNoteAction.TRASH))
        val missing = ByteArray(16) { 0x55.toByte() }
        val before = snapshot(source)
        val cases = listOf(
            Selection(objects = listOf(missing)),
            Selection(albums = listOf(missing)),
            Selection(notes = listOf(PrivateNoteSelection(missing, 1))),
            Selection(notes = listOf(PrivateNoteSelection(note.noteId, note.revision + 1))),
            Selection(objects = listOf(trashedId)),
            Selection(notes = listOf(PrivateNoteSelection(trash.noteId, trash.revision))),
        )
        for ((index, selection) in cases.withIndex()) {
            val output = file("refused-$index.chur")
            FileOutputStream(output).use { fd ->
                val failure = failure(source) { source.beginSelectiveExport(generation(source), selection.objects,
                    selection.albums, selection.notes, ARCHIVE_PASSWORD, descriptor(fd.fd)) }
                assertNotEquals(0, failure)
            }
            assertEquals(0L, output.length(), "selection refusal must precede archive output")
            assertEquals(before, snapshot(source))
        }
        source.lock(LockReason.USER); source.unlock(SOURCE_PASSWORD)
        assertContentEquals(byteArrayOf(1, 2, 3), source.readRange(id, 0, 3))
    }

    @Test
    fun wrong_password_corruption_and_truncation_are_authenticated_before_target_mutation(): Unit = runBlocking {
        val source = created()
        val note = source.savePrivateNote(generation(source), title = "Selected", body = "Imported note fixture")
        val objectId = importObject(source, ByteArray(100_003) { (it % 193).toByte() }, "selected.bin", "application/octet-stream")
        val archive = file("complete.chur")
        FileOutputStream(archive).use { output -> finish(source, source.beginSelectiveExport(generation(source), listOf(objectId), emptyList(),
            listOf(PrivateNoteSelection(note.noteId, note.revision)), ARCHIVE_PASSWORD, descriptor(output.fd))) }
        val bytes = archive.readBytes()
        val manifest = backupPayload(bytes, 1)
        val container = backupPayload(bytes, 4)
        // Container payload starts with the 109-byte StreamInventoryEntryV1.
        val objectStart = container.first + 109
        val manifestLength = unsigned(bytes, objectStart + 20, 4).toInt()
        // ChurObjectV1: 28-byte preamble, manifest, 20-byte chunk header, ciphertext.
        val chunkByte = objectStart + 28 + manifestLength + 20 + 16
        assertTrue(chunkByte in container)
        val corruptManifest = file("corrupt-manifest.chur").apply { writeBytes(flipped(bytes, manifest.first + 24 + 16)) }
        val corruptChunk = file("corrupt-chunk.chur").apply { writeBytes(flipped(bytes, chunkByte)) }
        val catalog = backupPayload(bytes, 3)
        val middle = bytes.size / 2
        val corruptMiddle = file("corrupt-middle.chur").apply { writeBytes(flipped(bytes, middle)) }
        val truncated = file("truncated.chur").apply { writeBytes(bytes.copyOf(bytes.size - 17)) }
        val target = created(TARGET_PASSWORD)
        importObject(target, "Existing fixture".encodeToByteArray(), "selected.bin", "application/octet-stream")
        target.savePrivateNote(generation(target), title = "Selected", body = "Existing fixture note")
        val before = snapshot(target)
        val cases = listOf(
            Triple("wrong password", archive, SOURCE_PASSWORD),
            Triple("encrypted manifest", corruptManifest, ARCHIVE_PASSWORD),
            Triple("original chunk ciphertext", corruptChunk, ARCHIVE_PASSWORD),
            Triple("truncated final commit", truncated, ARCHIVE_PASSWORD),
            Triple("middle bitflip at $middle, catalog=${catalog.first}..${catalog.last}, relative=${middle - catalog.first}",
                corruptMiddle, ARCHIVE_PASSWORD),
        )
        for ((label, inputFile, password) in cases) {
            FileInputStream(inputFile).use { input ->
                assertNotEquals(0, failure(target) { target.beginSelectiveImport(generation(target), descriptor(input.fd), password) }, label)
            }
            assertEquals(before, snapshot(target), "$label: entire package must authenticate before target catalog changes")
        }
    }

    @Test
    fun stale_session_generations_cannot_start_export_or_import_after_reunlock(): Unit = runBlocking {
        val source = created()
        val oldGeneration = generation(source)
        val note = source.savePrivateNote(oldGeneration, title = "Selected fixture", body = "Selected body")
        val archive = file("generation.chur")
        FileOutputStream(archive).use { output -> finish(source, source.beginSelectiveExport(oldGeneration, emptyList(), emptyList(),
            listOf(PrivateNoteSelection(note.noteId, note.revision)), ARCHIVE_PASSWORD, descriptor(output.fd))) }
        source.lock(LockReason.PANIC); source.unlock(SOURCE_PASSWORD)
        val before = snapshot(source)
        val refused = file("stale-generation.chur")
        FileOutputStream(refused).use { output ->
            assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> {
                source.beginSelectiveExport(oldGeneration, emptyList(), emptyList(),
                    listOf(PrivateNoteSelection(note.noteId, note.revision)), ARCHIVE_PASSWORD, descriptor(output.fd))
            }.status)
        }
        FileInputStream(archive).use { input ->
            assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> {
                source.beginSelectiveImport(oldGeneration, descriptor(input.fd), ARCHIVE_PASSWORD)
            }.status)
        }
        assertEquals(0L, refused.length())
        assertEquals(before, snapshot(source))
    }

    private data class Selection(val objects: List<ByteArray> = emptyList(), val albums: List<ByteArray> = emptyList(),
        val notes: List<PrivateNoteSelection> = emptyList())

    private suspend fun assertSelected(vault: VaultRepository, document: ByteArray, binary: ByteArray, picture: ByteArray) {
        val objects = vault.page(ObjectQuery()).objects
        assertEquals(3, objects.size, "explicit selection and descendant overlap must be deduplicated")
        val expected = mapOf("selected.txt" to document, "selected.bin" to binary, "selected.png" to picture)
        for (item in objects) {
            val detail = vault.detail(item.objectId)
            assertContentEquals(assertNotNull(expected[detail.filename]), vault.readRange(item.objectId, 0, detail.plaintextSize.toInt()))
            assertTrue(detail.tags.isEmpty())
        }
        assertTrue(vault.tags().isEmpty())
        val albums = vault.albums()
        assertEquals(setOf("Selected parent", "Selected child", "Selected empty child"), albums.map { it.name }.toSet())
        val parent = albums.single { it.name == "Selected parent" }
        val child = albums.single { it.name == "Selected child" }
        val empty = albums.single { it.name == "Selected empty child" }
        assertEquals(null, parent.parentId)
        assertContentEquals(parent.albumId, child.parentId)
        assertContentEquals(child.albumId, empty.parentId)
        assertEquals(1, vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = parent.albumId)).objects.size)
        assertEquals(2, vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = child.albumId)).objects.size)
        assertEquals(0, vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = empty.albumId)).objects.size)
        val summary = vault.privateNotes(generation(vault)).notes.single()
        val note = vault.privateNote(generation(vault), summary.noteId)
        assertEquals(MAX_TITLE, note.title)
        assertEquals(MAX_BODY, note.body)
        assertTrue(note.pinned)
        assertEquals(262_402L, note.exportSize)
    }

    private suspend fun snapshot(vault: VaultRepository): List<Any?> {
        val objects = vault.page(ObjectQuery())
        return listOf(objects.catalogGeneration, objects.objects.map { it.id }.sorted(),
            objects.objects.sortedBy { it.id }.map { item ->
                val d = vault.detail(item.objectId)
                listOf(item.id, d.filename, d.contentType, d.captureTimeMs, d.importTimeMs, d.plaintextSize,
                    d.caption, d.tags.map { it.first.toList() to it.second })
            },
            vault.albums(), vault.tags(), vault.privateNotes(generation(vault)).notes.map {
                val n = vault.privateNote(generation(vault), it.noteId)
                listOf(n.id, n.revision, n.title, n.body, n.pinned, n.trashedAtMs)
            }, vault.privateNotes(generation(vault), trashed = true).notes.map { listOf(it.id, it.revision) }, vault.slots())
    }

    private suspend fun importObject(vault: VaultRepository, bytes: ByteArray, name: String, type: String, mediaClass: Int = 4): ByteArray {
        val source = file("input").apply { writeBytes(bytes) }
        return FileInputStream(source).use { input ->
            val progress = terminal(vault, vault.beginImport(descriptor(input.fd), ImportRequest(type, true, mediaClass,
                width = if (mediaClass == 1) 1 else 0, height = if (mediaClass == 1) 1 else 0,
                knownLength = bytes.size.toLong(), originalFilename = name)))
            assertEquals(0, progress.status)
            assertNotNull(progress.importedObjectId())
        }
    }
    private suspend fun failure(vault: VaultRepository, begin: suspend () -> Long): Int = try {
        terminal(vault, begin()).status
    } catch (error: ChurFailure) { error.status.value }
    private suspend fun finish(vault: VaultRepository, operation: Long) { assertEquals(0, terminal(vault, operation).status) }
    private suspend fun terminal(vault: VaultRepository, operation: Long): OperationProgress = try {
        withTimeout(60_000) {
            var progress = vault.poll(operation)
            while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
            progress
        }
    } finally { vault.closeOperation(operation) }
    private fun repository(): VaultRepository {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-selective-host-${System.nanoTime()}").apply { mkdirs(); roots.add(this) }
        return VaultRepository(File(root, "vault").path, { now }).also { repositories.add(it) }
    }
    private suspend fun created(password: ByteArray = SOURCE_PASSWORD): VaultRepository = repository().also { it.start(); it.create(password, false) }
    private fun file(name: String): File = File(roots.firstOrNull() ?: error("a repository must exist"), "$name-${System.nanoTime()}")
    private fun generation(vault: VaultRepository) = (vault.state.value as VaultState.Unlocked).generation
    private fun descriptor(fd: FileDescriptor): Int = fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(fd)
    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && (0..bytes.size - needle.size).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } }
    private fun flipped(bytes: ByteArray, offset: Int): ByteArray = bytes.copyOf().apply {
        assertTrue(offset in indices)
        this[offset] = (this[offset].toInt() xor 1).toByte()
    }
    private fun unsigned(bytes: ByteArray, offset: Int, count: Int): Long {
        assertTrue(offset >= 0 && offset + count <= bytes.size)
        return (offset until offset + count).fold(0L) { value, index -> (value shl 8) or (bytes[index].toLong() and 255) }
    }
    private fun backupPayload(bytes: ByteArray, type: Int): IntRange {
        var offset = 32
        while (offset + 12 <= bytes.size) {
            val length = unsigned(bytes, offset + 4, 8)
            assertTrue(length in 0..(bytes.size - offset - 12).toLong())
            val start = offset + 12
            val end = start + length.toInt()
            if (bytes[offset].toInt() and 255 == type) return start until end
            offset = end
        }
        error("fixture has no backup record of type $type")
    }
    private companion object {
        val SOURCE_PASSWORD = "selective-source-fixture-password".encodeToByteArray()
        val ARCHIVE_PASSWORD = "selective-archive-fixture-password".encodeToByteArray()
        val TARGET_PASSWORD = "selective-target-fixture-password".encodeToByteArray()
        val MAX_TITLE = "é".repeat(128)
        val MAX_BODY = "😀".repeat(65_536)
    }
}
