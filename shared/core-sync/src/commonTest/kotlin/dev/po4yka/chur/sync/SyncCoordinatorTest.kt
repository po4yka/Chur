package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.PreparedShare
import dev.po4yka.chur.ffi.PreparedShareRevocation
import dev.po4yka.chur.ffi.SyncForkState
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.ffi.SyncRecordKind
import dev.po4yka.chur.ffi.SharedReceivePlan
import dev.po4yka.chur.ffi.SharedSourceObject
import dev.po4yka.chur.ffi.SharedSourceRange
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCoordinatorTest {
    private val vaultId = ByteArray(16) { 1 }
    private val deviceId = ByteArray(16) { 2 }
    private val identity =
        SharingIdentity(
            vaultId = vaultId,
            deviceId = deviceId,
            signingPublicKey = ByteArray(32) { 3 },
            hpkePublicKey = ByteArray(32) { 4 },
            fingerprint = "fingerprint",
            enrollment = byteArrayOf(5, 6),
            initialOperation = byteArrayOf(7, 8),
        )

    private class FakeStore : SyncStateStore {
        var saved: SyncState? = null
        var clears = 0

        override suspend fun load(): SyncState? = saved

        override suspend fun save(state: SyncState) {
            saved = state
        }

        override suspend fun clear() {
            saved = null
            clears++
        }
    }

    private class FakeBoundary : SyncVaultBoundary {
        var identity: SharingIdentity? = null
        val staged = mutableListOf<Pair<SyncRecordKind, ByteArray>>()
        var processed = 0
        var firstRejection = 0
        val accepted = mutableListOf<ByteArray>()

        /** The fork state the catalog keeps, which outlives the process. */
        var forks: SyncForkState? = null
        var acknowledgements = 0

        override suspend fun identity(): SharingIdentity? = identity

        override suspend fun stage(
            vaultId: ByteArray,
            kind: SyncRecordKind,
            stagedAtMs: Long,
            record: ByteArray,
        ) {
            staged += kind to record
        }

        /** Runs as the pass returns, as a lock queued behind it does. */
        var afterProcess: () -> Unit = {}

        override suspend fun process(): SyncProcessReport? {
            processed++
            afterProcess()
            val rejected = if (firstRejection == 0) 0L else 1L
            return SyncProcessReport(staged.size.toLong() - rejected, 0, 0, rejected, firstRejection)
        }

        override suspend fun forkState(): SyncForkState? = forks

        override suspend fun acknowledgeForks(): Boolean {
            acknowledgements++
            forks = forks?.let { SyncForkState(detected = 0, acknowledged = it.detected + it.acknowledged) }
            return true
        }

        override suspend fun acceptSharePackage(packageBytes: ByteArray): Boolean {
            accepted += packageBytes
            return true
        }

        override suspend fun receiveSharedOperations(
            packageBytes: ByteArray,
            operations: List<ByteArray>,
        ): SharedReceivePlan {
            accepted += packageBytes
            return SharedReceivePlan(ByteArray(16), ByteArray(16), ByteArray(16), 0, emptyList())
        }

        override suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray): ULong? = 0uL

        override suspend fun appendSharedDownload(
            collectionId: ByteArray,
            objectId: ByteArray,
            offset: ULong,
            bytes: ByteArray,
        ): Boolean = true

        override suspend fun finishSharedDownload(
            collectionId: ByteArray,
            objectId: ByteArray,
            nowMs: Long,
        ): Boolean = true

        override suspend fun sourceCollectionId(): ByteArray? = null

        override suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray): List<SharedSourceObject>? = null

        override suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int): SharedSourceRange? = null

        override suspend fun sourceAuthor(source: SharedSourceObject): SharedSourceObject? = null
    }

    /** One engine whose every answer comes from [handler], counting requests. */
    private class CountingEngine(
        handler: MockRequestHandler,
    ) {
        var requests = 0
        val engine =
            MockEngine { request ->
                requests++
                handler(request)
            }
    }

    private fun okEngine(response: (String) -> MockAnswer) =
        CountingEngine { request ->
            response(request.url.encodedPath).let { answer ->
                when (answer) {
                    is MockAnswer.Body -> {
                        respond(answer.bytes, HttpStatusCode.OK)
                    }

                    MockAnswer.NetworkError -> {
                        // A network failure is a transport exception, not an
                        // HTTP status: the server never answered.
                        throw RuntimeException("the connection was reset")
                    }

                    MockAnswer.Unauthorized -> {
                        respond(
                            byteArrayOf(0, 0, 0, ChurStatus.AUTHENTICATION_FAILED.value.toByte()),
                            HttpStatusCode.Unauthorized,
                        )
                    }
                }
            }
        }

    private sealed interface MockAnswer {
        data class Body(
            val bytes: ByteArray,
        ) : MockAnswer

        data object NetworkError : MockAnswer

        data object Unauthorized : MockAnswer
    }

    private fun coordinator(
        store: FakeStore,
        boundary: FakeBoundary,
        engine: CountingEngine,
        sleeps: MutableList<Long> = mutableListOf(),
    ): SyncCoordinator =
        SyncCoordinator(
            store = store,
            clock = { 0L },
            sleep = { delay -> sleeps += delay },
            clientFactory = { url, token -> SyncClient(url, token, HttpClient(engine.engine)) },
        ).apply { bind(boundary) }

    @Test
    fun bootstrap_enrolls_this_device_and_stores_its_cursor() =
        runTest {
            val engine = okEngine { if (it.endsWith("/bootstrap")) MockAnswer.Body(ByteArray(0)) else error(it) }
            val store = FakeStore()
            val boundary = FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }
            val coordinator = coordinator(store, boundary, engine)

            coordinator.configure("https://sync.example", SECRET_HEX)

            assertEquals(1, engine.requests)
            val state = store.saved
            assertEquals("https://sync.example", state?.serverUrl)
            assertEquals(32, state?.transportToken?.size)
            assertEquals(1, state?.cursors?.size)
            assertContentEquals(deviceId, state?.cursors?.first()?.deviceId)
            assertEquals(0uL, state?.cursors?.first()?.after)
            assertTrue(coordinator.status.value.configured)
        }

    @Test
    fun a_wrong_address_or_secret_is_a_bounded_input_failure() =
        runTest {
            val engine = okEngine { error(it) }
            // Unlocked, so the input is what fails and not the lock.
            val boundary = FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }
            val coordinator = coordinator(FakeStore(), boundary, engine)

            assertFailsWith<dev.po4yka.chur.ffi.ChurFailure> {
                coordinator.configure("http://sync.example", SECRET_HEX)
            }
            assertFailsWith<dev.po4yka.chur.ffi.ChurFailure> {
                coordinator.configure("https://sync.example", "not hex")
            }
            assertFailsWith<dev.po4yka.chur.ffi.ChurFailure> {
                coordinator.configure("https://sync.example", "00".repeat(31))
            }
            assertEquals(0, engine.requests)
        }

    @Test
    fun setup_needs_the_vault_unlocked() =
        runTest {
            val engine = okEngine { error(it) }
            val store = FakeStore()
            val coordinator = coordinator(store, FakeBoundary(), engine)

            assertFailsWith<dev.po4yka.chur.ffi.ChurFailure> {
                coordinator.configure("https://sync.example", SECRET_HEX)
            }

            assertNull(store.saved)
        }

    @Test
    fun sync_pulls_stages_applies_and_advances_the_cursors() =
        runTest {
            val operation = byteArrayOf(1, 2)
            val second = byteArrayOf(3)
            val checkpoint = byteArrayOf(4, 5)
            val engine =
                okEngine { path ->
                    when {
                        path.contains("/operations/") -> MockAnswer.Body(frame2(operation, second))
                        path.endsWith("/checkpoints") -> MockAnswer.Body(frame(checkpoint))
                        path.endsWith("/sharing/packages") -> MockAnswer.Body(byteArrayOf(0, 0, 0, 0))
                        else -> error(path)
                    }
                }
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 7u)))
                }
            val boundary = FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }
            val coordinator = coordinator(store, boundary, engine)
            coordinator.refresh()

            val completed = coordinator.syncNow()

            assertTrue(completed)
            assertEquals(
                listOf(SyncRecordKind.OPERATION, SyncRecordKind.OPERATION, SyncRecordKind.CHECKPOINT),
                boundary.staged.map { it.first },
            )
            assertContentEquals(operation, boundary.staged[0].second)
            assertContentEquals(second, boundary.staged[1].second)
            assertEquals(1, boundary.processed)
            assertEquals(
                9uL,
                store.saved
                    ?.cursors
                    ?.single()
                    ?.after,
            )
            // The copy is the user's: no operation or checkpoint counts.
            assertEquals("Up to date.", coordinator.status.value.message)
            assertNull(coordinator.status.value.integrityStop)
        }

    @Test
    fun a_fork_found_by_a_run_stops_saying_synced_until_disconnect() =
        runTest {
            val engine =
                okEngine { path ->
                    when {
                        path.contains("/operations/") -> MockAnswer.Body(frame(byteArrayOf(1)))
                        path.endsWith("/checkpoints") || path.endsWith("/sharing/packages") ->
                            MockAnswer.Body(byteArrayOf(0, 0, 0, 0))
                        else -> error(path)
                    }
                }
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 0u)))
                }
            val boundary =
                FakeBoundary().apply {
                    identity = this@SyncCoordinatorTest.identity
                    firstRejection = ChurStatus.SYNC_CHAIN_FORK.value
                }
            val coordinator = coordinator(store, boundary, engine)
            coordinator.refresh()

            // `ROLLBACK_PROTECTION.md` §4: every other chain keeps applying,
            // so the run completes, and it still must not read as a success.
            assertTrue(coordinator.syncNow())
            assertEquals(ChurStatus.SYNC_CHAIN_FORK, coordinator.status.value.integrityStop)
            assertNull(coordinator.status.value.message)

            // A clean pass after it does not clear the verdict.
            boundary.firstRejection = 0
            assertTrue(coordinator.syncNow())
            assertEquals(ChurStatus.SYNC_CHAIN_FORK, coordinator.status.value.integrityStop)
            assertFalse(coordinator.status.value.message.orEmpty().startsWith("Up to date"))

            coordinator.disconnect()
            assertNull(coordinator.status.value.integrityStop)
        }

    @Test
    fun a_rollback_in_what_was_staged_while_locked_is_kept_at_unlock() =
        runTest {
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
                }
            val boundary =
                FakeBoundary().apply {
                    identity = this@SyncCoordinatorTest.identity
                    firstRejection = ChurStatus.SYNC_HEAD_ROLLBACK.value
                }
            val engine = okEngine { error(it) }
            val coordinator = coordinator(store, boundary, engine)
            coordinator.refresh()

            coordinator.applyStaged()

            assertEquals(1, boundary.processed)
            assertEquals(0, engine.requests)
            assertEquals(ChurStatus.SYNC_HEAD_ROLLBACK, coordinator.status.value.integrityStop)
            assertTrue(coordinator.status.value.configured)
        }

    @Test
    fun a_rollback_is_kept_when_the_vault_locks_as_the_pass_returns() =
        runTest {
            val engine =
                okEngine { path ->
                    when {
                        path.contains("/operations/") -> MockAnswer.Body(frame(byteArrayOf(1)))
                        path.endsWith("/checkpoints") || path.endsWith("/sharing/packages") ->
                            MockAnswer.Body(byteArrayOf(0, 0, 0, 0))
                        else -> error(path)
                    }
                }
            val passes: List<suspend (SyncCoordinator) -> Unit> = listOf({ it.syncNow() }, { it.applyStaged() })
            for (pass in passes) {
                val store =
                    FakeStore().apply {
                        saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 0u)))
                    }
                // A lock queued behind the pass takes the vault before the
                // pass hands back the report, and the catalog keeps no
                // rollback, so the report is the only place it reaches.
                val boundary =
                    FakeBoundary().apply {
                        identity = this@SyncCoordinatorTest.identity
                        firstRejection = ChurStatus.SYNC_HEAD_ROLLBACK.value
                        afterProcess = { identity = null }
                    }
                val coordinator = coordinator(store, boundary, engine)
                coordinator.refresh()

                pass(coordinator)
                boundary.identity = identity
                coordinator.endSession()
                assertTrue(coordinator.refresh())

                // `ROLLBACK_PROTECTION.md` §4: the user is told at the next unlock.
                assertEquals(ChurStatus.SYNC_HEAD_ROLLBACK, coordinator.status.value.integrityStop)
            }
        }

    @Test
    fun a_fork_the_catalog_keeps_is_raised_at_unlock_and_stays_once_acknowledged() =
        runTest {
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
                }
            // What a restart finds: the pass that reported the fork belonged
            // to the process before, and only the catalog still knows.
            val boundary =
                FakeBoundary().apply {
                    identity = this@SyncCoordinatorTest.identity
                    forks = SyncForkState(detected = 1, acknowledged = 0)
                }
            val coordinator = coordinator(store, boundary, okEngine { error(it) })

            assertTrue(coordinator.refresh())
            assertEquals(ChurStatus.SYNC_CHAIN_FORK, coordinator.status.value.integrityStop)
            assertFalse(coordinator.status.value.integrityAcknowledged)

            // `ROLLBACK_PROTECTION.md` §4: acknowledged, and still frozen.
            coordinator.acknowledgeIntegrityStop()
            assertEquals(1, boundary.acknowledgements)
            assertEquals(ChurStatus.SYNC_CHAIN_FORK, coordinator.status.value.integrityStop)
            assertTrue(coordinator.status.value.integrityAcknowledged)

            // The frozen chain refuses one more record the server serves; the
            // catalog still holds only the acknowledged fork, so no new report.
            boundary.firstRejection = ChurStatus.SYNC_CHAIN_FORK.value
            coordinator.applyStaged()
            assertTrue(coordinator.status.value.integrityAcknowledged)

            // The next restart reads the acknowledged state.
            val restarted = coordinator(store, boundary, okEngine { error(it) })
            restarted.refresh()
            assertEquals(ChurStatus.SYNC_CHAIN_FORK, restarted.status.value.integrityStop)
            assertTrue(restarted.status.value.integrityAcknowledged)

            // A fork on another device's chain is new, so it is reported again.
            boundary.forks = SyncForkState(detected = 1, acknowledged = 1)
            restarted.applyStaged()
            assertFalse(restarted.status.value.integrityAcknowledged)

            restarted.disconnect()
            assertNull(restarted.status.value.integrityStop)
            assertFalse(restarted.status.value.integrityAcknowledged)
        }

    @Test
    fun the_saved_server_shows_only_to_the_vault_that_configured_it() =
        runTest {
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
                }
            val boundary = FakeBoundary()
            val coordinator = coordinator(store, boundary, okEngine { error(it) })
            val otherVault =
                SharingIdentity(
                    vaultId = ByteArray(16) { 9 },
                    deviceId = deviceId,
                    signingPublicKey = identity.signingPublicKey,
                    hpkePublicKey = identity.hpkePublicKey,
                    fingerprint = "other",
                    enrollment = identity.enrollment,
                    initialOperation = identity.initialOperation,
                )

            // Locked: no identity to own it.
            coordinator.refresh()
            assertFalse(coordinator.status.value.configured)

            // Another identity, such as a decoy, sees no server at all.
            boundary.identity = otherVault
            coordinator.refresh()
            assertFalse(coordinator.status.value.configured)
            assertNull(coordinator.status.value.serverUrl)

            boundary.identity = identity
            coordinator.refresh()
            assertTrue(coordinator.status.value.configured)
            assertEquals("https://sync.example", coordinator.status.value.serverUrl)

            // And a lock followed by the other identity takes it away again.
            boundary.identity = otherVault
            coordinator.refresh()
            assertNull(coordinator.status.value.serverUrl)

            // The periodic run reaches that session too, and shows it no server.
            assertFalse(coordinator.syncNow())
            assertFalse(coordinator.status.value.configured)
            assertNull(coordinator.status.value.serverUrl)
            assertEquals(0, store.clears)
        }

    @Test
    fun a_verdict_shows_only_to_the_vault_whose_pass_reported_it() =
        runTest {
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
                }
            val boundary =
                FakeBoundary().apply {
                    identity = this@SyncCoordinatorTest.identity
                    firstRejection = ChurStatus.SYNC_HEAD_ROLLBACK.value
                }
            val engine = okEngine { if (it.endsWith("/bootstrap")) MockAnswer.Body(ByteArray(0)) else error(it) }
            val coordinator = coordinator(store, boundary, engine)
            val otherVault =
                SharingIdentity(
                    vaultId = ByteArray(16) { 9 },
                    deviceId = deviceId,
                    signingPublicKey = identity.signingPublicKey,
                    hpkePublicKey = identity.hpkePublicKey,
                    fingerprint = "other",
                    enrollment = identity.enrollment,
                    initialOperation = identity.initialOperation,
                )
            coordinator.refresh()
            coordinator.applyStaged()
            assertEquals(ChurStatus.SYNC_HEAD_ROLLBACK, coordinator.status.value.integrityStop)

            // `DECOY_VAULT.md` §10: another identity's status carries no
            // verdict, and it cannot acknowledge the owner's.
            coordinator.endSession()
            boundary.identity = otherVault
            boundary.firstRejection = 0
            assertFalse(coordinator.refresh())
            assertNull(coordinator.status.value.integrityStop)
            assertFalse(coordinator.status.value.integrityAcknowledged)
            coordinator.acknowledgeIntegrityStop()
            assertEquals(0, boundary.acknowledgements)

            // The owner's verdict waits for the owner.
            coordinator.endSession()
            boundary.identity = identity
            assertTrue(coordinator.refresh())
            assertEquals(ChurStatus.SYNC_HEAD_ROLLBACK, coordinator.status.value.integrityStop)

            // The saved state goes away without a disconnect, and the other
            // identity configures its own server: it inherits no verdict.
            coordinator.endSession()
            store.saved = null
            boundary.identity = otherVault
            assertFalse(coordinator.refresh())
            coordinator.configure("https://sync.example", SECRET_HEX)
            assertTrue(coordinator.status.value.configured)
            assertNull(coordinator.status.value.integrityStop)
            assertFalse(coordinator.status.value.integrityAcknowledged)
        }

    @Test
    fun nothing_after_the_end_of_a_session_shows_until_its_owner_unlocks() =
        runTest {
            // Each request comes after a lock, and the setup and the run still finish.
            lateinit var coordinator: SyncCoordinator
            val engine =
                okEngine { path ->
                    coordinator.endSession()
                    MockAnswer.Body(if (path.endsWith("/bootstrap")) ByteArray(0) else byteArrayOf(0, 0, 0, 0))
                }
            val store = FakeStore()
            coordinator = coordinator(store, FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }, engine)

            coordinator.configure("https://sync.example", SECRET_HEX)
            assertEquals("https://sync.example", store.saved?.serverUrl)
            assertFalse(coordinator.status.value.configured)
            assertNull(coordinator.status.value.serverUrl)

            assertTrue(coordinator.refresh())
            assertEquals("https://sync.example", coordinator.status.value.serverUrl)

            assertTrue(coordinator.syncNow())
            assertNull(coordinator.status.value.serverUrl)
            assertNull(coordinator.status.value.message)
        }

    @Test
    fun other_rejections_are_not_a_fork_verdict() =
        runTest {
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
                }
            val boundary = FakeBoundary().apply { firstRejection = ChurStatus.NON_CANONICAL_ENCODING.value }
            val coordinator = coordinator(store, boundary, okEngine { error(it) })

            coordinator.applyStaged()

            assertNull(coordinator.status.value.integrityStop)
        }

    @Test
    fun a_network_failure_backs_off_and_is_bounded() =
        runTest {
            val engine = okEngine { MockAnswer.NetworkError }
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 0u)))
                }
            val sleeps = mutableListOf<Long>()
            val coordinator = coordinator(store, FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }, engine, sleeps)
            coordinator.refresh()

            val completed = coordinator.syncNow()

            assertFalse(completed)
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), sleeps)
            assertEquals(SyncCoordinator.MAX_ATTEMPTS, engine.requests)
            assertEquals(ChurStatus.NETWORK_FAILURE, coordinator.status.value.failure)
            // Nothing was applied and no cursor advanced, so the next run re-pulls.
            assertEquals(
                0uL,
                store.saved
                    ?.cursors
                    ?.single()
                    ?.after,
            )
        }

    @Test
    fun the_backoff_resets_on_success() =
        runTest {
            val operation = byteArrayOf(1)
            var failing = true
            val engine =
                okEngine { path ->
                    when {
                        failing -> MockAnswer.NetworkError
                        path.contains("/operations/") -> MockAnswer.Body(frame(operation))
                        path.endsWith("/checkpoints") -> MockAnswer.Body(frame(byteArrayOf(2)))
                        path.endsWith("/sharing/packages") -> MockAnswer.Body(byteArrayOf(0, 0, 0, 0))
                        else -> error(path)
                    }
                }
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 0u)))
                }
            val sleeps = mutableListOf<Long>()
            val coordinator = coordinator(store, FakeBoundary(), engine, sleeps)

            // The first run exhausts the bounded schedule.
            assertFalse(coordinator.syncNow())
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), sleeps)

            // A success resets the schedule: the next failure starts at the
            // initial delay again rather than where the last run stopped.
            failing = false
            assertTrue(coordinator.syncNow())
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), sleeps)

            failing = true
            assertFalse(coordinator.syncNow())
            assertEquals(
                listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L),
                sleeps,
            )
        }

    @Test
    fun a_refusal_stops_the_run_immediately() =
        runTest {
            val engine = okEngine { MockAnswer.Unauthorized }
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 0u)))
                }
            val sleeps = mutableListOf<Long>()
            val coordinator = coordinator(store, FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }, engine, sleeps)
            coordinator.refresh()

            val completed = coordinator.syncNow()

            assertFalse(completed)
            assertEquals(1, engine.requests)
            assertEquals(0, sleeps.size)
            // The engine owns no copy: the status goes up typed, and no line
            // carries its name, `ERROR_MODEL.md` "Layer mapping".
            assertEquals(ChurStatus.AUTHENTICATION_FAILED, coordinator.status.value.failure)
            assertNull(coordinator.status.value.message)
        }

    @Test
    fun no_configuration_is_a_quiet_no() =
        runTest {
            val engine = okEngine { error(it) }
            val coordinator = coordinator(FakeStore(), FakeBoundary(), engine)

            val completed = coordinator.syncNow()

            assertFalse(completed)
            assertEquals(0, engine.requests)
            assertFalse(coordinator.status.value.configured)
        }

    @Test
    fun disconnect_forgets_the_server() =
        runTest {
            val store =
                FakeStore().apply {
                    saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
                }
            val engine = okEngine { error(it) }
            val coordinator = coordinator(store, FakeBoundary(), engine)

            coordinator.disconnect()

            assertEquals(1, store.clears)
            assertNull(store.saved)
            assertFalse(coordinator.status.value.configured)
        }

    @Test
    fun unlocked_sync_accepts_addressed_share_packages() = runTest {
        val packageBytes = byteArrayOf(9, 8, 7)
        val engine = okEngine { path ->
            when {
                path.contains("/operations/") || path.endsWith("/checkpoints") -> MockAnswer.Body(byteArrayOf(0, 0, 0, 0))
                path.endsWith("/sharing/packages") -> MockAnswer.Body(frame(packageBytes))
                else -> error(path)
            }
        }
        val store = FakeStore().apply {
            saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 0u)))
        }
        val boundary = FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }
        val coordinator = coordinator(store, boundary, engine)
        coordinator.refresh()

        assertTrue(coordinator.syncNow())
        assertContentEquals(packageBytes, boundary.accepted.single())
        assertTrue(coordinator.status.value.message!!.contains("1 share(s)"))
        // The server can return the same current grant again. Native acceptance is idempotent.
        assertTrue(coordinator.syncNow())
        assertEquals(2, boundary.accepted.size)
    }

    @Test
    fun prepared_share_and_revocation_are_published_in_order() = runTest {
        val requests = mutableListOf<String>()
        val engine = okEngine { path ->
            requests += path
            MockAnswer.Body(ByteArray(0))
        }
        val store = FakeStore().apply {
            saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
        }
        val coordinator = coordinator(store, FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }, engine)

        coordinator.publishShare(PreparedShare(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4)))
        coordinator.publishRevocation(
            PreparedShareRevocation(byteArrayOf(5), byteArrayOf(6), listOf(byteArrayOf(7)), emptyList(), true),
        )

        assertEquals(
            listOf("/sharing/memberships", "/sharing/grants", "/sharing/memberships", "/operations"),
            requests.map { it.substringAfter("/v1/vaults/${vaultId.toHex()}") },
        )
    }

    @Test
    fun failed_partial_publication_is_retried_before_the_next_pull() = runTest {
        var failGrant = true
        val requests = mutableListOf<String>()
        val engine = okEngine { path ->
            requests += path
            when {
                path.endsWith("/sharing/grants") && failGrant -> MockAnswer.NetworkError
                path.contains("/operations/") || path.endsWith("/checkpoints") || path.endsWith("/sharing/packages") ->
                    MockAnswer.Body(byteArrayOf(0, 0, 0, 0))
                else -> MockAnswer.Body(ByteArray(0))
            }
        }
        val store = FakeStore().apply {
            saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), listOf(DeviceCursor(deviceId, 0u)))
        }
        val boundary = FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }
        val coordinator = coordinator(store, boundary, engine)
        coordinator.refresh()
        val share = PreparedShare(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4))

        assertFailsWith<SyncTransportFailure> { coordinator.publishShare(share) }
        assertEquals(1, store.saved?.pendingSharing?.size)
        assertEquals("Sharing changes need upload.", coordinator.status.value.message)

        failGrant = false
        assertTrue(coordinator.syncNow())
        assertTrue(store.saved!!.pendingSharing.isEmpty())
        assertEquals(2, requests.count { it.endsWith("/sharing/grants") })
        assertTrue(requests.indexOfLast { it.endsWith("/sharing/grants") } < requests.indexOfFirst { it.contains("/operations/") })
    }

    @Test
    fun later_rotation_batches_are_not_dropped_when_membership_repeats() = runTest {
        val operations = mutableListOf<ByteArray>()
        val engine = CountingEngine { request ->
            if (request.url.encodedPath.endsWith("/operations")) {
                operations += request.body.toByteArray()
            }
            respond(ByteArray(0), HttpStatusCode.OK)
        }
        val store = FakeStore().apply {
            saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
        }
        val boundary = FakeBoundary().apply { identity = this@SyncCoordinatorTest.identity }
        val coordinator = coordinator(store, boundary, engine)
        val first = PreparedShareRevocation(byteArrayOf(1), byteArrayOf(2), listOf(byteArrayOf(3)), emptyList(), false)
        val second = PreparedShareRevocation(byteArrayOf(1), byteArrayOf(2), listOf(byteArrayOf(4)), emptyList(), true)

        coordinator.publishRevocation(first)
        coordinator.publishRevocation(second)

        assertEquals(2, operations.size)
        assertContentEquals(byteArrayOf(3), operations[0])
        assertContentEquals(byteArrayOf(4), operations[1])
    }

    @Test
    fun a_configured_vault_cannot_publish_from_another_identity() = runTest {
        val engine = okEngine { error(it) }
        val store = FakeStore().apply {
            saved = SyncState("https://sync.example", vaultId, deviceId, ByteArray(32), emptyList())
        }
        val boundary = FakeBoundary().apply {
            identity = SharingIdentity(ByteArray(16) { 9 }, deviceId, ByteArray(32), ByteArray(32), "other", byteArrayOf(), byteArrayOf())
        }
        val coordinator = coordinator(store, boundary, engine)

        assertFailsWith<dev.po4yka.chur.ffi.ChurFailure> {
            coordinator.publishShare(PreparedShare(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4)))
        }
        assertTrue(store.saved!!.pendingSharing.isEmpty())
        assertEquals(0, engine.requests)
        assertFalse(coordinator.syncNow())
        assertEquals(0, engine.requests)
    }

    private companion object {
        val SECRET_HEX = (0 until 32).joinToString("") { "ab" }
        const val ATTEMPTS = 6
    }
}

private fun frame(record: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1, 0, 0, 0, record.size.toByte()) + record

private fun frame2(
    first: ByteArray,
    second: ByteArray,
): ByteArray =
    byteArrayOf(0, 0, 0, 2, 0, 0, 0, first.size.toByte()) + first +
        byteArrayOf(0, 0, 0, second.size.toByte()) + second
