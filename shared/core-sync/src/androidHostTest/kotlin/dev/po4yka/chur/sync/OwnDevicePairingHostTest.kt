package dev.po4yka.chur.sync

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production coordinator, JNI, encrypted backup, and native own-device media pipeline. */
class OwnDevicePairingHostTest {
    @Test
    fun a_restored_device_gets_a_fresh_identity_and_verified_new_original() = runBlocking {
        Fixture().use { fixture ->
            fixture.connectPeer()
            val sourceIdentity = ChurVault.sharingIdentity(fixture.source.session)
            val peerIdentity = ChurVault.sharingIdentity(fixture.peer.session)
            assertFalse(sourceIdentity.deviceId.contentEquals(peerIdentity.deviceId))
            assertEquals(sourceIdentity.vaultId.toHex(), peerIdentity.vaultId.toHex())

            val original = ByteArray(8_192) { ((it * 17) and 255).toByte() }
            val objectId = fixture.import(original)
            assertTrue(fixture.sourceSync.syncNow())
            assertTrue(fixture.peerSync.syncNow())
            assertTrue(fixture.peerSync.status.value.message?.contains("Waiting for the source device") == true, fixture.peerSync.status.value.toString())
            assertTrue(ChurVault.query(fixture.peer.session, ObjectQuery()).objects.isEmpty())

            assertTrue(fixture.sourceSync.syncNow())
            assertTrue(fixture.peerSync.syncNow())
            val received = ChurVault.query(fixture.peer.session, ObjectQuery()).objects.single()
            assertContentEquals(objectId, received.objectId)
            assertContentEquals(original, fixture.export(fixture.peer.session, objectId))
            assertTrue(fixture.relay.requests.isEmpty())
            assertTrue(fixture.relay.grants.isEmpty())

            fixture.restartPeer()
            assertTrue(fixture.peerSync.syncNow())
            assertContentEquals(original, fixture.export(fixture.peer.session, objectId))
        }
    }

    @Test
    fun pending_dependency_signal_keeps_healthy_native_downloads_and_reports_waiting() = runBlocking {
        Fixture().use { fixture ->
            fixture.connectPeer()
            val original = ByteArray(8192) { (it % 233).toByte() }
            val objectId = fixture.import(original)
            assertTrue(fixture.sourceSync.syncNow())
            fixture.peer.additionalPendingDependencies = 1
            assertTrue(fixture.peerSync.syncNow())
            assertTrue(fixture.sourceSync.syncNow())
            assertTrue(fixture.peerSync.syncNow())
            assertContentEquals(original, fixture.export(fixture.peer.session, objectId))
            val message = fixture.peerSync.status.value.message.orEmpty()
            assertTrue(message.contains("Some changes are waiting for missing dependencies."), message)
            assertFalse(message.contains("Up to date."), message)
            fixture.peer.additionalPendingDependencies = 0
            assertTrue(fixture.peerSync.syncNow())
            assertTrue(fixture.peerSync.status.value.message.orEmpty().contains("Up to date."))
        }
    }

    @Test
    fun approval_checkpoints_follow_unpublished_originals_and_later_cached_retry_edits() = runBlocking {
        Fixture().use { fixture ->
            fixture.relay.requireRelayedCheckpointHeads = true
            fixture.import(ByteArray(8192) { (it % 241).toByte() })
            val request = fixture.peerSync.prepareDevicePairing()
            val preview = fixture.sourceSync.inspectDevicePairing(request.request)
            val approval = fixture.sourceSync.approveDevicePairing(request.request, preview.fingerprint)
            val firstHead = ChurVault.ownSyncOperations(fixture.source.session, 0uL).last().sequence
            assertEquals(firstHead, fixture.sourceStore.saved!!.ownPushCursor)
            assertEquals(firstHead, fixture.relay.checkpointSequences.last())
            fixture.leavePublishedMediaOperationsUnsent()
            val nextHead = ChurVault.ownSyncOperations(fixture.source.session, firstHead).last().sequence
            fixture.restartSource()
            val retry = fixture.sourceSync.approveDevicePairing(request.request, preview.fingerprint)
            assertContentEquals(approval, retry)
            assertContentEquals(fixture.relay.enrollmentBodies[0], fixture.relay.enrollmentBodies[1])
            assertEquals(nextHead, fixture.sourceStore.saved!!.ownPushCursor)
            assertEquals(nextHead, fixture.relay.checkpointSequences.last())
            assertTrue(fixture.peerSync.acceptPairingApproval(retry))
        }
    }

