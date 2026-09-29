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

/**
 * The Android device check before a recovery phrase, `DESIGN.md` §17.1 step
 * 2, against a real controller and vault.
 *
 * From API 30 the prompt and its device credential are SystemUI windows, so
 * the activity stops only when the user leaves, and the vault locks at once,
 * `ANDROID.md` §19.3. On API 29 the credential is a full-screen activity, so
 * the check holds the lock off as a picker does. Either bracket ends once.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidOwnerCheckHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-owner-${System.nanoTime()}")
    private lateinit var controller: ChurController

    /** Completes when the owner check is up. */
    private var asked = CompletableDeferred<Unit>()

    /** What the prompt answers, when the test says so. */
    private var answer = CompletableDeferred<OwnerCheck>()

    /** Whether the prompt can open a full-screen credential activity, as on API 29. */
    private var takesScreen = false

    /** A device that holds no slot and whose owner check waits for [answer]. */
    private val device = object : DeviceUnlock {
        override val available = true

        override suspend fun wrap(
            alias: ByteArray,
            aad: ByteArray,
            rootSecret: ByteArray,
        ): Pair<ByteArray, ByteArray> = throw UnsupportedOperationException("no device slot here")

        override suspend fun unwrap(
            alias: ByteArray,
            aad: ByteArray,
            gcmNonce: ByteArray,
            wrappedRootSecret: ByteArray,
        ): ByteArray? = null

        override fun ownerCheckTakesScreen(strict: Boolean): Boolean = takesScreen

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
            deviceUnlock = device,
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
    fun leaving_during_the_owner_check_on_api_30_locks_at_once(): Unit = runBlocking {
        askOwner()

        // Pressing Home pauses the activity first; the prompt holds the lock.
        controller.background()
        assertIs<VaultState.Unlocked>(controller.vaultState.value)

        // The activity stops, so the user left.
        controller.enteredBackground()
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }

        // SystemUI cancels the prompt as the app leaves; a late pass shows nothing.
        answer.complete(OwnerCheck.CONFIRMED)
        assertEquals(null, controller.recoveryPhrase.value)
    }

    @Test
    fun a_completed_or_cancelled_check_ends_its_bracket_once(): Unit = runBlocking {
        for (screen in listOf(false, true)) {
            for (end in listOf(OwnerCheck.CONFIRMED, OwnerCheck.CANCELLED)) {
                val case = "API ${if (screen) 29 else 30}, $end"
                takesScreen = screen
                asked = CompletableDeferred()
                answer = CompletableDeferred()

                // A picker bracket is open around the check. A check that
                // ended its bracket twice would end the picker's too.
                controller.beginHostActivity()
                askOwner()
                answer.complete(end)
                if (end == OwnerCheck.CONFIRMED) {
                    withTimeout(10_000) { controller.recoveryPhrase.first { it != null } }
                }
                controller.background()
                assertNull(
                    withTimeoutOrNull(1_000) { controller.vaultState.first { it is VaultState.Locked } },
                    "$case: the check ended the picker's bracket",
                )

                // The picker returns. A check that left its bracket open
                // would still hold the lock off.
                controller.endHostActivity()
                controller.background()
                withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
                assertIs<VaultState.Locked>(controller.vaultState.value, case)
                assertNull(controller.recoveryPhrase.value, "$case: the lock kept the phrase")

                controller.goTo(AppRoute.Unlock)
                controller.unlock(PASSWORD)
                withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            }
        }
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
        const val PASSWORD = "Owner-check-password"
    }
}
