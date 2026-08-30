package dev.po4yka.chur.ffi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SharingRecordsTest {
    @Test
    fun prepared_revocation_keeps_batch_and_grant_boundaries() {
        val encoded = byteArrayOf(
            0, 1,
            0, 0, 0, 1, 11,
            0, 0, 0, 1, 12,
            0, 0, 0, 2,
            0, 0, 0, 1, 13,
            0, 0, 0, 1, 14,
            0, 0, 0, 1,
            0, 0, 0, 1, 15,
            0, 0, 0, 1, 16,
            1,
        )

        val revocation = decodePreparedShareRevocation(encoded, encoded.size)

        assertContentEquals(byteArrayOf(11), revocation.membership)
        assertContentEquals(byteArrayOf(12), revocation.membershipOperation)
        assertContentEquals(byteArrayOf(13), revocation.rotationOperations[0])
        assertContentEquals(byteArrayOf(14), revocation.rotationOperations[1])
        assertContentEquals(byteArrayOf(15), revocation.grants.single().grant)
        assertContentEquals(byteArrayOf(16), revocation.grants.single().operation)
        kotlin.test.assertTrue(revocation.rotationComplete)

        assertFailsWith<ChurFailure> {
            decodePreparedShareRevocation(
                byteArrayOf(
                    0, 1,
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 16, 1,
                ),
                14,
            )
        }
    }

    @Test
    fun prepared_share_keeps_relay_dependency_order() {
        val encoded = byteArrayOf(
            0, 1,
            0, 0, 0, 1, 11,
            0, 0, 0, 1, 12,
            0, 0, 0, 1, 13,
            0, 0, 0, 1, 14,
        )

        val share = decodePreparedShare(encoded, encoded.size)

        assertContentEquals(byteArrayOf(11), share.membership)
        assertContentEquals(byteArrayOf(12), share.membershipOperation)
        assertContentEquals(byteArrayOf(13), share.grant)
        assertContentEquals(byteArrayOf(14), share.grantOperation)
    }

    @Test
    fun share_acceptance_bundle_keeps_issuer_and_pair_boundaries() {
        val encoded = encodeShareAcceptance(
            ShareAcceptance(
                issuers = listOf(
                    SharingIssuerEvidence(
                        membership = listOf(byteArrayOf(1)),
                        operations = listOf(byteArrayOf(2), byteArrayOf(3)),
                    ),
                ),
                membership = listOf(
                    SharingMembershipEvidence(byteArrayOf(4), byteArrayOf(5)),
                ),
                grant = byteArrayOf(6),
                grantOperation = byteArrayOf(7),
            ),
        )

        assertContentEquals(
            byteArrayOf(
                0, 1,
                0, 0, 0, 1,
                0, 0, 0, 1, 0, 0, 0, 1, 1,
                0, 0, 0, 2, 0, 0, 0, 1, 2, 0, 0, 0, 1, 3,
                0, 0, 0, 1,
                0, 0, 0, 1, 4, 0, 0, 0, 1, 5,
                0, 0, 0, 1, 6,
                0, 0, 0, 1, 7,
            ),
            encoded,
        )
        assertFailsWith<IllegalArgumentException> {
            encodeShareAcceptance(
                ShareAcceptance(
                    issuers = List(258) { SharingIssuerEvidence(emptyList(), emptyList()) },
                    membership = emptyList(),
                    grant = byteArrayOf(1),
                    grantOperation = byteArrayOf(2),
                ),
            )
        }
    }
}
