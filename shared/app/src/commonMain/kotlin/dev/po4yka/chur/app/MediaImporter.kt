package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.OperationProgress
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.LivePhotoBundle
import dev.po4yka.chur.imports.LivePhotoBundleException
import dev.po4yka.chur.imports.readLivePhotoBundle
import dev.po4yka.chur.imports.DecoderCleanupException
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.requiredDerivatives
import dev.po4yka.chur.imports.fileImportProbe
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
class MediaImporter(
    private val codec: MediaCodec,
    private val derivativeTimeout: Duration = 30.seconds,
    private val derivativeTimeSource: TimeSource = TimeSource.Monotonic,
) {
    /** The original is committed, but preview work did not finish. */
    enum class PreviewWarning { STORAGE_FAILED, TIMED_OUT, DECODER_CLEANUP_FAILED, DECODING_FAILED }

    /** What an import attempt ended as, §13. */
    sealed interface Outcome {
        /** The object is in the vault, with the derivatives it needed. */
        data class Imported(
            val objectId: ByteArray,
            val derivatives: Int,
            /** Cancellation after commit keeps the original and skips remaining previews. */
            val previewsSkipped: Boolean = false,
            /** A preview failure does not change the committed original into a failed import. */
            val previewWarning: PreviewWarning? = null,
            /** A media source with no usable decoder remains exportable as a file. */
            val storedAsFile: Boolean = false,
        ) : Outcome {
            override fun equals(other: Any?): Boolean = other is Imported && objectId.contentEquals(other.objectId)

            override fun hashCode(): Int = objectId.contentHashCode()
        }

        /** The source is outside the bounds of §12, and nothing was written. */
        data class TooLarge(
            val reason: String,
        ) : Outcome

        /** The provider could not open the source. */
        data object Unreadable : Outcome

        /**
         * The boundary refused, carrying its stable status.
         *
         * The status and not its text: a host tells a cancellation from a
         * failure by the code, and `userCopy` turns it into what the user
         * reads, `docs/ERROR_MODEL.md` "Layer mapping".
         */
        data class Refused(
            val status: ChurStatus,
        ) : Outcome
    }

    /**
     * Runs one import.
     *
     * A missing [source] after a cancel is the cancel: a host that was still
     * fetching the item, as an iCloud original on iOS, stops the fetch when
     * the user cancels and has no file to hand over, `IOS.md` §15.2.
     */
    suspend fun import(
        repository: VaultRepository,
        source: PickedMedia?,
        expectedGeneration: Long? = null,
        requestedObjectId: ByteArray? = null,
        onProgress: (OperationProgress) -> Unit = {},
        cancelRequested: () -> Boolean = { false },
    ): Outcome {
        var media = source
            ?: return if (cancelRequested()) Outcome.Refused(ChurStatus.CANCELLED) else Outcome.Unreadable
        try {
            if (cancelRequested()) return Outcome.Refused(ChurStatus.CANCELLED)
            val generation = expectedGeneration ?: (repository.state.value as? VaultState.Unlocked)?.generation
                ?: return Outcome.Refused(ChurStatus.VAULT_LOCKED)
            if ((repository.state.value as? VaultState.Unlocked)?.generation != generation) {
                return Outcome.Refused(ChurStatus.VAULT_LOCKED)
            }
            val livePhoto = try {
                if (media.livePhoto == null && media.contentTypeHint != LivePhotoBundle.CONTENT_TYPE &&
                    media.originalFilename?.endsWith(".churlive", ignoreCase = true) != true) null
                else readLivePhotoBundle(media.descriptor, media.knownLength, cancelRequested = {
                    cancelRequested() || (repository.state.value as? VaultState.Unlocked)?.generation != generation
                })
            } catch (_: LivePhotoBundleException) {
                return Outcome.Refused(ChurStatus.OBJECT_CORRUPT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Outcome.Refused(when {
                    cancelRequested() -> ChurStatus.CANCELLED
                    (repository.state.value as? VaultState.Unlocked)?.generation != generation -> ChurStatus.VAULT_LOCKED
                    else -> ChurStatus.IO_FAILURE
                })
            }
            if (livePhoto == null && (media.livePhoto != null || media.contentTypeHint == LivePhotoBundle.CONTENT_TYPE ||
                    media.originalFilename?.endsWith(".churlive", ignoreCase = true) == true)) {
                return Outcome.Refused(ChurStatus.OBJECT_CORRUPT)
            }
            if (livePhoto != null) media = media.copy(livePhoto = livePhoto, importAsFile = false,
                contentTypeHint = LivePhotoBundle.CONTENT_TYPE, knownLength = livePhoto.totalLength,
                captureTimeMs = livePhoto.captureTimeMs)
            // §2 stage 3, before stage 4: an over-large source is refused
            // before an object key exists.
            val detected = if (media.importAsFile) null else try {
                codec.probe(media)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ChurFailure) {
                throw failure
            } catch (_: Exception) {
                null
            }
            val probe = if (livePhoto != null) (detected ?: fileImportProbe(media)).copy(contentType = LivePhotoBundle.CONTENT_TYPE)
                else detected ?: fileImportProbe(media)
            val storedAsFile = !media.importAsFile && probe.mediaClass == MediaBounds.CLASS_OPAQUE
            MediaBounds.check(probe)?.let { return Outcome.TooLarge(it) }
            if (cancelRequested()) return Outcome.Refused(ChurStatus.CANCELLED)

            val operation =
                repository.beginImport(
                    sourceFd = media.descriptor,
                    expectedGeneration = generation,
                    request =
                        ImportRequest(
                            contentType = probe.contentType,
                            seekable = media.seekable,
                            mediaClass = probe.mediaClass,
                            width = probe.width,
                            height = probe.height,
                            durationMs = probe.durationMs,
                            knownLength = media.knownLength,
                            captureTimeMs = media.captureTimeMs,
                            originalFilename = media.originalFilename,
                            requestedObjectId = requestedObjectId,
                        ),
                )
            val terminal = try {
                drain(repository, operation, onProgress, cancelRequested)
            } finally {
                try {
                    repository.cancel(operation)
                } finally {
                    repository.closeOperation(operation)
                }
            }
            if (terminal.status != 0) {
                return Outcome.Refused(ChurStatus.fromValue(terminal.status))
            }

            // §10 of the FFI contract: the terminal snapshot names the object
            // the import activated, so the derivatives of stage 7 attach to
            // the object this import created rather than to whichever row the
            // timeline happens to put first.
            val objectId =
                terminal.importedObjectId()
                    ?: return Outcome.Refused(ChurStatus.NOT_FOUND)

            // §2 stage 7 and §13: a codec failure here leaves the object
            // imported with fewer derivatives rather than failing the import,
            // and the catalog never claims a derivative that does not exist.
            var written = 0
            var decodingFailed = false
            val derivatives = requiredDerivatives(probe).let {
                if (livePhoto != null && probe.mediaClass == MediaBounds.CLASS_IMAGE) (it + StreamKind.SCREEN_PREVIEW).distinct() else it
            }
            for (kind in derivatives) {
                if (cancelRequested()) return Outcome.Imported(objectId, written, previewsSkipped = true, storedAsFile = storedAsFile)
                val started = derivativeTimeSource.markNow()
                val timedOut = { started.elapsedNow() >= derivativeTimeout }
                val derivative = try {
                    // The codec owns its resources until this call returns. A
                    // timeout must not leave a decoder reading a closed source.
                    codec.deriveBounded(media, probe, kind) { cancelRequested() || timedOut() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: DecoderCleanupException) {
                    // The decoder retains its uncertain owner and forbids reuse.
                    // Keep the committed identity so the host can place this
                    // original and stop the batch without opening another source.
                    return Outcome.Imported(objectId, written, previewWarning = PreviewWarning.DECODER_CLEANUP_FAILED, storedAsFile = storedAsFile)
                } catch (_: Exception) {
                    null
                }
                if (cancelRequested()) {
                    derivative?.bytes?.fill(0)
                    return Outcome.Imported(objectId, written, previewsSkipped = true, storedAsFile = storedAsFile)
                }
                if (timedOut()) {
                    derivative?.bytes?.fill(0)
                    return Outcome.Imported(objectId, written, previewWarning = PreviewWarning.TIMED_OUT, storedAsFile = storedAsFile)
                }
                if (derivative == null) {
                    if (probe.mediaClass != MediaBounds.CLASS_AUDIO || kind == StreamKind.AUDIO_WAVEFORM) {
                        decodingFailed = true
                    }
                    continue
                }
                try {
                    repository.putDerived(
                        objectId = objectId,
                        kind = kind,
                        width = derivative.width,
                        height = derivative.height,
                        bytes = derivative.bytes,
                        expectedGeneration = generation,
                    )
                    written += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return Outcome.Imported(objectId, written, previewWarning = PreviewWarning.STORAGE_FAILED, storedAsFile = storedAsFile)
                } finally {
                    derivative.bytes.fill(0)
                }
            }
            return Outcome.Imported(objectId, written,
                previewWarning = if (decodingFailed || storedAsFile) PreviewWarning.DECODING_FAILED else null,
                storedAsFile = storedAsFile)
        } catch (failure: ChurFailure) {
            return Outcome.Refused(failure.status)
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
    private suspend fun drain(
        repository: VaultRepository,
        operation: Long,
        onProgress: (OperationProgress) -> Unit,
        cancelRequested: () -> Boolean,
    ): OperationProgress {
        var signalled = false
        while (true) {
            if (!signalled && cancelRequested()) {
                repository.cancel(operation)
                signalled = true
            }
            val progress = repository.poll(operation)
            onProgress(progress)
            if (progress.terminal) return progress
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        /** Fast enough to feel immediate, slow enough not to spin a core. */
        const val POLL_INTERVAL_MS = 50L
    }
}
