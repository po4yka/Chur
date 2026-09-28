package dev.po4yka.chur.app

import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system prompts that hold the background lock off, against a real
 * controller and vault.
 *
 * A prompt only makes the scene resign active or the activity pause, so the
 * application entering the background during one means the user left, and
 * the vault locks at once, `DESIGN.md` §14.4, `IOS.md` §21.2 and `ANDROID.md`
 * §19.3. A prompt that answers after its session ended must not end a prompt
 * of the next one. The owner check here is the Local Authentication sheet of
 * the iOS host.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemPromptHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-prompt-${System.nanoTime()}")
    private lateinit var controller: ChurController

    /** Completes when the owner check is up. */
    private val asked = CompletableDeferred<Unit>()

    /** What the sheet answers, when the test says so. */
    private val answer = CompletableDeferred<OwnerCheck>()

    /** A Keychain that holds no slot and whose owner check waits for [answer]. */
    private val keychain = object : AppleDeviceUnlock {
        override val available = true

        override fun newItemId(): ByteArray = error("no Apple device slot here")
        override fun itemIds(): List<ByteArray> = emptyList()
        override fun storeSecret(itemId: ByteArray, secret: ByteArray, strict: Boolean): Unit =
            error("no Apple device slot here")
        override fun releaseSecret(itemId: ByteArray): ByteArray = error("no Apple device slot here")
        override fun beginUnlock() = Unit
        override fun endUnlock() = Unit

        override suspend fun confirmOwner(strict: Boolean): OwnerCheck {
            asked.complete(Unit)
            return answer.await()
        }
    }

    @BeforeTest
    fun open(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root.mkdirs()
        controller = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            appleDeviceUnlock = keychain,
            clock = { System.currentTimeMillis() },
            notes = InMemoryNoteStore(),
        )
        controller.start()
        controller.openVaultEntry()
        controller.create(PASSWORD, offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        controller.vault.shutdown()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun leaving_the_app_during_the_owner_check_locks_at_once(): Unit = runBlocking {
        askOwner()

        // The sheet makes the scene resign active: the prompt keeps the vault
        // open while the user answers it.
        controller.background()
        assertIs<VaultState.Unlocked>(controller.vaultState.value)

        // The scene enters the background, so the user left.
        controller.enteredBackground()
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }

        // An answer after that shows no phrase.
        answer.complete(OwnerCheck.CONFIRMED)
        assertEquals(null, controller.recoveryPhrase.value)
    }

    @Test
    fun a_prompt_of_an_ended_session_does_not_end_one_of_the_next(): Unit = runBlocking {
        askOwner()
        controller.lock()
        withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }

        // A prompt of the new session is up when the old owner check answers.
        // The old answer must not end it, so the vault stays open under it.
        val alert = controller.beginPrompt()
        answer.complete(OwnerCheck.CANCELLED)
        controller.background()
        assertNull(
            withTimeoutOrNull(1_000) { controller.vaultState.first { it is VaultState.Locked } },
            "the old session's answer ended the new session's prompt",
        )

        // The new prompt's own answer ends it, and the next leave locks.
        assertTrue(controller.endPrompt(alert))
        controller.background()
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
    }

    /**
     * Asks for a phrase until the owner check is up. A request made while the
     * creation's own request still ends is ignored.
     */
    private suspend fun askOwner() {
        withTimeout(10_000) {
            while (!asked.isCompleted) {
                controller.addRecoverySlot()
                delay(10)
            }
        }
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
        const val PASSWORD = "System-prompt-password"
    }
}