    @Test
    fun approval_recovers_an_older_revocation_before_advancing_its_prefix() = runBlocking {
        Fixture().use { fixture ->
            fixture.relay.requireRelayedCheckpointHeads = true
            fixture.connectPeer()
            assertTrue(fixture.peerSync.syncNow())
            assertTrue(fixture.sourceSync.syncNow())
            val peer = fixture.sourceSync.pairedDevices().single { !it.current }
            fixture.sourceStore.failNextSave = true
            assertFailsWith<IllegalStateException> {
                fixture.sourceSync.revokePairedDevice(peer.deviceId, peer.fingerprint)
            }
            fixture.relay.publicationOrder.clear()
            fixture.connectAdditionalPeer()
            val revoke = fixture.relay.publicationOrder.indexOf("revoke")
            val enroll = fixture.relay.publicationOrder.indexOf("enroll")
            assertTrue(revoke >= 0 && enroll > revoke)
            assertTrue(fixture.sourceStore.saved!!.pendingDeviceRevocations.isEmpty())
        }
    }

    @Test
    fun approval_and_installation_retries_keep_the_same_identity_and_transport_credential() = runBlocking {
        Fixture().use { fixture ->
            val request = fixture.peerSync.prepareDevicePairing()
            val preview = fixture.sourceSync.inspectDevicePairing(request.request)
            val refused = assertFailsWith<ChurFailure> {
                fixture.sourceSync.approveDevicePairing(request.request, "not the compared fingerprint")
            }
            assertEquals(ChurStatus.AUTHENTICATION_FAILED, refused.status)
            assertEquals(0, fixture.relay.enrollmentBodies.size)

            fixture.relay.failEnrollmentOnce = true
            assertFailsWith<SyncTransportFailure> {
                fixture.sourceSync.approveDevicePairing(request.request, preview.fingerprint)
            }
            val approval = fixture.sourceSync.approveDevicePairing(request.request, preview.fingerprint)
            assertContentEquals(fixture.relay.enrollmentBodies[0], fixture.relay.enrollmentBodies[1])
            val largeMalformed = assertFailsWith<ChurFailure> {
                fixture.peerSync.pairingApprovalServer(ByteArray(65_537))
            }
            assertEquals(ChurStatus.UNSUPPORTED_VERSION, largeMalformed.status)
            assertFailsWith<IllegalArgumentException> {
                fixture.peerSync.pairingApprovalServer(ByteArray(16 * 1024 * 1024 + 1))
            }
            val damaged = approval.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFailsWith<ChurFailure> { fixture.peerSync.pairingApprovalServer(damaged) }
            assertEquals("https://relay.example", fixture.peerSync.pairingApprovalServer(approval))

            fixture.peerStore.failNextSave = true
            assertFailsWith<IllegalStateException> { fixture.peerSync.acceptPairingApproval(approval) }
            val installed = ChurVault.sharingIdentity(fixture.peer.session).deviceId
            assertTrue(fixture.peerSync.acceptPairingApproval(approval))
            assertContentEquals(installed, ChurVault.sharingIdentity(fixture.peer.session).deviceId)
            assertTrue(fixture.peerSync.status.value.configured)
        }
    }

    @Test
    fun missing_signed_history_keeps_setup_retryable_without_publishing_a_connection() = runBlocking {
        Fixture().use { fixture ->
            val request = fixture.peerSync.prepareDevicePairing()
            val preview = fixture.sourceSync.inspectDevicePairing(request.request)
            val approval = fixture.sourceSync.approveDevicePairing(request.request, preview.fingerprint)
            fixture.relay.withholdOperations = true
            assertFalse(fixture.peerSync.acceptPairingApproval(approval))
            assertFalse(fixture.peerSync.status.value.configured)
            assertEquals(null, fixture.peerStore.saved)

            fixture.restartPeer()
            fixture.relay.withholdOperations = false
            assertTrue(fixture.peerSync.acceptPairingApproval(approval))
            assertTrue(fixture.peerSync.status.value.configured)
        }
    }

