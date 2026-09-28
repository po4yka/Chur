package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.sync.SyncClient
import dev.po4yka.chur.sync.SyncCoordinator
import dev.po4yka.chur.sync.SyncState
import dev.po4yka.chur.sync.SyncStateStore
import dev.po4yka.chur.sync.SyncStatus
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync engine as the controller's sessions see it.
 *
 * The saved state is one file per device, and its server belongs to the vault
 * that configured it, so a decoy session must neither show that server nor
 * pull from it, `DECOY_VAULT.md` §7 and §10, nor see what the last session's
 * run showed, §6. And the bracket that keeps the
 * iOS local network alert from locking the vault must not delay the lock of a
 * user who left the app, `IOS.md` §27.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncSessionHostTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "chur-sync-session-${System.nanoTime()}")
    private val store = MemoryStore()

    @BeforeTest
    fun open() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root.mkdirs()
    }

    @AfterTest
    fun close() {
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun a_saved_server_shows_and_pulls_only_in_the_vault_that_configured_it(): Unit = runBlocking {
        seedOwnerAndOther()
        // A relaunch, with the state a configured owner left on the device.
        // Its server refuses every connection, so a pull ends at once.
        val clients = MutableStateFlow(0)
        val sync =
            SyncCoordinator(store, sleep = {}) { _, token ->
                clients.update { it + 1 }
                SyncClient(unreachable, token)
            }
        val relaunched = controller(sync)
        try {
            relaunched.start()
            assertFalse(sync.status.value.configured, "a launch shows no identity's server")

            relaunched.goTo(AppRoute.Unlock)
            relaunched.unlock(OWNER)
            // The owner's unlock shows its server and pulls from it.
            withTimeout(10_000) { clients.first { it == 1 } }
            withTimeout(10_000) { sync.status.first { !it.busy } }
            assertTrue(sync.status.value.configured)
            assertEquals(SERVER, sync.status.value.serverUrl)

            relaunched.lock()
            withTimeout(10_000) { relaunched.route.first { it == AppRoute.PublicShell } }
            relaunched.goTo(AppRoute.Unlock)
            relaunched.unlock(OTHER)
            withTimeout(10_000) { relaunched.route.first { it == AppRoute.Vault } }
            withTimeout(10_000) { sync.status.first { !it.configured } }
            assertNull(sync.status.value.serverUrl, "the other identity sees the owner's server")
            assertEquals(1, clients.value, "the other identity pulled from the owner's server")
        } finally {
            relaunched.vault.shutdown()
        }
    }

    @Test
    fun a_lock_during_a_backoff_ends_the_run_and_what_it_showed(): Unit = runBlocking {
        seedOwnerAndOther()
        // The owner's server does not answer, and the run waits in its first
        // backoff until something cancels it, as a real one waits up to 16 s.
        val backoff = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val sync =
            SyncCoordinator(
                store,
                sleep = {
                    backoff.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        ended.complete(Unit)
                    }
                },
            ) { _, token -> SyncClient(unreachable, token) }
        val relaunched = controller(sync)
        try {
            relaunched.start()
            relaunched.goTo(AppRoute.Unlock)
            relaunched.unlock(OWNER)
            withTimeout(10_000) { backoff.await() }
            assertEquals(SERVER, sync.status.value.serverUrl)
            assertTrue(sync.status.value.busy, "the owner's pull is retrying")

            relaunched.panic()
            withTimeout(10_000) { relaunched.route.first { it == AppRoute.PublicShell } }
            assertNull(sync.status.value.serverUrl, "the lock left the owner's server on show")
            withTimeout(10_000) { ended.await() }

            // From here on, nothing shows the owner's server or its run.
            val seen = Collections.synchronizedList(mutableListOf<SyncStatus>())
            val watch = launch(Dispatchers.Unconfined) { sync.status.collect { seen += it } }
            val loads = store.loads.value
            relaunched.goTo(AppRoute.Unlock)
            relaunched.unlock(OTHER)
            withTimeout(10_000) { relaunched.route.first { it == AppRoute.Vault } }
            // The other identity's refresh reads the state file, so it did
            // not wait behind the owner's run.
            withTimeout(10_000) { store.loads.first { it > loads } }
            watch.cancel()
            assertTrue(
                seen.none { it.serverUrl != null || it.busy || it.message != null || it.failure != null },
                "the other identity saw $seen",
            )
        } finally {
            relaunched.vault.shutdown()
        }
    }

    @Test
    fun a_run_that_started_locked_shows_the_next_identity_nothing(): Unit = runBlocking {
        seedOwnerAndOther()
        // The periodic run starts while the vault is locked, as the Android
        // worker and the iOS background task do, and waits in its backoff
        // against the owner's server until the test lets it go on.
        val backoff = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sync =
            SyncCoordinator(
                store,
                sleep = {
                    backoff.complete(Unit)
                    release.await()
                },
            ) { _, token -> SyncClient(unreachable, token) }
        val relaunched = controller(sync)
        try {
            relaunched.start()
            val seen = Collections.synchronizedList(mutableListOf<SyncStatus>())
            val watch = launch(Dispatchers.Unconfined) { sync.status.collect { seen += it } }
            val run = launch(Dispatchers.Default) { sync.syncNow() }
            withTimeout(10_000) { backoff.await() }

            relaunched.goTo(AppRoute.Unlock)
            relaunched.unlock(OTHER)
            withTimeout(10_000) { relaunched.route.first { it == AppRoute.Vault } }
            // The run retries to its cap, and then the other identity's
            // refresh reads the state file.
            val loads = store.loads.value
            release.complete(Unit)
            withTimeout(10_000) { run.join() }
            withTimeout(10_000) { store.loads.first { it > loads } }
            watch.cancel()
            assertTrue(
                seen.none { it.configured || it.serverUrl != null || it.busy || it.message != null || it.failure != null },
                "the other identity saw $seen",
            )
        } finally {
            relaunched.vault.shutdown()
        }
    }

    @Test
    fun a_local_setup_with_no_answer_says_where_to_allow_access(): Unit = runBlocking {
        // Every server refuses at once, as one does when iOS holds the
        // connection back.
        val sync = SyncCoordinator(store) { _, token -> SyncClient(unreachable, token) }
        val controller = controller(sync)
        try {
            controller.start()
            controller.create(OWNER, offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }

            controller.configureSync(LOCAL_SERVER, SECRET, localNetworkAlert = true)
            withTimeout(10_000) { controller.notice.first { it?.text == LOCAL_NETWORK_REFUSED } }
            controller.configureSync(PUBLIC_SERVER, SECRET, localNetworkAlert = true)
            withTimeout(10_000) { controller.notice.first { it?.text == syncCopy(ChurStatus.NETWORK_FAILURE) } }
        } finally {
            controller.vault.shutdown()
        }
    }

    @Test
    fun an_unlock_does_not_pull_from_a_server_this_device_cannot_reach(): Unit = runBlocking {
        seedOwnerAndOther()
        val clients = MutableStateFlow(0)
        val sync =
            SyncCoordinator(store, sleep = {}) { _, token ->
                clients.update { it + 1 }
                SyncClient(unreachable, token)
            }
        val asked = CompletableDeferred<String>()
        val relaunched = controller(sync) { serverUrl -> asked.complete(serverUrl).let { false } }
        try {
            relaunched.start()
            relaunched.goTo(AppRoute.Unlock)
            relaunched.unlock(OWNER)
            assertEquals(SERVER, withTimeout(10_000) { asked.await() })
            assertTrue(sync.status.value.configured)
            assertFalse(sync.status.value.busy, "Sync now waits on a pull that can only time out")
            assertEquals(0, clients.value, "the unlock pulled from a server it cannot reach")

            // Sync now still runs, and it is where a host asks for access.
            relaunched.syncNow()
            withTimeout(10_000) { clients.first { it == 1 } }
        } finally {
            relaunched.vault.shutdown()
        }
    }

    @Test
    fun leaving_the_app_during_a_local_setup_locks_at_once(): Unit = runBlocking {
        // The setup waits on the saved state, inside the bracket, until the
        // test answers; then it ends without a server, which is enough here.
        val sync = SyncCoordinator(store) { _, _ -> throw IllegalArgumentException("no server in this test") }
        val controller = controller(sync)
        try {
            controller.start()
            controller.create(OWNER, offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }

            // The alert takes the scene out of the foreground, and the user
            // answers it: the bracket keeps the vault open, and nothing locks
            // after it.
            var setup = store.hold()
            controller.configureSync(LOCAL_SERVER, SECRET, localNetworkAlert = true)
            withTimeout(10_000) { setup.reached.await() }
            controller.background()
            assertIs<VaultState.Unlocked>(controller.vaultState.value)
            setup.release.complete(Unit)
            withTimeout(10_000) { controller.notice.first { it != null } }
            assertIs<VaultState.Unlocked>(controller.vaultState.value)

            // The user leaves the app while the setup runs: the vault locks
            // at once, as leaving always does, and the bracket is over, so
            // the next leave locks too while that setup still runs.
            setup = store.hold()
            controller.configureSync(LOCAL_SERVER, SECRET, localNetworkAlert = true)
            withTimeout(10_000) { setup.reached.await() }
            controller.background()
            controller.enteredBackground()
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
            controller.goTo(AppRoute.Unlock)
            controller.unlock(OWNER)
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Unlocked } }
            controller.background()
            withTimeout(10_000) { controller.vaultState.first { it is VaultState.Locked } }
            setup.release.complete(Unit)
        } finally {
            controller.vault.shutdown()
        }
    }

    /** Creates the owner, which configured [SERVER], and another identity. */
    private suspend fun seedOwnerAndOther() {
        val first = controller(SyncCoordinator(store))
        first.start()
        first.create(OWNER, offerRecovery = false)
        withTimeout(10_000) { first.route.first { it == AppRoute.Vault } }
        val owner = checkNotNull(first.vault.syncIdentity())
        store.saved = SyncState(SERVER, owner.vaultId, owner.deviceId, ByteArray(32), emptyList())
        first.createSecondIdentity()
        // The owner's create still loads its Library after the route changed,
        // and a create before it ends is ignored, so this asks until one runs.
        withTimeout(10_000) {
            while (first.route.value != AppRoute.Vault) {
                first.create(OTHER, offerRecovery = false)
                delay(50)
            }
        }
        first.vault.shutdown()
    }

    private fun controller(
        sync: SyncCoordinator,
        reachable: suspend (String) -> Boolean = { true },
    ): ChurController =
        ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
            sync = sync,
            syncReachable = reachable,
        ).also { sync.bind(RepositorySyncBoundary(it.vault)) }

    /** A server address with nothing listening, which refuses at once. */
    private val unreachable: String = ServerSocket(0).use { "https://127.0.0.1:${it.localPort}" }

    private class Hold {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class MemoryStore : SyncStateStore {
        var saved: SyncState? = null
        val loads = MutableStateFlow(0)
        private var held: Hold? = null

        /** Makes the next load wait until the test releases it. */
        fun hold(): Hold = Hold().also { held = it }

        override suspend fun load(): SyncState? {
            loads.update { it + 1 }
            held?.let { hold ->
                held = null
                hold.reached.complete(Unit)
                hold.release.await()
            }
            return saved
        }

        override suspend fun save(state: SyncState) {
            saved = state
        }

        override suspend fun clear() {
            saved = null
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
        const val OWNER = "Sync-owner-password"
        const val OTHER = "Sync-other-password"
        const val SERVER = "https://sync.invalid"
        const val LOCAL_SERVER = "https://nas.local:8443"
        const val PUBLIC_SERVER = "https://203.0.113.7"
        val SECRET = "ab".repeat(32)
    }
}
