@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.sync

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile

class SyncStateIosTest {
    @Test
    fun atomic_replacement_keeps_protection_and_backup_exclusion() {
        val directory = "${NSTemporaryDirectory()}chur-sync-${NSUUID().UUIDString}"
        val manager = NSFileManager.defaultManager
        assertTrue(manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null))
        try {
            val path = "$directory/state.json"
            assertEquals(null, readSyncStateFile(path))
            val legacy = "legacy".encodeToByteArray()
            val legacyData = legacy.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = legacy.size.toULong())
            }
            assertTrue(legacyData.writeToFile(path, atomically = true))
            assertEquals("legacy", readSyncStateFile(path))
            assertEquals(
                true,
                NSURL.fileURLWithPath(path)
                    .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
                    ?.get(NSURLIsExcludedFromBackupKey),
            )
            for (text in listOf("first", "replacement", "")) {
                writeSyncStateFile(path, text)
                assertEquals(text, readSyncStateFile(path))
                // Simulator filesystems may not report Data Protection attributes.
                manager.attributesOfItemAtPath(path, error = null)?.get(NSFileProtectionKey)?.let {
                    assertEquals(NSFileProtectionCompleteUntilFirstUserAuthentication, it.toString())
                }
                assertEquals(
                    true,
                    NSURL.fileURLWithPath(path)
                        .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
                        ?.get(NSURLIsExcludedFromBackupKey),
                )
            }
            assertEquals(
                true,
                NSURL.fileURLWithPath(directory, isDirectory = true)
                    .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
                    ?.get(NSURLIsExcludedFromBackupKey),
            )
        } finally {
            manager.removeItemAtPath(directory, error = null)
        }
    }
}
