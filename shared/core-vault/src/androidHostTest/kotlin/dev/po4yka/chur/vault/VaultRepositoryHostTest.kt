package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.core.platformkeys.DeviceSlotException
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    private fun repository(
        policy: LockPolicy = LockPolicy(),
        destroyPlatformSlot: (ByteArray) -> Unit = { error("unexpected platform deletion") },
    ): VaultRepository {
        val directory = File(System.getProperty("java.io.tmpdir"), "chur-repo-${System.nanoTime()}")
        directory.mkdirs()
        roots.add(directory)
        return VaultRepository(directory.absolutePath, { now }, policy, destroyPlatformSlot)
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
        assertIs<VaultState.Creating>(repository.state.value, "§4: the creation waits for the phrase to be confirmed")
        assertIs<VaultState.Creating>(repository.start(), "a start keeps a waiting creation")
        assertTrue(repository.confirmRecoveryPhrase())
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
        var beforeLockCalls = 0

        now += 500
        assertEquals(false, repository.lockIfIdle { beforeLockCalls += 1 })
        assertEquals(0, beforeLockCalls)
        assertIs<VaultState.Unlocked>(repository.state.value)

        now += 600
        assertEquals(true, repository.lockIfIdle {
            assertIs<VaultState.Unlocked>(repository.state.value)
            beforeLockCalls += 1
        })
        assertEquals(1, beforeLockCalls)
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
    fun a_lock_without_a_vault_keeps_no_vault() = runBlocking {
        val repository = repository()
        repository.start()
        // A new user leaves the application before creating anything. The
        // background lock has nothing to close, and `Locked` would send the
        // vault entry to an unlock screen no credential can pass.
        repository.onBackground()
        assertIs<VaultState.NoVault>(repository.state.value)
        repository.lock(LockReason.PANIC)
        assertIs<VaultState.NoVault>(repository.state.value)
        assertNull(repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false))
        assertIs<VaultState.Unlocked>(repository.state.value)
        repository.shutdown()
    }

    @Test
    fun an_unlock_over_an_open_session_ends_that_session_first() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        // The handle is private on purpose; the test reads it to show the
        // session it replaced is gone in Rust, not just forgotten here.
        val handle = VaultRepository::class.java.getDeclaredField("session").apply { isAccessible = true }
        val first = handle.getLong(repository)

        repository.unlock(PASSWORD.encodeToByteArray())

        assertIs<VaultState.Unlocked>(repository.state.value)
        assertTrue(handle.getLong(repository) != first)
        assertFailsWith<ChurFailure> { ChurVault.slots(first) }
        assertEquals(1, repository.slots().size, "the new session works")
        repository.shutdown()
    }

    @Test
    fun a_refused_first_creation_keeps_no_vault() = runBlocking {
        val repository = repository()
        repository.start()
        // Past the 1 024-byte canonical password limit, so Rust refuses
        // before anything reaches the disk.
        assertFailsWith<ChurFailure> {
            repository.create(ByteArray(1_025) { 'x'.code.toByte() }, offerRecovery = false)
        }
        assertIs<VaultState.NoVault>(repository.state.value)
        repository.onBackground()
        assertIs<VaultState.NoVault>(repository.state.value)
        repository.shutdown()
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
    fun a_second_start_keeps_the_open_session_rather_than_reporting_locked() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        assertIs<VaultState.Unlocked>(repository.state.value)

        // The Android host calls `start` again whenever the platform recreates
        // its activity - a locale change, a font-scale change, a display-size
        // change, a multi-window resize. §8.1 of
        // `docs/interop/FFI_CONTRACT.md` says there is no per-scene vault
        // state, so the session outlives the window and the state has to say
        // so: a `Locked` published here is the unlock screen in front of an
        // unlocked vault, with the session's handles unreachable.
        assertIs<VaultState.Unlocked>(repository.start())
        assertIs<VaultState.Unlocked>(repository.state.value)
        assertEquals(0, repository.page(ObjectQuery()).objects.size)
        repository.shutdown()
    }

    @Test
    fun the_recovery_phrase_opens_the_vault_after_the_password_is_gone() = runBlocking {
        val repository = repository()
        repository.start()
        val phrase = repository.create(PASSWORD.encodeToByteArray(), offerRecovery = true)
        assertNotNull(phrase)
        assertTrue(repository.confirmRecoveryPhrase())
        assertEquals(2, repository.slots().size)
        repository.lock(LockReason.USER)

        repository.unlockWithRecovery(phrase)
        assertIs<VaultState.Unlocked>(repository.state.value)
        repository.shutdown()
    }

    @Test
    fun a_phrase_lost_before_confirmation_leaves_no_slot_behind() = runBlocking {
        val repository = repository()
        repository.start()
        // `PROVISIONING.md` §4 commits the recovery slot only after the phrase
        // is confirmed, so the creation waits at step 5 of §3.
        assertNotNull(repository.create(PASSWORD.encodeToByteArray(), offerRecovery = true))
        assertIs<VaultState.Creating>(repository.state.value)
        now += LockPolicy.DEFAULT_IDLE_TIMEOUT_MS + 1
        assertFalse(repository.lockIfIdle(), "copying the phrase outlasts the idle timeout")
        now += LockPolicy.RECOVERY_PHRASE_TIMEOUT_MS
        assertTrue(repository.lockIfIdle())
        assertIs<VaultState.NoVault>(repository.state.value, "a creation never confirmed leaves nothing openable")
        assertFalse(repository.confirmRecoveryPhrase(), "the lock discarded what the phrase belonged to")

        assertNotNull(repository.create(PASSWORD.encodeToByteArray(), offerRecovery = true))
        assertTrue(repository.confirmRecoveryPhrase())
        assertIs<VaultState.Unlocked>(repository.state.value)
        assertEquals(2, repository.slots().size)

        // `RECOVERY.md` §8 from settings: a lock before confirmation closes
        // the session and the staged slot with it.
        val lost = repository.beginRecoverySlot()
        repository.lock(LockReason.BACKGROUND)
        repository.unlock(PASSWORD.encodeToByteArray())
        assertEquals(2, repository.slots().size, "the lock discarded the unconfirmed slot")
        repository.beginRecoverySlot()
        assertTrue(repository.confirmRecoveryPhrase())
        assertEquals(2, repository.slots().size, "the confirmed phrase replaced the creation's")

        repository.lock(LockReason.USER)
        val refused = assertFailsWith<ChurFailure> { repository.unlockWithRecovery(lost) }
        assertEquals(ChurStatus.AUTHENTICATION_FAILED, refused.status)
        repository.shutdown()
    }

    @Test
    fun a_confirmed_phrase_replaces_the_old_one() = runBlocking {
        val repository = repository()
        repository.start()
        val old = repository.create(PASSWORD.encodeToByteArray(), offerRecovery = true)
        assertNotNull(old)
        assertTrue(repository.confirmRecoveryPhrase())
        val first = repository.slots().single { it.slotType == RECOVERY }

        // A lock before confirmation keeps the old phrase and adds nothing.
        repository.beginRecoverySlot()
        repository.lock(LockReason.USER)
        repository.unlock(PASSWORD.encodeToByteArray())
        assertEquals(listOf(first), repository.slots().filter { it.slotType == RECOVERY })

        // `RECOVERY.md` §8: each confirmed phrase replaces the one before it,
        // so the Access list never grows a second Recovery row.
        repository.beginRecoverySlot()
        assertTrue(repository.confirmRecoveryPhrase())
        val new = repository.beginRecoverySlot()
        assertTrue(repository.confirmRecoveryPhrase())
        val recovery = repository.slots().filter { it.slotType == RECOVERY }
        assertEquals(1, recovery.size)
        assertFalse(first in recovery)

        repository.lock(LockReason.USER)
        val refused = assertFailsWith<ChurFailure> { repository.unlockWithRecovery(old) }
        assertEquals(ChurStatus.AUTHENTICATION_FAILED, refused.status)
        repository.unlockWithRecovery(new)
        assertIs<VaultState.Unlocked>(repository.state.value)
        repository.shutdown()
    }

    @Test
    fun a_running_operation_does_not_extend_the_phrase_window() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.beginRecoverySlot()
        // An import from a pipe that nothing writes to runs until the pipe
        // closes, and each poll of it refreshes the idle clock.
        val fifo = File(roots.last(), "source")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.path).start().waitFor())
        var writer: FileOutputStream? = null
        val opener = thread { writer = FileOutputStream(fifo) }
        val operation = FileInputStream(fifo).use { source ->
            opener.join()
            repository.beginImport(
                descriptorOf(source.fd),
                ImportRequest(contentType = "image/jpeg", seekable = false, mediaClass = 1),
            )
        }
        now += LockPolicy.RECOVERY_PHRASE_TIMEOUT_MS - 1
        assertFalse(repository.poll(operation).terminal, "the import waits for its source")
        writer!!.close()
        while (!repository.poll(operation).terminal) Thread.yield()
        repository.closeOperation(operation)

        assertFalse(repository.lockIfIdle(), "the phrase window is still open")
        now += 1
        assertTrue(repository.lockIfIdle(), "DESIGN.md §17.2: ten minutes after the phrase appeared")
        repository.shutdown()
    }

    @Test
    fun an_unlock_abandons_a_creation_waiting_for_its_phrase() = runBlocking {
        val repository = repository()
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.lock(LockReason.USER)
        assertNotNull(repository.create(SECOND_PASSWORD.encodeToByteArray(), offerRecovery = true))

        repository.unlock(PASSWORD.encodeToByteArray())

        // Activating it now would assign over the session the unlock opened,
        // and one runtime shares one session.
        assertFalse(repository.confirmRecoveryPhrase(), "the unlock abandoned the waiting creation")
        assertIs<VaultState.Unlocked>(repository.state.value)
        assertEquals(1, repository.slots().size, "the session the unlock opened")
        repository.lock(LockReason.USER)
        assertEquals(
            ChurStatus.AUTHENTICATION_FAILED,
            assertFailsWith<ChurFailure> { repository.unlock(SECOND_PASSWORD.encodeToByteArray()) }.status,
        )
        repository.shutdown()
    }

    @Test
    fun an_apple_slot_rolls_back_a_failed_store_and_unlocks_after_a_successful_store() = runBlocking {
        val deleted = mutableListOf<ByteArray>()
        val repository = repository(destroyPlatformSlot = { deleted += it.copyOf() })
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)

        var refusedSecret: ByteArray? = null
        assertFailsWith<IllegalStateException> {
            repository.enrollAppleSlot(ByteArray(16) { 1 }) { secret ->
                refusedSecret = secret
                error("Keychain refused the item")
            }
        }
        assertEquals(1, repository.slots().size)
        assertTrue(refusedSecret!!.all { it == 0.toByte() })
        assertTrue(deleted.single().contentEquals(ByteArray(16) { 1 }))

        var savedSecret = ByteArray(0)
        repository.enrollAppleSlot(ByteArray(16) { 2 }) { secret ->
            savedSecret = secret.copyOf()
        }
        assertEquals(2, repository.slots().size)
        repository.lock(LockReason.USER)
        repository.unlockWithDeviceSecret(savedSecret)
        savedSecret.fill(0)
        assertIs<VaultState.Unlocked>(repository.state.value)
        repository.shutdown()
    }

    @Test
    fun apple_store_conflict_does_not_delete_an_existing_platform_item() = runBlocking {
        var deleted = false
        val repository = repository(destroyPlatformSlot = { deleted = true })
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)

        assertEquals(
            ChurStatus.CONFLICT,
            assertFailsWith<ChurFailure> {
                repository.enrollAppleSlot(ByteArray(16) { 3 }) {
                    throw ChurFailure(ChurStatus.CONFLICT, "item already exists")
                }
            }.status,
        )
        assertFalse(deleted)
        assertEquals(1, repository.slots().size)
        repository.shutdown()
    }

    @Test
    fun removing_an_apple_slot_deletes_the_matching_item_after_descriptor_commit() = runBlocking {
        val itemId = ByteArray(16) { 9 }
        val deleted = mutableListOf<ByteArray>()
        val repository = repository(destroyPlatformSlot = { deleted += it.copyOf() })
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.enrollAppleSlot(itemId) { }
        val slot = repository.slots().single { it.slotType == 3 }

        repository.removeSlot(slot.slotId)

        assertEquals(1, deleted.size)
        assertTrue(deleted.single().contentEquals(itemId))
        assertFalse(repository.slots().any { it.slotId.contentEquals(slot.slotId) })
        repository.shutdown()
    }

    @Test
    fun removing_an_android_slot_deletes_its_alias_and_reports_cleanup_failure() = runBlocking {
        var alias = ByteArray(0)
        var deleted = ByteArray(0)
        val repository = repository(destroyPlatformSlot = { identifier ->
            deleted = identifier.copyOf()
            throw DeviceSlotException(ChurStatus.PLATFORM_KEY_UNAVAILABLE, "delete refused")
        })
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)
        repository.enrollKeystoreSlot { identifier, _, _ ->
            alias = identifier.copyOf()
            ByteArray(12) { 1 } to ByteArray(48) { 2 }
        }
        val slot = repository.slots().single { it.slotType == 2 }

        assertEquals(
            ChurStatus.PLATFORM_KEY_UNAVAILABLE,
            assertFailsWith<ChurFailure> { repository.removeSlot(slot.slotId) }.status,
        )
        assertTrue(deleted.contentEquals(alias))
        assertFalse(repository.slots().any { it.slotId.contentEquals(slot.slotId) })
        repository.shutdown()
    }

    @Test
    fun a_failed_keystore_commit_deletes_the_uncommitted_platform_key() = runBlocking {
        var alias = ByteArray(0)
        var deleted = ByteArray(0)
        val repository = repository(destroyPlatformSlot = { deleted = it.copyOf() })
        repository.start()
        repository.create(PASSWORD.encodeToByteArray(), offerRecovery = false)

        assertEquals(
            ChurStatus.INVALID_INPUT,
            assertFailsWith<ChurFailure> {
                repository.enrollKeystoreSlot { identifier, _, _ ->
                    alias = identifier.copyOf()
                    ByteArray(1) to ByteArray(48)
                }
            }.status,
        )
        assertTrue(deleted.contentEquals(alias))
        assertEquals(1, repository.slots().size)
        repository.shutdown()
    }

    /** The integer descriptor behind a JVM stream, which Rust duplicates. */
    private fun descriptorOf(descriptor: FileDescriptor): Int =
        descriptor.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(descriptor)

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val SECOND_PASSWORD = "a second identity's own passphrase"

        /** The Recovery family, `KEY_SLOTS.md` §1. */
        const val RECOVERY = 4
    }
}
