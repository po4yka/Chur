@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read
import platform.posix.unlink
import platform.posix.write

private const val APPROVAL_LIMIT = 16 * 1024 * 1024

/** The host presents system file pickers for encrypted device approvals. */
public object IosPairingFiles {
    public var presentOpen: ((answer: (String?) -> Unit) -> Unit)? = null
    public var presentSave: ((path: String, answer: (String?) -> Unit) -> Unit)? = null
}

internal class IosPairingFileActions(val save: () -> Unit, val open: () -> Unit)

@Composable
internal fun rememberPairingFiles(controller: ChurController): IosPairingFileActions {
    val scope = rememberCoroutineScope()
    val files = remember { mutableSetOf<String>() }
    val lease = remember { intArrayOf(0) }
    fun begin() { lease[0]++; controller.beginHostActivity() }
    fun end() { if (lease[0] > 0) { lease[0]--; controller.endHostActivity() } }
    fun generation() = (controller.vaultState.value as? VaultState.Unlocked)?.generation
    DisposableEffect(controller) {
        onDispose {
            files.forEach { unlink(it) }; files.clear()
            while (lease[0] > 0) end()
        }
    }
    return IosPairingFileActions(
        save = save@{
            if (lease[0] != 0) return@save
            val bytes = controller.pairingApproval.value?.copyOf() ?: return@save
            val present = IosPairingFiles.presentSave ?: run { controller.report("The file picker is unavailable."); return@save }
            val expected = generation()
            scope.launch {
                var path: String? = null
                try {
                    withContext(Dispatchers.Default) { path = writePairingApproval(bytes) }
                    val ready = requireNotNull(path)
                    if (generation() != expected) { unlink(ready); return@launch }
                    files.add(ready)
                    begin()
                    present(ready) { result ->
                        unlink(ready); files.remove(ready); end()
                        if (result != null && generation() == expected) controller.report("Connection file saved.")
                    }
                } catch (cancelled: CancellationException) { path?.let { unlink(it) }; throw cancelled }
                catch (_: Exception) { path?.let { unlink(it) }; end(); controller.report("The connection file could not be saved.") }
                finally { bytes.fill(0) }
            }
        },
        open = open@{
            if (lease[0] != 0) return@open
            val present = IosPairingFiles.presentOpen ?: run { controller.report("The file picker is unavailable."); return@open }
            val expected = generation()
            begin()
            try {
                present { path ->
                    end()
                    if (path != null && (generation() != expected || !scope.isActive)) unlink(path)
                    else if (path != null) scope.launch {
                        try {
                            val bytes = withContext(Dispatchers.Default) { readPairingApproval(path) }
                            if (generation() == expected) controller.acceptPairingApproval(bytes)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { controller.report("That connection file could not be read, or is too large.") }
                        finally { unlink(path) }
                    }
                }
            } catch (_: Exception) { end(); controller.report("The file picker could not be opened.") }
        },
    )
}

internal fun writePairingApproval(bytes: ByteArray): String {
    require(bytes.size in 1..APPROVAL_LIMIT)
    val path = NSTemporaryDirectory() + "chur-pairing/" + NSUUID().UUIDString + ".churpair"
    val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, 384)
    check(fd >= 0)
    var complete = false
    try {
        check(NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionComplete), ofItemAtPath = path, error = null,
        ))
        check(NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null))
        bytes.usePinned { pinned ->
            var at = 0
            while (at < bytes.size) {
                val count = write(fd, pinned.addressOf(at), (bytes.size - at).toULong())
                check(count > 0)
                at += count.toInt()
            }
        }
        complete = true
        return path
    } finally { close(fd); if (!complete) unlink(path) }
}

internal fun readPairingApproval(path: String): ByteArray {
    val fd = open(path, O_RDONLY)
    check(fd >= 0)
    try {
        val bytes = ByteArray(APPROVAL_LIMIT + 1)
        var at = 0
        bytes.usePinned { pinned ->
            while (at < bytes.size) {
                val count = read(fd, pinned.addressOf(at), minOf(64 * 1024, bytes.size - at).toULong())
                check(count >= 0)
                if (count == 0L) break
                at += count.toInt()
                require(at <= APPROVAL_LIMIT)
            }
        }
        return bytes.copyOf(at)
    } finally { close(fd) }
}
