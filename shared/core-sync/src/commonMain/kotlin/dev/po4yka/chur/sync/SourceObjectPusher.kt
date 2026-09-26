package dev.po4yka.chur.sync

/** One immutable source object before its collection operations are authored. */
public class SourceObjectPublication(
    public val collectionId: ByteArray,
    public val objectId: ByteArray,
    public val storeId: ByteArray,
    public val length: ULong,
    public val fullSha256: ByteArray,
)

/** Native ciphertext range and the SHA-256 digest required by the upload route. */
public class SourceObjectRange(public val bytes: ByteArray, public val sha256: ByteArray)

/** Stable signed records authored only after the complete source object is on the server. */
public class SourceObjectOperations(public val createOperation: ByteArray, public val commitOperation: ByteArray)

/** Publishes ciphertext before making its signed object operations visible. */
public class SourceObjectPusher(private val client: SyncClient) {
    public suspend fun push(
        vaultId: ByteArray,
        publication: SourceObjectPublication,
        read: suspend (objectId: ByteArray, offset: ULong, maxBytes: Int) -> SourceObjectRange,
        author: suspend (SourceObjectPublication) -> SourceObjectOperations,
    ) {
        require(publication.length > 0uL && publication.fullSha256.size == 32)
        val transferId = publication.storeId
        var progress = client.beginUpload(vaultId, publication.storeId, transferId, publication.length)
        require(
            progress.expected == publication.length && progress.received <= publication.length &&
                (!progress.complete || progress.received == publication.length),
        )
        while (progress.received < publication.length) {
            val maxBytes = minOf(CHUNK_BYTES.toULong(), publication.length - progress.received).toInt()
            val range = read(publication.objectId, progress.received, maxBytes)
            require(range.bytes.isNotEmpty() && range.bytes.size <= maxBytes && range.sha256.size == 32)
            val expected = progress.received + range.bytes.size.toULong()
            progress = client.appendUpload(vaultId, transferId, progress.received, range.sha256, range.bytes)
            require(progress.expected == publication.length && progress.received == expected)
        }
        progress = client.finishUpload(vaultId, transferId, publication.fullSha256)
        require(progress.complete && progress.expected == publication.length && progress.received == publication.length)
        client.publishSharedObject(vaultId, publication.collectionId, publication.storeId)
        val operations = author(publication)
        require(operations.createOperation.isNotEmpty() && operations.commitOperation.isNotEmpty())
        client.putCollectionOperation(vaultId, operations.createOperation)
        client.putCollectionOperation(vaultId, operations.commitOperation)
    }
}

private const val CHUNK_BYTES = 1024 * 1024
