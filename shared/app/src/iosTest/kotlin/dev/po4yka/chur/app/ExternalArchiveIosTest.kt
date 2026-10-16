@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.ExternalArchiveFile
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.OperationProgress
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
import kotlin.test.assertTrue

/** Actual iOS C ABI and authenticated restore, with public source files and no source vault. */
class ExternalArchiveIosTest {
    @Test
    fun no_vault_external_files_use_the_existing_archive_and_restore_original_bytes(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "chur-external-ios-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val source = VaultRepository("$root/source", { 1_700_000_000_000L })
        val restored = VaultRepository("$root/restored", { 1_700_000_000_000L })
        val password = "public-external-ios-archive-fixture".encodeToByteArray()
        val text = "External iOS document 🔐\n".encodeToByteArray()
        val binary = ByteArray(70_003) { (it % 251).toByte() }
        try {
            source.start()
            assertTrue(source.state.value is VaultState.NoVault)
            writeFile("$root/text", text); writeFile("$root/binary", binary)
            withFile("$root/text", O_RDONLY) { first ->
                withFile("$root/binary", O_RDONLY) { second ->
                    withFile("$root/archive.churbak", O_WRONLY or O_CREAT or O_TRUNC) { output ->
                        assertEquals(0, terminal(source, source.beginExternalArchive(listOf(
                            ExternalArchiveFile(first, "document.txt", "text/plain", text.size.toLong()),
                            ExternalArchiveFile(second, "document.bin", "application/octet-stream", null),
                        ), password, output)).status)
                    }
                }
            }
            assertTrue(source.state.value is VaultState.NoVault)
            assertContentEquals(text, readFile("$root/text"))
            assertContentEquals(binary, readFile("$root/binary"))
            val encoded = readFile("$root/archive.churbak")
            assertContentEquals("CHURBAK1".encodeToByteArray(), encoded.copyOfRange(0, 8))
            restored.start()
            withFile("$root/archive.churbak", O_RDONLY) { input ->
                assertEquals(0, terminal(restored, restored.beginRestore(input, password)).status)
            }
            restored.unlock(password)
            val objects = restored.page(ObjectQuery()).objects
            assertEquals(2, objects.size)
            for ((name, expected) in listOf("document.txt" to text, "document.bin" to binary)) {
                val item = objects.single { restored.detail(it.objectId).filename == name }
                assertContentEquals(expected, restored.readRange(item.objectId, 0, expected.size))
            }
            assertTrue(restored.albums().isEmpty())
            assertEquals(listOf(1), restored.slots().map { it.slotType })
        } finally {
            password.fill(0)
            runCatching { source.shutdown() }; runCatching { restored.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private suspend fun terminal(vault: VaultRepository, operation: Long): OperationProgress = try {
        withTimeout(60_000) {
            var result = vault.poll(operation)
            while (!result.terminal) { delay(10); result = vault.poll(operation) }
            result
        }
    } finally { vault.closeOperation(operation) }
    private inline fun <T> withFile(path: String, flags: Int, block: (Int) -> T): T {
        val fd = open(path, flags, 384)
        assertTrue(fd >= 0)
        return try { block(fd) } finally { close(fd) }
    }
    private fun writeFile(path: String, bytes: ByteArray) = withFile(path, O_WRONLY or O_CREAT or O_TRUNC) { fd ->
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                assertTrue(count > 0); offset += count
            }
        }
    }
    private fun readFile(path: String): ByteArray = withFile(path, O_RDONLY) { fd ->
        val size = lseek(fd, 0, SEEK_END)
        assertTrue(size in 1..Int.MAX_VALUE.toLong())
        assertEquals(0L, lseek(fd, 0, SEEK_SET))
        ByteArray(size.toInt()).also { bytes -> bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = read(fd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                assertTrue(count > 0); offset += count
            }
        } }
    }
}
