package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * The session state machine against the real vault.
 *
 * `docs/security/PROVISIONING.md` §2 makes "no vault" a state rather than an
 * error, and `DESIGN.md` §14 makes the gate between locked and unlocked the
 * only way in. This drives both, plus the lock triggers of §14.
 */
class VaultRepositoryHostTest {
    private val roots = mutableListOf<File>()
    private var now = 1_700_000_000_000L

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun repository(policy: LockPolicy = LockPolicy()): VaultRepository {
        val directory = File(System.getProperty("java.io.tmpdir"), "chur-repo-${System.nanoTime()}")
        directory.mkdirs()
        roots.add(directory)
        return VaultRepository(directory.absolutePath, { now }, policy)
    }

    @Test
    fun a_fresh_root_reports_no_vault_rather_than_a_failure() = runBlocking {
        val repository = repository()
        assertIs<VaultState.NoVault>(repository.start())
        repository.shutdown()
    }

    @Test
    fun creation_reaches_unlocked_and_hands_back_the_recovery_phrase_once() = runBlocking {
        val repository = repository()
        repository.start()
        val phrase = repository.create(PASSWORD.encodeToByteArray(), offerRecovery = true)
        assertNotNull(phrase)
        assertEquals(24, phrase.split(" ").size, "RECOVERY.md §2 shows a 24-word phrase")
        assertIs<VaultState.Unlocked>(repository.state.value)
        // §3: the vault is usable at once, which is what step 6 means by
        // opening the session.
        assertEquals(0, repository.page(ObjectQuery()).objects.size)
        repository.shutdown()
    }

    @Test
    fun creation_without_the_recovery_offer_hands_back_nothing() = runBlocking {
        val repository = repository()
        repository.start()
        assertNull(repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false))
        assertEquals(1, repository.slots().size, "only the mandatory password slot")
        repository.shutdown()
    }

    @Test
    fun a_wrong_password_leaves_the_state_locked_and_records_why() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.lock(LockReason.USER)

        val failure = assertFailsWith<ChurFailure> {
            repository.unlock("wrong".encodeToByteArray())
        }
        assertEquals(ChurStatus.AUTHENTICATION_FAILED, failure.status)
        val state = repository.state.value
        assertIs<VaultState.Locked>(state)
        assertEquals(ChurStatus.AUTHENTICATION_FAILED, state.lastFailure)
        repository.shutdown()
    }

    @Test
    fun a_call_made_while_locked_is_vault_locked_rather_than_a_null_handle() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.lock(LockReason.USER)
        assertEquals(
            ChurStatus.VAULT_LOCKED,
            assertFailsWith<ChurFailure> { repository.page(ObjectQuery()) }.status,
        )
        repository.shutdown()
    }

    @Test
    fun locking_twice_is_not_a_failure() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.lock(LockReason.PANIC)
        repository.lock(LockReason.BACKGROUND)
        assertIs<VaultState.Locked>(repository.state.value)
        repository.shutdown()
    }

    @Test
    fun the_idle_timer_locks_only_once_the_timeout_has_passed() = runBlocking {
        val repository = repository(LockPolicy(idleTimeoutMs = 1_000))
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)

        now += 500
        assertEquals(false, repository.lockIfIdle())
        assertIs<VaultState.Unlocked>(repository.state.value)

        now += 600
        assertEquals(true, repository.lockIfIdle())
        assertIs<VaultState.Locked>(repository.state.value)

        // A locked session has nothing to time out.
        assertEquals(false, repository.lockIfIdle())
        repository.shutdown()
    }

    @Test
    fun a_call_refreshes_the_idle_clock() = runBlocking {
        val repository = repository(LockPolicy(idleTimeoutMs = 1_000))
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)

        now += 900
        repository.page(ObjectQuery())
        now += 900
        assertEquals(false, repository.lockIfIdle(), "the query moved the clock forward")
        repository.shutdown()
    }

    @Test
    fun going_to_the_background_locks_under_the_default_policy_and_not_otherwise() = runBlocking {
        val locking = repository(LockPolicy(lockOnBackground = true))
        locking.start()
        locking.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        locking.onBackground()
        assertIs<VaultState.Locked>(locking.state.value)
        locking.shutdown()

        val staying = repository(LockPolicy(lockOnBackground = false))
        staying.start()
        staying.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        staying.onBackground()
        assertIs<VaultState.Unlocked>(staying.state.value)
        staying.shutdown()
    }

    @Test
    fun a_second_start_finds_the_vault_the_first_created() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.lock(LockReason.USER)
        assertIs<VaultState.Locked>(repository.start())
        repository.unlock(PASSWORD.encodeToByteArray())
        assertIs<VaultState.Unlocked>(repository.state.value)
        repository.shutdown()
    }

    @Test
    fun the_recovery_phrase_opens_the_vault_after_the_password_is_gone() = runBlocking {
        val repository = repository()
        repository.start()
        val phrase = repository.create(PASSWORD.encodeToByteArray(), offerRecovery = true)
        assertNotNull(phrase)
        assertEquals(2, repository.slots().size)
        repository.lock(LockReason.USER)

        repository.unlockWithRecovery(phrase)
        assertIs<VaultState.Unlocked>(repository.state.value)
        repository.shutdown()
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
