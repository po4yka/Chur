package dev.po4yka.chur.android

import android.content.ContentResolver
import android.net.Uri
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.requiredDerivatives
import dev.po4yka.chur.vault.VaultRepository

/**
 * The import stages of `docs/interop/MEDIA_PIPELINE.md` §2, in order.
 *
 * The order is the specification's: acquire the descriptor, validate capability
 * and bounds, create the Rust import transaction, stream the original, probe
 * the canonical metadata, generate the derivatives, encrypt them, and release
 * the source. The bounds are checked before the transaction opens, so a source
 * §12 refuses never costs a key or a container.
 *
 * §13 keeps the failures distinct, and this returns which one happened rather
 * than a boolean: a codec failure must not commit a catalog entry claiming the
 * derivatives exist, and an over-large source is not a corrupt one.
 */
class ChurImporter(private val codec: AndroidMediaCodec) {

    /** What an import attempt ended as, §13. */
    sealed interface Outcome {
        /** The object is in the vault, with the derivatives it needed. */
        data class Imported(val objectId: ByteArray, val derivatives: Int) : Outcome {
            override fun equals(other: Any?): Boolean =
                other is Imported && objectId.contentEquals(other.objectId)

            override fun hashCode(): Int = objectId.contentHashCode()
        }

        /** The source is outside the bounds of §12, and nothing was written. */
        data class TooLarge(val reason: String) : Outcome

        /** The provider could not open the source. */
        data object Unreadable : Outcome

        /** The boundary refused, carrying its stable status. */
        data class Refused(val status: String) : Outcome
    }

    /** Runs one import. */
    suspend fun import(
        repository: VaultRepository,
        resolver: ContentResolver,
        uri: Uri,
    ): Outcome {
        val media = codec.open(uri) ?: return Outcome.Unreadable
        try {
            // §2 stage 3, before stage 4: an over-large source is refused
            // before an object key exists.
            val probe = codec.probe(media)
                ?: return Outcome.Refused("UNSUPPORTED_VERSION")
            MediaBounds.check(probe)?.let { return Outcome.TooLarge(it) }

            val operation = repository.beginImport(
                sourceFd = media.descriptor,
                request = ImportRequest(
                    contentType = probe.contentType,
                    mediaClass = probe.mediaClass,
                    width = probe.width,
                    height = probe.height,
                    durationMs = probe.durationMs,
                    knownLength = media.knownLength,
                    captureTimeMs = media.captureTimeMs,
                    originalFilename = media.originalFilename,
                ),
            )
            val terminal = drain(repository, operation)
            repository.closeOperation(operation)
            if (terminal != 0) {
                return Outcome.Refused(statusName(terminal))
            }

            // The object the import just activated is the newest row of the
            // timeline, which is what the default sort of §16.2 puts first.
            val objectId = repository.page(ObjectQuery(QueryScope.TIMELINE, limit = 1))
                .objects.firstOrNull()?.objectId
                ?: return Outcome.Refused("NOT_FOUND")

            // §2 stage 7 and §13: a codec failure here leaves the object
            // imported with fewer derivatives rather than failing the import,
            // and the catalog never claims a derivative that does not exist.
            var written = 0
            for (kind in requiredDerivatives(probe)) {
                val derivative = codec.derive(media, probe, kind) ?: continue
                repository.putDerived(
                    objectId = objectId,
                    kind = kind,
                    width = derivative.width,
                    height = derivative.height,
                    bytes = derivative.bytes,
                )
                written += 1
            }
            return Outcome.Imported(objectId, written)
        } catch (failure: ChurFailure) {
            return Outcome.Refused(failure.status.name)
        } finally {
            // §2 stage 10, and §13 of the FFI contract: Rust duplicated the
            // descriptor, so closing the caller's is deterministic here.
            media.close()
        }
    }

    /**
     * Polls to the terminal result, §10.
     *
     * Polling is cheap and never waits on the operation, so a sleep between
     * polls is the caller's rate rather than a lock: this one yields, because
     * it already runs on an I/O dispatcher and an import of a large file is
     * bounded by the disk rather than by this loop.
     */
    private suspend fun drain(repository: VaultRepository, operation: Long): Int {
        while (true) {
            val progress = repository.poll(operation)
            if (progress.terminal) return progress.status
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
        }
    }

    private fun statusName(code: Int): String =
        dev.po4yka.chur.core.model.ChurStatus.fromValue(code).name

    private companion object {
        /** Fast enough to feel immediate, slow enough not to spin a core. */
        const val POLL_INTERVAL_MS = 50L
    }
}
