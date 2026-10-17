package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.ContentTextExtractor
import dev.po4yka.chur.app.vault.ContentTextSource
import dev.po4yka.chur.ffi.ContentIndexCandidate
import dev.po4yka.chur.imports.DecoderCleanupException
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Counts only. Neither OCR text nor source names enter a UI projection. */
data class ContentIndexState(
    val available: Boolean = true,
    val running: Boolean = false,
    val clearing: Boolean = false,
    val stopping: Boolean = false,
    val indexed: Int = 0,
    val partial: Int = 0,
    val failed: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
    val message: String? = null,
    /** In-memory ownership only. Equality must distinguish runs with identical visible counts. */
    internal val runEpoch: Long = 0,
)

internal class ContentIndexLease(val source: ContentTextSource, val close: () -> Unit)

internal interface ContentIndexAccess {
    suspend fun candidates(generation: Long, after: ByteArray?, rebuild: Boolean): List<ContentIndexCandidate>
    suspend fun open(generation: Long, candidate: ContentIndexCandidate): ContentIndexLease
    suspend fun put(generation: Long, candidate: ContentIndexCandidate, text: ByteArray, partial: Boolean)
    suspend fun clear(generation: Long)
}

internal class VaultContentIndexAccess(private val vault: VaultRepository) : ContentIndexAccess {
    override suspend fun candidates(generation: Long, after: ByteArray?, rebuild: Boolean) =
        vault.contentIndexCandidates(generation, after, rebuild)

    override suspend fun open(generation: Long, candidate: ContentIndexCandidate): ContentIndexLease {
        val reader = vault.leaseContentIndexReader(generation, candidate.objectId)
        try {
            val info = vault.readerContentInfo(reader)
            check(info.complete && info.plaintextSize == candidate.plaintextSize)
            return ContentIndexLease(ContentTextSource(
                size = candidate.plaintextSize,
                read = { offset, length -> vault.readLeased(reader, offset, length) },
                canRead = { (vault.state.value as? VaultState.Unlocked)?.generation == generation },
            )) { vault.releaseReader(reader) }
        } catch (failure: Throwable) {
            vault.releaseReader(reader)
            throw failure
        }
    }

    override suspend fun put(generation: Long, candidate: ContentIndexCandidate, text: ByteArray, partial: Boolean) =
        vault.putContentIndex(generation, candidate, text, partial)
    override suspend fun clear(generation: Long) = vault.clearContentIndex(generation)
}

