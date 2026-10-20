package dev.po4yka.chur.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.vault.VaultState
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val PAIRING_APPROVAL_MAX = 16 * 1024 * 1024

internal fun readPairingApproval(input: InputStream, limit: Int = PAIRING_APPROVAL_MAX): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(chunk, 0, minOf(chunk.size, limit - output.size() + 1))
        if (count < 0) return output.toByteArray()
        require(count != 0) { "connection file made no read progress" }
        require(count <= limit - output.size()) { "connection file exceeds its size limit" }
        output.write(chunk, 0, count)
    }
}

internal class PairingApprovalActions(val save: () -> Unit, val open: () -> Unit)

/** Exchanges an encrypted approval through bounded files, without a clipboard copy. */
@Composable
internal fun rememberPairingApprovalFiles(
    controller: ChurController,
): PairingApprovalActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var generation by remember { mutableStateOf<Long?>(null) }
    var active by remember { mutableStateOf(false) }
    fun current() = (controller.vaultState.value as? VaultState.Unlocked)?.generation
    fun end() { if (active) { active = false; controller.endHostActivity() } }
    fun begin() { generation = current(); active = true; controller.beginHostActivity() }
    DisposableEffect(controller) {
        onDispose { pending?.fill(0); pending = null; end() }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pending
        pending = null
        end()
        if (uri == null || bytes == null || current() != generation) { bytes?.fill(0) }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(bytes.size <= PAIRING_APPROVAL_MAX)
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("connection file is unavailable")
                    stream.use { it.write(bytes) }
                }
                controller.report("Connection file saved.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { controller.report("The connection file could not be saved.") }
            finally { bytes.fill(0) }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        end()
        if (uri != null && current() == generation) scope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri) ?: error("connection file is unavailable")
                    stream.use { readPairingApproval(it) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { controller.report("That connection file could not be read, or is too large."); return@launch }
            if (current() == generation) controller.acceptPairingApproval(bytes)
            bytes.fill(0)
        }
    }
    return PairingApprovalActions(
        save = save@{
            if (active) return@save
            val bytes = controller.pairingApproval.value?.copyOf()
            if (bytes == null) controller.report("No connection file is ready.")
            else {
                pending?.fill(0)
                pending = bytes
                begin()
                try { save.launch("Chur-device-connection.churpair") }
                catch (_: Exception) { pending?.fill(0); pending = null; end(); controller.report("The file picker could not be opened.") }
            }
        },
        open = open@{
            if (active) return@open
            begin()
            try { open.launch(arrayOf("application/octet-stream", "application/*")) }
            catch (_: Exception) { end(); controller.report("The file picker could not be opened.") }
        },
    )
}
