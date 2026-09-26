@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.sync

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

internal actual fun readSyncStateFile(path: String): String? {
    protectSyncStateDirectory(path)
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    protectExistingSyncState(path)
    val data = NSData.dataWithContentsOfFile(path) ?: error("cannot read the sync state file at $path")
    val length = data.length.toInt()
    if (length == 0) return ""
    val bytes = ByteArray(length)
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return bytes.decodeToString()
}

/**
 * Writes through Foundation's protected atomic replacement.
 *
 * The protection option applies to the auxiliary file before replacement.
 */
internal actual fun writeSyncStateFile(
    path: String,
    text: String,
) {
    protectSyncStateDirectory(path)
    protectExistingSyncState(path)
    val bytes = text.encodeToByteArray()
    val data =
        if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        }
    val options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
    if (!data.writeToFile(path, options = options, error = null)) {
        error("cannot replace the sync state file at $path")
    }
    excludeSyncStateFromBackup(path)
}

/** Upgrades a pre-existing file before reading or replacing it. */
private fun protectExistingSyncState(path: String) {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(path)) return
    check(
        manager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            ofItemAtPath = path,
            error = null,
        ),
    ) { "cannot protect the sync state file at $path" }
    excludeSyncStateFromBackup(path)
}

private fun protectSyncStateDirectory(path: String) {
    val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
    check(directory.isNotEmpty()) { "the sync state path has no parent directory" }
    check(NSFileManager.defaultManager.setAttributes(
        mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
        ofItemAtPath = directory,
        error = null,
    )) { "cannot protect the sync state directory" }
    excludeSyncStateFromBackup(directory)
}

private fun excludeSyncStateFromBackup(path: String) {
    check(NSURL.fileURLWithPath(path).setResourceValue(
        true,
        forKey = NSURLIsExcludedFromBackupKey,
        error = null,
    )) { "cannot exclude the sync state file from backup" }
}

internal actual fun randomToken(): ByteArray =
    ByteArray(TOKEN_BYTES).apply {
        usePinned { pinned ->
            val status = SecRandomCopyBytes(kSecRandomDefault, TOKEN_BYTES.toULong(), pinned.addressOf(0))
            require(status == 0) { "SecRandomCopyBytes failed with $status" }
        }
    }

/** The transport token's length, `SYNC_PROTOCOL_V1.md` §6. */
private const val TOKEN_BYTES = 32
