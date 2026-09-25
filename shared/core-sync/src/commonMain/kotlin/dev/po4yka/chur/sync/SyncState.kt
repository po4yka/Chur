package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.PreparedGrant
import dev.po4yka.chur.ffi.PreparedShare
import dev.po4yka.chur.ffi.PreparedShareRevocation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The durable half of the sync engine: where it pulls from, who it is on that
 * server, and how far it has read each device chain.
 *
 * The file holds one bearer credential — the transport token — so its path is
 * part of the contract: `SYNC_PROTOCOL_V1.md` §7 puts sync state in storage
 * that is app-private and excluded from platform backup (SEC-034), and the
 * host that names the path owns that requirement. On Android that is
 * `noBackupFilesDir`; on iOS it is the directory the Xcode project marks
 * excluded from iCloud and iTunes backup.
 *
 * The format is one versioned JSON file, `FileNoteStore`'s shape, because the
 * scale is one configuration and a handful of cursors: the whole file is
 * rewritten on every change, and the version field is what a later reader
 * migrates from rather than guesses at.
 */
public class FileSyncStateStore(
    private val path: String,
) : SyncStateStore {
    private val mutex = Mutex()
    private var cache: SyncState? = null

    override suspend fun load(): SyncState? =
        mutex.withLock {
            cache?.let { return it }
            val text = readSyncStateFile(path) ?: return null
            // A cleared store is an empty file, which reads as no state rather
            // than as an unreadable one.
            if (text.isBlank()) return null
            val file =
                try {
                    json.decodeFromString(SyncStateFileV1.serializer(), text)
                } catch (cause: Exception) {
                    throw IllegalStateException("the sync state file at $path is not readable", cause)
                }
            SyncState(
                serverUrl = file.serverUrl,
                vaultId = fromHex(file.vaultId),
                deviceId = fromHex(file.deviceId),
                transportToken = fromHex(file.transportToken),
                cursors = file.cursors.map { DeviceCursor(fromHex(it.deviceId), it.after.toULong()) },
                pendingSharing = file.pendingSharing.map { pending ->
                    val membership = fromHex(pending.membership)
                    val operation = fromHex(pending.membershipOperation)
                    when (pending.kind) {
                        "share" -> PendingSharingPublication(
                            share = PreparedShare(
                                membership,
                                operation,
                                fromHex(requireNotNull(pending.grant)),
                                fromHex(requireNotNull(pending.grantOperation)),
                            ),
                        )
                        "revocation" -> PendingSharingPublication(
                            revocation = PreparedShareRevocation(
                                membership,
                                operation,
                                pending.rotationOperations.map(::fromHex),
                                pending.grants.map { PreparedGrant(fromHex(it.grant), fromHex(it.operation)) },
                                pending.rotationComplete,
                            ),
                        )
                        else -> error("unknown pending sharing record")
                    }
                },
            ).also { cache = it }
        }

    override suspend fun save(state: SyncState): Unit =
        mutex.withLock {
            val text =
                json.encodeToString(
                    SyncStateFileV1.serializer(),
                    SyncStateFileV1(
                        serverUrl = state.serverUrl,
                        vaultId = state.vaultId.toHex(),
                        deviceId = state.deviceId.toHex(),
                        transportToken = state.transportToken.toHex(),
                        cursors = state.cursors.map { CursorV1(it.deviceId.toHex(), it.after.toString()) },
                        pendingSharing = state.pendingSharing.map { pending ->
                            val share = pending.share
                            val revocation = pending.revocation
                            PendingSharingFileV1(
                                kind = if (share != null) "share" else "revocation",
                                membership = (share?.membership ?: requireNotNull(revocation).membership).toHex(),
                                membershipOperation = (share?.membershipOperation ?: requireNotNull(revocation).membershipOperation).toHex(),
                                grant = share?.grant?.toHex(),
                                grantOperation = share?.grantOperation?.toHex(),
                                rotationOperations = revocation?.rotationOperations?.map(ByteArray::toHex).orEmpty(),
                                grants = revocation?.grants?.map { GrantFileV1(it.grant.toHex(), it.operation.toHex()) }.orEmpty(),
                                rotationComplete = revocation?.rotationComplete ?: true,
                            )
                        },
                    ),
                )
            writeSyncStateFile(path, text)
            cache = state
        }

    override suspend fun clear(): Unit =
        mutex.withLock {
            cache = null
            writeSyncStateFile(path, "")
        }

    private companion object {
        val json: Json = Json { prettyPrint = false }
    }
}

/** The sync state's persistence, which the engine reads at the start of a run. */
public interface SyncStateStore {
    /** The saved state, or `null` when sync was never configured. */
    public suspend fun load(): SyncState?

    /** Replaces the state, which a bootstrap and every cursor advance do. */
    public suspend fun save(state: SyncState)

    /** Forgets sync entirely, which disconnecting does. */
    public suspend fun clear()
}

/** The sync engine's durable configuration, one server identity and its cursors. */
public data class SyncState(
    /** The server the client talks to, as the user entered it. */
    public val serverUrl: String,
    /** This vault's identity on that server. */
    public val vaultId: ByteArray,
    /** This device's identity, which names the operation chain it owns. */
    public val deviceId: ByteArray,
    /** The bearer credential the server accepts for this device. */
    public val transportToken: ByteArray,
    /** One pull cursor per device chain, `SYNC_PROTOCOL_V1.md` §5. */
    public val cursors: List<DeviceCursor>,
    /** Signed sharing batches waiting for idempotent server publication. */
    public val pendingSharing: List<PendingSharingPublication> = emptyList(),
)

/** One already prepared batch retained until its server upload succeeds. */
public data class PendingSharingPublication(
    public val share: PreparedShare? = null,
    public val revocation: PreparedShareRevocation? = null,
) {
    init { require((share == null) != (revocation == null)) }
}

@Serializable
private data class SyncStateFileV1(
    val version: Int = 1,
    val serverUrl: String,
    val vaultId: String,
    val deviceId: String,
    val transportToken: String,
    val cursors: List<CursorV1> = emptyList(),
    val pendingSharing: List<PendingSharingFileV1> = emptyList(),
)

@Serializable
private data class PendingSharingFileV1(
    val kind: String,
    val membership: String,
    val membershipOperation: String,
    val grant: String? = null,
    val grantOperation: String? = null,
    val rotationOperations: List<String> = emptyList(),
    val grants: List<GrantFileV1> = emptyList(),
    val rotationComplete: Boolean = true,
)

@Serializable
private data class GrantFileV1(val grant: String, val operation: String)

@Serializable
private data class CursorV1(
    val deviceId: String,
    val after: String,
)

/** The sync state file's text, or `null` when there is none yet. */
internal expect fun readSyncStateFile(path: String): String?

/**
 * Replaces the sync state file's text.
 *
 * The replacement is atomic, on both platforms, so a process death between the
 * write and the rename leaves either the previous state or the new one.
 */
internal expect fun writeSyncStateFile(
    path: String,
    text: String,
)

/**
 * One transport token from the platform CSPRNG.
 *
 * `SYNC_PROTOCOL_V1.md` §6 makes the token a 32-byte secret the enrolling
 * device generates and the server stores as the only credential that chain
 * answers to, so a predictable source would be the whole sync identity.
 */
internal expect fun randomToken(): ByteArray
