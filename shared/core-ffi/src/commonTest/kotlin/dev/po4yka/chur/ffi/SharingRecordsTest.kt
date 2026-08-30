package dev.po4yka.chur.ffi

import kotlin.test.Test
import kotlin.test.assertContentEquals

class SharingRecordsTest {
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
}
