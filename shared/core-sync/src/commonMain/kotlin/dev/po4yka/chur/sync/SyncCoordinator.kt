package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.PreparedShare
import dev.po4yka.chur.ffi.PreparedShareRevocation
import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SharedReceivePlan
import dev.po4yka.chur.ffi.SharedSourceObject
import dev.po4yka.chur.ffi.SharedSourceRange
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.ffi.SyncRecordKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** One bounded line about the last run, carrying counts and status names. */
    public val message: String?,
    /** Whether a run is in progress. */
    public val busy: Boolean,
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

    /** What a sync surface shows. */
    public val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Binds the vault the engine stages into and applies on.
     *
     * The composition root calls this once; a `null` unbinds, which is what a
     * host shutdown does.
     */
    public fun bind(vault: SyncVaultBoundary?) {
        boundary = vault
    }

    /** Loads the saved state into the visible status, which a host start does. */
    public suspend fun refresh(): Unit =
        mutex.withLock {
            val state = store.load()
            _status.value =
                if (state == null) {
                    NOT_CONFIGURED
                } else {
                    SyncStatus(
                        configured = true,
                        serverUrl = state.serverUrl,
                        message = if (state.pendingSharing.isEmpty()) null else "Sharing changes need upload.",
                        busy = false,
                    )
                }
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
                    _status.value = SyncStatus(false, null, failure.status.name, false)
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
            _status.value = SyncStatus(true, state.serverUrl, "Connected.", false)
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
                    _status.value = NOT_CONFIGURED
                    return false
                }
            val vault =
                boundary ?: run {
                    _status.value = SyncStatus(true, state.serverUrl, "The vault is not open.", false)
                    return false
                }
            vault.identity()?.let { identity ->
                if (!matches(state, identity)) {
                    _status.value = SyncStatus(true, state.serverUrl, "Configured sync belongs to another vault.", false)
                    return false
                }
            }
            _status.value = SyncStatus(true, state.serverUrl, "Syncing…", true)
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
                        vault.process()
                        val shares =
                            if (vault.identity()?.let { matches(state, it) } == true) {
                                SharingPuller(client, vault).pullOnce(state.vaultId, clock())
                            } else {
                                0
                            }
                        // §5: the cursor advances only once the records are in
                        // native storage, so a dropped page is fetched again.
                        store.save(state.copy(cursors = report.cursors.ifEmpty { state.cursors }))
                        _status.value =
                            SyncStatus(
                                configured = true,
                                serverUrl = state.serverUrl,
                                message =
                                    "Synced ${report.operations} operation(s) " +
                                        "and ${report.checkpoints} checkpoint(s)." +
                                        (if (shares == 0) "" else " Received $shares share(s)."),
                                busy = false,
                            )
                        return true
                    } catch (failure: SyncTransportFailure) {
                        // §10: corruption and refusals stop the run; only the
                        // network backs off, and only up to the cap.
                        if (failure.status != ChurStatus.NETWORK_FAILURE) {
                            _status.value = SyncStatus(true, state.serverUrl, failure.status.name, false)
                            return false
                        }
                        if (attempt == MAX_ATTEMPTS - 1) {
                            _status.value =
                                SyncStatus(
                                    true,
                                    state.serverUrl,
                                    "${failure.status.name} after $MAX_ATTEMPTS attempt(s).",
                                    false,
                                )
                            return false
                        }
                        _status.value = SyncStatus(true, state.serverUrl, "The server is unreachable; retrying.", true)
                        sleep(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                }
                false
            } finally {
                client.close()
                if (_status.value.busy) _status.value = _status.value.copy(busy = false)
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
            _status.value = SyncStatus(true, state.serverUrl, "Publishing access…", true)
            val client = clientFactory(state.serverUrl) { state.transportToken }
            try {
                flushPending(client, state)
                publishSourceObjects(client, state, requireNotNull(boundary))
                _status.value = SyncStatus(true, state.serverUrl, "Access published.", false)
            } catch (failure: Exception) {
                _status.value = SyncStatus(true, state.serverUrl, "Sharing changes need upload.", false)
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
            _status.value = SyncStatus(true, state.serverUrl, "Publishing revocation…", true)
            val client = clientFactory(state.serverUrl) { state.transportToken }
            try {
                flushPending(client, state)
                _status.value = SyncStatus(true, state.serverUrl, "Revocation published.", false)
            } catch (failure: Exception) {
                _status.value = SyncStatus(true, state.serverUrl, "Sharing changes need upload.", false)
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
            _status.value = NOT_CONFIGURED
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