    @Test
    fun peer_revocation_is_confirmed_and_retries_after_transport_failure() = runBlocking {
        Fixture().use { fixture ->
            fixture.connectPeer()
            assertTrue(fixture.peerSync.syncNow()) // Publish the new device's signed Ready operation.
            assertTrue(fixture.sourceSync.syncNow())
            val devices = fixture.sourceSync.pairedDevices()
            val current = devices.single { it.current }
            val peer = devices.single { !it.current }
            assertFailsWith<ChurFailure> { fixture.sourceSync.revokePairedDevice(current.deviceId, current.fingerprint) }
            assertFailsWith<ChurFailure> { fixture.sourceSync.revokePairedDevice(peer.deviceId, "changed fingerprint") }
            fixture.relay.failRevocationOnce = true
            assertFailsWith<SyncTransportFailure> { fixture.sourceSync.revokePairedDevice(peer.deviceId, peer.fingerprint) }
            assertFalse(fixture.sourceSync.pairedDevices().single { !it.current }.active)
            assertEquals(1, fixture.sourceStore.saved!!.pendingDeviceRevocations.size)
            assertFailsWith<IllegalArgumentException> { fixture.sourceSync.disconnect() }
            assertTrue(fixture.sourceSync.syncNow())
            assertEquals(0, fixture.sourceStore.saved!!.pendingDeviceRevocations.size)
            assertContentEquals(fixture.relay.revocationBodies[0], fixture.relay.revocationBodies[1])
            fixture.restartSource()
            assertFalse(fixture.sourceSync.pairedDevices().single { !it.current }.active)
        }
    }

    @Test
    fun native_revocation_recovers_after_host_save_failure_before_the_ordinary_outbox() = runBlocking {
        Fixture().use { fixture ->
            fixture.connectPeer()
            assertTrue(fixture.peerSync.syncNow())
            assertTrue(fixture.sourceSync.syncNow())
            val peer = fixture.sourceSync.pairedDevices().single { !it.current }
            val before = fixture.sourceStore.saved!!.ownPushCursor
            fixture.sourceStore.failNextSave = true
            assertFailsWith<IllegalStateException> {
                fixture.sourceSync.revokePairedDevice(peer.deviceId, peer.fingerprint)
            }
            assertTrue(fixture.sourceStore.saved!!.pendingDeviceRevocations.isEmpty())
            assertTrue(fixture.relay.revocationBodies.isEmpty())
            assertTrue(fixture.sourceSync.pairedDevices().single { !it.current }.retryAvailable)
            val prepared = ChurVault.prepareDeviceRevocation(fixture.source.session, peer.deviceId, 1_700_000_000_000L)
            val sequence = ChurVault.ownSyncOperations(fixture.source.session, before)
                .single { it.record.contentEquals(prepared.operation) }.sequence

            fixture.restartSource()
            fixture.relay.refuseRevocations = true
            assertFalse(fixture.sourceSync.syncNow(), "an offline revocation must stop the ordinary outbox")
            val queued = fixture.sourceStore.saved!!.pendingDeviceRevocations.single()
            assertContentEquals(prepared.operation, queued.operation)
            assertTrue(fixture.sourceStore.saved!!.ownPushCursor < sequence)

            fixture.restartSource()
            fixture.relay.refuseRevocations = false
            fixture.relay.publicationOrder.clear()
            assertTrue(fixture.sourceSync.syncNow())
            val revokeIndex = fixture.relay.publicationOrder.indexOf("revoke")
            assertTrue(revokeIndex >= 0)
            assertTrue(fixture.relay.publicationOrder.indexOf("operation:$sequence") > revokeIndex)
            assertTrue(fixture.sourceStore.saved!!.pendingDeviceRevocations.isEmpty())
            assertTrue(fixture.sourceStore.saved!!.ownPushCursor >= sequence)
            val sent = fixture.relay.revocationBodies.size
            fixture.restartSource()
            assertTrue(fixture.sourceSync.syncNow())
            assertEquals(sent, fixture.relay.revocationBodies.size, "an acknowledged native cache must not upload forever")
        }
    }