/** One foreground session owns a scan. A replacement session cannot accept its results. */
internal class ContentIndexController(
    private val vaultState: StateFlow<VaultState>,
    private val access: ContentIndexAccess,
    private val extractor: ContentTextExtractor?,
    private val scope: CoroutineScope,
    private val changed: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(ContentIndexState(available = extractor != null))
    val state: StateFlow<ContentIndexState> = _state.asStateFlow()
    private var job: Job? = null
    private val visit = MutableStateFlow(0L)

    fun start(rebuild: Boolean = false) {
        val engine = extractor ?: return
        val generation = (vaultState.value as? VaultState.Unlocked)?.generation ?: return
        if (job != null) return
        val token = visit.value + 1
        visit.value = token
        _state.value = ContentIndexState(running = true, runEpoch = token)
        job = scope.launch(start = CoroutineStart.LAZY) {
            var cancelled = false
            try {
                withContext(dispatcher) {
                    val context = currentCoroutineContext()
                    fun current() = token == visit.value && context.isActive &&
                        (vaultState.value as? VaultState.Unlocked)?.generation == generation
                    var after: ByteArray? = null
                    while (current()) {
                        val candidates = access.candidates(generation, after, rebuild)
                        if (candidates.isEmpty()) break
                        for (candidate in candidates) {
                            context.ensureActive()
                            if (!current()) throw CancellationException()
                            publish(token) { it.copy(page = 0, pages = 0) }
                            try {
                                val lease = access.open(generation, candidate)
                                try {
                                    val result = engine.extract(lease.source, candidate.contentType,
                                        candidate.mediaClass, cancelRequested = { !current() },
                                        onPage = { page, pages ->
                                            if (current()) publish(token) { it.copy(page = page, pages = pages) }
                                        })
                                    try {
                                        context.ensureActive()
                                        if (!current()) throw CancellationException()
                                        access.put(generation, candidate, result.bytes, result.partial)
                                        if (current()) publish(token) { it.copy(
                                            indexed = it.indexed + 1,
                                            partial = it.partial + if (result.partial) 1 else 0,
                                        ) }
                                    } finally { result.bytes.fill(0) }
                                } finally { lease.close() }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: DecoderCleanupException) {
                                // An unconfirmed child shutdown must stop the scan, as it stops import.
                                throw failure
                            } catch (_: Exception) {
                                if (!current()) throw CancellationException()
                                // Platform errors and filenames must not reach the UI or logs.
                                publish(token) { it.copy(failed = it.failed + 1) }
                            }
                            after = candidate.objectId.copyOf()
                        }
                    }
                }
            } catch (failure: CancellationException) {
                cancelled = true
                if (failure.hasDecoderCleanupFailure()) {
                    publish(token) { it.copy(message = "Content indexing could not stop safely. Restart Chur before indexing more files.") }
                }
            } catch (_: DecoderCleanupException) {
                publish(token) { it.copy(message = "Content indexing could not stop safely. Restart Chur before indexing more files.") }
            } catch (_: Exception) {
                publish(token) { it.copy(message = "Content indexing could not finish. Try again.") }
            } finally {
                withContext(NonCancellable) {
                    if (token == visit.value) {
                        job = null
                        if ((vaultState.value as? VaultState.Unlocked)?.generation == generation) {
                            publish(token) { it.copy(running = false, stopping = false, page = 0, pages = 0,
                                message = it.message ?: if (cancelled) "Indexing stopped. Saved results remain searchable." else "Content indexing finished.") }
                            changed()
                        } else reset()
                    }
                }
            }
        }
        job?.start()
    }

    fun cancel() {
        if (job == null || _state.value.clearing) return
        publish(visit.value) { it.copy(stopping = true) }
        job?.cancel()
    }

    fun clear() {
        val generation = (vaultState.value as? VaultState.Unlocked)?.generation ?: return
        if (job != null || extractor == null) return
        val token = visit.value + 1
        visit.value = token
        _state.value = ContentIndexState(running = true, clearing = true, message = "Clearing content index…", runEpoch = token)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(dispatcher) { access.clear(generation) }
                if (token == visit.value && (vaultState.value as? VaultState.Unlocked)?.generation == generation) {
                    publish(token) { ContentIndexState(message = "Content index cleared. Files are unchanged.") }
                    changed()
                }
            } catch (_: CancellationException) {
                // A lock clears the projection below.
            } catch (_: Exception) {
                publish(token) { ContentIndexState(message = "The content index could not be cleared. Try again.") }
            } finally {
                if (token == visit.value) job = null
            }
        }
        job?.start()
    }

    /** Invalidate progress before cancellation can produce a late callback. */
    fun reset() {
        val token = visit.value + 1
        visit.value = token
        job?.cancel()
        job = null
        _state.value = ContentIndexState(available = extractor != null, runEpoch = token)
    }

    /** The owner check and projection write share one CAS, including its retry after reset. */
    private inline fun publish(token: Long, projection: (ContentIndexState) -> ContentIndexState) {
        _state.update { state ->
            if (state.runEpoch == token) projection(state).copy(runEpoch = token) else state
        }
    }

    /** Stack-trace recovery can copy cancellation and keep the original as its cause. */
    private fun CancellationException.hasDecoderCleanupFailure(): Boolean {
        val visited = mutableListOf<CancellationException>()
        var current: CancellationException? = this
        while (true) {
            val failure = current ?: return false
            if (visited.any { it === failure }) return false
            visited += failure
            if (failure.suppressedExceptions.any { it is DecoderCleanupException }) return true
            current = failure.cause as? CancellationException
        }
    }
}
