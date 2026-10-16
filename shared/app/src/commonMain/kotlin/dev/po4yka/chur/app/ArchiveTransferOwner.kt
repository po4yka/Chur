package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultState

/** A transient credential. Ownership passes to the controller when work starts. */
class ArchiveCredential(password: String) {
    private var data: ByteArray? = password.encodeToByteArray()
    internal fun copyBytes(): ByteArray = data?.copyOf()
        ?: throw ChurFailure(ChurStatus.SESSION_EXPIRED, "the archive credential")
    fun clear() { data?.fill(0); data = null }
    override fun toString(): String = "ArchiveCredential(<redacted>)"
}

/** An open host input; its owner must close it even when a picker answers late. */
class ArchiveSource(val descriptor: Int, val close: () -> Unit) {
    override fun toString(): String = "ArchiveSource(<redacted>)"
}

/** Owns credentials and foreground leases while a platform file picker is open. */
class ArchiveTransferOwner(private val controller: ChurController, private val generation: Long) {
    private var export: Pair<SelectiveExportRequest, ArchiveCredential>? = null
    private var importing: ArchiveCredential? = null
    private var disposed = false
    private var lease = false

    private fun current(): Boolean = !disposed &&
        (controller.vaultState.value as? VaultState.Unlocked)?.generation == generation

    private fun acquire(): Boolean {
        if (!current() || lease) return false
        controller.beginHostActivity()
        lease = true
        return true
    }

    private fun release() {
        if (!lease) return
        lease = false
        if ((controller.vaultState.value as? VaultState.Unlocked)?.generation == generation) {
            controller.endHostActivity()
        }
    }

    fun export(
        request: SelectiveExportRequest,
        credential: ArchiveCredential,
        target: ExportTarget,
        chooseFile: ((String) -> Unit)? = null,
    ) {
        if (!current() || request.generation != generation) { credential.clear(); return }
        if (target != ExportTarget.FILES || chooseFile == null) {
            controller.exportSelectiveArchive(request, credential, target)
            return
        }
        if (!acquire()) { credential.clear(); return }
        export = request to credential
        try { chooseFile("chur-archive.churbak") } catch (_: Exception) {
            export = null
            credential.clear()
            release()
            controller.report("The file picker could not be opened.")
        }
    }

    /** Android has already created the document when this callback arrives. */
    fun exportedFile(uri: String?) {
        val pending = export
        export = null
        release()
        if (uri == null) { pending?.second?.clear(); return }
        if (pending == null || !current()) {
            pending?.second?.clear()
            controller.discardArchiveDestination(uri)
            return
        }
        controller.exportSelectiveArchive(pending.first, pending.second, ExportTarget.FILES, uri)
    }

    fun import(credential: ArchiveCredential, chooseFile: () -> Unit) {
        if (!acquire()) { credential.clear(); return }
        importing = credential
        try { chooseFile() } catch (_: Exception) {
            importing = null
            credential.clear()
            release()
            controller.report("The file picker could not be opened.")
        }
    }

    fun importedFile(source: ArchiveSource?) {
        val credential = importing
        importing = null
        release()
        if (source == null || credential == null || !current()) {
            credential?.clear()
            source?.close?.invoke()
            return
        }
        controller.importSelectiveArchive(generation, source.descriptor, credential, source.close)
    }

    fun close() {
        disposed = true
        export?.second?.clear()
        export = null
        importing?.clear()
        importing = null
        release()
    }
}