    @Test
    fun recovered_revocations_publish_each_prefix_before_checkpoints_with_later_edits() = runBlocking {
        Fixture().use { fixture ->
            fixture.relay.requireRelayedCheckpointHeads = true
            fixture.connectPeer()
            assertTrue(fixture.peerSync.syncNow())
            assertTrue(fixture.sourceSync.syncNow())
            fixture.connectAdditionalPeer()
            val peers = fixture.sourceSync.pairedDevices().filter { !it.current }
            assertEquals(2, peers.size)
            val before = fixture.sourceStore.saved!!.ownPushCursor
            fixture.sourceStore.failNextSave = true
            assertFailsWith<IllegalStateException> {
                fixture.sourceSync.revokePairedDevice(peers[0].deviceId, peers[0].fingerprint)
            }
            fixture.leavePublishedMediaOperationsUnsent()
            fixture.sourceStore.failNextSave = true
            assertFailsWith<IllegalStateException> {
                fixture.sourceSync.revokePairedDevice(peers[1].deviceId, peers[1].fingerprint)
            }
            fixture.leavePublishedMediaOperationsUnsent()
            val latest = ChurVault.ownSyncOperations(fixture.source.session, before).last().sequence
            assertTrue(fixture.sourceStore.saved!!.pendingDeviceRevocations.isEmpty())
            fixture.restartSource()
            fixture.relay.publicationOrder.clear()
            fixture.relay.revocationBodies.clear()
            assertTrue(fixture.sourceSync.syncNow(), "a future checkpoint must not block its own missing prefix")
            assertEquals(2, fixture.relay.revocationBodies.size)
            assertTrue(fixture.sourceStore.saved!!.pendingDeviceRevocations.isEmpty())
            assertEquals(latest, fixture.sourceStore.saved!!.ownPushCursor)
            assertEquals(latest, fixture.relay.checkpointSequences.last())
            val first = fixture.relay.publicationOrder.indexOf("revoke")
            val second = fixture.relay.publicationOrder.lastIndexOf("revoke")
            assertTrue(first >= 0 && second > first)
            assertTrue(fixture.relay.publicationOrder.subList(first + 1, second).any { it.startsWith("operation:") })
            fixture.restartSource()
            assertTrue(fixture.sourceSync.syncNow())
            assertEquals(2, fixture.relay.revocationBodies.size)
        }
    }

    @Test
    fun final_checkpoint_failure_retains_revocation_after_the_ordinary_cursor_is_saved() = runBlocking {
        Fixture().use { fixture ->
            fixture.relay.requireRelayedCheckpointHeads = true
            fixture.connectPeer()
            assertTrue(fixture.peerSync.syncNow())
            assertTrue(fixture.sourceSync.syncNow())
            val peer = fixture.sourceSync.pairedDevices().single { !it.current }
            fixture.sourceStore.failNextSave = true
            assertFailsWith<IllegalStateException> {
                fixture.sourceSync.revokePairedDevice(peer.deviceId, peer.fingerprint)
            }
            fixture.leavePublishedMediaOperationsUnsent()
            val latest = ChurVault.ownSyncOperations(fixture.source.session, fixture.sourceStore.saved!!.ownPushCursor).last().sequence
            fixture.relay.blockedCheckpointSequence = latest
            fixture.restartSource()
            assertFalse(fixture.sourceSync.syncNow())
            assertEquals(latest, fixture.sourceStore.saved!!.ownPushCursor)
            assertEquals(1, fixture.sourceStore.saved!!.pendingDeviceRevocations.size)
            fixture.restartSource()
            fixture.relay.blockedCheckpointSequence = null
            assertTrue(fixture.sourceSync.syncNow())
            assertTrue(fixture.sourceStore.saved!!.pendingDeviceRevocations.isEmpty())
            assertEquals(latest, fixture.relay.checkpointSequences.last())
        }
    }

    private class Store : SyncStateStore {
        var saved: SyncState? = null
        var failNextSave = false
        override suspend fun load() = saved
        override suspend fun save(state: SyncState) {
            if (failNextSave) { failNextSave = false; error("simulated durable state write failure") }
            saved = state
        }
        override suspend fun clear() { saved = null }
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("chur-own-pairing-").toFile()
        val relay = Relay()
        val source = NativeBoundary(File(directory, "source"))
        val peer = NativeBoundary(File(directory, "peer"))
        private val additionalPeers = mutableListOf<NativeBoundary>()
        val peerStore = Store()
        val sourceStore = Store()
        var sourceSync = coordinator(sourceStore, source)
        val peerSync = coordinator(peerStore, peer)

        init {
            source.session = ChurVault.activateCreation(ChurVault.beginCreation(source.runtime, PASSWORD.encodeToByteArray()))
            ChurVault.sharingIdentity(source.session)
            val backup = File(directory, "vault.backup")
            RandomAccessFile(backup, "rw").use { file -> terminal(ChurVault.beginBackup(source.session, descriptor(file))) }
            RandomAccessFile(backup, "r").use { file -> terminal(ChurVault.beginRestore(peer.runtime, descriptor(file), PASSWORD.encodeToByteArray())) }
            peer.session = ChurVault.unlockWithPassword(peer.runtime, PASSWORD.encodeToByteArray())
            runBlocking { sourceSync.configure("https://relay.example", "00".repeat(32)) }
        }

