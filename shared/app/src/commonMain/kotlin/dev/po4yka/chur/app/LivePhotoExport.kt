package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.readVaultLivePhoto
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.imports.LivePhotoBundle
import dev.po4yka.chur.imports.livePhotoDigest
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Restores exact resources and a complete portable original into an exclusive output folder. */
suspend fun restoreLivePhotoResources(
    vault: VaultRepository,
    objectId: ByteArray,
    directory: ExportDirectory,
    cancelled: () -> Boolean = { false },
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    write: (Int, ByteArray) -> Unit = ::writeLivePhotoExportBytes,
): Int = withContext(Dispatchers.Default) {
    val generation = (vault.state.value as? VaultState.Unlocked)?.generation
        ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the Live Photo export")
    val outputs = mutableListOf<ExportSink.Destination>()
    var reader: Long? = null
    var complete = false
    var failure: Throwable? = null
    suspend fun check() {
        currentCoroutineContext().ensureActive()
        if (cancelled()) throw ChurFailure(ChurStatus.CANCELLED, "the Live Photo export")
        if ((vault.state.value as? VaultState.Unlocked)?.generation != generation) {
            throw ChurFailure(ChurStatus.SESSION_EXPIRED, "the Live Photo export")
        }
    }
    try {
        check()
        val bundle = readVaultLivePhoto(vault, objectId)
            ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "the Live Photo original")
        // Assign ownership before a cancellable dispatcher handoff can resume.
        withContext(NonCancellable) { reader = vault.leaseReader(objectId) }
        val lease = requireNotNull(reader)
        val info = vault.readerContentInfo(lease)
        if (!info.complete || info.plaintextSize != bundle.totalLength) {
            throw ChurFailure(ChurStatus.INVALID_INPUT, "the incomplete Live Photo original")
        }
        suspend fun range(offset: Long, length: Long, consume: (ByteArray) -> Unit) {
            var at = 0L
            while (at < length) {
                check()
                val take = minOf(65_536L, length - at).toInt()
                val bytes = vault.readLeased(lease, offset + at, take)
                try {
                    if (bytes.size != take) throw ChurFailure(ChurStatus.IO_FAILURE, "the Live Photo resource")
                    consume(bytes)
                } finally { bytes.fill(0) }
                at += take
            }
            check()
        }
        // A user may have stored the package through explicit opaque file import.
        // Validate every digest before creating any externally visible resource.
        for (resource in bundle.resources) {
            val digest = livePhotoDigest()
            var finished = false
            try {
                range(resource.offset, resource.byteLength) { digest.update(it) }
                finished = true
                val actual = digest.finish()
                try {
                    if (!actual.contentEquals(resource.sha256)) {
                        throw ChurFailure(ChurStatus.INVALID_INPUT, "the damaged Live Photo resource")
                    }
                } finally { actual.fill(0) }
            } finally {
                // The iOS digest owns native memory until finish, including
                // cancellation and a source read failure during hashing.
                if (!finished) digest.finish().fill(0)
            }
        }
        suspend fun output(name: String, type: String, offset: Long, length: Long) {
            check()
            val destination = directory.createFile(name, type, length)
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the Live Photo destination")
            outputs += destination
            try {
                range(offset, length) { write(destination.descriptor, it) }
                check()
                if (!destination.publishChecked()) {
                    throw ChurFailure(ChurStatus.IO_FAILURE, "the Live Photo publication")
                }
                check()
            } finally { destination.close() }
        }
        val total = bundle.resources.size + 1
        bundle.resources.forEachIndexed { index, resource ->
            val name = "${(index + 1).toString().padStart(3, '0')}-" +
                safeExportName(resource.originalFilename, "resource")
            output(name, resource.contentType, resource.offset, resource.byteLength)
            onProgress(index + 1, total)
        }
        output("LivePhoto.churlive", LivePhotoBundle.CONTENT_TYPE, 0, bundle.totalLength)
        onProgress(total, total)
        check()
        complete = true
        bundle.resources.size
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            var cleanup: Throwable? = null
            if (!complete) outputs.asReversed().forEach { output ->
                try { output.discard() } catch (error: Exception) { if (cleanup == null) cleanup = error }
                try { output.close() } catch (error: Exception) { if (cleanup == null) cleanup = error }
            }
            reader?.let(vault::releaseReader)
            // Preserve cancellation and its cause even if a provider revokes the
            // grant during rollback; still attempt every remaining destination.
            cleanup?.let { error -> failure?.addSuppressed(error) ?: throw error }
        }
    }
}

/** Duplicates the caller's descriptor; it never takes ownership of that descriptor. */
internal expect fun writeLivePhotoExportBytes(descriptor: Int, bytes: ByteArray)
