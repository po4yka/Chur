package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.SlotSummary
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.NoteStore
import dev.po4yka.chur.vault.LockPolicy
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The application state machine, shared by both hosts.
 *
 * `docs/ARCHITECTURE.md` §9 puts binding in the composition root, and both
 * hosts have one; what they do not have is a reason to write this twice. The
 * two platform-specific pieces are injected: where an export lands, and the
 * privacy cover, because neither is expressible in common code.
 *
 * `docs/interop/FFI_CONTRACT.md` §14 permits one runtime per process, so a host
 * creates one of these. §8 makes every native call synchronous, so every call
 * below moves to the I/O dispatcher and none blocks the main thread.
 */
class ChurController(
    storageRoot: String,
    private val privacy: PrivacyCover,
    private val exports: ExportSink,
    private val clock: () -> Long,
    private val notes: NoteStore = InMemoryNoteStore(),
    policy: LockPolicy = LockPolicy(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repository = VaultRepository(storageRoot, clock, policy)

    private val _route = MutableStateFlow<AppRoute>(AppRoute.PublicShell)
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _page = MutableStateFlow(ObjectPage(emptyList(), 0, 0, null))
    private val _albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    private val _slots = MutableStateFlow<List<SlotSummary>>(emptyList())
    private val _message = MutableStateFlow<String?>(null)
    private val _recoveryPhrase = MutableStateFlow<String?>(null)

    /** Where the application is, `DESIGN.md` §10.3. */
    val route: StateFlow<AppRoute> = _route.asStateFlow()

    /** The vault state machine. */
    val vaultState: StateFlow<VaultState> = repository.state

    /** The public notes. */
    val notesState: StateFlow<List<Note>> = _notes.asStateFlow()

    /** The current library page. */
    val page: StateFlow<ObjectPage> = _page.asStateFlow()

    /** The albums. */
    val albums: StateFlow<List<AlbumSummary>> = _albums.asStateFlow()

    /** The key slots. */
    val slots: StateFlow<List<SlotSummary>> = _slots.asStateFlow()

    /** A bounded message that carries no private value. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The recovery phrase, held only until the user acknowledges it. */
    val recoveryPhrase: StateFlow<String?> = _recoveryPhrase.asStateFlow()

    /** The repository, for a host flow that drives operations itself. */
    val vault: VaultRepository get() = repository

    /** Opens the runtime, loads the public shell, and starts the idle timer. */
    suspend fun start() {
        withContext(Dispatchers.Default) { repository.start() }
        _notes.value = notes.all()
        scope.launch { runIdleTimer() }
    }

    /**
     * The timer behind the auto-lock choices of `DESIGN.md` §14.4.
     *
     * It lives here rather than in each host because the rule is the same on
     * both and a timer neither host started is the failure this replaces: the
     * idle check existed and nothing called it, so a timed lock never happened.
     *
     * `collectLatest` cancels the loop as soon as the session is not unlocked,
     * so no wakeup runs while there is nothing to lock.
     */
    private suspend fun runIdleTimer() {
        repository.state.collectLatest { current ->
            if (current !is VaultState.Unlocked) return@collectLatest
            while (true) {
                delay(IDLE_TICK_MS)
                checkIdle()
            }
        }
    }

    /** Creates a vault, `PROVISIONING.md` §3. */
    fun create(password: String, offerRecovery: Boolean) = guarded {
        val bytes = password.encodeToByteArray()
        val phrase = try {
            withContext(Dispatchers.Default) { repository.create(bytes, offerRecovery) }
        } finally {
            bytes.fill(0)
        }
        if (phrase != null) _recoveryPhrase.value = phrase else enterVault()
    }

    /** Acknowledges the phrase, which is the only way past that screen. */
    fun acknowledgeRecoveryPhrase() {
        _recoveryPhrase.value = null
        if (repository.state.value is VaultState.Unlocked) {
            scope.launch { enterVault() }
        }
    }

    /** Unlocks with a password. */
    fun unlock(password: String) = guarded {
        val bytes = password.encodeToByteArray()
        try {
            withContext(Dispatchers.Default) { repository.unlock(bytes) }
        } finally {
            bytes.fill(0)
        }
        enterVault()
    }

    /** Unlocks with the recovery phrase. */
    fun recover(phrase: String) = guarded {
        withContext(Dispatchers.Default) { repository.unlockWithRecovery(phrase.trim()) }
        enterVault()
    }

    /** Locks now, `DESIGN.md` §14.3. */
    fun lock(reason: LockReason = LockReason.USER) = guarded {
        withContext(Dispatchers.Default) { repository.lock(reason) }
        privacy.setEnabled(false)
        clearPrivateProjections()
        _route.value = AppRoute.PublicShell
    }

    /** The application left the foreground. */
    suspend fun onBackground() {
        privacy.setEnabled(true)
        withContext(Dispatchers.Default) { repository.onBackground() }
        if (repository.state.value !is VaultState.Unlocked) {
            clearPrivateProjections()
            _route.value = AppRoute.PublicShell
        }
    }

    /** The idle check of `DESIGN.md` §14.4, which [runIdleTimer] drives. */
    suspend fun checkIdle() {
        if (withContext(Dispatchers.Default) { repository.lockIfIdle() }) {
            privacy.setEnabled(false)
            clearPrivateProjections()
            _route.value = AppRoute.PublicShell
        }
    }

    /** Moves to a route the public shell offers. */
    fun goTo(next: AppRoute) {
        _route.value = next
    }

    /** The route the visible settings entry of §2 leads to. */
    fun openVaultEntry() {
        _route.value = if (repository.state.value is VaultState.NoVault) {
            AppRoute.CreateVault
        } else {
            AppRoute.Unlock
        }
    }

    /** Loads one query scope. */
    fun load(query: ObjectQuery) = guarded {
        _page.value = withContext(Dispatchers.Default) { repository.page(query) }
    }

    /** Loads the albums. */
    fun loadAlbums() = guarded {
        _albums.value = withContext(Dispatchers.Default) { repository.albums() }
    }

    /** Loads the key slots. */
    fun loadSlots() = guarded {
        _slots.value = withContext(Dispatchers.Default) { repository.slots() }
    }

    /** Searches, `CATALOG_SCHEMA_V1.md` §16.4. */
    fun search(terms: String) = guarded {
        _page.value = withContext(Dispatchers.Default) {
            repository.page(ObjectQuery(QueryScope.SEARCH, terms = terms))
        }
    }

    /** Sets or clears the favourite flag. */
    fun setFavorite(objectId: ByteArray, favorite: Boolean) = guarded {
        withContext(Dispatchers.Default) { repository.setFavorite(objectId, favorite) }
        reload()
    }

    /** Deletes an object, `CATALOG_SCHEMA_V1.md` §14.1. */
    fun delete(objectId: ByteArray) = guarded {
        withContext(Dispatchers.Default) { repository.delete(objectId) }
        reload()
    }

    /** Creates an album and reloads the list. */
    fun createAlbum(name: String) = guarded {
        withContext(Dispatchers.Default) { repository.createAlbum(name) }
        _albums.value = withContext(Dispatchers.Default) { repository.albums() }
    }

    /** Adds a recovery slot and shows the phrase once. */
    fun addRecoverySlot() = guarded {
        _recoveryPhrase.value = withContext(Dispatchers.Default) { repository.addRecoverySlot() }
        _slots.value = withContext(Dispatchers.Default) { repository.slots() }
    }

    /** One object's detail record, §6.5. */
    suspend fun detailOf(objectId: ByteArray): ObjectDetail? = try {
        withContext(Dispatchers.Default) { repository.detail(objectId) }
    } catch (failure: ChurFailure) {
        _message.value = failure.status.name
        null
    }

    /**
     * Exports one object, `PLAINTEXT_LIFECYCLE.md` §6.
     *
     * The message says the copy is outside the vault rather than reporting a
     * silent success: §6 makes this the moment the user deliberately leaves the
     * boundary, and recipients, editors, and share extensions persist plaintext
     * under their own policies from here on.
     */
    fun export(objectId: ByteArray) = guarded {
        val detail = withContext(Dispatchers.Default) { repository.detail(objectId) }
        val destination = exports.create(
            detail.filename.ifBlank { "chur-export" },
            detail.contentType,
        ) ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the export destination")
        try {
            val operation = withContext(Dispatchers.Default) {
                repository.beginExport(objectId, destination.descriptor)
            }
            val terminal = drain(operation)
            withContext(Dispatchers.Default) { repository.closeOperation(operation) }
            if (terminal != 0) {
                destination.discard()
                throw ChurFailure(ChurStatus.fromValue(terminal), "the export")
            }
            destination.publish()
            _message.value = "Exported. The copy is outside the vault."
        } finally {
            destination.close()
        }
    }

    /**
     * Verifies every object, `CATALOG_SCHEMA_V1.md` §13.
     *
     * The scan runs on a worker inside Rust and this polls it, which §10 makes
     * the only way to observe a terminal result. The message carries a count
     * and a status name and nothing private.
     */
    fun verifyEverything() = guarded {
        val operation = withContext(Dispatchers.Default) { repository.beginIntegrityScan(null) }
        try {
            while (true) {
                val progress = repository.poll(operation)
                if (progress.terminal) {
                    _message.value = if (progress.status == 0) {
                        "Verified ${progress.processed} object(s)."
                    } else {
                        ChurStatus.fromValue(progress.status).name
                    }
                    break
                }
                _message.value = "Verifying ${progress.processed}"
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            withContext(Dispatchers.Default) { repository.closeOperation(operation) }
            reload()
        }
    }

    /** Writes a public note. */
    fun putNote(note: Note) = guarded {
        notes.put(note)
        _notes.value = notes.all()
    }

    /** Removes a public note. */
    fun removeNote(id: String) = guarded {
        notes.remove(id)
        _notes.value = notes.all()
    }

    /** Reports the outcome of a host-driven import, §13 of the media pipeline. */
    fun reportImport(message: String?) {
        _message.value = message
        scope.launch { reload() }
    }

    /** Sets the message a host flow produced. */
    fun report(message: String?) {
        _message.value = message
    }

    /** Closes the runtime, which a finishing host does. */
    suspend fun shutdown() {
        repository.shutdown()
    }

    /** Polls one operation to its terminal status. */
    suspend fun drain(operation: Long): Int {
        while (true) {
            val progress = repository.poll(operation)
            if (progress.terminal) return progress.status
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun reload() {
        if (repository.state.value is VaultState.Unlocked) {
            _page.value = withContext(Dispatchers.Default) { repository.page(ObjectQuery()) }
        }
    }

    private fun clearPrivateProjections() {
        // §10.3: a lock transition destroys private back-stack projections, and
        // these flows are that projection.
        _page.value = ObjectPage(emptyList(), 0, 0, null)
        _albums.value = emptyList()
        _slots.value = emptyList()
    }

    private suspend fun enterVault() {
        privacy.setEnabled(true)
        _route.value = AppRoute.Vault
        _page.value = withContext(Dispatchers.Default) { repository.page(ObjectQuery()) }
    }

    /**
     * Runs work and turns a boundary failure into a message.
     *
     * `docs/ERROR_MODEL.md` keeps a private value out of a message, and the
     * boundary carries only a status, so the message is the status name.
     */
    private fun guarded(body: suspend () -> Unit) {
        scope.launch {
            try {
                _message.value = null
                body()
            } catch (failure: ChurFailure) {
                _message.value = failure.status.name
            }
        }
    }

    private companion object {
        /** Fast enough to feel immediate, slow enough not to spin a core. */
        const val POLL_INTERVAL_MS = 50L

        /**
         * How often the idle timer looks.
         *
         * The shortest choice of §14.4 is "immediately", so the tick has to be
         * short enough that the shortest choice still reads as immediate; a
         * second is that, and it is one wakeup a second only while a session is
         * unlocked.
         */
        const val IDLE_TICK_MS = 1_000L
    }
}

/**
 * Where an export lands, `PLAINTEXT_LIFECYCLE.md` §6.
 *
 * It is an interface because the two platforms have no common answer: Android
 * writes into the shared Downloads collection through `MediaStore`, and iOS
 * writes a temporary file the share sheet consumes. What they agree on is the
 * shape: a destination is created, written through a descriptor, and either
 * published or discarded, so an interrupted export is invisible rather than a
 * truncated file a reader cannot tell from a whole one.
 */
interface ExportSink {
    /** One open destination. */
    interface Destination {
        /** The descriptor Rust writes into; §13 has Rust duplicate it. */
        val descriptor: Int

        /** Makes the result visible once the whole object is written. */
        fun publish()

        /** Removes a destination whose export failed. */
        fun discard()

        /** Closes the descriptor, which the caller owns. */
        fun close()
    }

    /** Creates a destination for one export. */
    fun create(displayName: String, contentType: String): Destination?
}