        private fun coordinator(store: Store, boundary: NativeBoundary) = SyncCoordinator(
            store, clock = { 1_700_000_000_000L }, sleep = {},
            clientFactory = { url, token -> SyncClient(url, token, HttpClient(relay.engine)) },
        ).apply { bind(boundary) }

        suspend fun connectPeer() {
            val request = peerSync.prepareDevicePairing()
            val preview = sourceSync.inspectDevicePairing(request.request)
            val approval = sourceSync.approveDevicePairing(request.request, preview.fingerprint)
            assertTrue(peerSync.acceptPairingApproval(approval))
        }

        suspend fun connectAdditionalPeer() {
            val next = NativeBoundary(File(directory, "peer-${additionalPeers.size + 2}"))
            additionalPeers.add(next)
            RandomAccessFile(File(directory, "vault.backup"), "r").use { file ->
                terminal(ChurVault.beginRestore(next.runtime, descriptor(file), PASSWORD.encodeToByteArray()))
            }
            next.session = ChurVault.unlockWithPassword(next.runtime, PASSWORD.encodeToByteArray())
            val sync = coordinator(Store(), next)
            val request = sync.prepareDevicePairing()
            val preview = sourceSync.inspectDevicePairing(request.request)
            val approval = sourceSync.approveDevicePairing(request.request, preview.fingerprint)
            assertTrue(sync.acceptPairingApproval(approval))
            assertTrue(sync.syncNow())
            assertTrue(sourceSync.syncNow())
        }

        suspend fun leavePublishedMediaOperationsUnsent() {
            val objectId = import(ByteArray(8192) { (it % 239).toByte() })
            val candidate = source.ownSyncCollections().flatMap { source.sourcePage(it, ByteArray(16)) }
                .single { it.objectId.contentEquals(objectId) }
            val state = sourceStore.saved!!
            val client = SyncClient(state.serverUrl, { state.transportToken }, HttpClient(relay.engine))
            try {
                assertFailsWith<IllegalStateException> {
                    SourceObjectPusher(client, publishSharedMapping = false) { _, _ ->
                        error("simulated crash after native authoring and ciphertext upload")
                    }.push(
                        state.vaultId,
                        SourceObjectPublication(candidate.collectionId, candidate.objectId, candidate.storeId, candidate.length, candidate.fullSha256),
                        read = { id, offset, amount -> source.sourceRange(id, offset, amount).let { SourceObjectRange(it.bytes, it.sha256) } },
                        author = { source.ownSyncAuthor(candidate).let { SourceObjectOperations(it.createOperation, it.commitOperation) } },
                    )
                }
            } finally { client.close() }
        }

        fun import(bytes: ByteArray): ByteArray {
            val previous = ChurVault.query(source.session, ObjectQuery()).objects.map { it.objectId.toHex() }.toSet()
            val sourceFile = File(directory, "original.bin").apply { writeBytes(bytes) }
            RandomAccessFile(sourceFile, "r").use { file ->
                terminal(ChurVault.beginImport(source.session, descriptor(file), ImportRequest(
                    contentType = "application/octet-stream", seekable = true, mediaClass = 4,
                    knownLength = bytes.size.toLong(), originalFilename = "original.bin",
                )))
            }
            return ChurVault.query(source.session, ObjectQuery()).objects.single { it.objectId.toHex() !in previous }.objectId
        }

        fun export(session: Long, objectId: ByteArray): ByteArray {
            val output = File(directory, "export-${System.nanoTime()}.bin")
            RandomAccessFile(output, "rw").use { file -> terminal(ChurVault.beginExport(session, objectId, descriptor(file))) }
            return output.readBytes()
        }

        fun restartSource() {
            ChurVault.closeSession(source.session)
            ChurVault.closeRuntime(source.runtime)
            source.runtime = ChurVault.openRuntime(source.root.absolutePath)
            source.session = ChurVault.unlockWithPassword(source.runtime, PASSWORD.encodeToByteArray())
            sourceSync = coordinator(sourceStore, source)
        }

