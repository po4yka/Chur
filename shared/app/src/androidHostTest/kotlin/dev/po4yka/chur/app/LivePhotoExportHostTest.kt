package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.imports.LivePhotoBundle
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real JNI encryption and range reads, with real files behind the injected descriptor writer. */
class LivePhotoExportHostTest {
    @Test
    fun every_resource_and_the_portable_bundle_are_exported_byte_for_byte() = withVault { vault, root ->
        val original = fixture()
        val bundle = parsed(original)
        val id = import(vault, root, original)
        val directory = FileDirectory(File(root, "outputs"))
        val progress = mutableListOf<Pair<Int, Int>>()
        val restored = restoreLivePhotoResources(vault, id, directory,
            onProgress = { complete, total ->
                assertEquals(complete, directory.outputs.count { it.published && it.closed })
                progress += complete to total
            }, write = directory::write)
        assertEquals(bundle.resources.size, restored)
        assertEquals((1..bundle.resources.size + 1).map { it to bundle.resources.size + 1 }, progress)
        assertEquals(bundle.resources.size + 1, directory.outputs.size)
        bundle.resources.forEachIndexed { index, resource ->
            val output = directory.outputs[index]
            assertEquals("${(index + 1).toString().padStart(3, '0')}-" +
                safeExportName(resource.originalFilename, "resource"), output.file.name)
            assertEquals(resource.contentType, output.contentType)
            assertContentEquals(original.copyOfRange(resource.offset.toInt(),
                (resource.offset + resource.byteLength).toInt()), output.file.readBytes())
        }
        assertContentEquals(original, directory.outputs.last().file.readBytes())
        assertEquals("LivePhoto.churlive", directory.outputs.last().file.name)
        assertTrue(directory.outputs.all { it.published && it.closed })
    }

    @Test
    fun a_bad_resource_digest_in_an_opaque_original_creates_no_output() = withVault { vault, root ->
        val damaged = fixture().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        // Explicit file import preserves arbitrary bytes; restoration must independently reject hashes.
        val id = import(vault, root, damaged, opaque = true)
        val directory = FileDirectory(File(root, "outputs"))
        val failure = assertFailsWith<ChurFailure> {
            restoreLivePhotoResources(vault, id, directory, write = directory::write)
        }
        assertEquals(ChurStatus.INVALID_INPUT, failure.status)
        assertTrue(directory.outputs.isEmpty())
        assertEquals(emptyList(), directory.root.listFiles()!!.toList())
    }

    @Test
    fun cancellation_after_the_first_published_resource_removes_it_and_keeps_unrelated_files() = withVault { vault, root ->
        val id = import(vault, root, fixture())
        val directory = FileDirectory(File(root, "outputs"))
        val unrelated = File(directory.root, "existing.txt").apply { writeText("keep") }
        val cancelled = AtomicBoolean(false)
        val failure = assertFailsWith<ChurFailure> {
            restoreLivePhotoResources(vault, id, directory, cancelled = cancelled::get,
                onProgress = { completed, _ ->
                    assertEquals(1, completed)
                    assertTrue(directory.outputs.single().published)
                    assertTrue(directory.outputs.single().closed)
                    assertTrue(directory.outputs.single().file.isFile)
                    cancelled.set(true)
                }, write = directory::write)
        }
        assertEquals(ChurStatus.CANCELLED, failure.status)
        assertEquals(1, directory.outputs.size)
        assertEquals(listOf(unrelated), directory.root.listFiles()!!.toList())
        assertEquals("keep", unrelated.readText())
        assertTrue(directory.outputs.all { it.closed && !it.file.exists() })
    }

    @Test
    fun a_lock_during_a_resource_write_removes_all_outputs() = withVault { vault, root ->
        val id = import(vault, root, fixture())
        val directory = FileDirectory(File(root, "outputs"))
        var writes = 0
        val failure = assertFailsWith<ChurFailure> {
            restoreLivePhotoResources(vault, id, directory, write = { fd, bytes ->
                directory.write(fd, bytes)
                writes += 1
                runBlocking { vault.lock(LockReason.USER) }
            })
        }
        assertEquals(ChurStatus.SESSION_EXPIRED, failure.status)
        assertEquals(1, writes)
        assertEquals(1, directory.outputs.size)
        assertTrue(directory.outputs.all { it.closed && !it.file.exists() })
        assertTrue(directory.root.listFiles()!!.isEmpty())
    }

    private fun fixture(): ByteArray = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .take(10).map { File(it, "tests/media/live-photo-native/native.churlive") }
        .first { it.isFile }.readBytes()

    private fun parsed(bytes: ByteArray): LivePhotoBundle =
        LivePhotoBundle.parse(bytes.copyOf(LivePhotoBundle.headerLength(bytes)), bytes.size.toLong())

    private suspend fun import(vault: VaultRepository, root: File, bytes: ByteArray, opaque: Boolean = false): ByteArray {
        val source = File(root, "source.churlive").apply { writeBytes(bytes) }
        return RandomAccessFile(source, "r").use { input ->
            val operation = vault.beginImport(descriptor(input), ImportRequest(
                contentType = LivePhotoBundle.CONTENT_TYPE,
                seekable = true,
                mediaClass = if (opaque) 4 else 1,
                width = if (opaque) 0 else 64,
                height = if (opaque) 0 else 48,
                knownLength = bytes.size.toLong(),
                originalFilename = "source.churlive",
            ))
            try {
                val result = withTimeout(10_000) {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
                    progress
                }
                assertEquals(0, result.status)
                assertNotNull(result.importedObjectId())
            } finally { vault.closeOperation(operation) }
        }
    }

    private fun withVault(test: suspend (VaultRepository, File) -> Unit): Unit = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-live-export-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").absolutePath, { 1_700_000_000_000L })
        try {
            vault.start()
            vault.create("Live Photo resource export password".encodeToByteArray(), offerRecovery = false)
            test(vault, root)
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    private class FileDirectory(val root: File) : ExportDirectory {
        val outputs = mutableListOf<FileDestination>()
        init { check(root.mkdirs()) }
        override fun createDirectory(displayName: String): ExportDirectory? = null
        override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination {
            check(displayName == File(displayName).name)
            val file = File(root, ".partial-${outputs.size}")
            check(file.createNewFile())
            return FileDestination(file, displayName, contentType, plaintextSize).also(outputs::add)
        }
        fun write(fd: Int, bytes: ByteArray) = outputs.single { !it.closed && it.descriptor == fd }.handle.write(bytes)
        override fun close() { outputs.forEach { it.close() } }
    }

    private class FileDestination(
        var file: File,
        private val displayName: String,
        val contentType: String,
        private val expectedLength: Long,
    ) : ExportSink.Destination {
        val handle = RandomAccessFile(file, "rw")
        override val descriptor = descriptor(handle)
        var published = false
        var closed = false
        override fun publish() { check(publishChecked()) }
        override fun publishChecked(): Boolean {
            check(!closed)
            check(handle.length() == expectedLength)
            handle.fd.sync()
            val target = File(file.parentFile, displayName)
            check(!target.exists())
            if (!file.renameTo(target)) return false
            file = target
            published = true
            return true
        }
        override fun discard() { check(!file.exists() || file.delete()); published = false }
        override fun close() {
            if (!closed) { handle.close(); closed = true }
            if (!published) check(!file.exists() || file.delete())
        }
    }

    private companion object {
        fun descriptor(file: RandomAccessFile): Int = file.fd.javaClass.getDeclaredField("fd")
            .apply { isAccessible = true }.getInt(file.fd)
    }
}
