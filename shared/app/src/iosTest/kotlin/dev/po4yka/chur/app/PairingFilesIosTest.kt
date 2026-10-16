@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSNumber
import platform.posix.O_APPEND
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.unlink
import platform.posix.write
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

class PairingFilesIosTest {
    @Test fun encryptedApprovalRoundTripsThroughPrivateScratch() {
        prepareDirectory()
        val bytes = ByteArray(226) { it.toByte() }
        val path = writePairingApproval(bytes)
        try {
            assertContentEquals(bytes, readPairingApproval(path))
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
            // Simulator does not expose NSFileProtectionKey. Test its real
            // owner-only access mode; hardware protection requires a device.
            val permissions = attributes?.get(NSFilePosixPermissions) as? NSNumber
            assertTrue(permissions?.intValue == 384, "scratch file must have mode 0600")
        } finally { unlink(path) }
    }

    @Test fun exactLimitIsAcceptedAndOneExtraByteIsRejected() {
        prepareDirectory()
        val bytes = ByteArray(16 * 1024 * 1024) { (it % 251).toByte() }
        val path = writePairingApproval(bytes)
        try {
            assertContentEquals(bytes, readPairingApproval(path))
            val fd = open(path, O_WRONLY or O_APPEND)
            check(fd >= 0)
            try { byteArrayOf(1).usePinned { check(write(fd, it.addressOf(0), 1uL) == 1L) } }
            finally { close(fd) }
            assertFailsWith<IllegalArgumentException> { readPairingApproval(path) }
        } finally { unlink(path) }
    }

    @Test fun invalidExportSizesFailBeforeCreatingAFile() {
        assertFailsWith<IllegalArgumentException> { writePairingApproval(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { writePairingApproval(ByteArray(16 * 1024 * 1024 + 1)) }
    }

    private fun prepareDirectory() {
        check(NSFileManager.defaultManager.createDirectoryAtPath(
            NSTemporaryDirectory() + "chur-pairing", true,
            mapOf(NSFileProtectionKey to NSFileProtectionComplete), null,
        ))
    }
}