        fun restartPeer() {
            ChurVault.closeSession(peer.session)
            ChurVault.closeRuntime(peer.runtime)
            peer.runtime = ChurVault.openRuntime(peer.root.absolutePath)
            peer.session = ChurVault.unlockWithPassword(peer.runtime, PASSWORD.encodeToByteArray())
        }

        override fun close() {
            source.close()
            peer.close()
            additionalPeers.forEach { it.close() }
            directory.deleteRecursively()
        }
    }

    private class NativeBoundary(val root: File) : SyncVaultBoundary, AutoCloseable {
        var runtime = ChurVault.openRuntime(root.apply { mkdirs() }.absolutePath)
        var session = 0L
        // Inject only the pending signal; media transfer and verification use JNI.
        var additionalPendingDependencies = 0
        override suspend fun identity(): SharingIdentity? = try { ChurVault.sharingIdentity(session) } catch (failure: ChurFailure) {
            if (failure.status == ChurStatus.RECOVERY_REQUIRED) null else throw failure
        }
        override suspend fun acceptedHeads() = ChurVault.syncHeads(session).map { DeviceCursor(it.deviceId, it.sequence) }
        override suspend fun stage(vaultId: ByteArray, kind: SyncRecordKind, stagedAtMs: Long, record: ByteArray) =
            ChurVault.stageSync(runtime, vaultId, kind, stagedAtMs, record)
        override suspend fun process() = ChurVault.processSync(session, 1_700_000_000_000L)
        override suspend fun forkState() = ChurVault.syncForkState(session)
        override suspend fun acknowledgeForks(): Boolean { ChurVault.acknowledgeSyncForks(session); return true }
        override suspend fun acceptSharePackage(packageBytes: ByteArray): Boolean { ChurVault.acceptSharePackage(session, packageBytes); return true }
        override suspend fun receiveSharedOperations(packageBytes: ByteArray, operations: List<ByteArray>) = ChurVault.receiveSharedOperations(session, packageBytes, operations)
        override suspend fun sourceCollectionId(): ByteArray? = null
        override suspend fun sourcePage(collectionId: ByteArray, afterObjectId: ByteArray) = ChurVault.sharedSourcePage(session, collectionId, afterObjectId)
        override suspend fun sourceRange(objectId: ByteArray, offset: ULong, maxBytes: Int) = ChurVault.sharedSourceRange(session, objectId, offset, maxBytes)
        override suspend fun sourceAuthor(source: SharedSourceObject) = ChurVault.sharedSourceAuthor(session, source)
        override suspend fun ownSyncCollections() = ChurVault.ownSyncCollections(session)
        override suspend fun ownSyncOperations(after: ULong) = ChurVault.ownSyncOperations(session, after)
        override suspend fun ownSyncPlan(collectionId: ByteArray) = ChurVault.ownSyncPlan(session, collectionId).let { plan ->
            SharedReceivePlan(plan.sourceVaultId, plan.collectionId, plan.selector,
                plan.pendingOperations + additionalPendingDependencies, plan.downloads)
        }
        override suspend fun ownSyncAuthor(source: SharedSourceObject) = ChurVault.ownSyncAuthor(session, source)
        override suspend fun prepareDevicePairing() = ChurVault.prepareDevicePairing(session)
        override suspend fun inspectDevicePairing(request: ByteArray) = ChurVault.inspectDevicePairing(session, request)
        override suspend fun approveDevicePairing(request: ByteArray, serverUrl: String, token: ByteArray, nowMs: Long) =
            ChurVault.approveDevicePairing(session, request, serverUrl, token, nowMs)
        override suspend fun startDevicePairing(approval: ByteArray) = ChurVault.startDevicePairing(session, approval)
        override suspend fun finishDevicePairing(nowMs: Long) = ChurVault.finishDevicePairing(session, nowMs)
        override suspend fun currentDeviceCheckpoint(nowMs: Long) = ChurVault.currentDeviceCheckpoint(session, nowMs)
        override suspend fun pairedDevices() = ChurVault.pairedDevices(session)
        override suspend fun prepareDeviceRevocation(deviceId: ByteArray, nowMs: Long) = ChurVault.prepareDeviceRevocation(session, deviceId, nowMs)
        override suspend fun objectKeyRequest(collectionId: ByteArray, objectId: ByteArray) = ChurVault.objectKeyRequest(session, collectionId, objectId)
        override suspend fun objectKeyIssue(prekey: ByteArray) = ChurVault.objectKeyIssue(session, prekey)
        override suspend fun objectKeyAccept(packageBytes: ByteArray, grant: ByteArray): Boolean { ChurVault.objectKeyAccept(session, packageBytes, grant); return true }
        override suspend fun sharedDownloadOffset(collectionId: ByteArray, objectId: ByteArray) = ChurVault.sharedDownloadOffset(session, collectionId, objectId)
        override suspend fun appendSharedDownload(collectionId: ByteArray, objectId: ByteArray, offset: ULong, bytes: ByteArray): Boolean {
            ChurVault.appendSharedDownload(session, collectionId, objectId, offset, bytes); return true
        }
        override suspend fun finishSharedDownload(collectionId: ByteArray, objectId: ByteArray, nowMs: Long): Boolean {
            ChurVault.finishSharedDownload(session, collectionId, objectId, nowMs); return true
        }
        override fun close() {
            if (session != 0L) ChurVault.closeSession(session)
            ChurVault.closeRuntime(runtime)
        }
    }

