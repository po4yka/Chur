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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** Authenticated native device heads, or `null` while the vault is locked. */
    public suspend fun acceptedHeads(): List<DeviceCursor>? = null

    public suspend fun ownSyncCollections(): List<ByteArray>? = emptyList()
    public suspend fun ownSyncOperations(after: ULong): List<dev.po4yka.chur.ffi.OwnSyncOperation>? = emptyList()
    public suspend fun ownSyncPlan(collectionId: ByteArray): SharedReceivePlan? = null
    public suspend fun ownSyncAuthor(source: SharedSourceObject): SharedSourceObject? = null

    public suspend fun prepareDevicePairing(): dev.po4yka.chur.ffi.DevicePairingRequest? = null
    public suspend fun inspectDevicePairing(request: ByteArray): dev.po4yka.chur.ffi.DevicePairingPreview? = null
    public suspend fun approveDevicePairing(request: ByteArray, serverUrl: String, token: ByteArray, nowMs: Long): dev.po4yka.chur.ffi.PreparedDevicePairing? = null
    public suspend fun startDevicePairing(approval: ByteArray): dev.po4yka.chur.ffi.DevicePairingCredentials? = null
    public suspend fun finishDevicePairing(nowMs: Long): ByteArray? = null
    public suspend fun currentDeviceCheckpoint(nowMs: Long): ByteArray? = null
    public suspend fun pairedDevices(): List<dev.po4yka.chur.ffi.PairedDevice>? = null
    public suspend fun prepareDeviceRevocation(deviceId: ByteArray, nowMs: Long): dev.po4yka.chur.ffi.PreparedDeviceRevocation? = null

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

    /** Requests a key only for an authenticated pending object; null means already ready. */
    public suspend fun objectKeyRequest(collectionId: ByteArray, objectId: ByteArray): ByteArray? = null

    public suspend fun objectKeyIssue(prekey: ByteArray): ByteArray? = null

    public suspend fun objectKeyAccept(packageBytes: ByteArray, grant: ByteArray): Boolean = false

    public suspend fun outboundCollectionOperations(after: ULong): List<dev.po4yka.chur.ffi.CollectionOutbound>? = emptyList()

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
     * completes and this stays set: for its own vault it clears only on
     * [SyncCoordinator.disconnect]. The verdict is local, `ERROR_MODEL.md`, so
     * it comes from the native process report and never from a server code.
     * That report puts a verdict ahead of any other rejection in the same pass,
     * so junk the server stages beside the forked record cannot hide it.
     * The catalog keeps the fork state itself, and [SyncCoordinator.refresh]
     * raises this from it at each unlock, so a fork outlives a restart. A
     * rollback leaves no state there, so it lasts for the process.
     *
     * Either verdict belongs to the vault whose pass reported it. Only that
     * vault's status carries it, and a vault that takes over the saved state
     * starts without it, so no other identity, such as a decoy, can read one
     * from here, `DECOY_VAULT.md` §10.
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
 * from authenticated native heads after unlocked validation.
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

    /** The vault whose pass reported [integrityStop], which [scopeVerdict] keeps it to. */
    private var integrityVault: ByteArray? = null

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

    /**
     * Sets [status] for session [at], unless [endSession] ended it before or
     * during the write.
     *
     * The standing verdict goes only into the status of the session that owns
     * the saved state, and [scopeVerdict] keeps it to that vault, so another
     * identity's status never carries it, `DECOY_VAULT.md` §10.
     */
    private fun publish(at: Any, value: SyncStatus) {
        if (session !== at) return
        _status.value =
            if (owner === at) {
                value.copy(integrityStop = integrityStop, integrityAcknowledged = integrityAcknowledged)
            } else {
                value
            }
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
     * server is saved, because the file is one per device. That refusal reads
     * as an ordinary failed setup, and `DECOY_VAULT.md` §5 accepts what still
     * differs, such as its timing and, on iOS, the local network alert. It
     * returns whether the open vault has a server, which is what decides the
     * unlock's pull.
     *
     * The open vault's own fork state comes with it, `ROLLBACK_PROTECTION.md`
     * §4: the pass that found a fork reported it once, and the catalog is what
     * still knows after a restart. Another identity's status carries no
     * verdict, and the owner's verdict stays for its next refresh.
     */
    public suspend fun refresh(): Boolean =
        mutex.withLock {
            val at = session
            val state = store.load()
            val identity = if (state == null) null else boundary?.identity()
            val own = state != null && identity != null && matches(state, identity)
            if (own) {
                scopeVerdict(identity.vaultId)
                raise(boundary?.forkState())
            }
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
     *
     * The saved state is one file per device, so a setup is refused while it
     * holds another identity's server or sharing changes that wait for
     * upload. The refusal comes after the address and the secret are
     * validated, and it is the [ChurStatus.AUTHENTICATION_FAILED] a server
     * gives a bootstrap secret it does not accept, so another identity, such
     * as a decoy, reads the copy of an ordinary failed setup. It sends no
     * request, so it answers sooner than a real refusal and shows no iOS local
     * network alert; `DECOY_VAULT.md` §5 accepts these and the other signals
     * that remain.
     */
    public suspend fun configure(
        serverUrl: String,
        bootstrapSecret: String,
    ): Unit =
        mutex.withLock {
            val at = session
            val existing = store.load()
            val vault = requireNotNull(boundary) { "no vault is bound to sync" }
            val identity =
                vault.identity()
                    ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "sync setup needs an unlocked vault")
            // The size is checked here and not only by the client, so a wrong
            // entry gets this line before the refusal below, in every identity.
            val secret =
                try {
                    fromHex(bootstrapSecret.trim()).also { bytes ->
                        if (bytes.size != SECRET_BYTES) {
                            bytes.fill(0)
                            throw IllegalArgumentException()
                        }
                    }
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
                if (existing != null && (existing.pendingSharing.isNotEmpty() || !matches(existing, identity))) {
                    throw SyncTransportFailure(ChurStatus.AUTHENTICATION_FAILED, "the saved sync state blocks this setup")
                }
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
            scopeVerdict(identity.vaultId)
            owner = at
            shown = SyncStatus(true, state.serverUrl, "Connected.", false)
        }

    public suspend fun prepareDevicePairing(): dev.po4yka.chur.ffi.DevicePairingRequest = pairingLocked {
        if (store.load() != null) throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "saved sync state blocks device setup")
        pairingVault().prepareDevicePairing()
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device setup needs an unlocked vault")
    }

    public suspend fun inspectDevicePairing(request: ByteArray): dev.po4yka.chur.ffi.DevicePairingPreview = pairingLocked {
        requireMatchingIdentity(requireConfiguredState())
        pairingVault().inspectDevicePairing(request)
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device approval needs an unlocked vault")
    }

    public suspend fun approveDevicePairing(request: ByteArray, fingerprint: String): ByteArray = pairingLocked {
        var state = requireConfiguredState()
        requireMatchingIdentity(state)
        val vault = pairingVault()
        val preview = vault.inspectDevicePairing(request)
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device approval needs an unlocked vault")
        if (!preview.vaultId.contentEquals(state.vaultId) || preview.fingerprint != fingerprint) {
            throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "the approved device fingerprint changed")
        }
        val proposedToken = randomToken()
        val prepared = try {
            vault.approveDevicePairing(request, state.serverUrl, proposedToken, clock())
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device approval needs an unlocked vault")
        } finally { proposedToken.fill(0) }
        var client: SyncClient? = null
        shown = SyncStatus(true, state.serverUrl, "Approving device…", true)
        try {
            val transport = clientFactory(state.serverUrl) { state.transportToken }.also { client = it }
            // Every own-outbox writer recovers native commits before advancing
            // the cursor. Fresh builds cannot acknowledge an unsubmitted revoke.
            state = recoverDeviceRevocations(state)
            state = flushOwnPrefixBefore(transport, state, vault, prepared.operation) { current, record ->
                current.pendingDeviceRevocations.firstOrNull { it.operation.contentEquals(record) }?.let { pending ->
                    transport.putCheckpoint(current.vaultId, pending.checkpoint)
                    transport.revoke(current.vaultId, pending.revocation, pending.operation)
                }
            }
            transport.putCheckpoint(state.vaultId, prepared.checkpoint)
            transport.enroll(state.vaultId, prepared.transportToken, prepared.enrollment, prepared.operation)
            state = flushPending(transport, state)
            state = flushOwnOperations(transport, state, vault)
            val checkpoint = vault.currentDeviceCheckpoint(clock())
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device approval checkpoint was locked")
            transport.putCheckpoint(state.vaultId, checkpoint)
            shown = SyncStatus(true, state.serverUrl, "Device approved. Save the approval file.", false)
            prepared.approval
        } finally {
            client?.close()
            prepared.transportToken.fill(0)
            if (shown.busy) shown = shown.copy(busy = false)
        }
    }

    public suspend fun pairedDevices(): List<dev.po4yka.chur.ffi.PairedDevice> = pairingLocked {
        requireMatchingIdentity(requireConfiguredState())
        pairingVault().pairedDevices()
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device management needs an unlocked vault")
    }

    /** Native stores the signed revocation before the durable transport queue is updated. */
    public suspend fun revokePairedDevice(deviceId: ByteArray, expectedFingerprint: String): Unit = pairingLocked {
        val existing = requireConfiguredState()
        requireMatchingIdentity(existing)
        val vault = pairingVault()
        val peer = vault.pairedDevices()?.singleOrNull { it.deviceId.contentEquals(deviceId) }
            ?: throw ChurFailure(ChurStatus.NOT_FOUND, "the paired device is absent")
        if (peer.current || peer.fingerprint != expectedFingerprint) {
            throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "the device revocation fingerprint changed")
        }
        val prepared = vault.prepareDeviceRevocation(deviceId, clock())
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device management needs an unlocked vault")
        val state = existing.copy(pendingDeviceRevocations = existing.pendingDeviceRevocations +
            if (existing.pendingDeviceRevocations.any { it.operation.contentEquals(prepared.operation) }) emptyList()
            else listOf(prepared))
        store.save(state)
        shown = SyncStatus(true, state.serverUrl, "Revoking device…", true)
        val client = clientFactory(state.serverUrl) { state.transportToken }
        try {
            flushPending(client, state)
            shown = SyncStatus(true, state.serverUrl, "Device revoked.", false)
        } finally {
            client.close()
            if (shown.busy) shown = shown.copy(message = "Device revocation needs upload.", busy = false)
        }
    }

    /** Authenticates the approval before the host asks for access to its network address. */
    public suspend fun pairingApprovalServer(approval: ByteArray): String = pairingLocked {
        val credentials = pairingVault().startDevicePairing(approval)
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device setup needs an unlocked vault")
        try {
            // Endpoint validation creates no network request.
            clientFactory(credentials.serverUrl) { credentials.transportToken }.close()
            credentials.serverUrl
        } finally { credentials.transportToken.fill(0) }
    }

    /** False means that signed history is still absent and the same file can be retried. */
    public suspend fun acceptPairingApproval(approval: ByteArray): Boolean = pairingLocked {
        val at = session
        val vault = pairingVault()
        val credentials = vault.startDevicePairing(approval)
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device setup needs an unlocked vault")
        var client: SyncClient? = null
        try {
            val transport = clientFactory(credentials.serverUrl) { credentials.transportToken }.also { client = it }
            val existing = store.load()
            if (existing != null && (!existing.vaultId.contentEquals(credentials.vaultId) ||
                    !existing.deviceId.contentEquals(credentials.deviceId) || existing.serverUrl != credentials.serverUrl ||
                    !existing.transportToken.contentEquals(credentials.transportToken) || existing.pendingSharing.isNotEmpty() ||
                    existing.pendingDeviceRevocations.isNotEmpty())
            ) throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "saved sync state blocks device setup")
            repeat(256) {
                val device = try { vault.finishDevicePairing(clock()) } catch (failure: ChurFailure) {
                    if (failure.status == ChurStatus.RECOVERY_REQUIRED) null else throw failure
                }
                if (device != null) {
                    if (!device.contentEquals(credentials.deviceId)) {
                        throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "installed device identity differs")
                    }
                    val heads = vault.acceptedHeads()
                        ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device setup was locked")
                    val state = existing?.copy(cursors = heads) ?: SyncState(
                        credentials.serverUrl, credentials.vaultId, credentials.deviceId,
                        credentials.transportToken.copyOf(), heads,
                    )
                    store.save(state)
                    scopeVerdict(credentials.vaultId)
                    owner = at
                    shown = SyncStatus(true, state.serverUrl, "Device connected.", false)
                    return@pairingLocked true
                }
                val before = vault.acceptedHeads()
                    ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device setup was locked")
                for (cursor in before) {
                    for (record in transport.operations(credentials.vaultId, cursor.deviceId, cursor.after)) {
                        vault.stage(credentials.vaultId, SyncRecordKind.OPERATION, clock(), record)
                    }
                }
                // The native approval pins the exact bootstrap checkpoint.
                // Do not replace it with a server-selected checkpoint here.
                val report = vault.process()
                    ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device setup was locked")
                if (report.firstRejection != 0) {
                    throw ChurFailure(ChurStatus.fromValue(report.firstRejection), "device setup history was rejected")
                }
                val after = vault.acceptedHeads()
                    ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device setup was locked")
                if (before.associate { it.deviceId.toHex() to it.after } == after.associate { it.deviceId.toHex() to it.after }) {
                    return@pairingLocked false
                }
            }
            false
        } finally {
            client?.close()
            credentials.transportToken.fill(0)
        }
    }

    private fun pairingVault(): SyncVaultBoundary = boundary
        ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "sync has no vault boundary")

    private suspend fun <T> pairingLocked(body: suspend () -> T): T =
        withContext(Dispatchers.Default) { mutex.withLock { body() } }

    /**
     * Runs one pull-and-apply cycle, returning whether it completed.
     *
     * A run with no configured server is a `false`, not a failure: the host
     * schedules the engine without knowing whether the user ever connected.
     */
    public suspend fun syncNow(): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            var state =
                store.load() ?: run {
                    shown = NOT_CONFIGURED
                    return@withLock false
                }
            val vault =
                boundary ?: run {
                    shown = SyncStatus(true, state.serverUrl, "The vault is not open.", false)
                    return@withLock false
                }
            vault.identity()?.let { identity ->
                if (!matches(state, identity)) {
                    // The periodic run reaches a decoy session too, which sees
                    // no sibling's server, as in [refresh].
                    shown = NOT_CONFIGURED
                    return@withLock false
                }
            }
            shown = SyncStatus(true, state.serverUrl, "Syncing…", true)
            val client = clientFactory(state.serverUrl) { state.transportToken }
            try {
                var backoff = INITIAL_BACKOFF_MS
                repeat(MAX_ATTEMPTS) { attempt ->
                    try {
                        state = flushPending(client, state)
                        acceptedCursors(state, vault)?.let { state = state.copy(cursors = it) }
                        val ownSync = OwnDeviceSynchronizer(client, vault)
                        var publicationFailure: ChurStatus? = null
                        if (vault.identity()?.let { matches(state, it) } == true) {
                            state = flushOwnOperations(client, state, vault)
                            // Previously authored tombstones must leave even if a source container is damaged.
                            state = flushCollectionOperations(client, state, vault)
                            try {
                                ownSync.publishMedia(state.vaultId) {
                                    state = flushOwnOperations(client, state, vault)
                                }
                            } catch (failure: ChurFailure) {
                                if (failure.status !in SOURCE_FILE_FAILURES) throw failure
                                // A local source failure does not invalidate independent incoming records.
                                publicationFailure = failure.status
                            }
                            try {
                                publishSourceObjects(client, state, vault)
                            } catch (failure: ChurFailure) {
                                if (failure.status !in SOURCE_FILE_FAILURES) throw failure
                                publicationFailure = publicationFailure ?: failure.status
                            }
                            state = flushCollectionOperations(client, state, vault)
                            serviceObjectKeyRequests(client, state, vault)
                        }
                        val puller =
                            LockedSyncPuller(client) { vaultId, kind, stagedAtMs, record ->
                                vault.stage(vaultId, kind, stagedAtMs, record)
                            }
                        val pullReport = puller.pullOnce(state.vaultId, state.cursors, clock())
                        val processingVault = vault.identity()?.vaultId
                        val processReport = vault.process()
                        fold(processingVault, processReport)
                        val sharingPuller = SharingPuller(client, vault)
                        val shares =
                            if (vault.identity()?.let { matches(state, it) } == true) {
                                ownSync.receive(state.vaultId, clock())
                                sharingPuller.pullOnce(state.vaultId, clock())
                            } else {
                                0
                            }
                        // Staging can evict and expire records. A locked or
                        // rejected download must remain fetchable from the
                        // last head that native validation accepted.
                        acceptedCursors(state, vault)?.let { state = state.copy(cursors = it) }
                        store.save(state)
                        // A standing verdict means one chain is frozen, so the
                        // run does not call the vault up to date.
                        shown =
                            SyncStatus(
                                configured = true,
                                serverUrl = state.serverUrl,
                                message =
                                    listOfNotNull(
                                        "Up to date.".takeIf {
                                            integrityStop == null && publicationFailure == null && !pullReport.hasMoreOperations && !ownSync.hasMoreDownloads && processReport != null && sharingPuller.pendingObjectKeys + ownSync.pendingObjectKeys + sharingPuller.unavailableObjects + ownSync.unavailableObjects == 0 && ownSync.pendingOperations == 0L
                                        },
                                        "More changes need sync. Sync will continue on the next run.".takeIf {
                                            integrityStop == null && (pullReport.hasMoreOperations || ownSync.hasMoreDownloads)
                                        },
                                        "Some local files could not upload. Sync will retry.".takeIf { publicationFailure != null },
                                        "Downloaded encrypted records. Unlock to apply.".takeIf {
                                            integrityStop == null && processReport == null
                                        },
                                        "Waiting for the source device to send object keys.".takeIf {
                                            integrityStop == null && sharingPuller.pendingObjectKeys + ownSync.pendingObjectKeys > 0
                                        },
                                        "Some encrypted files are unavailable. Sync will retry.".takeIf {
                                            integrityStop == null && sharingPuller.unavailableObjects + ownSync.unavailableObjects > 0
                                        },
                                        "Some changes are waiting for missing dependencies.".takeIf {
                                            integrityStop == null && ownSync.pendingOperations > 0
                                        },
                                        "Received $shares share(s).".takeIf { shares > 0 },
                                    ).joinToString(" ").ifEmpty { null },
                                busy = false,
                                failure = publicationFailure,
                            )
                        return@withLock publicationFailure == null
                    } catch (failure: SyncTransportFailure) {
                        // §10: corruption and refusals stop the run; only the
                        // network backs off, and only up to the cap.
                        if (failure.status != ChurStatus.NETWORK_FAILURE) {
                            shown = SyncStatus(true, state.serverUrl, null, false, failure.status)
                            return@withLock false
                        }
                        if (attempt == MAX_ATTEMPTS - 1) {
                            shown = SyncStatus(true, state.serverUrl, null, false, failure.status)
                            return@withLock false
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

    // All own-cursor writers recover native revocations before an ACK can
    // cover them. A committed cursor therefore covers their revoke endpoint.
    private suspend fun flushOwnOperations(client: SyncClient, state: SyncState, vault: SyncVaultBoundary): SyncState {
        var latest = state
        OwnDeviceSynchronizer(client, vault).publishOperations(state.vaultId, state.ownPushCursor) { sequence ->
            val next = latest.copy(ownPushCursor = sequence)
            store.save(next)
            latest = next
        }
        return latest
    }

    /** Publishes the checkpoint's ordinary prefix without passing its control operation. */
    private suspend fun flushOwnPrefixBefore(
        client: SyncClient,
        initial: SyncState,
        vault: SyncVaultBoundary,
        stop: ByteArray,
        beforeOperation: suspend (SyncState, ByteArray) -> Unit = { _, _ -> },
    ): SyncState {
        // A manual retry can reference an already acknowledged operation. Find
        // the native boundary before sending any newer records in that case.
        var search = initial.ownPushCursor
        var stopSequence: ULong? = null
        while (stopSequence == null) {
            val page = vault.ownSyncOperations(search)
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device checkpoint prefix was locked")
            if (page.isEmpty()) return initial
            for (operation in page) {
                require(operation.sequence > search) { "the native own operation page did not advance" }
                search = operation.sequence
                if (operation.record.contentEquals(stop)) { stopSequence = operation.sequence; break }
            }
        }
        var state = initial
        while (true) {
            val page = vault.ownSyncOperations(state.ownPushCursor)
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device checkpoint prefix was locked")
            check(page.isNotEmpty()) { "the native checkpoint prefix disappeared" }
            for (operation in page) {
                require(operation.sequence > state.ownPushCursor) { "the native own operation page did not advance" }
                if (operation.sequence == stopSequence) {
                    check(operation.record.contentEquals(stop)) { "the native control operation changed" }
                    return state
                }
                check(operation.sequence < stopSequence) { "the native control operation was skipped" }
                beforeOperation(state, operation.record)
                client.putOperation(state.vaultId, operation.record)
                state = state.copy(ownPushCursor = operation.sequence)
                store.save(state)
            }
        }
    }

    private suspend fun flushCollectionOperations(client: SyncClient, state: SyncState, vault: SyncVaultBoundary): SyncState =
        CollectionOutboxPusher(client, store).flush(state) { vault.outboundCollectionOperations(it) }

    private suspend fun serviceObjectKeyRequests(client: SyncClient, state: SyncState, vault: SyncVaultBoundary): Unit =
        respondToObjectKeyRequests(client, state.vaultId, vault::objectKeyIssue)

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

    /** Restores native commits lost before the host transport queue was saved. */
    private suspend fun recoverDeviceRevocations(initial: SyncState): SyncState {
        val vault = boundary ?: return initial
        if (vault.identity()?.let { matches(initial, it) } != true) return initial
        val devices = vault.pairedDevices()?.filter { it.retryAvailable && !it.current } ?: return initial
        if (devices.isEmpty()) return initial
        val candidates = devices.map { device ->
            vault.prepareDeviceRevocation(device.deviceId, clock())
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device revocation recovery needs an unlocked vault")
        }.toMutableList()
        val ordered = mutableListOf<dev.po4yka.chur.ffi.PreparedDeviceRevocation>()
        var cursor = initial.ownPushCursor
        while (candidates.isNotEmpty()) {
            val page = vault.ownSyncOperations(cursor)
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device revocation recovery was locked")
            if (page.isEmpty()) break
            for (operation in page) {
                require(operation.sequence > cursor) { "the native own operation page did not advance" }
                cursor = operation.sequence
                val index = candidates.indexOfFirst { it.operation.contentEquals(operation.record) }
                if (index >= 0) ordered.add(candidates.removeAt(index))
            }
        }
        if (ordered.none { recovered ->
                initial.pendingDeviceRevocations.none { it.operation.contentEquals(recovered.operation) }
            }
        ) return initial
        // Preserve ordinary-chain order if a later revocation was already queued.
        val state = initial.copy(pendingDeviceRevocations = ordered + initial.pendingDeviceRevocations.filter { pending ->
            ordered.none { it.operation.contentEquals(pending.operation) }
        })
        store.save(state)
        return state
    }

    private suspend fun flushPending(client: SyncClient, initial: SyncState): SyncState {
        // Native preparation is durable before the separate host-state save.
        // Recover it before HTTP and before syncNow can advance ownPushCursor.
        var state = recoverDeviceRevocations(initial)
        val pusher = SharingPusher(client)
        val hadDeviceRevocations = state.pendingDeviceRevocations.isNotEmpty()
        // Retain this durable work until the final checkpoint is acknowledged.
        // Cursor saves below must keep it retryable across a crash or HTTP loss.
        for (pending in state.pendingDeviceRevocations) {
            state = flushOwnPrefixBefore(client, state, pairingVault(), pending.operation)
            client.putCheckpoint(state.vaultId, pending.checkpoint)
            client.revoke(state.vaultId, pending.revocation, pending.operation)
        }
        if (hadDeviceRevocations) {
            val vault = pairingVault()
            state = flushOwnOperations(client, state, vault)
            val checkpoint = vault.currentDeviceCheckpoint(clock())
                ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "device revocation checkpoint was locked")
            client.putCheckpoint(state.vaultId, checkpoint)
            state = state.copy(pendingDeviceRevocations = emptyList())
            store.save(state)
        }
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
            val pending = store.load()
            require(pending?.pendingSharing.isNullOrEmpty() && pending?.pendingDeviceRevocations.isNullOrEmpty()) {
                "publish pending access changes before disconnecting"
            }
            store.clear()
            integrityStop = null
            integrityAcknowledged = false
            integrityVault = null
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
            val vault = boundary ?: return@withLock
            fold(vault.identity()?.vaultId, vault.process())
            val state = store.load() ?: return@withLock
            acceptedCursors(state, vault)?.let { store.save(state.copy(cursors = it)) }
        }

    private suspend fun acceptedCursors(state: SyncState, vault: SyncVaultBoundary): List<DeviceCursor>? {
        if (vault.identity()?.let { matches(state, it) } != true) return null
        val heads = vault.acceptedHeads() ?: return null
        // A lock or identity change between native calls must not associate
        // another vault's accepted heads with this server configuration.
        return heads.takeIf { vault.identity()?.let { matches(state, it) } == true }
    }

    /**
     * Records that the user saw the verdict, which the banner's action does.
     *
     * `ROLLBACK_PROTECTION.md` §4 moves each detected fork to `acknowledged`
     * in the catalog, so the next unlock does not announce it again, and keeps
     * every chain frozen: the verdict and the banner stay. Only the vault the
     * verdict belongs to can acknowledge it.
     */
    public suspend fun acknowledgeIntegrityStop(): Unit =
        mutex.withLock {
            val open = boundary?.identity()?.vaultId
            if (integrityStop == null || !(open contentEquals integrityVault)) return@withLock
            if (boundary?.acknowledgeForks() != true) return@withLock
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
     *
     * The verdict belongs to vault [open], whose inbox the pass processed. The
     * caller reads [open] before the pass, not after it: a lock queued behind
     * the pass takes the vault first, and a rollback leaves nothing in the
     * catalog, so a verdict dropped here is one the user is never told,
     * `ROLLBACK_PROTECTION.md` §4. Nothing suspends between the pass and
     * keeping a rollback. Only when no vault was open before the pass does
     * this read the one open after it, and with none open then either the
     * verdict has no vault to be kept for.
     */
    private suspend fun fold(
        open: ByteArray?,
        report: SyncProcessReport?,
    ) {
        val verdict = report?.let { ChurStatus.fromValue(it.firstRejection) }?.takeIf { it in LOCAL_VERDICTS } ?: return
        scopeVerdict(open ?: boundary?.identity()?.vaultId ?: return)
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
     * clears one, or [scopeVerdict] when another vault takes over the state.
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

    /**
     * Keeps the verdict to vault [vaultId], dropping one another vault's pass
     * reported.
     *
     * The saved state is one per device, so a vault can take it over without
     * a [disconnect], for example after the state file went away. A verdict
     * is about the chains of the vault that reported it, and a status that
     * carried another vault's verdict would tell this one that a sibling
     * syncs, `DECOY_VAULT.md` §10.
     */
    private fun scopeVerdict(vaultId: ByteArray) {
        if (integrityVault contentEquals vaultId) return
        integrityStop = null
        integrityAcknowledged = false
        integrityVault = vaultId
    }

    internal companion object {
        /** §10: bounded means both a growth cap and an attempt cap. */
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_ATTEMPTS = 6

        /** The bootstrap secret is 32 bytes, `SERVER_OPERATOR.md`. */
        const val SECRET_BYTES = 32
    }
}

/** The status before any server is configured, shared so it reads as one state. */
private val NOT_CONFIGURED = SyncStatus(configured = false, serverUrl = null, message = null, busy = false)

/** The two security verdicts of `ROLLBACK_PROTECTION.md` §4, which only this device reaches. */
internal val LOCAL_VERDICTS: Set<ChurStatus> = setOf(ChurStatus.SYNC_CHAIN_FORK, ChurStatus.SYNC_HEAD_ROLLBACK)

// These statuses describe local source files. Session, key, catalog, and transport
// failures keep the existing stop behavior; incoming records still need native validation.
private val SOURCE_FILE_FAILURES = setOf(
    ChurStatus.NOT_FOUND,
    ChurStatus.OBJECT_INCOMPLETE,
    ChurStatus.OBJECT_CORRUPT,
    ChurStatus.IO_FAILURE,
    ChurStatus.STORAGE_UNAVAILABLE,
)
