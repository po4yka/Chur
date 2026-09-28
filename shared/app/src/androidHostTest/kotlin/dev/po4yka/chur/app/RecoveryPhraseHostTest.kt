package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.LockPolicy
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    /** What the device answers the owner check of `DESIGN.md` §17.1 step 2. */
    private var ownerAnswer = OwnerCheck.CONFIRMED

    /** The policy of every owner check asked, `true` for biometrics only. */
    private val ownerAsked = mutableListOf<Boolean>()

    /** A device that answers the owner check as [ownerAnswer] says and holds no slot. */
    private val owner = object : DeviceUnlock {
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

        override suspend fun confirmOwner(strict: Boolean): OwnerCheck {
            ownerAsked += strict
            return ownerAnswer
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
            deviceUnlock = owner,
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
    fun a_confirmed_phrase_from_settings_commits_one_slot_and_says_so(): Unit = runBlocking {
        controller.create(PASSWORD, offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        val before = controller.vault.slots().size

        // The creation's request ends only after the Library loads, which is
        // after the route reads `Vault`, and a request made before it ends is
        // ignored. A repeat is also ignored once the phrase shows, so asking
        // until it shows stages exactly one slot.
        withTimeout(10_000) {
            while (controller.recoveryPhrase.value == null) {
                controller.addRecoverySlot()
                delay(10)
            }
        }
        // `PROVISIONING.md` §4: the phrase on screen commits nothing yet.
        assertEquals(before, controller.vault.slots().size)

        // `DESIGN.md` §17.1 step 6: the user is told the slot committed.
        controller.acknowledgeRecoveryPhrase()
        assertEquals(
            "Recovery phrase saved.",
            withTimeout(10_000) { controller.notice.first { it != null } }?.text,
        )
        assertEquals(before + 1, controller.vault.slots().size)
        assertEquals(AppRoute.Vault, controller.route.value)
    }

    /**
     * `DESIGN.md` §17.1 step 2: an open vault is not proof of who holds the
     * device, so Settings shows a phrase only after the platform prompt, under
     * the device-slot policy in force.
     */
    @Test
    fun a_phrase_from_settings_waits_for_the_device_authentication(): Unit = runBlocking {
        controller.create(PASSWORD, offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        val before = controller.vault.slots()

        // A cancel is quiet, shows no phrase and stages nothing.
        ownerAnswer = OwnerCheck.CANCELLED
        askOwner()
        assertEquals(null, controller.recoveryPhrase.value)
        assertEquals(null, controller.notice.value)
        assertFalse(controller.vault.confirmRecoveryPhrase())
        assertEquals(before, controller.vault.slots())

        // "Biometrics only" reaches the prompt, and a device without the
        // factor is told what to set up rather than shown a phrase.
        controller.toggleDeviceSlotPolicy()
        withTimeout(10_000) { controller.deviceSlotStrict.first { it } }
        ownerAnswer = OwnerCheck.NOT_SET_UP
        askOwner()
        assertEquals(listOf(false, true), ownerAsked)
        assertEquals(null, controller.recoveryPhrase.value)
        val notice = assertNotNull(withTimeout(10_000) { controller.notice.first { it != null } })
        assertTrue(notice.text.startsWith("Set up a fingerprint or face unlock first."), notice.text)

        ownerAnswer = OwnerCheck.CONFIRMED
        askOwner()
        withTimeout(10_000) { controller.recoveryPhrase.first { it != null } }
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

    /**
     * Asks for a phrase until the owner check runs once more. A request made
     * while the creation's own request still ends is ignored.
     */
    private suspend fun askOwner() {
        val asked = ownerAsked.size
        withTimeout(10_000) {
            while (ownerAsked.size == asked) {
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
        const val PASSWORD = "Recovery-phrase-password"
    }
}
