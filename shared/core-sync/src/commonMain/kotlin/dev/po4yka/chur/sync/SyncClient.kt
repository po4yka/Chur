package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/** Ciphertext-only client for one self-hosted Chur sync deployment. */
public class SyncClient(
    baseUrl: String,
    private val token: suspend () -> ByteArray,
    private val client: HttpClient = platformSyncHttpClient(),
) {
    private val baseUrl: String = validateBaseUrl(baseUrl)

    /** Creates the first server membership with the operator bootstrap secret. */
    public suspend fun bootstrap(
        vaultId: ByteArray,
        bootstrapToken: ByteArray,
        transportToken: ByteArray,
        enrollment: ByteArray,
        operation: ByteArray,
    ): Unit = success(
        HttpMethod.Post,
        "/v1/vaults/${vaultId.id()}/bootstrap",
        transportToken.fixed(TOKEN_BYTES, "transport token") + pair(enrollment, operation),
        authorizationHeader = "Bootstrap ${bootstrapToken.fixed(TOKEN_BYTES, "bootstrap token").hex()}",
    )

    /** Fetches one bounded membership page after [after]. */
    public suspend fun memberships(vaultId: ByteArray, after: ULong): List<ByteArray> =
        page(HttpMethod.Get, "/v1/vaults/${vaultId.id()}/memberships?after=$after")

    /** Enrolls a device with its new transport token and paired signed records. */
    public suspend fun enroll(
        vaultId: ByteArray,
        newToken: ByteArray,
        enrollment: ByteArray,
        operation: ByteArray,
    ): Unit = success(
        HttpMethod.Post,
        "/v1/vaults/${vaultId.id()}/memberships/enroll",
        newToken.fixed(TOKEN_BYTES, "new token") + pair(enrollment, operation),
    )

    /** Revokes a device with paired signed records. */
    public suspend fun revoke(vaultId: ByteArray, revocation: ByteArray, operation: ByteArray): Unit =
        success(
            HttpMethod.Post,
            "/v1/vaults/${vaultId.id()}/memberships/revoke",
            pair(revocation, operation),
        )

    /** Uploads one signed operation. Exact replays are idempotent. */
    public suspend fun putOperation(vaultId: ByteArray, operation: ByteArray): Unit =
        success(HttpMethod.Post, "/v1/vaults/${vaultId.id()}/operations", operation)

    /** Fetches one bounded operation page for [deviceId]. */
    public suspend fun operations(vaultId: ByteArray, deviceId: ByteArray, after: ULong): List<ByteArray> =
        page(
            HttpMethod.Get,
            "/v1/vaults/${vaultId.id()}/operations/${deviceId.id()}?after=$after",
        )

    /** Uploads one collection membership record with its authenticated outer operation. */
    public suspend fun putSharingMembership(
        vaultId: ByteArray,
        membership: ByteArray,
        operation: ByteArray,
    ): Unit = success(
        HttpMethod.Post,
        "/v1/vaults/${vaultId.id()}/sharing/memberships",
        pair(membership, operation),
    )

    /** Fetches bounded collection membership chains visible to this device. */
    public suspend fun sharingMemberships(vaultId: ByteArray): List<ByteArray> =
        page(HttpMethod.Get, "/v1/vaults/${vaultId.id()}/sharing/memberships")

    /** Uploads one recipient grant with its authenticated outer operation. */
    public suspend fun putSharingGrant(
        vaultId: ByteArray,
        grant: ByteArray,
        operation: ByteArray,
    ): Unit = success(
        HttpMethod.Post,
        "/v1/vaults/${vaultId.id()}/sharing/grants",
        pair(grant, operation),
    )

    /** Fetches current grants addressed to this device. */
    public suspend fun sharingGrants(vaultId: ByteArray): List<ByteArray> =
        page(HttpMethod.Get, "/v1/vaults/${vaultId.id()}/sharing/grants")

    /** Fetches an authenticated issuer device-membership page for a current share. */
    public suspend fun sharingIssuerMemberships(
        vaultId: ByteArray,
        issuerVaultId: ByteArray,
        after: ULong,
    ): List<ByteArray> = page(
        HttpMethod.Get,
        "/v1/vaults/${vaultId.id()}/sharing/issuers/${issuerVaultId.id()}/memberships?after=$after",
    )

    /** Fetches one source issuer operation chain needed to authenticate a share. */
    public suspend fun sharingIssuerOperations(
        vaultId: ByteArray,
        issuerVaultId: ByteArray,
        issuerDeviceId: ByteArray,
        after: ULong,
    ): List<ByteArray> = page(
        HttpMethod.Get,
        "/v1/vaults/${vaultId.id()}/sharing/issuers/${issuerVaultId.id()}/operations/${issuerDeviceId.id()}?after=$after",
    )

    /** Uploads one opaque signed collection operation. */
    public suspend fun putCollectionOperation(vaultId: ByteArray, operation: ByteArray): Unit =
        success(HttpMethod.Post, "/v1/vaults/${vaultId.id()}/sharing/operations", operation)

    /** Fetches one keyset-paginated opaque collection-operation page. */
    public suspend fun collectionOperations(
        vaultId: ByteArray,
        selector: ByteArray,
        after: CollectionOperationCursor? = null,
    ): List<ByteArray> {
        val cursor = after?.let {
            "?after_vault=${it.issuerVaultId.id()}&after_device=${it.issuerDeviceId.id()}&after=${it.sequence}"
        }.orEmpty()
        return page(
            HttpMethod.Get,
            "/v1/vaults/${vaultId.id()}/sharing/operations/${selector.id()}$cursor",
        )
    }

    /** Uploads one signed checkpoint. Exact replays are idempotent. */
    public suspend fun putCheckpoint(vaultId: ByteArray, checkpoint: ByteArray): Unit =
        success(HttpMethod.Post, "/v1/vaults/${vaultId.id()}/checkpoints", checkpoint)

    /** Fetches the bounded checkpoint set. */
    public suspend fun checkpoints(vaultId: ByteArray): List<ByteArray> =
        page(HttpMethod.Get, "/v1/vaults/${vaultId.id()}/checkpoints")

    /** Fetches one checkpoint by its authenticated commitment. */
    public suspend fun checkpoint(vaultId: ByteArray, commitment: ByteArray): ByteArray = body(
        HttpMethod.Get,
        "/v1/vaults/${vaultId.id()}/checkpoints/${commitment.fixed(COMMITMENT_BYTES, "commitment").hex()}",
    )

    /** Replaces the current device transport token. */
    public suspend fun rotateToken(vaultId: ByteArray, newToken: ByteArray): Unit =
        success(
            HttpMethod.Post,
            "/v1/vaults/${vaultId.id()}/token",
            newToken.fixed(TOKEN_BYTES, "new token"),
        )

    /** Starts or resumes an opaque object upload. */
    public suspend fun beginUpload(
        vaultId: ByteArray,
        storeId: ByteArray,
        transferId: ByteArray,
        length: ULong,
    ): UploadProgress = progress(
        HttpMethod.Post,
        "/v1/vaults/${vaultId.id()}/objects/${storeId.id()}/uploads/${transferId.id()}?length=$length",
    )

    /** Appends one checksum-bound opaque object range. */
    public suspend fun appendUpload(
        vaultId: ByteArray,
        transferId: ByteArray,
        offset: ULong,
        sha256: ByteArray,
        bytes: ByteArray,
    ): UploadProgress = progress(
        HttpMethod.Patch,
        "/v1/vaults/${vaultId.id()}/uploads/${transferId.id()}?offset=$offset&sha256=${sha256.fixed(SHA256_BYTES, "SHA-256").hex()}",
        bytes,
    )

    /** Commits a complete opaque object upload. */
    public suspend fun finishUpload(
        vaultId: ByteArray,
        transferId: ByteArray,
        sha256: ByteArray,
    ): UploadProgress = progress(
        HttpMethod.Post,
        "/v1/vaults/${vaultId.id()}/uploads/${transferId.id()}/finish?sha256=${sha256.fixed(SHA256_BYTES, "SHA-256").hex()}",
    )

    /** Downloads one bounded opaque object range. */
    public suspend fun download(
        vaultId: ByteArray,
        storeId: ByteArray,
        offset: ULong,
        length: ULong,
    ): ByteArray = body(
        HttpMethod.Get,
        "/v1/vaults/${vaultId.id()}/objects/${storeId.id()}?offset=$offset&length=$length",
    )

    /** Applies a signed whole-vault deletion authorization without a transport token. */
    public suspend fun delete(vaultId: ByteArray, authorization: ByteArray): Unit =
        success(
            HttpMethod.Post,
            "/v1/vaults/${vaultId.id()}/deletions",
            authorization,
            authenticated = false,
        )

    /** Closes the platform HTTP engine. */
    public fun close(): Unit = client.close()

    private suspend fun page(method: HttpMethod, path: String): List<ByteArray> {
        val bytes = body(method, path)
        val reader = Reader(bytes)
        val count = reader.u32()
        require(count in 0..PAGE_RECORDS_MAX) { "sync page exceeds the record limit" }
        return List(count) { reader.bytes(reader.u32()) }.also {
            require(reader.done()) { "sync page has trailing bytes" }
        }
    }

    private suspend fun progress(method: HttpMethod, path: String, requestBody: ByteArray? = null): UploadProgress {
        val reader = Reader(body(method, path, requestBody))
        require(reader.size == PROGRESS_BYTES) { "upload progress has the wrong length" }
        val received = reader.u64()
        val expected = reader.u64()
        val complete = reader.byte()
        require(complete in 0..1) { "upload progress has an invalid completion flag" }
        val progress = UploadProgress(received, expected, complete == 1)
        require(reader.done()) { "upload progress has trailing bytes" }
        return progress
    }

    private suspend fun success(
        method: HttpMethod,
        path: String,
        requestBody: ByteArray? = null,
        authenticated: Boolean = true,
        authorizationHeader: String? = null,
    ) {
        body(method, path, requestBody, authenticated, authorizationHeader)
    }

    private suspend fun body(
        method: HttpMethod,
        path: String,
        requestBody: ByteArray? = null,
        authenticated: Boolean = true,
        authorizationHeader: String? = null,
    ): ByteArray {
        require(requestBody == null || requestBody.size <= RESPONSE_BYTES_MAX) {
            "sync request exceeds the byte limit"
        }
        val authorization = authorizationHeader ?: if (authenticated) bearer() else null
        val response = try {
            client.request("$baseUrl$path") {
                this.method = method
                authorization?.let { header(HttpHeaders.Authorization, it) }
                requestBody?.let { setBody(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SyncTransportFailure(ChurStatus.NETWORK_FAILURE, "sync request failed", failure)
        }
        val bytes = try {
            response.bodyAsChannel()
                .readRemaining(RESPONSE_BYTES_MAX.toLong() + 1)
                .readByteArray()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SyncTransportFailure(ChurStatus.NETWORK_FAILURE, "sync response failed", failure)
        }
        require(bytes.size <= RESPONSE_BYTES_MAX) { "sync response exceeds the byte limit" }
        if (response.status.value !in 200..299) {
            val value = if (bytes.size == Int.SIZE_BYTES) Reader(bytes).i32() else ChurStatus.INTERNAL_FAILURE.value
            throw SyncTransportFailure(ChurStatus.fromValue(value), "sync server rejected $path")
        }
        return bytes
    }

    private suspend fun bearer(): String {
        val bytes = token().fixed(TOKEN_BYTES, "transport token").copyOf()
        return try {
            "Bearer ${bytes.hex()}"
        } finally {
            bytes.fill(0)
        }
    }
}

/** The durable server state of one object upload. */
public data class UploadProgress(public val received: ULong, public val expected: ULong, public val complete: Boolean)

/** Last accepted collection-operation position in the server's canonical order. */
public data class CollectionOperationCursor(
    public val issuerVaultId: ByteArray,
    public val issuerDeviceId: ByteArray,
    public val sequence: ULong,
)

/** A stable server or local transport-boundary failure. */
public class SyncTransportFailure(
    public val status: ChurStatus,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal expect fun platformSyncHttpClient(): HttpClient

private class Reader(private val bytes: ByteArray) {
    private var offset = 0
    val size: Int get() = bytes.size

    fun byte(): Int = bytes(1)[0].toInt() and 0xff
    fun i32(): Int = u32()
    fun u32(): Int {
        val value = bytes(4)
        return ((value[0].toInt() and 0xff) shl 24) or
            ((value[1].toInt() and 0xff) shl 16) or
            ((value[2].toInt() and 0xff) shl 8) or
            (value[3].toInt() and 0xff)
    }
    fun u64(): ULong {
        var value = 0uL
        repeat(8) { value = (value shl 8) or byte().toUInt().toULong() }
        return value
    }
    fun bytes(length: Int): ByteArray {
        require(length >= 0 && offset <= bytes.size - length) { "sync response is truncated" }
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }
    fun done(): Boolean = offset == bytes.size
}

private fun validateBaseUrl(value: String): String {
    val url = Url(value)
    require(url.protocol.name == "https" || (url.protocol.name == "http" && url.host in LOCAL_HOSTS)) {
        "sync endpoint must use HTTPS except on the loopback host"
    }
    require(url.encodedPath.isEmpty() || url.encodedPath == "/") { "sync endpoint must not have a path" }
    require(url.parameters.isEmpty()) { "sync endpoint must not have a query" }
    require(url.fragment.isEmpty()) { "sync endpoint must not have a fragment" }
    return value.trimEnd('/')
}

private fun pair(first: ByteArray, second: ByteArray): ByteArray = first.size.u32() + first + second
private fun ByteArray.id(): String = fixed(ID_BYTES, "ID").hex()
private fun ByteArray.fixed(length: Int, name: String): ByteArray = apply { require(size == length) { "$name must be $length bytes" } }
private fun ByteArray.hex(): String = buildString(size * 2) {
    for (byte in this@hex) {
        append(HEX[(byte.toInt() ushr 4) and 15])
        append(HEX[byte.toInt() and 15])
    }
}
private fun Int.u32(): ByteArray {
    require(this >= 0)
    return byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte())
}

private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")
private const val HEX = "0123456789abcdef"
private const val ID_BYTES = 16
private const val TOKEN_BYTES = 32
private const val SHA256_BYTES = 32
private const val COMMITMENT_BYTES = 32
private const val PROGRESS_BYTES = 17
private const val PAGE_RECORDS_MAX = 256
private const val RESPONSE_BYTES_MAX = 16_777_216
