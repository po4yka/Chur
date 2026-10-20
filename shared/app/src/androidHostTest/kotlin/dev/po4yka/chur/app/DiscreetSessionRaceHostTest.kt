package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.vault.LockPolicy
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    fun launcher_admission_waits_for_cleanup_after_the_native_locked_state_is_published() = scenario {
        controller.start()
        create()
        val cleanup = withRepositoryBlocked {
            controller.lock()
            pendingLock().also { main.take(it).run() }
        }
        // The repository mutex forced a real suspension. Native teardown has now
        // returned, but its Main continuation is held before projection cleanup.
        val nativeCompletion = main.take(cleanup)
        assertIs<VaultState.Locked>(controller.vaultState.value)
        assertFalse(cleanup.isCompleted)
        assertFalse(controller.requestLauncherAction(LauncherAction.SEARCH))
        assertNull(controller.launcherRequest.value)
        nativeCompletion.run()
        finishLock()
        assertFalse(cleanup.isCancelled)
        assertTrue(controller.requestLauncherAction(LauncherAction.SEARCH))
        assertEquals(AppRoute.Unlock, controller.route.value)
        assertNull(controller.launcherRequest.value, "a locked session still requires authentication")
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
        completeRead { controller.unlock(PASSWORD) }
        assertEquals(AppRoute.Vault, controller.route.value)
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
        val lateRetry = withRepositoryBlocked {
            val unlock = actionJob { controller.unlock(PASSWORD) }
            main.take(unlock).run() // Queues the one cleanup retry and waits for it.
            val retry = pendingLock()
            main.take(retry).run() // Native teardown suspends on the held mutex.
            retry
        }.let { main.take(it) }
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
        completeRead { controller.create(SECOND_PASSWORD, false) }
        assertEquals(AppRoute.Vault, controller.route.value)
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
        completeRead { controller.create(SECOND_PASSWORD, false) }
        assertEquals(AppRoute.Vault, controller.route.value)
        completeRead { controller.loadSlots() }
        assertTrue(controller.slots.value.isNotEmpty())
        assertTrue(controller.vault.albums().isEmpty(), "the recovered create opens its own identity")
    }

    @Test
    fun a_new_unlock_waits_for_the_earlier_lock_and_then_remains_open() = scenario {
        create()
        controller.lock()
        controller.goTo(AppRoute.Unlock)
        completeRead { controller.unlock(PASSWORD) }
        assertEquals(AppRoute.Vault, controller.route.value)
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
        val authorization = holdNativeResult { controller.unlock(PASSWORD) }
        val lateGateCompletion = withRepositoryBlocked {
            authorization.run() // Successful credentials queue the tracked whole-app close.
            val close = pendingLock()
            main.take(close).run()
            close
        }.let { main.take(it) }
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
        val lateUnlock = holdNativeResult { controller.unlock(PASSWORD) }
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
        val name = CoroutineName("held-idle-check")
        val idleCheck = withRepositoryBlocked {
            CoroutineScope(main + name).launch { controller.checkIdle() }.also {
                main.take(it).run() // Idle policy suspends on the held repository mutex.
            }
        }
        main.take(name).run() // The real repository's before-close callback conceals on Main.
        val lateIdleCompletion = main.take(name) // Native lock finished; Main cleanup is held.
        controller.panic()
        lateIdleCompletion.run()
        main.until { idleCheck.isCompleted }
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
    fun fresh_authentication_waits_for_a_held_stale_creation_and_all_its_teardown_decisions() = scenario {
        controller.goTo(AppRoute.CreateVault)
        val oldCreationResult = holdNativeResult { controller.create(PASSWORD, false) }
        val identity = assertNotNull(controller.vault.syncIdentity()).vaultId
        controller.panic()
        controller.goTo(AppRoute.Unlock)
        val freshUnlock = actionJob { controller.unlock(PASSWORD) }
        assertTrue(controller.unlocking.value)
        main.take(pendingLock()).run() // Cleanup suspends on the old native ticket.
        main.take(freshUnlock).run() // Fresh authentication suspends on that cleanup.
        oldCreationResult.run() // queues stale teardown, then releases the ticket.
        assertTrue(controller.unlocking.value, "old request finally cleared the new busy state")
        main.until { freshUnlock.isCompleted }
        assertEquals(AppRoute.Vault, controller.route.value)
        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        assertContentEquals(identity, assertNotNull(controller.vault.syncIdentity()).vaultId)
        controller.vault.createAlbum("Fresh album after stale creation")
        completeRead { controller.loadAlbums() }
        assertEquals("Fresh album after stale creation", controller.albums.value.single().name)
    }

    @Test
    fun a_stale_unlock_finally_cannot_clear_busy_or_close_the_fresh_authenticated_session() = scenario {
        create()
        controller.lock()
        finishLock()
        controller.goTo(AppRoute.Unlock)
        val oldUnlockResult = holdNativeResult { controller.unlock(PASSWORD) }
        val identity = assertNotNull(controller.vault.syncIdentity()).vaultId
        controller.panic()
        controller.goTo(AppRoute.Unlock)
        val freshUnlock = actionJob { controller.unlock(PASSWORD) }
        main.take(pendingLock()).run()
        main.take(freshUnlock).run()
        oldUnlockResult.run()
        assertTrue(controller.unlocking.value, "stale unlock finally cleared a fresh request's busy flag")
        main.until { freshUnlock.isCompleted }
        assertEquals(AppRoute.Vault, controller.route.value)
        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        assertContentEquals(identity, assertNotNull(controller.vault.syncIdentity()).vaultId)
        controller.vault.createAlbum("Fresh album after stale unlock")
        completeRead { controller.loadAlbums() }
        assertEquals("Fresh album after stale unlock", controller.albums.value.single().name)
    }

    @Test
    fun an_old_apple_context_finishes_before_a_new_apple_prompt_can_own_the_binding() {
        val apple = BlockingApple()
        scenario(apple) {
            create()
            controller.vault.enrollAppleSlot(apple.itemId) { apple.secret = it.copyOf() }
            controller.lock()
            finishLock()
            controller.goTo(AppRoute.Unlock)
            val oldAppleRequest = actionJob { controller.unlockWithAppleDevice() }
            main.awaitSignal(apple.entered[0])
            controller.panic()
            controller.goTo(AppRoute.Unlock)
            val freshAppleRequest = actionJob { controller.unlockWithAppleDevice() }
            finishLock()
            main.barrier()
            assertEquals(1, apple.begins.get(), "new prompt must wait for the previous binding context")
            apple.releases[0].countDown()
            main.awaitSignal(apple.entered[1])
            assertEquals(listOf("begin1", "end1", "begin2"), apple.events.toList())
            assertEquals(2, apple.active.get(), "old finally must not invalidate the new context")
            assertTrue(controller.unlocking.value, "old request finally changed the new busy state")
            apple.releases[1].countDown()
            main.until { freshAppleRequest.isCompleted && oldAppleRequest.isCompleted }
            assertEquals(AppRoute.Vault, controller.route.value)
            main.awaitSignal(apple.ended[1])
            assertEquals(0, apple.active.get())
            assertIs<VaultState.Unlocked>(controller.vaultState.value)
            completeRead { controller.loadSlots() }
            assertTrue(controller.slots.value.isNotEmpty())
        }
    }

    @Test
    fun fresh_password_authentication_does_not_wait_for_a_stale_apple_prompt_or_its_finally() {
        val apple = BlockingApple()
        scenario(apple) {
            create()
            controller.vault.enrollAppleSlot(apple.itemId) { apple.secret = it.copyOf() }
            val identity = assertNotNull(controller.vault.syncIdentity()).vaultId
            controller.lock()
            finishLock()
            controller.goTo(AppRoute.Unlock)
            val oldAppleRequest = actionJob { controller.unlockWithAppleDevice() }
            main.awaitSignal(apple.entered[0])
            controller.panic()
            controller.goTo(AppRoute.Unlock)
            controller.unlock(PASSWORD)
            main.until { controller.route.value == AppRoute.Vault && !controller.unlocking.value }
            assertEquals(1, apple.active.get(), "the old OS prompt remains deliberately pending")
            assertContentEquals(identity, assertNotNull(controller.vault.syncIdentity()).vaultId)
            controller.vault.createAlbum("Password session while Apple prompt is pending")
            completeRead { controller.loadAlbums() }
            apple.releases[0].countDown()
            main.awaitSignal(apple.ended[0])
            main.until { oldAppleRequest.isCompleted }
            assertIs<VaultState.Unlocked>(controller.vaultState.value)
            assertEquals(AppRoute.Vault, controller.route.value)
            assertEquals("Password session while Apple prompt is pending", controller.albums.value.single().name)
        }
    }

    @Test
    fun successful_old_apple_end_unlock_cannot_close_a_fresh_password_session() {
        for (background in listOf(false, true)) {
            val apple = BlockingApple(holdEnd = true)
            scenario(apple) {
                create()
                controller.vault.enrollAppleSlot(apple.itemId) { apple.secret = it.copyOf() }
                val identity = assertNotNull(controller.vault.syncIdentity()).vaultId
                controller.lock()
                finishLock()
                controller.goTo(AppRoute.Unlock)
                val oldAppleRequest = actionJob { controller.unlockWithAppleDevice() }
                main.awaitSignal(apple.entered[0])
                apple.releases[0].countDown()
                main.awaitSignal(apple.endEntered[0]) // old Apple native open succeeded; platform teardown is held.
                val oldGeneration = (controller.vaultState.value as VaultState.Unlocked).generation
                if (background) controller.background() else controller.panic()
                assertEquals(AppRoute.PublicShell, controller.route.value)
                controller.goTo(AppRoute.Unlock)
                completeRead { controller.unlock(PASSWORD) }
                assertEquals(AppRoute.Vault, controller.route.value)
                val freshGeneration = (controller.vaultState.value as VaultState.Unlocked).generation
                assertTrue(oldGeneration != freshGeneration)
                controller.vault.createAlbum("Fresh password album before old Apple end returns")
                completeRead { controller.loadAlbums() }
                apple.endReleases[0].countDown()
                main.awaitSignal(apple.ended[0])
                main.until { oldAppleRequest.isCompleted }
                assertEquals(freshGeneration, (controller.vaultState.value as VaultState.Unlocked).generation)
                assertContentEquals(identity, assertNotNull(controller.vault.syncIdentity()).vaultId)
                assertEquals(AppRoute.Vault, controller.route.value)
                assertEquals("Fresh password album before old Apple end returns", controller.albums.value.single().name)
                completeRead { controller.loadAlbums() }
                assertEquals("Fresh password album before old Apple end returns", controller.albums.value.single().name)
            }
        }
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
        val lateConfirmation = holdNativeResult { controller.acknowledgeRecoveryPhrase() }
        controller.panic()
        lateConfirmation.run()
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertNull(controller.recoveryPhrase.value)
        finishLock()
    }

    @Test
    fun a_recovery_phrase_returning_after_lock_is_never_published() = scenario {
        create()
        val lateResult = holdNativeResult { controller.addRecoverySlot() }
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
        for (read in listOf<() -> Unit>(controller::loadAlbums, controller::loadTags, controller::loadSlots)) {
            val lateResult = holdNativeResult(read)
            controller.panic()
            lateResult.run()
            assertTrue(controller.albums.value.isEmpty())
            assertTrue(controller.tags.value.isEmpty())
            assertTrue(controller.slots.value.isEmpty())
            finishLock()
            controller.goTo(AppRoute.Unlock)
            completeRead { controller.unlock(PASSWORD) }
            assertEquals(AppRoute.Vault, controller.route.value)
        }
    }

    @Test
    fun a_result_from_the_first_identity_cannot_be_published_into_the_second_identity() = scenario {
        create()
        controller.vault.createAlbum("Only first identity fixture")
        val lateResult = holdNativeResult { controller.loadAlbums() }
        controller.vault.lock(LockReason.USER)
        controller.vault.create("Second identity fixture password".encodeToByteArray(), false)
        lateResult.run()
        assertTrue(controller.albums.value.isEmpty())
        assertTrue(controller.vault.albums().isEmpty())
    }

    private suspend fun Fixture.createWhileHiding(offerRecovery: Boolean, hide: suspend Fixture.() -> Unit) {
        controller.goTo(AppRoute.CreateVault)
        val lateResult = holdNativeResult { controller.create(PASSWORD, offerRecovery) }
        hide()
        lateResult.run()
        assertNull(controller.recoveryPhrase.value)
        assertEquals(AppRoute.PublicShell, controller.route.value)
        finishLock()
        assertTrue(controller.vaultState.value !is VaultState.Unlocked)
        assertTrue(controller.vaultState.value !is VaultState.Creating)
    }

    private fun scenario(apple: AppleDeviceUnlock = NoAppleDeviceUnlock,
        test: suspend Fixture.() -> Unit) = runBlocking {
        val main = HeldMain()
        Dispatchers.setMain(main)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-discreet-race-${System.nanoTime()}").apply { mkdirs() }
        val cover = RecordingCover()
        val exports = Exports()
        val clock = ManualClock()
        val controller = ChurController(root.path, cover, exports, deviceUnlock = Owner, appleDeviceUnlock = apple,
            clock = { clock.now })
        try {
            controller.vault.start()
            withTimeout(30_000) { Fixture(controller, main, cover, exports, clock).test() }
        } finally {
            if (apple is BlockingApple) {
                apple.releases.forEach { it.countDown() }
                apple.endReleases.forEach { it.countDown() }
            }
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
            // Finish the request, including its initial page. A count of dispatcher
            // hops is not a barrier: fast withContext work need not suspend.
            awaitPhraseRequestCompletion()
        }
        fun actionJob(action: () -> Unit): Job {
            val scope = controller.javaClass.getDeclaredField("scope").apply { isAccessible = true }
                .get(controller) as CoroutineScope
            val parent = checkNotNull(scope.coroutineContext[Job])
            val before = parent.children.toSet()
            action()
            // The guarded action is attached synchronously, before HeldMain can run it.
            return parent.children.filterNot { it in before }.single()
        }
        suspend fun completeRead(read: () -> Unit) {
            val operation = actionJob(read)
            // withContext may finish inline; await the action, not an assumed number of hops.
            main.until { operation.isCompleted }
        }
        suspend fun <T> withRepositoryBlocked(body: suspend () -> T): T {
            val mutex = controller.vault.javaClass.getDeclaredField("mutex").apply { isAccessible = true }
                .get(controller.vault) as Mutex
            mutex.lock()
            return try { body() } finally { mutex.unlock() }
        }
        suspend fun holdNativeResult(action: () -> Unit): Runnable {
            val operation = withRepositoryBlocked {
                actionJob(action).also {
                    // Force withContext to suspend Main before allowing the real native call.
                    // Otherwise a fast Default call can return inline, leaving no result to hold.
                    main.take(it).run()
                    assertTrue(it.isActive, "the action must wait for its repository call")
                }
            }
            return main.take(operation)
        }
        fun pendingLock(): Job = checkNotNull(
            controller.javaClass.getDeclaredField("pendingLock").apply { isAccessible = true }
                .get(controller) as? Job,
        )
        suspend fun awaitPhraseRequestCompletion() {
            val requested = controller.javaClass.getDeclaredField("phraseRequested").apply { isAccessible = true }
            main.until { !requested.getBoolean(controller) }
        }
        suspend fun finishLock() {
            // Native state publication precedes the lock coroutine's Main
            // cleanup. Observe that Job, including when AppUnlock correctly
            // keeps its privacy cover enabled after successful cleanup.
            val pending = pendingLock()
            main.until { pending.isCompleted }
            assertTrue(controller.vaultState.value !is VaultState.Unlocked)
            assertTrue(controller.vaultState.value !is VaultState.Creating)
        }
    }

    private class HeldMain : CoroutineDispatcher() {
        private data class Continuation(val context: CoroutineContext, val block: Runnable)
        private val continuations = Channel<Continuation>(Channel.UNLIMITED)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(continuations.trySend(Continuation(context, block)).isSuccess)
        }
        private suspend fun take(matching: (CoroutineContext) -> Boolean): Runnable {
            while (true) {
                val next = continuations.receive()
                if (matching(next.context)) return next.block
                next.block.run()
            }
        }
        suspend fun take(job: Job): Runnable = take { it[Job] === job }
        suspend fun take(name: CoroutineName): Runnable = take { it[CoroutineName] == name }
        suspend fun take(): Runnable = continuations.receive().block
        suspend fun runNext() { take().run() }
        suspend fun until(done: () -> Boolean) { while (!done()) runNext() }
        suspend fun <T> awaitSignal(signal: Deferred<T>): T {
            while (!signal.isCompleted) {
                select<Unit> {
                    signal.onAwait { }
                    continuations.onReceive { it.block.run() }
                }
            }
            return signal.await()
        }
        suspend fun barrier() {
            val done = CompletableDeferred<Unit>()
            CoroutineScope(this).launch { done.complete(Unit) }
            awaitSignal(done)
        }
    }

    private class BlockingApple(private val holdEnd: Boolean = false) : AppleDeviceUnlock {
        override val available = true
        val itemId = ByteArray(16) { 7 }
        var secret = ByteArray(0)
        val begins = AtomicInteger(0)
        val active = AtomicInteger(0)
        val entered = List(2) { CompletableDeferred<Unit>() }
        val ended = List(2) { CompletableDeferred<Unit>() }
        val endEntered = List(2) { CompletableDeferred<Unit>() }
        val endReleases = List(2) { CountDownLatch(if (holdEnd) 1 else 0) }
        val releases = List(2) { CountDownLatch(1) }
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun newItemId() = itemId.copyOf()
        override fun itemIds() = listOf(itemId.copyOf())
        override fun storeSecret(itemId: ByteArray, secret: ByteArray, strict: Boolean) { this.secret = secret.copyOf() }
        override fun beginUnlock() {
            check(active.get() == 0) { "a previous Apple context still owns the binding" }
            val owner = begins.incrementAndGet()
            active.set(owner)
            events += "begin$owner"
        }
        override fun releaseSecret(itemId: ByteArray): ByteArray {
            val owner = active.get()
            entered[owner - 1].complete(Unit)
            check(releases[owner - 1].await(30, TimeUnit.SECONDS)) { "the test did not release its Apple prompt" }
            check(active.get() == owner) { "the context was invalidated while the OS prompt was pending" }
            return secret.copyOf()
        }
        override fun endUnlock() {
            val owner = active.get()
            endEntered[owner - 1].complete(Unit)
            check(endReleases[owner - 1].await(30, TimeUnit.SECONDS)) { "the test did not release Apple teardown" }
            check(active.compareAndSet(owner, 0)) { "old teardown invalidated a newer Apple context" }
            events += "end$owner"
            ended[owner - 1].complete(Unit)
        }
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
