package dev.po4yka.chur.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Kotlin half of the error-mapping contract.
 *
 * `docs/ERROR_MODEL.md` requires every internal error to map to one stable
 * code, unknown codes to fail closed, and features never to branch on message
 * text. These tests assert the first two; the third is a review property.
 */
class ChurStatusTest {
    @Test
    fun the_registry_holds_the_documented_codes() {
        assertEquals(33, ChurStatus.entries.size)
        assertEquals(0, ChurStatus.OK)
    }

    @Test
    fun values_are_unique_positive_and_ascending() {
        var previous = 0
        for (status in ChurStatus.entries) {
            assertTrue(status.value > previous, "${status.name} breaks ascending order")
            previous = status.value
        }
        assertEquals(ChurStatus.entries.size, ChurStatus.entries.map { it.value }.toSet().size)
    }

    @Test
    fun the_permanently_unallocated_low_block_is_empty() {
        for (value in 1..99) {
            assertFalse(ChurStatus.isAllocated(value), "$value is allocated")
        }
        assertFalse(ChurStatus.isAllocated(ChurStatus.OK))
    }

    @Test
    fun the_reserved_blocks_are_empty() {
        for (value in 700..899) {
            assertFalse(ChurStatus.isAllocated(value), "$value is allocated")
        }
        assertFalse(ChurStatus.isAllocated(1000))
        assertFalse(ChurStatus.isAllocated(Int.MAX_VALUE))
    }

    @Test
    fun an_unknown_value_fails_closed() {
        for (value in listOf(-1, Int.MIN_VALUE, ChurStatus.OK, 42, 700, 1000, Int.MAX_VALUE)) {
            assertEquals(ChurStatus.INTERNAL_FAILURE, ChurStatus.fromValue(value))
        }
    }

    @Test
    fun every_status_round_trips_through_its_native_value() {
        for (status in ChurStatus.entries) {
            assertEquals(status, ChurStatus.fromValue(status.value))
            assertTrue(ChurStatus.isAllocated(status.value))
        }
    }

    @Test
    fun the_authentication_family_shares_one_external_result() {
        // ERROR_MODEL.md "Authentication errors": a wrong password, a wrong
        // recovery secret, damaged slot ciphertext, damaged slot AAD, a slot
        // pointing at another vault, and an absent real or decoy credential are
        // one external result. The registry therefore offers exactly one code
        // for all of them.
        assertEquals(100, ChurStatus.AUTHENTICATION_FAILED.value)
        assertEquals(Retry.YES, ChurStatus.AUTHENTICATION_FAILED.retry)
        assertFalse(
            ChurStatus.entries.any { it != ChurStatus.AUTHENTICATION_FAILED && it.name.contains("PASSWORD") },
        )
    }

    @Test
    fun a_key_derivation_memory_failure_is_a_device_state_not_a_credential_result() {
        // PASSWORD_PROFILE.md section 6: the code is decided before any
        // credential is used and reveals nothing about which slots exist.
        assertEquals(107, ChurStatus.KDF_MEMORY_UNAVAILABLE.value)
        assertEquals(Retry.YES, ChurStatus.KDF_MEMORY_UNAVAILABLE.retry)
    }

    @Test
    fun the_two_sync_verdicts_are_never_retryable() {
        assertEquals(Retry.NO, ChurStatus.SYNC_CHAIN_FORK.retry)
        assertEquals(Retry.NO, ChurStatus.SYNC_HEAD_ROLLBACK.retry)
    }
}
