package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure

/** Dismisses this source device's unavailable requests so its inbox can advance. */
internal suspend fun respondToObjectKeyRequests(
    client: SyncClient,
    vaultId: ByteArray,
    issue: suspend (ByteArray) -> ByteArray?,
): Unit {
    for (request in client.objectKeyRequests(vaultId)) {
        val grant = try { issue(request) } catch (failure: ChurFailure) {
            if (failure.status in setOf(ChurStatus.NOT_FOUND, ChurStatus.AUTHENTICATION_FAILED) && request.size >= 18) {
                // Dismiss only for this source; another replica can still serve it.
                // A recipient retry lets this device try again after catching up.
                client.acknowledgeObjectKeyRequest(vaultId, request.copyOfRange(2, 18))
                continue
            }
            throw failure
        } ?: return
        client.putObjectKeyGrant(vaultId, grant)
    }
}

/** Only authenticated native acceptance can create a successful delivery receipt. */
internal suspend fun acceptObjectKeyGrant(
    client: SyncClient,
    vaultId: ByteArray,
    grant: ByteArray,
    accept: suspend () -> Boolean,
): Boolean {
    require(grant.size == 346) { "object key grant length is invalid" }
    val grantId = grant.copyOfRange(2, 18)
    val accepted = try { accept() } catch (failure: ChurFailure) {
        if (failure.status == ChurStatus.NOT_FOUND) {
            // An absent local object is not proof of a bad source response.
            // Drop the delivery without pinning a receipt or excluding its source.
            client.discardObjectKeyGrant(vaultId, grantId)
            return true
        }
        if (failure.status !in setOf(
                ChurStatus.AUTHENTICATION_FAILED,
                ChurStatus.INVALID_INPUT,
                ChurStatus.NON_CANONICAL_ENCODING,
            )
        ) throw failure
        // Retire this response only. Another source can answer the same request.
        client.rejectObjectKeyGrant(vaultId, grantId)
        return true
    }
    if (!accepted) return false
    client.acknowledgeObjectKeyGrant(vaultId, grantId)
    return true
}
