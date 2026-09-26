package dev.po4yka.chur.app

import dev.po4yka.chur.notes.InMemoryNoteStore
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
import kotlin.test.assertTrue

/**
 * A credential that opens or creates a vault while another session is open.
 *
 * The repository ends that session in Rust; these pin the controller's half,
 * `PLAINTEXT_LIFECYCLE.md` §8 step 7: nothing derived from the first session
 * survives into the second, which `DECOY_VAULT.md` §10 needs when the two are
 * different identities.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionReplacementHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-replace-${System.nanoTime()}")
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
        controller.create(PASSWORD, offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
        controller.createAlbum("First identity")
        controller.loadSlots()
        withTimeout(10_000) { controller.albums.first { it.isNotEmpty() } }
        withTimeout(10_000) { controller.slots.first { it.isNotEmpty() } }
    }

    @AfterTest
    fun close(): Unit = runBlocking {
        controller.vault.shutdown()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun an_unlock_over_an_open_session_starts_from_nothing(): Unit = runBlocking {
        // The unlock gate over an open session: a host that showed the public
        // shell over it and then took the vault entry.
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }

        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        assertTrue(controller.albums.value.isEmpty(), "the first session's albums")
        assertTrue(controller.slots.value.isEmpty(), "the first session's slots")
        controller.loadAlbums()
        withTimeout(10_000) { controller.albums.first { it.isNotEmpty() } }
    }

    @Test
    fun a_refused_unlock_over_an_open_session_leaves_it_locked(): Unit = runBlocking {
        controller.goTo(AppRoute.Unlock)
        controller.unlock("not the password")
        withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
        withTimeout(10_000) { controller.message.first { it != null } }

        // The session ended as the attempt began, on both sides: nothing of
        // it on screen, and nothing of it left open behind the gate.
        assertEquals(AppRoute.Unlock, controller.route.value)
        assertTrue(controller.albums.value.isEmpty(), "the first session's albums")
        assertTrue(controller.slots.value.isEmpty(), "the first session's slots")
    }

    @Test
    fun a_second_identity_starts_from_nothing(): Unit = runBlocking {
        controller.createSecondIdentity()
        assertEquals(AppRoute.CreateVault, controller.route.value)
        controller.create("Second-identity-password", offerRecovery = false)
        withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }

        assertIs<VaultState.Unlocked>(controller.vaultState.value)
        assertTrue(controller.albums.value.isEmpty(), "the first identity's albums")
        assertTrue(controller.slots.value.isEmpty(), "the first identity's slots")
        assertEquals(null, controller.message.value)
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
        const val PASSWORD = "Session-replacement-password"
    }
}
