package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.vault.LockPolicy
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real JNI repository; Main continuations are held after native reads, without timing sleeps. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiscreetSessionRaceHostTest {
    @Test
    fun lock_clears_the_route_and_loaded_private_metadata_before_any_native_cleanup_runs() = scenario {
        create()
        controller.vault.createAlbum("Private album fixture")
        completeRead { controller.loadAlbums() }
        completeRead { controller.loadSlots() }
        assertTrue(controller.albums.value.isNotEmpty())
        assertTrue(controller.slots.value.isNotEmpty())
        controller.lock()
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertTrue(controller.albums.value.isEmpty())
        assertTrue(controller.slots.value.isEmpty())
        // Its coroutine has not even started; concealment cannot depend on the native mutex.
        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        finishLock()
    }

    @Test
    fun provider_cleanup_failure_cannot_prevent_concealment_or_native_lock() = scenario {
        create()
        controller.vault.createAlbum("Private fixture before provider failure")
        completeRead { controller.loadAlbums() }
        exports.failCleanup = true
        controller.lock()
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertTrue(controller.albums.value.isEmpty())
        assertNull(controller.recoveryPhrase.value)
        main.until { controller.vaultState.value is VaultState.Locked && controller.notice.value != null }
        assertTrue(cover.covered, "failed cleanup must retain the privacy cover")
        assertEquals(userCopy(dev.po4yka.chur.core.model.ChurStatus.INTERNAL_FAILURE), controller.notice.value?.text)
        exports.failCleanup = false
        controller.lock()
        finishLock()
    }

    @Test
    fun an_unlock_cannot_open_native_state_when_the_fresh_cleanup_attempt_also_fails() = scenario {
        create()
        exports.failCleanup = true
        controller.lock()
        finishLock()
        val cleanupCalls = exports.cleanupCalls
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        main.until { !controller.unlocking.value }
        assertEquals(cleanupCalls + 1, exports.cleanupCalls, "one explicit attempt permits one cleanup retry")
        assertIs<VaultState.Locked>(controller.vaultState.value)
        assertEquals(AppRoute.Unlock, controller.route.value)
        assertEquals(userCopy(dev.po4yka.chur.core.model.ChurStatus.INTERNAL_FAILURE), controller.formError.value)
        assertTrue(cover.covered)
        exports.failCleanup = false
        controller.lock()
        finishLock()
    }

    @Test
    fun a_recovered_provider_allows_fresh_authentication_and_the_original_album_projection() = scenario {
        create()
        controller.vault.createAlbum("Existing album after cleanup recovery")
        exports.failCleanup = true
        controller.lock()
        finishLock()
        val cleanupCalls = exports.cleanupCalls
        exports.failCleanup = false
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        main.until { controller.route.value == AppRoute.Vault }
        main.runNext()
        assertEquals(cleanupCalls + 1, exports.cleanupCalls)
        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        completeRead { controller.loadAlbums() }
        assertEquals("Existing album after cleanup recovery", controller.albums.value.single().name)
    }

    @Test
    fun a_new_panic_revokes_authentication_waiting_for_the_single_cleanup_retry() = scenario {
        create()
        exports.failCleanup = true
        controller.lock()
        finishLock()
        exports.failCleanup = false
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        main.runNext() // credential attempt queues its one cleanup retry.
        main.runNext() // retry closes native state off Main.
        val lateRetry = main.take()
        controller.panic()
        lateRetry.run()
        main.until { !controller.unlocking.value }
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertIs<VaultState.Locked>(controller.vaultState.value)
        finishLock()
    }

    @Test
    fun creation_uses_the_same_failed_cleanup_barrier_and_can_retry_after_provider_recovery() = scenario {
        create()
        exports.failCleanup = true
        controller.lock()
        finishLock()
        val cleanupCalls = exports.cleanupCalls
        controller.createSecondIdentity()
        controller.create(SECOND_PASSWORD, false)
        awaitPhraseRequestCompletion()
        assertEquals(cleanupCalls + 1, exports.cleanupCalls)
        assertIs<VaultState.Locked>(controller.vaultState.value)
        assertEquals(AppRoute.CreateVault, controller.route.value)
        assertEquals(userCopy(dev.po4yka.chur.core.model.ChurStatus.INTERNAL_FAILURE), controller.formError.value)
        assertTrue(cover.covered)
        exports.failCleanup = false
        controller.create(SECOND_PASSWORD, false)
        main.until { controller.route.value == AppRoute.Vault }
        main.runNext()
        completeRead { controller.loadSlots() }
        assertTrue(controller.slots.value.isNotEmpty(), "new identity metadata must be usable after cleanup")
    }

    @Test
    fun failed_open_session_replacement_remains_a_barrier_for_repeated_second_identity_creation() = scenario {
        create()
        controller.vault.createAlbum("Original identity survives failed replacement")
        exports.failCleanup = true
        controller.createSecondIdentity()
        val beforeFirstAttempt = exports.cleanupCalls
        controller.create(SECOND_PASSWORD, false)
        awaitPhraseRequestCompletion()
        assertEquals(beforeFirstAttempt + 1, exports.cleanupCalls)
        assertIs<VaultState.Locked>(controller.vaultState.value)
        assertEquals(AppRoute.CreateVault, controller.route.value)
        assertEquals(userCopy(dev.po4yka.chur.core.model.ChurStatus.INTERNAL_FAILURE), controller.formError.value)
        assertTrue(cover.covered)
        controller.create(SECOND_PASSWORD, false)
        awaitPhraseRequestCompletion()
        assertEquals(beforeFirstAttempt + 2, exports.cleanupCalls, "the second attempt must retry teardown before creation")
        assertIs<VaultState.Locked>(controller.vaultState.value)
        assertEquals(AppRoute.CreateVault, controller.route.value)
        assertEquals(userCopy(dev.po4yka.chur.core.model.ChurStatus.INTERNAL_FAILURE), controller.formError.value)
        exports.failCleanup = false
        controller.create(SECOND_PASSWORD, false)
        main.until { controller.route.value == AppRoute.Vault }
        main.runNext()
        completeRead { controller.loadSlots() }
        assertTrue(controller.slots.value.isNotEmpty())
        assertTrue(controller.vault.albums().isEmpty(), "the recovered create opens its own identity")
    }

    @Test
    fun a_new_unlock_waits_for_the_earlier_lock_and_then_remains_open() = scenario {
        create()
        controller.lock()
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        main.until { controller.route.value == AppRoute.Vault }
        main.runNext() // the initial page, after all earlier native cleanup completed.
        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        assertTrue(controller.vault.slots().isNotEmpty())
    }

    @Test
    fun immediate_hide_keeps_the_opted_in_whole_app_gate() = scenario {
        create()
        completeRead { controller.toggleAppLock() }
        assertTrue(controller.appLockEnabled.value)
        controller.panic()
        assertEquals(AppRoute.AppUnlock, controller.route.value)
        assertNull(controller.recoveryPhrase.value)
        finishLock()
    }

    @Test
    fun a_late_whole_app_authorization_cannot_open_the_public_shell_after_another_panic() = scenario {
        create()
        completeRead { controller.toggleAppLock() }
        controller.lock()
        finishLock()
        assertEquals(AppRoute.AppUnlock, controller.route.value)
        controller.unlock(PASSWORD)
        main.runNext() // credential verification begins.
        main.runNext() // successful credential queues the tracked whole-app close.
        main.runNext() // tracked close begins native teardown.
        val lateGateCompletion = main.take()
        controller.panic()
        lateGateCompletion.run()
        assertEquals(AppRoute.AppUnlock, controller.route.value)
        assertTrue(cover.covered, "stale whole-app authorization removed the new cover")
        finishLock()
    }

    @Test
    fun a_cancelled_unlock_result_preserves_newer_public_settings_navigation() = scenario {
        create()
        controller.lock()
        finishLock()
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        main.runNext()
        val lateUnlock = main.take()
        controller.panic()
        controller.goTo(AppRoute.PublicSettings)
        lateUnlock.run()
        main.until { !controller.unlocking.value }
        assertEquals(AppRoute.PublicSettings, controller.route.value)
        assertTrue(controller.albums.value.isEmpty())
        assertTrue(controller.slots.value.isEmpty())
        finishLock()
        assertEquals(AppRoute.PublicSettings, controller.route.value)
    }

    @Test
    fun a_late_idle_lock_completion_cannot_remove_a_newer_panic_cover() = scenario {
        create()
        clock.now += LockPolicy().idleTimeoutMs + 1
        val idleCheck = CoroutineScope(main).launch { controller.checkIdle() }
        main.runNext() // idle policy is evaluated in the real repository.
        main.runNext() // pre-close callback conceals on Main.
        val lateIdleCompletion = main.take() // native lock finished; Main cleanup is held.
        controller.panic()
        lateIdleCompletion.run()
        assertTrue(idleCheck.isCompleted)
        assertTrue(cover.covered, "an old idle completion removed the new panic cover")
        assertEquals(AppRoute.PublicShell, controller.route.value)
        finishLock()
    }

    @Test
    fun completing_an_old_lock_preserves_the_cover_on_a_newly_opened_credential_form() = scenario {
        create()
        controller.lock()
        controller.goTo(AppRoute.Unlock)
        finishLock()
        assertIs<VaultState.Locked>(controller.vaultState.value)
        assertEquals(AppRoute.Unlock, controller.route.value)
        assertTrue(cover.covered, "native lock completion exposed a credential form to screenshots")
    }

    @Test
    fun panic_discards_a_creation_result_returned_after_the_public_shell_is_visible() = scenario {
        createWhileHiding(offerRecovery = false, hide = { controller.panic() })
    }

    @Test
    fun background_discards_a_creation_recovery_phrase_returned_after_leaving() = scenario {
        createWhileHiding(offerRecovery = true, hide = { controller.background() })
    }

    @Test
    fun confirming_a_creation_phrase_cannot_reopen_the_vault_after_panic() = scenario {
        controller.goTo(AppRoute.CreateVault)
        controller.create(PASSWORD, true)
        main.until { controller.recoveryPhrase.value != null }
        controller.acknowledgeRecoveryPhrase()
        main.runNext()
        val lateConfirmation = main.take()
        controller.panic()
        lateConfirmation.run()
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertNull(controller.recoveryPhrase.value)
        finishLock()
    }

    @Test
    fun a_recovery_phrase_returning_after_lock_is_never_published() = scenario {
        create()
        controller.addRecoverySlot()
        main.runNext() // owner check succeeds; the native slot is staged off Main.
        val lateResult = main.take()
        controller.lock()
        lateResult.run()
        assertNull(controller.recoveryPhrase.value)
        assertEquals(AppRoute.PublicShell, controller.route.value)
        finishLock()
    }

    @Test
    fun completed_album_tag_slot_and_sharing_reads_cannot_repopulate_a_locked_projection() = scenario {
        create()
        controller.vault.createAlbum("Old identity album fixture")
        controller.vault.createTag("Old identity tag fixture")
        for (read in listOf<() -> Unit>(controller::loadAlbums, controller::loadTags, controller::loadSlots, controller::loadSharing)) {
            read()
            main.runNext()
            val lateResult = main.take()
            controller.panic()
            lateResult.run()
            assertTrue(controller.albums.value.isEmpty())
            assertTrue(controller.tags.value.isEmpty())
            assertTrue(controller.slots.value.isEmpty())
            assertNull(controller.sharingIdentity.value)
            assertNull(controller.sharingOverview.value)
            finishLock()
            controller.goTo(AppRoute.Unlock)
            controller.unlock(PASSWORD)
            main.until { controller.route.value == AppRoute.Vault }
            main.runNext() // enterVault's initial page returns.
        }
    }

    @Test
    fun a_result_from_the_first_identity_cannot_be_published_into_the_second_identity() = scenario {
        create()
        controller.vault.createAlbum("Only first identity fixture")
        controller.loadAlbums()
        main.runNext()
        val lateResult = main.take()
        controller.vault.lock(LockReason.USER)
        controller.vault.create("Second identity fixture password".encodeToByteArray(), false)
        lateResult.run()
        assertTrue(controller.albums.value.isEmpty())
        assertTrue(controller.vault.albums().isEmpty())
    }

    private suspend fun Fixture.createWhileHiding(offerRecovery: Boolean, hide: suspend Fixture.() -> Unit) {
        controller.goTo(AppRoute.CreateVault)
        controller.create(PASSWORD, offerRecovery)
        main.runNext()
        val lateResult = main.take() // Rust finished, but the result has not reached feature state.
        hide()
        lateResult.run()
        assertNull(controller.recoveryPhrase.value)
        assertEquals(AppRoute.PublicShell, controller.route.value)
        finishLock()
        assertTrue(controller.vaultState.value !is VaultState.Unlocked)
        assertTrue(controller.vaultState.value !is VaultState.Creating)
    }

    private fun scenario(test: suspend Fixture.() -> Unit) = runBlocking {
        val main = HeldMain()
        Dispatchers.setMain(main)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-discreet-race-${System.nanoTime()}").apply { mkdirs() }
        val cover = RecordingCover()
        val exports = Exports()
        val clock = ManualClock()
        val controller = ChurController(root.path, cover, exports, deviceUnlock = Owner,
            clock = { clock.now })
        try {
            controller.vault.start()
            withTimeout(30_000) { Fixture(controller, main, cover, exports, clock).test() }
        } finally {
            controller.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private class Fixture(val controller: ChurController, val main: HeldMain,
                          val cover: RecordingCover, val exports: Exports, val clock: ManualClock) {
        suspend fun create() {
            controller.goTo(AppRoute.CreateVault)
            controller.create(PASSWORD, false)
            main.until { controller.route.value == AppRoute.Vault }
            main.runNext() // enterVault's initial page returns.
        }
        suspend fun completeRead(read: () -> Unit) { read(); main.runNext(); main.runNext() }
        suspend fun awaitPhraseRequestCompletion() {
            val requested = controller.javaClass.getDeclaredField("phraseRequested").apply { isAccessible = true }
            main.until { !requested.getBoolean(controller) }
        }
        suspend fun finishLock() {
            // Native state publication precedes the lock coroutine's Main
            // cleanup. Observe that Job, including when AppUnlock correctly
            // keeps its privacy cover enabled after successful cleanup.
            val pending = controller.javaClass.getDeclaredField("pendingLock").apply { isAccessible = true }
                .get(controller) as? Job
            main.until { pending == null || pending.isCompleted }
            assertTrue(controller.vaultState.value !is VaultState.Unlocked)
            assertTrue(controller.vaultState.value !is VaultState.Creating)
        }
    }

    private class HeldMain : CoroutineDispatcher() {
        private val continuations = Channel<Runnable>(Channel.UNLIMITED)
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(continuations.trySend(block).isSuccess) }
        suspend fun take(): Runnable = continuations.receive()
        suspend fun runNext() { take().run() }
        suspend fun until(done: () -> Boolean) { while (!done()) runNext() }
    }

    private object Owner : DeviceUnlock {
        override val available = true
        override suspend fun confirmOwner(strict: Boolean): OwnerCheck = OwnerCheck.CONFIRMED
        override suspend fun wrap(alias: ByteArray, aad: ByteArray, rootSecret: ByteArray): Pair<ByteArray, ByteArray> = error("no enrolled slot")
        override suspend fun unwrap(alias: ByteArray, aad: ByteArray, gcmNonce: ByteArray, wrappedRootSecret: ByteArray): ByteArray? = null
    }
    private class ManualClock {
        @Volatile var now = 1_700_000_000_000L
    }
    private class RecordingCover : PrivacyCover {
        var covered = false
        override fun setEnabled(enabled: Boolean) { this.covered = enabled }
    }
    private class Exports : ExportSink {
        var failCleanup = false
        var cleanupCalls = 0
        override fun cancelPending() {
            cleanupCalls += 1
            check(!failCleanup) { "public provider failure fixture" }
        }
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }
    private companion object {
        const val PASSWORD = "Discreet race fixture password"
        const val SECOND_PASSWORD = "Second cleanup barrier fixture password"
    }
}
