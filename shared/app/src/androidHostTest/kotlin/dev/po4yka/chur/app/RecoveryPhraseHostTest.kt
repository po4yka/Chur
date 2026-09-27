package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.LockPolicy
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The recovery phrase of a creation, against a real controller and vault.
 *
 * `PROVISIONING.md` §4 commits nothing the user has not confirmed, and
 * `DESIGN.md` §17.2 bounds the phrase at ten minutes after it appeared. The
 * repository's half is pinned by `VaultRepositoryHostTest`; these pin what the
 * controller shows.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecoveryPhraseHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-phrase-${System.nanoTime()}")

    @Volatile
    private var now = 1_700_000_000_000L
    private lateinit var controller: ChurController

    @BeforeTest
    fun open(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root.mkdirs()
        controller = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            clock = { now },
            notes = InMemoryNoteStore(),
        )
        controller.start()
        controller.openVaultEntry()
        assertEquals(AppRoute.CreateVault, controller.route.value)
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        controller.vault.shutdown()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun the_idle_timer_clears_a_phrase_whose_window_has_passed(): Unit = runBlocking {
        controller.create(PASSWORD, offerRecovery = true)
        withTimeout(10_000) { controller.recoveryPhrase.first { it != null } }
        assertIs<VaultState.Creating>(controller.vaultState.value)

        // The idle timer runs the check. The lock publishes `NoVault` from
        // inside it, which ends the timer's `collectLatest` block, so the
        // clearing after the lock must not depend on that block.
        now += LockPolicy.RECOVERY_PHRASE_TIMEOUT_MS
        withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }

        assertEquals(null, controller.recoveryPhrase.value)
        assertIs<VaultState.NoVault>(controller.vaultState.value)
    }

    @Test
    fun a_second_create_while_the_first_runs_is_ignored(): Unit = runBlocking {
        // A double tap: the second request would abandon the creation whose
        // phrase the first one shows.
        controller.create(PASSWORD, offerRecovery = true)
        controller.create(PASSWORD, offerRecovery = true)
        val phrase = assertNotNull(withTimeout(10_000) { controller.recoveryPhrase.first { it != null } })
        controller.acknowledgeRecoveryPhrase()
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        controller.lock()
        withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }

        controller.goTo(AppRoute.Recover)
        controller.recover(phrase)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
    }

    @Test
    fun a_phrase_a_lock_discarded_is_reported_as_not_saved(): Unit = runBlocking {
        controller.create(PASSWORD, offerRecovery = true)
        withTimeout(10_000) { controller.recoveryPhrase.first { it != null } }
        // A lock that reaches the repository before the Continue does, and
        // whose clearing has not reached the screen yet.
        controller.vault.lock(LockReason.BACKGROUND)

        controller.acknowledgeRecoveryPhrase()

        assertEquals(
            "This recovery phrase was not saved.",
            withTimeout(10_000) { controller.formError.first { it != null } },
        )
        assertIs<VaultState.NoVault>(controller.vaultState.value)
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(
            displayName: String,
            contentType: String,
            target: ExportTarget,
            uri: String?,
        ): ExportSink.Destination? = null
    }

    private companion object {
        const val PASSWORD = "Recovery-phrase-password"
    }
}
