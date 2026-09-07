package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.SharingIdentity
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
 * session. This interface is that split as three calls; `:shared:app` binds
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
                    SyncStatus(configured = true, serverUrl = state.serverUrl, message = null, busy = false)
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
            val vault = requireNotNull(boundary) { "no vault is bound to sync" }
            val identity =
                vault.identity()
                    ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "sync setup needs an unlocked vault")
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
            val state =
                store.load() ?: run {
                    _status.value = NOT_CONFIGURED
                    return false
                }
            val vault =
                boundary ?: run {
                    _status.value = SyncStatus(true, state.serverUrl, "The vault is not open.", false)
                    return false
                }
            val client = clientFactory(state.serverUrl) { state.transportToken }
            try {
                var backoff = INITIAL_BACKOFF_MS
                repeat(MAX_ATTEMPTS) { attempt ->
                    try {
                        val puller =
                            LockedSyncPuller(client) { vaultId, kind, stagedAtMs, record ->
                                vault.stage(vaultId, kind, stagedAtMs, record)
                            }
                        val report = puller.pullOnce(state.vaultId, state.cursors, clock())
                        vault.process()
                        // §5: the cursor advances only once the records are in
                        // native storage, so a dropped page is fetched again.
                        store.save(state.copy(cursors = report.cursors.ifEmpty { state.cursors }))
                        _status.value =
                            SyncStatus(
                                configured = true,
                                serverUrl = state.serverUrl,
                                message =
                                    "Synced ${report.operations} operation(s) " +
                                        "and ${report.checkpoints} checkpoint(s).",
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
            }
        }

    /** Forgets the server entirely, which the settings entry offers. */
    public suspend fun disconnect(): Unit =
        mutex.withLock {
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
