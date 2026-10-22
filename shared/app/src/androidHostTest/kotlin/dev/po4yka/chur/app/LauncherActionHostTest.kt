package dev.po4yka.chur.app

import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LauncherActionHostTest {
    @Test
    fun a_cold_command_waits_for_startup_and_never_creates_a_vault() = scenario(start = false) {
        assertTrue(controller.requestLauncherAction(LauncherAction.IMPORT))
        assertNull(controller.launcherRequest.value)
        controller.start()
        assertIs<VaultState.NoVault>(controller.vaultState.value)
        assertEquals(AppRoute.PublicSettings, controller.route.value)
        assertNull(controller.launcherRequest.value)
    }

    @Test
    fun a_restored_whole_app_preference_does_not_drop_the_no_vault_setup_request() = scenario(start = false, appLock = true) {
        controller.requestLauncherAction(LauncherAction.IMPORT)
        controller.start()
        assertEquals(AppRoute.PublicSettings, controller.route.value)
        assertIs<VaultState.NoVault>(controller.vaultState.value)
        assertFalse(controller.appLockEnabled.value)
    }

    @Test
    fun lock_blocks_followup_navigation_before_its_coroutine_can_run() = scenario {
        create()
        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        try {
            controller.requestLauncherAction(LauncherAction.LOCK_AND_HIDE)
            assertEquals(AppRoute.PublicShell, controller.route.value)
            assertFalse(controller.requestLauncherAction(LauncherAction.SEARCH))
            assertNull(controller.launcherRequest.value)
        } finally {
            Dispatchers.setMain(Dispatchers.Unconfined)
            paused.scheduler.runCurrent()
        }
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
    }

    @Test
    fun lock_hides_a_recovery_phrase_before_any_asynchronous_native_cleanup() = scenario {
        controller.create(PASSWORD, offerRecovery = true)
        withTimeout(10_000) { controller.recoveryPhrase.first { it != null } }
        assertNotNull(controller.recoveryPhrase.value)
        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        try {
            controller.requestLauncherAction(LauncherAction.LOCK_AND_HIDE)
            assertNull(controller.recoveryPhrase.value)
            assertEquals(AppRoute.PublicShell, controller.route.value)
        } finally {
            Dispatchers.setMain(Dispatchers.Unconfined)
            paused.scheduler.runCurrent()
        }
        withTimeout(10_000) { controller.vaultState.first { it !is VaultState.Creating } }
    }

    @Test
    fun locked_command_survives_a_wrong_password_and_dispatches_once_after_unlock() = scenario {
        create()
        lock()
        assertTrue(controller.requestLauncherAction(LauncherAction.IMPORT))
        assertEquals(AppRoute.Unlock, controller.route.value)
        assertNull(controller.launcherRequest.value)
        controller.unlock("wrong-password")
        waitForAttempt()
        withTimeout(10_000) { controller.formError.first { it != null } }
        assertNotNull(controller.formError.value)
        assertIs<VaultState.Locked>(controller.vaultState.value)
        controller.unlock(PASSWORD)
        waitForAttempt()
        val request = assertNotNull(controller.launcherRequest.value)
        assertEquals(LauncherAction.IMPORT, request.action)
        assertEquals(AppRoute.Vault, controller.route.value)
        assertTrue(controller.acknowledgeLauncherAction(request.token, request.generation))
        assertFalse(controller.acknowledgeLauncherAction(request.token, request.generation))
        assertNull(controller.launcherRequest.value)
    }

    @Test
    fun explicit_private_action_uses_one_whole_app_authentication_but_normal_unlock_still_closes_the_session() = scenario {
        create()
        controller.toggleAppLock()
        withTimeout(10_000) { controller.appLockEnabled.first { it } }
        controller.onBackground()
        assertEquals(AppRoute.AppUnlock, controller.route.value)
        controller.requestLauncherAction(LauncherAction.SEARCH)
        controller.unlock(PASSWORD)
        waitForAttempt()
        val request = assertNotNull(controller.launcherRequest.value)
        assertEquals(LauncherAction.SEARCH, request.action)
        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        assertTrue(controller.acknowledgeLauncherAction(request.token, request.generation))
        controller.onBackground()
        controller.unlock(PASSWORD)
        waitForAttempt()
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertIs<VaultState.Locked>(controller.vaultState.value)
    }

    @Test
    fun recovery_authenticates_the_pending_private_action_without_a_second_unlock() = scenario {
        controller.create(PASSWORD, offerRecovery = true)
        withTimeout(10_000) { controller.recoveryPhrase.first { it != null } }
        val phrase = assertNotNull(controller.recoveryPhrase.value)
        controller.acknowledgeRecoveryPhrase()
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        controller.toggleAppLock()
        withTimeout(10_000) { controller.appLockEnabled.first { it } }
        lock()
        assertTrue(controller.requestLauncherAction(LauncherAction.NEW_PRIVATE_NOTE))
        controller.goTo(AppRoute.AppRecover)
        controller.recover(phrase)
        waitForAttempt()
        assertEquals(AppRoute.Vault, controller.route.value)
        assertEquals(LauncherAction.NEW_PRIVATE_NOTE, assertNotNull(controller.launcherRequest.value).action)
        assertIs<VaultState.Unlocked>(controller.vaultState.value)
    }

    @Test
    fun cancel_and_background_discard_commands_before_an_unrelated_unlock() = scenario {
        create()
        lock()
        controller.requestLauncherAction(LauncherAction.NEW_PRIVATE_NOTE)
        controller.goTo(AppRoute.PublicSettings)
        controller.openVaultEntry()
        controller.unlock(PASSWORD)
        waitForAttempt()
        assertNull(controller.launcherRequest.value)
        lock()
        controller.requestLauncherAction(LauncherAction.IMPORT)
        controller.onBackground()
        controller.openVaultEntry()
        controller.unlock(PASSWORD)
        waitForAttempt()
        assertNull(controller.launcherRequest.value)
    }

    @Test
    fun an_open_session_accepts_each_command_without_reauthentication_and_rejects_busy_navigation() = scenario {
        create()
        val generation = assertIs<VaultState.Unlocked>(controller.vaultState.value).generation
        for (action in listOf(LauncherAction.IMPORT, LauncherAction.SEARCH, LauncherAction.NEW_PRIVATE_NOTE)) {
            assertTrue(controller.requestLauncherAction(action))
            val request = assertNotNull(controller.launcherRequest.value)
            assertEquals(action, request.action)
            assertEquals(generation, request.generation)
            assertTrue(controller.acknowledgeLauncherAction(request.token, generation))
        }
        controller.setLauncherNavigationBusy(true)
        assertFalse(controller.requestLauncherAction(LauncherAction.SEARCH))
        assertNull(controller.launcherRequest.value)
        controller.setLauncherNavigationBusy(false)
        controller.beginHostActivity()
        assertFalse(controller.requestLauncherAction(LauncherAction.IMPORT))
        controller.endHostActivity()
        assertEquals(generation, assertIs<VaultState.Unlocked>(controller.vaultState.value).generation)
        assertTrue(controller.requestLauncherAction(LauncherAction.IMPORT))
        val delayed = assertNotNull(controller.launcherRequest.value)
        controller.setLauncherNavigationBusy(true)
        assertFalse(controller.acknowledgeLauncherAction(delayed.token, generation))
        assertNull(controller.launcherRequest.value)
    }

    @Test
    fun lock_and_hide_masks_synchronously_overrides_busy_work_and_preserves_saved_private_notes() = scenario {
        create()
        val generation = assertIs<VaultState.Unlocked>(controller.vaultState.value).generation
        val note = controller.vault.savePrivateNote(generation, title = "Private", body = "Saved before lock")
        controller.requestLauncherAction(LauncherAction.NEW_PRIVATE_NOTE)
        val request = assertNotNull(controller.launcherRequest.value)
        controller.setLauncherNavigationBusy(true)
        controller.beginHostActivity()
        assertTrue(controller.requestLauncherAction(LauncherAction.LOCK_AND_HIDE))
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertNull(controller.launcherRequest.value)
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
        assertFalse(controller.acknowledgeLauncherAction(request.token, request.generation))
        controller.openVaultEntry()
        controller.unlock(PASSWORD)
        waitForAttempt()
        val nextGeneration = assertIs<VaultState.Unlocked>(controller.vaultState.value).generation
        assertEquals(note.body, controller.vault.privateNote(nextGeneration, note.noteId).body)
        assertTrue(controller.notesState.value.isEmpty())
        assertNull(controller.launcherRequest.value)
    }

    @Test
    fun lock_and_hide_respects_whole_app_lock_and_revokes_a_concurrent_unlock() = scenario {
        create()
        controller.toggleAppLock()
        withTimeout(10_000) { controller.appLockEnabled.first { it } }
        lock()
        controller.requestLauncherAction(LauncherAction.SEARCH)
        controller.unlock(PASSWORD)
        controller.requestLauncherAction(LauncherAction.LOCK_AND_HIDE)
        assertEquals(AppRoute.AppUnlock, controller.route.value)
        waitForAttempt()
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
        assertNull(controller.launcherRequest.value)
        assertEquals(AppRoute.AppUnlock, controller.route.value)
    }

    @Test
    fun an_undispatched_gate_request_can_be_replaced_but_never_crosses_an_identity_change() = scenario {
        create()
        lock()
        assertTrue(controller.requestLauncherAction(LauncherAction.IMPORT))
        controller.requestLauncherAction(LauncherAction.NEW_PRIVATE_NOTE)
        controller.unlock(PASSWORD)
        waitForAttempt()
        val first = assertNotNull(controller.launcherRequest.value)
        assertEquals(LauncherAction.NEW_PRIVATE_NOTE, first.action)
        controller.createSecondIdentity()
        assertNull(controller.launcherRequest.value)
        controller.create(SECOND_PASSWORD, offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        lock()
        controller.requestLauncherAction(LauncherAction.SEARCH)
        controller.unlock(SECOND_PASSWORD)
        waitForAttempt()
        val second = assertNotNull(controller.launcherRequest.value)
        assertEquals(LauncherAction.SEARCH, second.action)
        assertFalse(controller.acknowledgeLauncherAction(first.token, first.generation))
        assertTrue(controller.acknowledgeLauncherAction(second.token, second.generation))
    }

    @Test
    fun public_shortcuts_work_without_a_vault_and_new_note_is_delivered_once() = scenario(start = false) {
        assertTrue(controller.requestLauncherAction(LauncherAction.PUBLIC_NOTES))
        controller.start()
        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertIs<VaultState.NoVault>(controller.vaultState.value)
        assertNotNull(controller.takePublicNoteRequest())
        assertNull(controller.takePublicNoteRequest())
        assertTrue(controller.requestLauncherAction(LauncherAction.PUBLIC_CAMERA))
        assertEquals(PublicShell.CAMERA, controller.publicShell.value)
        assertNull(controller.launcherRequest.value)
    }

    @Test
    fun a_public_shortcut_revokes_inflight_authentication_and_respects_the_whole_app_gate() = scenario {
        create()
        lock()
        controller.openVaultEntry()
        val paused = StandardTestDispatcher()
        Dispatchers.setMain(paused)
        try {
            controller.unlock(PASSWORD)
            assertTrue(controller.requestLauncherAction(LauncherAction.PUBLIC_CALCULATOR))
            assertEquals(AppRoute.PublicShell, controller.route.value)
        } finally {
            Dispatchers.setMain(Dispatchers.Unconfined)
            paused.scheduler.runCurrent()
        }
        waitForAttempt()
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
        assertEquals(AppRoute.PublicShell, controller.route.value)
        controller.openVaultEntry()
        controller.unlock(PASSWORD)
        waitForAttempt()
        controller.toggleAppLock()
        withTimeout(10_000) { controller.appLockEnabled.first { it } }
        lock()
        assertTrue(controller.requestLauncherAction(LauncherAction.PUBLIC_CAMERA))
        assertEquals(AppRoute.AppUnlock, controller.route.value)
    }

    private fun scenario(start: Boolean = true, appLock: Boolean = false, test: suspend Fixture.() -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-launcher-${System.nanoTime()}")
        root.mkdirs()
        val fixture = Fixture(ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            appLockSetting = AppLockSetting({ appLock }, {}),
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
        ))
        try {
            if (start) fixture.controller.start()
            fixture.test()
        } finally {
            // Locked is published before the cleanup coroutine returns. Join it
            // before replacing Main, including the concurrent-unlock test.
            val cleanup = fixture.controller.javaClass.getDeclaredField("pendingLock")
                .apply { isAccessible = true }.get(fixture.controller) as? Job
            withTimeout(10_000) { cleanup?.join() }
            fixture.controller.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private class Fixture(val controller: ChurController) {
        suspend fun create() {
            controller.create(PASSWORD, offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            assertEquals(AppRoute.Vault, controller.route.value)
        }

        suspend fun lock() {
            controller.lock()
            // Locked is published by the repository before Main finishes projection
            // cleanup and reopens launcher admission. Await that operation, not its first state.
            val cleanup = checkNotNull(controller.javaClass.getDeclaredField("pendingLock")
                .apply { isAccessible = true }.get(controller) as? Job)
            withTimeout(10_000) { cleanup.join() }
            assertFalse(cleanup.isCancelled, "lock cleanup must complete successfully")
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
            withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell || it == AppRoute.AppUnlock } }
        }

        suspend fun waitForAttempt() {
            withTimeout(10_000) { controller.unlocking.first { !it } }
        }
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }

    private companion object {
        const val PASSWORD = "123456789012"
        const val SECOND_PASSWORD = "Second-identity-password"
    }
}
