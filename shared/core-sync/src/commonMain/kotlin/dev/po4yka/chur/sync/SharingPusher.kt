package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.PreparedShare
import dev.po4yka.chur.ffi.PreparedShareRevocation

/** Uploads native-prepared sharing records in dependency order. */
public class SharingPusher(private val client: SyncClient) {
    /** Publishes one membership change before its addressed HPKE grant. */
    public suspend fun push(vaultId: ByteArray, share: PreparedShare) {
        client.putSharingMembership(vaultId, share.membership, share.membershipOperation)
        client.putSharingGrant(vaultId, share.grant, share.grantOperation)
    }

    /** Publishes one resumable revocation batch and any final replacement grants. */
    public suspend fun pushRevocation(vaultId: ByteArray, revocation: PreparedShareRevocation) {
        require(revocation.rotationComplete || revocation.grants.isEmpty()) {
            "an incomplete sharing rotation cannot publish grants"
        }
        client.putSharingMembership(
            vaultId,
            revocation.membership,
            revocation.membershipOperation,
        )
        revocation.rotationOperations.forEach { client.putCollectionOperation(vaultId, it) }
        revocation.grants.forEach { grant ->
            client.putSharingGrant(vaultId, grant.grant, grant.operation)
        }
    }
}
