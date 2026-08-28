package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.ffi.ContentInfo
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.KeystoreMaterial
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.OperationProgress
import dev.po4yka.chur.ffi.SlotSummary
import dev.po4yka.chur.ffi.StreamKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one owner of the runtime and session handles.
 *
 * `docs/interop/FFI_CONTRACT.md` §14 permits one runtime per process and §8.1
 * one process per vault, so this is a single instance held by the composition
 * root. Nothing above it sees a handle: a feature asks for a page or an object
 * and never for the `Long` behind it.
 *
 * Every call is guarded by one mutex. §8.1 already serializes catalog writes
 * inside Rust, so this adds no safety there; what it adds is that a lock cannot
 * interleave with a call that is about to use the session it closed.
 *
 * Calls block, because §8 makes every native call synchronous. The caller runs
 * this on an I/O dispatcher; this class does not choose one for it, so a test
 * can drive it directly.
 */
class VaultRepository(
    private val rootPath: String,
    private val clock: () -> Long,
    private val policy: LockPolicy = LockPolicy(),
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<VaultState>(VaultState.Starting)
    private var runtime = 0L
    private var session = 0L
    private var generation = 0L
    private var lastUsedMs = 0L


    /** What the application should show. */
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** Opens the runtime and reports whether a vault exists. */
    suspend fun start(): VaultState = mutex.withLock {
        if (runtime == 0L) {
            runtime = ChurVault.openRuntime(rootPath)
        }
        val next = if (ChurVault.vaultPresent(runtime)) VaultState.Locked() else VaultState.NoVault
        _state.value = next
        next
    }

    /**
     * Creates a vault, `PROVISIONING.md` §3.
     *
     * The recovery offer of step 5 happens inside, between the verified
     * password slot and `ACTIVE`, because that is where §3 puts it. The phrase
     * is returned once and this class keeps no copy: `RECOVERY.md` §2 shows it
     * exactly once and §8 there is how a user who loses it gets another.
     */
    suspend fun create(password: ByteArray, offerRecovery: Boolean): String? =
        mutex.withLock {
            requireRuntime()
            _state.value = VaultState.Creating
            var creation = 0L
            try {
                creation = ChurVault.beginCreation(runtime, password)
                val secret = if (offerRecovery) ChurVault.creationAddRecoverySlot(creation) else null
                session = ChurVault.activateCreation(creation)
                creation = 0L
                generation += 1
                touch()
                _state.value = VaultState.Unlocked(generation)
                secret
            } catch (failure: ChurFailure) {
                // §9 of the descriptor format: a creation that does not reach
                // ACTIVE leaves nothing openable, and abandoning is how.
                if (creation != 0L) {
                    runCatching { ChurVault.abandonCreation(creation) }
                }
                _state.value = VaultState.Locked(failure.status)
                throw failure
            }
        }

    /** Unlocks with a password, `KEY_SLOTS.md` §8. */
    suspend fun unlock(password: ByteArray) = mutex.withLock {
        requireRuntime()
        openSession { ChurVault.unlockWithPassword(runtime, password) }
    }

    /** Unlocks with the recovery phrase, `RECOVERY.md`. */
    suspend fun unlockWithRecovery(phrase: String) = mutex.withLock {
        requireRuntime()
        openSession { ChurVault.unlockWithRecovery(runtime, phrase) }
    }

    /** Unlocks with a `DeviceUnlockSecret` the platform keystore returned. */
    suspend fun unlockWithDeviceSecret(secret: ByteArray) = mutex.withLock {
        requireRuntime()
        openSession { ChurVault.unlockWithDeviceSecret(runtime, secret) }
    }

    /**
     * Locks the session, `PLAINTEXT_LIFECYCLE.md` §8.
     *
     * It is idempotent, because every trigger can fire while another already
     * has: the panic gesture during a background transition is the case the
     * product expects rather than the case it forbids.
     */
    suspend fun lock(reason: LockReason) = mutex.withLock {
        if (session != 0L) {
            runCatching { ChurVault.lock(session, reason) }
            runCatching { ChurVault.closeSession(session) }
            session = 0L
        }
        _state.value = VaultState.Locked()
    }

    /** Locks when the policy says the session has been idle too long. */
    suspend fun lockIfIdle(): Boolean {
        val decision = mutex.withLock {
            if (session == 0L) LockDecision.KEEP else idleDecision(policy, lastUsedMs, clock())
        }
        if (decision == LockDecision.LOCK) {
            lock(LockReason.TIMEOUT)
            return true
        }
        return false
    }

    /** Locks when the application leaves the foreground, if the policy says so. */
    suspend fun onBackground() {
        if (policy.lockOnBackground) {
            lock(LockReason.BACKGROUND)
        }
    }

    /** Closes everything, which a process shutdown does. */
    suspend fun shutdown() = mutex.withLock {
        if (runtime != 0L) {
            runCatching { ChurVault.closeRuntime(runtime) }
            runtime = 0L
            session = 0L
        }
        _state.value = VaultState.Starting
    }

    // -----------------------------------------------------------------------
    // The library, each call guarded and each one refreshing the idle clock
    // -----------------------------------------------------------------------

    /** One page of a scope. */
    suspend fun page(query: ObjectQuery): ObjectPage = withSession { ChurVault.query(it, query) }

    /** One object's detail record. */
    suspend fun detail(objectId: ByteArray): ObjectDetail =
        withSession { ChurVault.detail(it, objectId) }

    /** Sets or clears the favourite flag. */
    suspend fun setFavorite(objectId: ByteArray, favorite: Boolean) =
        withSession { ChurVault.setFavorite(it, objectId, favorite) }

    /** Deletes an object. */
    suspend fun delete(objectId: ByteArray) = withSession { ChurVault.deleteObject(it, objectId) }

    /** Every album. */
    suspend fun albums(): List<AlbumSummary> = withSession { ChurVault.albums(it) }

    /** Creates an album. */
    suspend fun createAlbum(name: String): ByteArray = withSession { ChurVault.createAlbum(it, name) }

    /** Adds or removes one album membership. */
    suspend fun setAlbumMembership(albumId: ByteArray, objectId: ByteArray, member: Boolean) =
        withSession { ChurVault.setAlbumMembership(it, albumId, objectId, member) }

    /** Creates a tag. */
    suspend fun createTag(name: String): ByteArray = withSession { ChurVault.createTag(it, name) }

    /** Applies or removes one tag. */
    suspend fun setObjectTag(tagId: ByteArray, objectId: ByteArray, tagged: Boolean) =
        withSession { ChurVault.setObjectTag(it, tagId, objectId, tagged) }

    /** The key slots, for the settings screen. */
    suspend fun slots(): List<SlotSummary> = withSession { ChurVault.slots(it) }

    /** Adds a recovery slot and returns the phrase once. */
    suspend fun addRecoverySlot(): String = withSession { ChurVault.addRecoverySlot(it) }

    /** Adds the platform device slot and returns the secret to store. */
    suspend fun addDeviceSlot(keychainItemId: ByteArray): ByteArray =
        withSession { ChurVault.addDeviceSlot(it, keychainItemId) }

    /**
     * Enrolls the Android Keystore slot, `KEY_SLOTS.md` §4.
     *
     * The enrollment is one repository call rather than two because the vault
     * must not be left holding a pending enrollment: [wrap] runs between the
     * two boundary calls and a failure in it abandons the enrollment instead of
     * committing half of one.
     *
     * [wrap] receives the AAD and the vault root and returns the nonce and the
     * wrapped bytes the Keystore produced. It must not keep either argument.
     */
    suspend fun enrollKeystoreSlot(
        wrap: (alias: ByteArray, aad: ByteArray, rootSecret: ByteArray) -> Pair<ByteArray, ByteArray>,
    ) = withSession { session ->
        val enrollment = ChurVault.beginKeystoreSlot(session)
        try {
            val (nonce, wrapped) = wrap(enrollment.alias, enrollment.aad, enrollment.rootSecret)
            ChurVault.commitKeystoreSlot(session, nonce, wrapped)
        } finally {
            enrollment.rootSecret.fill(0)
        }
    }

    /** What every enrolled Keystore slot needs for its unwrap, while locked. */
    suspend fun keystoreMaterial(): List<KeystoreMaterial> = mutex.withLock {
        requireRuntime()
        ChurVault.keystoreMaterial(runtime)
    }

    /** Unlocks with the root an Android Keystore unwrap returned. */
    suspend fun unlockWithKeystoreRoot(rootSecret: ByteArray) = mutex.withLock {
        requireRuntime()
        try {
            openSession { ChurVault.unlockWithKeystoreRoot(runtime, rootSecret) }
        } finally {
            rootSecret.fill(0)
        }
    }

    /** Removes one slot. */
    suspend fun removeSlot(slotId: ByteArray) = withSession { ChurVault.removeSlot(it, slotId) }

    /** Replaces the password slot. */
    suspend fun changePassword(password: ByteArray) =
        withSession { ChurVault.changePassword(it, password) }

    /** Stores a derivative the platform produced. */
    suspend fun putDerived(
        objectId: ByteArray,
        kind: StreamKind,
        width: Int,
        height: Int,
        bytes: ByteArray,
    ) = withSession { ChurVault.putDerived(it, objectId, kind, width, height, bytes) }

    /** Reads a derivative, which the timeline does for every visible row. */
    suspend fun readDerived(objectId: ByteArray, kind: StreamKind): ByteArray =
        withSession { ChurVault.readDerived(it, objectId, kind) }

    /** Reads a plaintext range of the original. */
    suspend fun readRange(objectId: ByteArray, offset: Long, length: Int): ByteArray =
        withSession { current ->
            val reader = ChurVault.openReader(current, objectId, StreamKind.ORIGINAL)
            try {
                ChurVault.readRange(reader, offset, length)
            } finally {
                runCatching { ChurVault.closeReader(reader) }
            }
        }

    // -----------------------------------------------------------------------
    // Reader leases, for a player
    // -----------------------------------------------------------------------

    /**
     * Opens a reader a player holds across many seeks, `FFI_CONTRACT.md` §6.3.
     *
     * [readRange] opens and closes one reader per call, which suits a single
     * range and is the wrong shape for playback: a player seeks continuously,
     * and re-authenticating the manifest on every range would serialize every
     * seek against every catalog query on this class's one mutex.
     *
     * A lease is taken under the mutex, because it needs the session handle,
     * and is then used without it. §8's table permits exactly that: a reader
     * handle is callable from any thread including one other than its creator,
     * and it names "a Media3 loader thread and an `AVAssetResourceLoader`
     * queue" as the callers it has in mind.
     *
     * A lease never outlives the session it came from, and this class does not
     * arrange that: `PLAINTEXT_LIFECYCLE.md` §8 step 2 invalidates every session
     * handle on lock, and `chur_vault_lock` does it by draining every handle the
     * session owns, of which a reader is one. Tracking the leases here as well
     * would add a second owner of the same fact and a set mutated from a player
     * thread and a lock at once; the caller [releaseReader]s what it took, and a
     * call on a handle the lock already closed is `SESSION_EXPIRED`.
     */
    suspend fun leaseReader(objectId: ByteArray, kind: StreamKind = StreamKind.ORIGINAL): Long =
        withSession { current -> ChurVault.openReader(current, objectId, kind) }

    /**
     * The content information a player needs before its first range request.
     *
     * `FFI_CONTRACT.md` §6.1 forbids attaching a reader on an incomplete object
     * to a player, because a player that has been given a length treats a later
     * failure as a transport error and retries indefinitely. The caller checks
     * [ContentInfo.complete] before it does.
     */
    fun readerContentInfo(reader: Long): ContentInfo = ChurVault.readerContentInfo(reader)

    /**
     * Reads a range through a leased reader, off the mutex.
     *
     * §6.3 permits a short read at any offset, and [ChurVault.readRange]
     * already loops until it has the range or observes zero.
     */
    fun readLeased(reader: Long, offset: Long, length: Int): ByteArray =
        ChurVault.readRange(reader, offset, length)

    /**
     * Closes one lease.
     *
     * It takes no lock and swallows the failure, because both of the things
     * that can go wrong here are ordinary: closing a handle twice is idempotent
     * inside Rust, and closing one a lock already invalidated is
     * `SESSION_EXPIRED`. A player releases on a thread of its own choosing and
     * must not block behind a catalog query to do it.
     */
    fun releaseReader(reader: Long) {
        runCatching { ChurVault.closeReader(reader) }
    }

    /** Starts an import from a descriptor the platform opened. */
    suspend fun beginImport(sourceFd: Int, request: ImportRequest): Long =
        withSession { ChurVault.beginImport(it, sourceFd, request) }

    /** Starts an export to a descriptor the platform opened. */
    suspend fun beginExport(objectId: ByteArray, destinationFd: Int): Long =
        withSession { ChurVault.beginExport(it, objectId, destinationFd) }

    /** Starts an integrity scan; a null identifier scans every object. */
    suspend fun beginIntegrityScan(objectId: ByteArray?): Long =
        withSession { ChurVault.beginIntegrityScan(it, objectId) }

    /**
     * Starts writing a backup package to a descriptor the platform opened,
     * `BACKUP_FORMAT_V1.md` §7.
     */
    suspend fun beginBackup(destinationFd: Int): Long =
        withSession { ChurVault.beginBackup(it, destinationFd) }

    /**
     * Starts restoring a package, §8.
     *
     * It takes no session: a restore installs an identity, so it runs from the
     * runtime and the credential comes from the package's own descriptor.
     */
    suspend fun beginRestore(sourceFd: Int, password: ByteArray): Long = mutex.withLock {
        requireRuntime()
        ChurVault.beginRestore(runtime, sourceFd, password)
    }

    /**
     * One progress snapshot, §10.
     *
     * It takes no session lock. §10 makes polling cheap and says it never waits
     * on the operation, and holding the session mutex here would make a poll
     * wait on the very worker it is asking about.
     */
    fun poll(operation: Long): OperationProgress = ChurVault.poll(operation)

    /** Asks an operation to stop, §9. Callable at any time, like poll. */
    fun cancel(operation: Long) = ChurVault.cancel(operation)

    /** Closes an operation handle, waiting for its worker. */
    fun closeOperation(operation: Long) = ChurVault.closeOperation(operation)

    // -----------------------------------------------------------------------

    private fun requireRuntime() {
        if (runtime == 0L) {
            throw ChurFailure(ChurStatus.INTERNAL_FAILURE, "the runtime is not open")
        }
    }

    private inline fun openSession(open: () -> Long) {
        try {
            session = open()
            generation += 1
            touch()
            _state.value = VaultState.Unlocked(generation)
        } catch (failure: ChurFailure) {
            _state.value = VaultState.Locked(failure.status)
            throw failure
        }
    }

    private fun touch() {
        lastUsedMs = clock()
    }

    /**
     * Runs [body] with the open session, refusing when there is none.
     *
     * A caller that reached here while locked gets `VAULT_LOCKED` rather than a
     * handle of zero, which the boundary would refuse with `INVALID_INPUT` and
     * a less useful message.
     */
    private suspend inline fun <T> withSession(body: (Long) -> T): T = mutex.withLock {
        if (session == 0L) {
            throw ChurFailure(ChurStatus.VAULT_LOCKED, "no session is open")
        }
        val result = body(session)
        touch()
        result
    }
}
