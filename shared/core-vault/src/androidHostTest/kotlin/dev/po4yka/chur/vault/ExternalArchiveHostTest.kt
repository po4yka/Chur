package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ExternalArchiveFile
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.OperationProgress
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual JNI, archive writer, authenticated restore, and catalog. Public fixtures only. */
class ExternalArchiveHostTest {
    private val roots = mutableListOf<File>()
    private val repositories = mutableListOf<VaultRepository>()

    @AfterTest
    fun cleanUp(): Unit = runBlocking {
        repositories.forEach { runCatching { it.shutdown() } }
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun no_vault_external_archive_restores_and_imports_with_existing_archive_paths(): Unit = runBlocking {
        val source = repository().also { it.start() }
        val text = "External document fixture 🔐\n".encodeToByteArray()
        val binary = ByteArray(70_003) { (it % 251).toByte() }
        val first = file("input.txt").apply { writeBytes(text) }
        val second = file("input.bin").apply { writeBytes(binary) }
        val archive = file("external.chur")
        FileInputStream(first).use { a -> FileInputStream(second).use { b -> FileOutputStream(archive).use { output ->
            finish(source, source.beginExternalArchive(listOf(
                ExternalArchiveFile(descriptor(a.fd), "document.txt", "text/plain", text.size.toLong()),
                ExternalArchiveFile(descriptor(b.fd), "document.bin", "application/octet-stream", null),
            ), ARCHIVE_PASSWORD, descriptor(output.fd)))
        } } }
        assertTrue(source.state.value is VaultState.NoVault)
        assertContentEquals(text, first.readBytes()); assertContentEquals(binary, second.readBytes())
        val encoded = archive.readBytes()
        assertContentEquals("CHURBAK1".encodeToByteArray(), encoded.copyOfRange(0, 8))
        assertFalse(contains(encoded, text))
        assertFalse(contains(encoded, "document.txt".encodeToByteArray()))

        val restored = repository().also { it.start() }
        FileInputStream(archive).use { finish(restored, restored.beginRestore(descriptor(it.fd), ARCHIVE_PASSWORD)) }
        restored.unlock(ARCHIVE_PASSWORD)
        val restoredObjects = restored.page(ObjectQuery()).objects
        assertEquals(2, restoredObjects.size)
        for ((name, expected) in listOf("document.txt" to text, "document.bin" to binary)) {
            val item = restoredObjects.single { restored.detail(it.objectId).filename == name }
            assertContentEquals(expected, restored.readRange(item.objectId, 0, expected.size))
        }
        assertEquals(listOf(1), restored.slots().map { it.slotType })

        val destination = repository().also { it.start(); it.create(VAULT_PASSWORD, false) }
        val identity = assertNotNull(destination.syncIdentity()).vaultId
        val generation = (destination.state.value as VaultState.Unlocked).generation
        FileInputStream(archive).use { finish(destination, destination.beginSelectiveImport(generation, descriptor(it.fd), ARCHIVE_PASSWORD)) }
        assertEquals(2, destination.page(ObjectQuery()).objects.size)
        assertContentEquals(identity, assertNotNull(destination.syncIdentity()).vaultId)
    }

    @Test
    fun locked_primary_vault_remains_locked_and_has_no_external_library_entries(): Unit = runBlocking {
        val source = repository().also { it.start(); it.create(VAULT_PASSWORD, false) }
        val kept = "Existing private library fixture".encodeToByteArray()
        val existing = file("existing.bin").apply { writeBytes(kept) }
        val keptId = FileInputStream(existing).use { input ->
            val progress = terminal(source, source.beginImport(descriptor(input.fd), ImportRequest(
                "application/octet-stream", true, 4, knownLength = kept.size.toLong(), originalFilename = "existing.bin")))
            assertEquals(0, progress.status)
            assertNotNull(progress.importedObjectId())
        }
        val identity = assertNotNull(source.syncIdentity()).vaultId
        val beforeGeneration = source.page(ObjectQuery()).catalogGeneration
        source.lock(LockReason.USER)
        val beforeState = source.state.value
        val external = file("external.bin").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val archive = file("locked.chur")
        FileInputStream(external).use { input -> FileOutputStream(archive).use { output ->
            finish(source, source.beginExternalArchive(listOf(ExternalArchiveFile(descriptor(input.fd),
                "external.bin", "application/octet-stream", external.length())), ARCHIVE_PASSWORD, descriptor(output.fd)))
        } }
        assertEquals(beforeState, source.state.value)
        source.unlock(VAULT_PASSWORD)
        assertContentEquals(identity, assertNotNull(source.syncIdentity()).vaultId)
        val after = source.page(ObjectQuery())
        assertEquals(beforeGeneration, after.catalogGeneration)
        assertEquals(1, after.objects.size)
        assertContentEquals(keptId, after.objects.single().objectId)
        assertContentEquals(kept, source.readRange(keptId, 0, kept.size))
    }

    @Test
    fun output_bound_failure_leaves_no_installable_archive_or_vault(): Unit = runBlocking {
        val source = repository().also { it.start() }
        val inputFile = file("source.bin").apply { writeBytes(ByteArray(128) { 4 }) }
        val outputFile = file("too-small.chur")
        FileInputStream(inputFile).use { input -> FileOutputStream(outputFile).use { output ->
            val progress = terminal(source, source.beginExternalArchive(listOf(ExternalArchiveFile(descriptor(input.fd),
                "source.bin", "application/octet-stream", 128)), ARCHIVE_PASSWORD, descriptor(output.fd), 1))
            assertEquals(ChurStatus.RESOURCE_LIMIT_EXCEEDED.value, progress.status)
        } }
        assertTrue(source.state.value is VaultState.NoVault)
        assertEquals(0L, outputFile.length())
        assertContentEquals(ByteArray(128) { 4 }, inputFile.readBytes())
    }

    private suspend fun finish(vault: VaultRepository, operation: Long) { assertEquals(0, terminal(vault, operation).status) }
    private suspend fun terminal(vault: VaultRepository, operation: Long): OperationProgress = try {
        withTimeout(60_000) {
            var progress = vault.poll(operation)
            while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
            progress
        }
    } finally { vault.closeOperation(operation) }
    private fun repository(): VaultRepository {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-external-host-${System.nanoTime()}").apply { mkdirs(); roots.add(this) }
        return VaultRepository(File(root, "vault").path, { 1_700_000_000_000L }).also { repositories.add(it) }
    }
    private fun file(name: String) = File(roots.first(), "$name-${System.nanoTime()}")
    private fun descriptor(fd: FileDescriptor): Int = fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(fd)
    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && (0..bytes.size - needle.size).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } }
    private companion object {
        val ARCHIVE_PASSWORD = "external-archive-fixture-password".encodeToByteArray()
        val VAULT_PASSWORD = "external-existing-vault-password".encodeToByteArray()
    }
}
