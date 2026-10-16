package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ContentIndexCandidate
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual JNI and SQLCipher, with public test content only. */
class ContentIndexRepositoryHostTest {
    private val roots = mutableListOf<File>()
    private val repositories = mutableListOf<VaultRepository>()

    @AfterTest
    fun cleanUp(): Unit = runBlocking {
        repositories.forEach { runCatching { it.shutdown() } }
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun keyset_pages_survive_index_writes_and_encrypted_content_search_survives_reopen(): Unit = runBlocking {
        val root = root()
        var vault = repository(root)
        vault.start(); vault.create(PASSWORD, false)
        repeat(3) { importObject(vault, "fixture-$it".encodeToByteArray(), "file-$it.txt") }
        val generation = generation(vault)
        val initial = vault.contentIndexCandidates(generation, rebuild = true, limit = 100)
        assertEquals(3, initial.size)
        val seen = mutableListOf<ContentIndexCandidate>()
        var after: ByteArray? = null
        while (true) {
            val page = vault.contentIndexCandidates(generation, after, limit = 1)
            if (page.isEmpty()) break
            val candidate = page.single()
            seen.add(candidate)
            vault.putContentIndex(generation, candidate, CANARY.encodeToByteArray(), partial = false)
            after = candidate.objectId
        }
        assertEquals(initial.map { it.objectId.toList() }, seen.map { it.objectId.toList() })
        assertTrue(vault.contentIndexCandidates(generation).isEmpty())
        assertEquals(3, search(vault, "секретный").size)
        assertEquals(3, search(vault, "searchcanary").size)
        vault.shutdown()
        assertNoPlaintext(File(root, "vault"), CANARY)
        vault = repository(root)
        vault.start(); vault.unlock(PASSWORD)
        val reopened = generation(vault)
        assertEquals(3, search(vault, "секретный").size)
        assertTrue(vault.contentIndexCandidates(reopened).isEmpty())
        assertEquals(3, vault.contentIndexCandidates(reopened, rebuild = true).size)
        vault.delete(seen.first().objectId)
        assertEquals(2, search(vault, "searchcanary").size)
        vault.restoreAll(listOf(seen.first().objectId))
        assertEquals(3, search(vault, "searchcanary").size)
        vault.clearContentIndex(reopened)
        assertTrue(search(vault, "searchcanary").isEmpty())
        assertEquals(3, vault.contentIndexCandidates(reopened).size)
    }

    @Test
    fun stale_unlock_generation_refuses_every_index_operation_and_reader_after_lock_or_identity_change(): Unit = runBlocking {
        val vault = repository(root())
        vault.start(); vault.create(PASSWORD, false)
        val bytes = "public OCR source fixture".encodeToByteArray()
        importObject(vault, bytes, "reader.txt")
        val oldGeneration = generation(vault)
        val candidate = vault.contentIndexCandidates(oldGeneration).single()
        val reader = vault.leaseContentIndexReader(oldGeneration, candidate.objectId)
        assertContentEquals(bytes, vault.readLeased(reader, 0, bytes.size))
        vault.lock(LockReason.PANIC)
        assertFailsWith<ChurFailure> { vault.readLeased(reader, 0, 1) }
        vault.releaseReader(reader)
        assertStale(vault, oldGeneration, candidate)
        vault.unlock(PASSWORD)
        assertStale(vault, oldGeneration, candidate)
        val currentGeneration = generation(vault)
        vault.putContentIndex(currentGeneration, candidate, CANARY.encodeToByteArray(), partial = true)
        assertEquals(1, search(vault, "searchcanary").size)
        vault.create(OTHER_PASSWORD, false)
        assertStale(vault, currentGeneration, candidate)
        assertTrue(vault.contentIndexCandidates(generation(vault)).isEmpty())
        assertFailsWith<ChurFailure> { vault.putContentIndex(generation(vault), candidate, CANARY.encodeToByteArray(), false) }
        assertTrue(search(vault, "searchcanary").isEmpty())
    }

    @Test
    fun stale_stream_invalid_utf8_and_oversized_text_preserve_committed_index(): Unit = runBlocking {
        val vault = repository(root())
        vault.start(); vault.create(PASSWORD, false)
        importObject(vault, "public source".encodeToByteArray(), "source.txt")
        val generation = generation(vault)
        val candidate = vault.contentIndexCandidates(generation).single()
        vault.putContentIndex(generation, candidate, CANARY.encodeToByteArray(), false)
        val stale = candidate.copy(primaryStreamId = ByteArray(16) { 77 })
        assertEquals(ChurStatus.CONFLICT, assertFailsWith<ChurFailure> {
            vault.putContentIndex(generation, stale, "replacement".encodeToByteArray(), false)
        }.status)
        assertEquals(ChurStatus.RESOURCE_LIMIT_EXCEEDED, assertFailsWith<ChurFailure> {
            vault.putContentIndex(generation, candidate, ByteArray(262_145), false)
        }.status)
        assertEquals(ChurStatus.INVALID_INPUT, assertFailsWith<ChurFailure> {
            vault.putContentIndex(generation, candidate, byteArrayOf(0xff.toByte()), false)
        }.status)
        assertEquals(ChurStatus.INVALID_INPUT, assertFailsWith<ChurFailure> {
            vault.putContentIndex(generation, candidate, byteArrayOf(65, 0, 66), false)
        }.status)
        assertEquals(1, search(vault, "searchcanary").size)
        val boundary = "😀".repeat(65_536).encodeToByteArray()
        assertEquals(262_144, boundary.size)
        vault.putContentIndex(generation, candidate, boundary, partial = true)
        boundary.fill(0)
        assertTrue(vault.contentIndexCandidates(generation).isEmpty())
        assertTrue(search(vault, "searchcanary").isEmpty())
        vault.putContentIndex(generation, candidate, byteArrayOf(), partial = false)
        assertTrue(vault.contentIndexCandidates(generation).isEmpty(), "an empty successful result must not be retried on every run")
        vault.putContentIndex(generation, candidate, CANARY.encodeToByteArray(), partial = false)
        vault.delete(candidate.objectId)
        assertFailsWith<ChurFailure> { vault.putContentIndex(generation, candidate, CANARY.encodeToByteArray(), false) }
        assertTrue(vault.contentIndexCandidates(generation, rebuild = true).isEmpty())
        vault.permanentlyDelete(candidate.objectId)
        assertTrue(search(vault, "searchcanary").isEmpty())
    }

    private suspend fun assertStale(vault: VaultRepository, generation: Long, candidate: ContentIndexCandidate) {
        assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> { vault.contentIndexCandidates(generation) }.status)
        assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> { vault.putContentIndex(generation, candidate, CANARY.encodeToByteArray(), false) }.status)
        assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> { vault.clearContentIndex(generation) }.status)
        assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> { vault.leaseContentIndexReader(generation, candidate.objectId) }.status)
    }

    private suspend fun search(vault: VaultRepository, terms: String) = vault.page(ObjectQuery(scope = QueryScope.SEARCH, terms = terms)).objects
    private suspend fun importObject(vault: VaultRepository, bytes: ByteArray, name: String): ByteArray {
        val source = File(roots.last(), "input-${System.nanoTime()}").apply { writeBytes(bytes) }
        try {
            return FileInputStream(source).use { input ->
                val operation = vault.beginImport(descriptor(input.fd), ImportRequest("text/plain", true, 4, knownLength = bytes.size.toLong(), originalFilename = name))
                try {
                    withTimeout(30_000) {
                        while (true) {
                            val progress = vault.poll(operation)
                            if (progress.terminal) {
                                assertEquals(0, progress.status)
                                return@withTimeout assertNotNull(progress.importedObjectId())
                            }
                            delay(10)
                        }
                        error("unreachable")
                    }
                } finally { vault.closeOperation(operation) }
            }
        } finally { source.delete() }
    }
    private fun root() = File(System.getProperty("java.io.tmpdir"), "chur-content-index-${System.nanoTime()}").apply { mkdirs(); roots.add(this) }
    private fun repository(root: File) = VaultRepository(File(root, "vault").path, { 1_700_000_000_000L }).also { repositories.add(it) }
    private fun generation(vault: VaultRepository) = (vault.state.value as VaultState.Unlocked).generation
    private fun descriptor(fd: FileDescriptor) = fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(fd)
    private fun assertNoPlaintext(root: File, text: String) {
        val needle = text.encodeToByteArray()
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val bytes = file.readBytes()
            assertFalse((0..bytes.size - needle.size).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } }, "OCR text must stay encrypted on disk")
        }
    }
    private companion object {
        val PASSWORD = "content-index-test-password".encodeToByteArray()
        val OTHER_PASSWORD = "content-index-other-identity".encodeToByteArray()
        const val CANARY = "Searchcanary секретный индекс 3a98e52f"
    }
}
