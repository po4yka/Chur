package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.notes.InMemoryNoteStore
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the unlock and recovery forms learn from an attempt, against a real
 * controller and vault.
 *
 * A second refusal leaves the repository in an equal `Locked` state, so the
 * form tells one attempt from the next only through [ChurController.unlocking]:
 * it is busy while the attempt runs and blank in its error region, and a
 * screen reader hears the refusal again when it comes back, `DESIGN.md` §23.2.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnlockAttemptHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-attempt-${System.nanoTime()}")
    private lateinit var controller: ChurController

    @BeforeTest
    fun open(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root.mkdirs()
        controller = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
        )
        controller.start()
        controller.openVaultEntry()
        controller.create(PASSWORD, offerRecovery = false)
        withTimeout(TIMEOUT_MS) { controller.route.first { it == AppRoute.Vault } }
        controller.lock()
        withTimeout(TIMEOUT_MS) { controller.route.first { it == AppRoute.PublicShell } }
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        controller.vault.shutdown()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun every_refused_password_is_its_own_attempt(): Unit = runBlocking {
        controller.goTo(AppRoute.Unlock)
        repeat(2) { attempt ->
            controller.unlock("not the password")
            assertTrue(controller.unlocking.value, "attempt $attempt is busy")
            assertNull(controller.formError.value, "attempt $attempt shows the last refusal while it runs")

            assertEquals(
                userCopy(ChurStatus.AUTHENTICATION_FAILED),
                withTimeout(TIMEOUT_MS) { controller.formError.first { it != null } },
            )
            assertFalse(controller.unlocking.value, "attempt $attempt stays busy after its refusal")
        }
    }

    @Test
    fun a_phrase_with_wrong_words_reads_apart_from_one_that_opens_nothing(): Unit = runBlocking {
        controller.goTo(AppRoute.Recover)

        // `RECOVERY.md` §2.2: the checksum fails, so no slot is tried.
        controller.recover(List(24) { "abandon" }.joinToString(" "))
        val words = withTimeout(TIMEOUT_MS) { controller.formError.first { it != null } }
        assertEquals(recoveryCopy(ChurStatus.INVALID_INPUT), words)
        assertFalse(controller.unlocking.value)

        // A valid phrase that belongs to no slot of this vault.
        controller.recover((List(23) { "abandon" } + "art").joinToString(" "))
        val opened = withTimeout(TIMEOUT_MS) { controller.formError.first { it != null } }
        assertEquals(recoveryCopy(ChurStatus.AUTHENTICATION_FAILED), opened)
        assertFalse(controller.unlocking.value)
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
        const val PASSWORD = "Unlock-attempt-password"
        const val TIMEOUT_MS = 30_000L
    }
}
