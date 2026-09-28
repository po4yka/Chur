package dev.po4yka.chur.app

import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * The auto-lock choice of `DESIGN.md` §14.4, against a real controller and
 * vault with a clock the test moves.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoLockHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-auto-lock-${System.nanoTime()}")

    @Volatile
    private var now = 1_700_000_000_000L

    /** The public file the Android host keeps the choice in. */
    private var stored: String? = null
    private val setting = AutoLockSetting({ stored }, { stored = it })

    /** Every controller a case made, shut down after it whatever happened. */
    private val made = mutableListOf<ChurController>()

    private fun controller() = ChurController(
        storageRoot = root.absolutePath,
        privacy = NoPrivacyCover,
        exports = NoExports,
        autoLockSetting = setting,
        clock = { now },
        notes = InMemoryNoteStore(),
    ).also { made += it }

    @BeforeTest
    fun open() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root.mkdirs()
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        made.forEach { it.vault.shutdown() }
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun the_setting_round_trips_and_reads_anything_else_as_two_minutes() {
        assertEquals(AutoLock.AFTER_2_MINUTES, setting.read())
        for (choice in AutoLock.entries) {
            setting.write(choice)
            assertEquals(choice, setting.read())
        }
        setting.write(AutoLock.AFTER_30_SECONDS)
        assertEquals("30", stored)
        stored = "When device locks"
        assertEquals(AutoLock.DEFAULT, setting.read())
        // A zero limit is no choice: it would lock the vault on the next tick.
        stored = "0"
        assertEquals(AutoLock.DEFAULT, setting.read())
    }

    /**
     * No choice locks the vault before the user can use it. The idle timer
     * checks every second while the vault is open, and `setAutoLock` needs an
     * open vault, so a choice that locked within a few seconds could never be
     * undone; a running import or export, whose progress polls refresh the
     * idle clock, would stop at the first check as well.
     */
    @Test
    fun every_choice_keeps_a_vault_idle_for_ten_seconds_open(): Unit = runBlocking {
        val controller = open(PASSWORD)
        for (choice in AutoLock.entries) {
            controller.setAutoLock(choice)
            withTimeout(10_000) { controller.autoLock.first { it == choice } }

            now += 10_000
            controller.checkIdle()
            assertIs<VaultState.Unlocked>(controller.vaultState.value, "${choice.label} locked too soon")
        }
    }

    @Test
    fun thirty_seconds_locks_a_vault_idle_for_31_seconds_and_survives_relaunch(): Unit = runBlocking {
        val first = open(PASSWORD)
        assertEquals(AutoLock.DEFAULT, first.autoLock.value)

        // Forty idle seconds keep the vault open under the two-minute default.
        // The choice counts as use, so they do not lock it the moment the
        // shorter limit applies.
        now += 40_000
        first.setAutoLock(AutoLock.AFTER_30_SECONDS)
        withTimeout(10_000) { first.autoLock.first { it == AutoLock.AFTER_30_SECONDS } }
        assertEquals("30", stored)
        first.checkIdle()
        assertIs<VaultState.Unlocked>(first.vaultState.value)

        now += 31_000
        first.checkIdle()
        assertIs<VaultState.Locked>(first.vaultState.value)
        withTimeout(10_000) { first.route.first { it == AppRoute.PublicShell } }
        first.vault.shutdown()

        // A relaunch applies the stored choice before anything is unlocked.
        val relaunched = controller()
        assertEquals(AutoLock.AFTER_30_SECONDS, relaunched.autoLock.value)
        relaunched.start()
        relaunched.openVaultEntry()
        relaunched.unlock(PASSWORD)
        withTimeout(10_000) { relaunched.route.first { it == AppRoute.Vault } }
        settle()
        now += 31_000
        relaunched.checkIdle()
        assertIs<VaultState.Locked>(relaunched.vaultState.value)
    }

    @Test
    fun one_minute_keeps_a_vault_idle_for_31_seconds_open(): Unit = runBlocking {
        val controller = open(PASSWORD)
        controller.setAutoLock(AutoLock.AFTER_1_MINUTE)
        withTimeout(10_000) { controller.autoLock.first { it == AutoLock.AFTER_1_MINUTE } }

        now += 31_000
        controller.checkIdle()
        assertIs<VaultState.Unlocked>(controller.vaultState.value)

        now += 30_000
        controller.checkIdle()
        assertIs<VaultState.Locked>(controller.vaultState.value)
    }

    /** A controller over a new vault, open on the Library. */
    private suspend fun open(password: String): ChurController {
        val controller = controller()
        controller.start()
        controller.openVaultEntry()
        controller.create(password, offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        settle()
        return controller
    }

    /**
     * Lets the loads an open starts finish. Each one is a vault call that
     * refreshes the idle clock, and one that ran after the clock moved would
     * restart the idle time the case measures.
     */
    private suspend fun settle() = delay(500)

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
        const val PASSWORD = "Auto-lock-password"
    }
}
