package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.PreparedShare
import dev.po4yka.chur.ffi.PreparedShareRevocation
import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SharedReceivePlan
import dev.po4yka.chur.ffi.SharedSourceObject
import dev.po4yka.chur.ffi.SharedSourceRange
import dev.po4yka.chur.ffi.SyncForkState
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.ffi.SyncRecordKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * What the vault offers the sync engine, at whichever lock state it is in.
 *
 * `SYNC_PROTOCOL_V1.md` §7 splits the protocol by lock state: a locked device
 * may transfer signed opaque bytes but may not open the catalog, so staging
 * needs only the runtime, while identity and application need the unlocked
 * session. This interface is that split; `:shared:app` binds
 * one implementation to the one [dev.po4yka.chur.vault.VaultRepository], which
 * owns the handles, so no engine caller ever sees a `Long`.
 */
public interface SyncVaultBoundary {
    /** The identity to enroll with, or `null` while the vault is locked. */
    public suspend fun identity(): SharingIdentity?

    /**
     * Stages one downloaded record into the native locked inbox.
     *
     * Staging validates nothing and grants nothing, §7: the record is opaque
     * bytes until the unlocked vault processes it.
     */
    public suspend fun stage(
        vaultId: ByteArray,
        kind: SyncRecordKind,
        stagedAtMs: Long,
        record: ByteArray,
    )

    /**
     * Validates and applies the retained inbox on the open session, or `null`
     * while locked, in which case the next unlock drains it.
     */
    public suspend fun process(): SyncProcessReport?

    /**
     * The fork state the catalog keeps, or `null` while locked.
     *
     * `ROLLBACK_PROTECTION.md` §4 keeps it until it clears, so it outlives the
     * [process] pass that found the fork, and a restart.
     */
    public suspend fun forkState(): SyncForkState?

    /**
     * Records that the user saw every detected fork, or `false` while locked.
     * Each chain stays frozen, §4.
     */
    public suspend fun acknowledgeForks(): Boolean

    /** Verifies and installs an addressed share, or `false` if the vault locked. */
    public suspend fun acceptSharePackage(packageBytes: ByteArray): Boolean

    /** Verifies one signed collection-operation page and identifies absent objects. */
    public suspend fun receiveSharedOperations(
        packageBytes: ByteArray,
        operations: List<ByteArray>,
    ): SharedReceivePlan?

    /** Reads the durable prefix of one authenticated pending shared object. */
    public suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray): ULong?

    /** Writes one bounded ciphertext range into native private staging. */
    public suspend fun appendSharedDownload(
        collectionId: ByteArray,
        objectId: ByteArray,
        offset: ULong,
        bytes: ByteArray,
    ): Boolean

    /** Authenticates the complete container and activates its catalog row. */
    public suspend fun finishSharedDownload(
        collectionId: ByteArray,
        objectId: ByteArray,
        nowMs: Long,
    ): Boolean

    /** Source collection with an active sharing history, if one exists. */
    public suspend fun sourceCollectionId(): ByteArray?

    /** Lists a bounded page of already imported source objects. */
    public suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>?

    /** Reads and hashes one committed source ciphertext range. */
    public suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange?

    /** Authors signed Create/Commit only after upload and association. */
    public suspend fun sourceAuthor(source: SharedSourceObject): SharedSourceObject?
}

