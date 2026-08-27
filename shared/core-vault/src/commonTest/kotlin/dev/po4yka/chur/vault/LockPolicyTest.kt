package dev.po4yka.chur.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LockPolicyTest {
    @Test
    fun a_session_inside_the_timeout_stays_open() {
        val policy = LockPolicy(idleTimeoutMs = 1_000)
        assertEquals(LockDecision.KEEP, idleDecision(policy, lastUsedMs = 0, nowMs = 999))
    }

    @Test
    fun a_session_at_or_past_the_timeout_locks() {
        val policy = LockPolicy(idleTimeoutMs = 1_000)
        assertEquals(LockDecision.LOCK, idleDecision(policy, lastUsedMs = 0, nowMs = 1_000))
        assertEquals(LockDecision.LOCK, idleDecision(policy, lastUsedMs = 0, nowMs = 5_000))
    }

    @Test
    fun the_immediate_policy_locks_as_soon_as_the_session_stops_being_used() {
        assertEquals(
            LockDecision.LOCK,
            idleDecision(LockPolicy.IMMEDIATE, lastUsedMs = 0, nowMs = 0),
        )
    }

    @Test
    fun a_negative_timeout_is_refused_rather_than_treated_as_immediate() {
        // A negative value would read as "never lock" through the comparison,
        // which is the opposite of what a caller passing it would mean.
        assertFailsWith<IllegalArgumentException> { LockPolicy(idleTimeoutMs = -1) }
    }

    @Test
    fun the_default_policy_locks_on_background_and_after_two_minutes() {
        val policy = LockPolicy()
        assertEquals(120_000, policy.idleTimeoutMs)
        assertEquals(true, policy.lockOnBackground)
    }
}