    /** A relay transports actual signed native bytes and actual encrypted containers. */
    private class Relay {
        val requests = linkedMapOf<String, ByteArray>()
        val grants = linkedMapOf<String, ByteArray>()
        val enrollmentBodies = mutableListOf<ByteArray>()
        var failEnrollmentOnce = false
        var failRevocationOnce = false
        var refuseRevocations = false
        var requireRelayedCheckpointHeads = false
        var blockedCheckpointSequence: ULong? = null
        val checkpointSequences = mutableListOf<ULong>()
        val publicationOrder = mutableListOf<String>()
        val revocationBodies = mutableListOf<ByteArray>()
        var withholdOperations = false
        private val deviceTokens = mutableMapOf<String, String>()
        private val operations = linkedMapOf<String, MutableMap<ULong, ByteArray>>()
        private val checkpoints = mutableListOf<ByteArray>()
        private val uploads = mutableMapOf<String, ByteArrayOutputStream>()
        private val lengths = mutableMapOf<String, Long>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray()
            val result = when {
                path.endsWith("/bootstrap") -> { deviceTokens[body.copyOfRange(0, 32).toHex()] = body.copyOfRange(54, 70).toHex(); addOperation(body.copyOfRange(36 + unsigned(body, 32, 4).toInt(), body.size)); byteArrayOf() }
                path.endsWith("/memberships/enroll") -> {
                    enrollmentBodies.add(body)
                    publicationOrder.add("enroll")
                    if (failEnrollmentOnce) { failEnrollmentOnce = false; error("simulated enrollment connection loss") }
                    deviceTokens[body.copyOfRange(0, 32).toHex()] = body.copyOfRange(54, 70).toHex()
                    addOperation(body.copyOfRange(36 + unsigned(body, 32, 4).toInt(), body.size)); byteArrayOf()
                }
                path.endsWith("/memberships/revoke") -> {
                    revocationBodies.add(body)
                    publicationOrder.add("revoke")
                    if (failRevocationOnce || refuseRevocations) { failRevocationOnce = false; error("simulated revocation connection loss") }
                    addOperation(body.copyOfRange(4 + unsigned(body, 0, 4).toInt(), body.size)); byteArrayOf()
                }
                path.endsWith("/operations") && request.method == HttpMethod.Post -> {
                    publicationOrder.add("operation:${unsigned(body, 50, 8)}")
                    addOperation(body); byteArrayOf()
                }
                path.contains("/operations/") -> {
                    val after = request.url.parameters["after"]!!.toULong()
                    frame(if (withholdOperations) emptyList() else operations[path.substringAfterLast('/')].orEmpty()
                        .filterKeys { it > after }.toSortedMap().values.take(128))
                }
                path.endsWith("/checkpoints") && request.method == HttpMethod.Post -> {
                    if (requireRelayedCheckpointHeads) {
                        repeat(unsigned(body, 82, 4).toInt()) { index ->
                            val offset = 86 + index * 56
                            val device = body.copyOfRange(offset, offset + 16).toHex()
                            val sequence = unsigned(body, offset + 16, 8)
                            if (sequence != 0uL && operations[device]?.containsKey(sequence) != true) {
                                val code = ChurStatus.SYNC_HEAD_ROLLBACK.value
                                return@MockEngine respond(byteArrayOf(0, 0, 0, code.toByte()), HttpStatusCode.Conflict)
                            }
                        }
                    }
                    check(unsigned(body, 34, 8) != blockedCheckpointSequence) { "simulated final checkpoint connection loss" }
                    checkpointSequences.add(unsigned(body, 34, 8))
                    checkpoints.add(body); byteArrayOf()
                }
                path.endsWith("/checkpoints") -> frame(checkpoints.takeLast(1))
                path.endsWith("/sharing/packages") -> frame(emptyList())
                path.endsWith("/sharing/object-key-requests/ack") -> { requests.remove(body.toHex()); byteArrayOf() }
                path.endsWith("/sharing/object-key-grants/ack") -> { grants.remove(body.toHex()); byteArrayOf() }
                path.endsWith("/sharing/object-key-requests") && request.method == HttpMethod.Post -> {
                    requests[body.copyOfRange(2, 18).toHex()] = body; byteArrayOf()
                }
                path.endsWith("/sharing/object-key-requests") -> frame(requests.values.toList())
                path.endsWith("/sharing/object-key-grants") && request.method == HttpMethod.Post -> {
                    grants[body.copyOfRange(2, 18).toHex()] = body
                    requests.remove(body.copyOfRange(114, 130).toHex()); byteArrayOf()
                }
                path.endsWith("/sharing/object-key-grants") -> frame(grants.values.filter {
                    it.copyOfRange(98, 114).toHex() == deviceTokens[request.headers["Authorization"]?.removePrefix("Bearer ")]
                })
                path.contains("/objects/") && path.contains("/uploads/") -> {
                    val id = path.substringAfterLast('/')
                    lengths[id] = request.url.parameters["length"]!!.toLong()
                    uploads.getOrPut(id) { ByteArrayOutputStream() }
                    uploadProgress(id)
                }
                path.contains("/uploads/") && request.method == HttpMethod.Patch -> {
                    val id = path.substringAfterLast('/')
                    assertEquals(uploads.getValue(id).size().toString(), request.url.parameters["offset"])
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(body).toHex(), request.url.parameters["sha256"])
                    uploads.getValue(id).write(body); uploadProgress(id)
                }
                path.endsWith("/finish") -> {
                    val id = path.substringBeforeLast('/').substringAfterLast('/')
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(uploads.getValue(id).toByteArray()).toHex(), request.url.parameters["sha256"])
                    uploadProgress(id)
                }
                path.contains("/sharing/collections/") && request.method == HttpMethod.Post ->
                    error("own media must not publish a shared-collection mapping")
                path.contains("/sharing/issuers/") && path.contains("/objects/") ->
                    error("own media must use the vault object download route")
                path.contains("/objects/") && request.method == HttpMethod.Get -> {
                    val bytes = uploads.getValue(path.substringAfterLast('/')).toByteArray()
                    val offset = request.url.parameters["offset"]!!.toInt()
                    bytes.copyOfRange(offset, offset + request.url.parameters["length"]!!.toInt())
                }
                else -> error("unexpected relay endpoint: $path")
            }
            respond(result)
        }

        private fun addOperation(record: ByteArray) {
            val device = record.copyOfRange(34, 50).toHex()
            val sequence = unsigned(record, 50, 8)
            val chain = operations.getOrPut(device) { linkedMapOf() }
            chain[sequence]?.let { assertContentEquals(it, record) }
            chain[sequence] = record
        }

        private fun uploadProgress(id: String): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeLong(uploads.getValue(id).size().toLong()); out.writeLong(lengths.getValue(id))
                out.writeBoolean(lengths.getValue(id) == uploads.getValue(id).size().toLong())
            }
        }.toByteArray()
    }

    companion object {
        private const val PASSWORD = "own-device-pairing-test-password"
        private fun descriptor(file: RandomAccessFile): Int = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
        private fun terminal(operation: Long) {
            val deadline = System.nanoTime() + 30_000_000_000L
            try {
                while (true) {
                    check(System.nanoTime() < deadline) { "native operation did not finish within 30 seconds" }
                    val progress = ChurVault.poll(operation)
                    if (progress.terminal) { assertEquals(0, progress.status); return }
                    Thread.yield()
                }
            } finally { ChurVault.closeOperation(operation) }
        }
        private fun frame(records: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out -> out.writeInt(records.size); records.forEach { out.writeInt(it.size); out.write(it) } }
        }.toByteArray()
        private fun unsigned(bytes: ByteArray, offset: Int, length: Int): ULong {
            var result = 0uL
            repeat(length) { result = (result shl 8) or bytes[offset + it].toUByte().toULong() }
            return result
        }
    }
}