/** The bounded, non-private state one sync surface shows. */
public data class SyncStatus(
    /** Whether a server is configured at all. */
    public val configured: Boolean,
    /** The configured server address, which the user typed and may see. */
    public val serverUrl: String?,
    /** One bounded line about the last run, such as "Up to date.", with no status name. */
    public val message: String?,
    /** Whether a run is in progress. */
    public val busy: Boolean,
    /**
     * The status that stopped the last run, or `null` when none did.
     *
     * The engine hands over the code rather than a sentence for it:
     * `docs/ERROR_MODEL.md` "Layer mapping" puts user-facing copy in the
     * feature layer, which also decides what a security state such as
     * [ChurStatus.SYNC_CHAIN_FORK] asks the user to do.
     */
    public val failure: ChurStatus? = null,
    /**
     * The fork or rollback verdict this device reached on one device's chain,
     * or `null` while there is none.
     *
     * `ROLLBACK_PROTECTION.md` §4 freezes that chain and has the user told,
     * while every other chain keeps applying, so a run that follows still
     * completes and this stays set: it clears only on
     * [SyncCoordinator.disconnect]. The verdict is local, `ERROR_MODEL.md`, so
     * it comes from the native process report and never from a server code.
     * That report puts a verdict ahead of any other rejection in the same pass,
     * so junk the server stages beside the forked record cannot hide it.
     * The catalog keeps the fork state itself, and [SyncCoordinator.refresh]
     * raises this from it at each unlock, so a fork outlives a restart. A
     * rollback leaves no state there, so it lasts for the process and is not
     * scoped to one vault identity.
     */
    public val integrityStop: ChurStatus? = null,
    /**
     * Whether the user acknowledged [integrityStop], §4's `acknowledged`.
     *
     * The chain stays frozen and the verdict stays; what changes is that an
     * unlock no longer announces it. A fork this device has not reported yet
     * makes it `false` again.
     */
    public val integrityAcknowledged: Boolean = false,
)

/**
 * The sync engine the application drives.
 *
 * It owns what `SyncClient` deliberately does not: when to talk to the server,
 * what to do when the server is unreachable, and where the pull cursors live.
 * One cycle is `SYNC_PROTOCOL_V1.md` §5 and §7 — pull the operation chains and
 * the checkpoints past the stored cursors, stage them in the locked inbox,
 * apply the inbox when the vault is unlocked, and advance the cursors only
 * after every record reached native storage.
 *
 * Failure follows §10: a network failure backs off on a bounded exponential
 * schedule and stops at the attempt cap, while any other status — an
 * authentication refusal or a server rejection of the bytes themselves — stops
 * the run immediately, because §10 forbids retrying authenticated corruption
 * from the same bytes indefinitely. The periodic host schedule is the outer
 * retry; this schedule is the inner one, and success resets it.
 *
 * Configuration is §6's bootstrap: the user names their server and the
 * operator's bootstrap secret, the vault's own identity comes from the open
 * session, and the transport token is generated here and never shown.
 */
public class SyncCoordinator(
    private val store: SyncStateStore,
    private val clock: () -> Long = { 0L },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clientFactory: (String, suspend () -> ByteArray) -> SyncClient =
        { url, token -> SyncClient(url, token) },
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(NOT_CONFIGURED)
    private var boundary: SyncVaultBoundary? = null
    private var integrityStop: ChurStatus? = null
    private var integrityAcknowledged = false

    /** The open session, which [endSession] replaces. */
    @Volatile private var session = Any()

    /** The [session] in which [refresh] or [configure] found that the open vault owns the saved state. */
    @Volatile private var owner: Any? = null

    /** What a sync surface shows. */
    public val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Sets [status], carrying the standing [SyncStatus.integrityStop] into
     * every line, only while the open vault owns the saved state.
     *
     * A run that no session owns shows nothing: the periodic one while the
     * vault is locked, and one that goes on after the lock of the session
     * that started it. A decoy that unlocks during such a run would otherwise
     * see the other vault's server and its retries, `DECOY_VAULT.md` §6 and
     * §10.
     */
    private var shown: SyncStatus
        get() = _status.value
        set(value) {
            val at = session
            if (owner === at) publish(at, value)
        }

    /** Sets [status] for session [at], unless [endSession] ended it before or during the write. */
    private fun publish(at: Any, value: SyncStatus) {
        if (session !== at) return
        _status.value = value.copy(integrityStop = integrityStop, integrityAcknowledged = integrityAcknowledged)
        if (session !== at) _status.value = NOT_CONFIGURED
    }

    /**
     * Binds the vault the engine stages into and applies on.
     *
     * The composition root calls this once; a `null` unbinds, which is what a
     * host shutdown does.
     */
    public fun bind(vault: SyncVaultBoundary?) {
        boundary = vault
    }

    /**
     * Loads the saved state into the visible status, which an unlock does.
     *
     * The state is one file per device, and the server in it belongs to the
     * vault that configured it. Only that vault sees it: a locked vault and
     * any other identity, such as a decoy, see no server here. [syncNow] shows
     * another identity none either and starts no run for it, so its sync
     * status does not differ by whether a sibling syncs, `DECOY_VAULT.md` §7
     * and §10. [configure] in another identity is still refused while a
     * server is saved, because the file is one per device. It returns whether
     * the open vault has a server, which is what decides the unlock's pull.
     *
     * The open vault's own fork state comes with it, `ROLLBACK_PROTECTION.md`
     * §4: the pass that found a fork reported it once, and the catalog is what
     * still knows after a restart.
     */
    public suspend fun refresh(): Boolean =
        mutex.withLock {
            val at = session
            val state = store.load()
            val identity = boundary?.identity()
            val own = state != null && identity != null && matches(state, identity)
            if (own) raise(boundary?.forkState())
            owner = if (own) at else null
            publish(
                at,
                if (state == null || !own) {
                    NOT_CONFIGURED
                } else {
                    SyncStatus(
                        configured = true,
                        serverUrl = state.serverUrl,
                        message = if (state.pendingSharing.isEmpty()) null else "Sharing changes need upload.",
                        busy = false,
                    )
                },
            )
            own
        }

    /**
     * Shows no server, and lets no run show one, until the next [refresh],
     * which an unlock does, finds the vault that owns the saved state.
     *
     * The status is the open session's: a run shows its server on every
     * retry, so without this the next identity saw the last one's server and
     * run until its own [refresh], `DECOY_VAULT.md` §6. A run, or a setup that
     * still finishes, shows nothing after this. It takes no lock, because a
     * run can hold one through its backoff. The verdicts stay, for the vault
     * that owns them to see at its next [refresh].
     */
    public fun endSession() {
        session = Any()
        _status.value = NOT_CONFIGURED
    }

    /**
     * Connects this unlocked vault to the user's server, §6.
     *
     * The address is validated by [SyncClient] itself, so the HTTPS-except-
     * loopback rule of the transport holds no matter who calls this. On
     * success the state is durable and the engine begins from its own device's
     * chain, because at bootstrap this device holds the only head there is.
     */
    public suspend fun configure(
        serverUrl: String,
        bootstrapSecret: String,
    ): Unit =
        mutex.withLock {
            val at = session
            require(store.load()?.pendingSharing.isNullOrEmpty()) { "publish pending sharing changes before changing the server" }
            val vault = requireNotNull(boundary) { "no vault is bound to sync" }
            val identity =
                vault.identity()
                    ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "sync setup needs an unlocked vault")
            store.load()?.let { existing ->
                require(matches(existing, identity)) { "disconnect the other vault before configuring sync" }
            }
            val secret =
                try {
                    fromHex(bootstrapSecret.trim())
                } catch (_: IllegalArgumentException) {
                    throw ChurFailure(
                        ChurStatus.INVALID_INPUT,
                        "the bootstrap secret must be 64 hexadecimal characters",
                    )
                }
            val token = randomToken()
            val client =
                try {
                    clientFactory(serverUrl.trim()) { token }
                } catch (_: IllegalArgumentException) {
                    throw ChurFailure(ChurStatus.INVALID_INPUT, "the server address is not a sync endpoint")
                }
            try {
                client.bootstrap(identity.vaultId, secret, token, identity.enrollment, identity.initialOperation)
            } catch (failure: SyncTransportFailure) {
                // A refused bootstrap leaves whatever was configured before intact:
                // a user fixing a typo must not lose a working server over it.
                if (!_status.value.configured) {
                    shown = SyncStatus(false, null, null, false, failure.status)
                }
                throw failure
            } finally {
                client.close()
                secret.fill(0)
            }
            val state =
                SyncState(
                    serverUrl = serverUrl.trim(),
                    vaultId = identity.vaultId,
                    deviceId = identity.deviceId,
                    transportToken = token,
                    cursors = listOf(DeviceCursor(identity.deviceId, 0uL)),
                )
            store.save(state)
            owner = at
            shown = SyncStatus(true, state.serverUrl, "Connected.", false)
        }

    /**
     * Runs one pull-and-apply cycle, returning whether it completed.
     *
     * A run with no configured server is a `false`, not a failure: the host
     * schedules the engine without knowing whether the user ever connected.
     */
    public suspend fun syncNow(): Boolean =
        mutex.withLock {
            var state =
                store.load() ?: run {
                    shown = NOT_CONFIGURED
                    return false
                }
            val vault =
                boundary ?: run {
                    shown = SyncStatus(true, state.serverUrl, "The vault is not open.", false)
                    return false
                }
            vault.identity()?.let { identity ->
                if (!matches(state, identity)) {
                    // The periodic run reaches a decoy session too, which sees
                    // no sibling's server, as in [refresh].
                    shown = NOT_CONFIGURED
                    return false
                }
            }
            shown = SyncStatus(true, state.serverUrl, "Syncing…", true)
            val client = clientFactory(state.serverUrl) { state.transportToken }
            try {
                var backoff = INITIAL_BACKOFF_MS
                repeat(MAX_ATTEMPTS) { attempt ->
                    try {
                        state = flushPending(client, state)
                        if (vault.identity()?.let { matches(state, it) } == true) {
                            publishSourceObjects(client, state, vault)
                        }
                        val puller =
                            LockedSyncPuller(client) { vaultId, kind, stagedAtMs, record ->
                                vault.stage(vaultId, kind, stagedAtMs, record)
                            }
                        val report = puller.pullOnce(state.vaultId, state.cursors, clock())
                        fold(vault.process())
                        val shares =
                            if (vault.identity()?.let { matches(state, it) } == true) {
                                SharingPuller(client, vault).pullOnce(state.vaultId, clock())
                            } else {
                                0
                            }
                        // §5: the cursor advances only once the records are in
                        // native storage, so a dropped page is fetched again.
                        store.save(state.copy(cursors = report.cursors.ifEmpty { state.cursors }))
                        // A standing verdict means one chain is frozen, so the
                        // run does not call the vault up to date.
                        shown =
                            SyncStatus(
                                configured = true,
                                serverUrl = state.serverUrl,
                                message =
                                    listOfNotNull(
                                        "Up to date.".takeIf { integrityStop == null },
                                        "Received $shares share(s).".takeIf { shares > 0 },
                                    ).joinToString(" ").ifEmpty { null },
                                busy = false,
                            )
                        return true
                    } catch (failure: SyncTransportFailure) {
                        // §10: corruption and refusals stop the run; only the
                        // network backs off, and only up to the cap.
                        if (failure.status != ChurStatus.NETWORK_FAILURE) {
                            shown = SyncStatus(true, state.serverUrl, null, false, failure.status)
                            return false
                        }
                        if (attempt == MAX_ATTEMPTS - 1) {
                            shown = SyncStatus(true, state.serverUrl, null, false, failure.status)
                            return false
                        }
                        shown = SyncStatus(true, state.serverUrl, "The server is unreachable; retrying.", true)
                        sleep(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                }
                false
            } finally {
                client.close()
                if (shown.busy) shown = shown.copy(busy = false)
            }
        }

    /** Publishes an already prepared grant in server dependency order. */
    public suspend fun publishShare(share: PreparedShare): Unit =
        mutex.withLock {
            val existing = requireConfiguredState()
            requireMatchingIdentity(existing)
            val state = existing.copy(
                pendingSharing = existing.pendingSharing +
                    if (existing.pendingSharing.any { it.share?.let { queued -> sameShare(queued, share) } == true }) {
                        emptyList()
                    } else {
                        listOf(PendingSharingPublication(share = share))
                    },
            )
            store.save(state)
            shown = SyncStatus(true, state.serverUrl, "Publishing access…", true)
            val client = clientFactory(state.serverUrl) { state.transportToken }
            try {
                flushPending(client, state)
                publishSourceObjects(client, state, requireNotNull(boundary))
                shown = SyncStatus(true, state.serverUrl, "Access published.", false)
            } catch (failure: Exception) {
                shown = SyncStatus(true, state.serverUrl, "Sharing changes need upload.", false)
                throw failure
            } finally {
                client.close()
            }
        }

    private suspend fun publishSourceObjects(client: SyncClient, state: SyncState, vault: SyncVaultBoundary) {
        val collectionId = vault.sourceCollectionId() ?: return
        val pusher = SourceObjectPusher(client)
        var after = ByteArray(16)
        while (true) {
            val page = vault.sourcePage(collectionId, after) ?: return
            if (page.isEmpty()) return
            for (source in page) {
                val publication = SourceObjectPublication(
                    source.collectionId, source.objectId, source.storeId, source.length, source.fullSha256,
                )
                pusher.push(
                    state.vaultId,
                    publication,
                    read = { objectId, offset, maxBytes ->
                        val range = vault.sourceRange(objectId, offset, maxBytes)
                            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the source vault locked during upload")
                        SourceObjectRange(range.bytes, range.sha256)
                    },
                    author = {
                        val authored = vault.sourceAuthor(source)
                            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the source vault locked before signing")
                        SourceObjectOperations(authored.createOperation, authored.commitOperation)
                    },
                )
            }
            after = page.last().objectId
        }
    }

    /** Publishes one already prepared forward-only revocation batch. */
    public suspend fun publishRevocation(revocation: PreparedShareRevocation): Unit =
        mutex.withLock {
            val existing = requireConfiguredState()
            requireMatchingIdentity(existing)
            val state = existing.copy(
                pendingSharing = existing.pendingSharing +
                    if (existing.pendingSharing.any { it.revocation?.let { queued -> sameRevocation(queued, revocation) } == true }) {
                        emptyList()
                    } else {
                        listOf(PendingSharingPublication(revocation = revocation))
                    },
            )
            store.save(state)
            shown = SyncStatus(true, state.serverUrl, "Publishing revocation…", true)
            val client = clientFactory(state.serverUrl) { state.transportToken }
            try {
                flushPending(client, state)
                shown = SyncStatus(true, state.serverUrl, "Revocation published.", false)
            } catch (failure: Exception) {
                shown = SyncStatus(true, state.serverUrl, "Sharing changes need upload.", false)
                throw failure
            } finally {
                client.close()
            }
        }

    private suspend fun requireConfiguredState(): SyncState =
        store.load() ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "sync is not configured")

    /** Checks the active vault before a native grant or revocation changes local state. */
    public suspend fun requireActiveVault(): Unit = mutex.withLock {
        requireMatchingIdentity(requireConfiguredState())
    }

    private suspend fun requireMatchingIdentity(state: SyncState) {
        val identity = boundary?.identity()
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "sharing needs an unlocked vault")
        if (!matches(state, identity)) {
            throw ChurFailure(ChurStatus.CONFLICT, "configured sync belongs to another vault")
        }
    }

    private fun matches(state: SyncState, identity: SharingIdentity): Boolean =
        state.vaultId.contentEquals(identity.vaultId) && state.deviceId.contentEquals(identity.deviceId)

    private fun sameShare(first: PreparedShare, second: PreparedShare): Boolean =
        first.membership.contentEquals(second.membership) &&
            first.membershipOperation.contentEquals(second.membershipOperation) &&
            first.grant.contentEquals(second.grant) &&
            first.grantOperation.contentEquals(second.grantOperation)

    private fun sameRevocation(first: PreparedShareRevocation, second: PreparedShareRevocation): Boolean =
        first.membership.contentEquals(second.membership) &&
            first.membershipOperation.contentEquals(second.membershipOperation) &&
            first.rotationComplete == second.rotationComplete &&
            first.rotationOperations.size == second.rotationOperations.size &&
            first.rotationOperations.zip(second.rotationOperations).all { (left, right) -> left.contentEquals(right) } &&
            first.grants.size == second.grants.size &&
            first.grants.zip(second.grants).all { (left, right) ->
                left.grant.contentEquals(right.grant) && left.operation.contentEquals(right.operation)
            }

    private suspend fun flushPending(client: SyncClient, initial: SyncState): SyncState {
        var state = initial
        val pusher = SharingPusher(client)
        while (state.pendingSharing.isNotEmpty()) {
            val pending = state.pendingSharing.first()
            pending.share?.let { pusher.push(state.vaultId, it) }
            pending.revocation?.let { pusher.pushRevocation(state.vaultId, it) }
            state = state.copy(pendingSharing = state.pendingSharing.drop(1))
            store.save(state)
        }
        return state
    }

    /** Forgets the server entirely, which the settings entry offers. */
    public suspend fun disconnect(): Unit =
        mutex.withLock {
            require(store.load()?.pendingSharing.isNullOrEmpty()) { "publish pending sharing changes before disconnecting" }
            store.clear()
            integrityStop = null
            integrityAcknowledged = false
            shown = NOT_CONFIGURED
        }

    /**
     * Applies what the locked puller staged, which an unlock does, §7.
     *
     * Records staged while the vault was locked are the usual case, so the
     * pass goes through the engine rather than straight to the vault: a fork
     * or rollback among them is kept as the verdict a run would keep.
     */
    public suspend fun applyStaged(): Unit =
        mutex.withLock {
            fold(boundary?.process())
        }

    /**
     * Records that the user saw the verdict, which the banner's action does.
     *
     * `ROLLBACK_PROTECTION.md` §4 moves each detected fork to `acknowledged`
     * in the catalog, so the next unlock does not announce it again, and keeps
     * every chain frozen: the verdict and the banner stay.
     */
    public suspend fun acknowledgeIntegrityStop(): Unit =
        mutex.withLock {
            if (integrityStop == null || boundary?.acknowledgeForks() != true) return@withLock
            integrityAcknowledged = true
            shown = _status.value
        }

    /**
     * Keeps a fork or rollback verdict from one pass over the inbox.
     *
     * The native engine already froze that chain and dropped the record, and
     * the report is the only place the verdict reaches this layer, so a pass
     * whose report is ignored would read as a clean one. `ERROR_MODEL.md`
     * forbids retrying either status, and nothing here does.
     *
     * A fork verdict always leaves its chain in the catalog's fork state:
     * `detected` when this pass found the fork, `acknowledged` when the pass
     * only refused one more record of a chain the user already acknowledged.
     * The catalog decides which, so a frozen chain the server keeps serving
     * does not bring back a report the user dismissed.
     */
    private suspend fun fold(report: SyncProcessReport?) {
        val verdict = report?.let { ChurStatus.fromValue(it.firstRejection) }?.takeIf { it in LOCAL_VERDICTS } ?: return
        val forks = if (verdict == ChurStatus.SYNC_CHAIN_FORK) boundary?.forkState() else null
        if (!raise(forks)) {
            integrityStop = verdict
            integrityAcknowledged = false
        }
        shown = _status.value
    }

    /**
     * Raises the verdict from the catalog's fork state, returning whether that
     * state holds a fork at all.
     *
     * A fork the user has not acknowledged makes the verdict unacknowledged;
     * one they have acknowledged starts no new report. It never lowers a
     * verdict: a rollback leaves nothing in the catalog, and only [disconnect]
     * clears one.
     */
    private fun raise(forks: SyncForkState?): Boolean {
        if (forks == null || forks.detected + forks.acknowledged == 0L) return false
        if (forks.detected > 0) {
            integrityAcknowledged = false
        } else if (integrityStop == null) {
            integrityAcknowledged = true
        }
        integrityStop = integrityStop ?: ChurStatus.SYNC_CHAIN_FORK
        return true
    }

    internal companion object {
        /** §10: bounded means both a growth cap and an attempt cap. */
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_ATTEMPTS = 6
    }
}

/** The status before any server is configured, shared so it reads as one state. */
private val NOT_CONFIGURED = SyncStatus(configured = false, serverUrl = null, message = null, busy = false)

/** The two security verdicts of `ROLLBACK_PROTECTION.md` §4, which only this device reaches. */
internal val LOCAL_VERDICTS: Set<ChurStatus> = setOf(ChurStatus.SYNC_CHAIN_FORK, ChurStatus.SYNC_HEAD_ROLLBACK)
