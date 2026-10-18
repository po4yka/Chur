package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.sync.FileSyncStateStore
import dev.po4yka.chur.sync.SyncCoordinator
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Platform file descriptors stay open until the native operation terminates. */
interface AlbumLiveSyncFiles {
    fun read(path: String): ByteArray
    fun write(path: String, bytes: ByteArray)
    suspend fun input(path: String, block: suspend (Int) -> Unit)
    suspend fun output(path: String, block: suspend (Int) -> Unit)
}

/**
 * Opt-in acceptance phases on two real mobile runtimes and the reference HTTP server.
 * Only the initial encrypted backup and pairing files cross through the host.
 * Every subsequent album edit, object key, and original crosses the production client.
 */
class AlbumLiveSyncScenario(
    private val root: String,
    private val files: AlbumLiveSyncFiles,
    clock: () -> Long,
) {
    private val vault = VaultRepository("$root/vault", clock)
    private val sync = SyncCoordinator(FileSyncStateStore("$root/sync.json"), clock = clock)
        .apply { bind(RepositorySyncBoundary(vault)) }

    suspend fun run(phase: String, endpoint: String, bootstrapSecret: String) {
        require(endpoint.startsWith("http://127.0.0.1:")) { "acceptance uses only a disposable loopback server" }
        vault.start()
        try {
            when (phase) {
                "init" -> {
                    vault.create(PASSWORD.encodeToByteArray(), false)
                    sync.configure(endpoint, bootstrapSecret)
                    files.output("$root/initial.backup") { terminal(vault.beginBackup(it)) }
                    save("source-device", checkNotNull(vault.syncIdentity()).deviceId)
                }
                "restore" -> {
                    files.input("$root/initial.backup") { terminal(vault.beginRestore(it, PASSWORD.encodeToByteArray())) }
                    vault.unlock(PASSWORD.encodeToByteArray())
                    save("pairing.request", sync.prepareDevicePairing().request)
                }
                else -> {
                    vault.unlock(PASSWORD.encodeToByteArray())
                    sync.refresh()
                    runOpenPhase(phase)
                }
            }
            println("ALBUM_LIVE_SYNC $phase PASS ${sync.status.value.message.orEmpty()}")
        } finally {
            sync.bind(null)
            vault.shutdown()
        }
    }

    private suspend fun runOpenPhase(phase: String) {
        when (phase) {
            "approve" -> {
                val request = load("pairing.request")
                val preview = sync.inspectDevicePairing(request)
                save("pairing.approval", sync.approveDevicePairing(request, preview.fingerprint))
            }
            "pair" -> {
                check(sync.acceptPairingApproval(load("pairing.approval"))) { "pairing did not complete" }
                val identity = checkNotNull(vault.syncIdentity())
                check(!identity.deviceId.contentEquals(load("source-device"))) { "pairing reused the source device identity" }
            }
            "sync" -> synchronize()
            "android-seed" -> {
                save("album-a", vault.createAlbum("Android created"))
                save("retained", vault.createAlbum("Retained originals"))
                val alpha = import(ALPHA)
                vault.setAlbumMembership(load("album-a"), alpha, true)
                vault.setAlbumMembership(load("retained"), alpha, true)
                synchronize()
            }
            "ios-edit" -> {
                synchronize()
                remember("album-a", "Android created")
                remember("retained", "Retained originals")
                members("album-a", setOf(ALPHA))
                originals(setOf(ALPHA))
                vault.renameAlbum(load("album-a"), "iOS renamed")
                save("album-b", vault.createAlbum("iOS created"))
                val beta = import(BETA)
                vault.setAlbumMembership(load("album-b"), beta, true)
                vault.setAlbumMembership(load("retained"), beta, true)
                synchronize()
            }
            "android-edit" -> {
                synchronize()
                remember("album-b", "iOS created")
                name("album-a", "iOS renamed")
                members("album-a", setOf(ALPHA))
                members("album-b", setOf(BETA))
                members("retained", setOf(ALPHA, BETA))
                originals(setOf(ALPHA, BETA))
                vault.renameAlbum(load("album-b"), "Android renamed")
                vault.removeAlbumObjects(load("album-a"), listOf(objectWith(ALPHA)))
                vault.setAlbumMembership(load("album-a"), objectWith(BETA), true)
                synchronize()
            }
            "ios-remove" -> {
                synchronize()
                name("album-b", "Android renamed")
                members("album-a", setOf(BETA))
                members("retained", setOf(ALPHA, BETA))
                vault.removeAlbumObjects(load("album-a"), listOf(objectWith(BETA)))
                synchronize()
            }
            "android-empty" -> {
                synchronize()
                members("album-a", emptySet())
                members("album-b", setOf(BETA))
                members("retained", setOf(ALPHA, BETA))
                originals(setOf(ALPHA, BETA))
            }
            "android-offline" -> {
                members("album-a", emptySet())
                vault.renameAlbum(load("album-a"), "Android offline")
                vault.setAlbumMembership(load("album-a"), objectWith(ALPHA), true)
                vault.createAlbum("Android offline created")
                offline()
                save("offline.snapshot", snapshot())
            }
            "ios-offline" -> {
                // Receive the earlier removal before disconnect, or observe it already locally.
                members("album-a", emptySet())
                name("album-a", "iOS renamed")
                vault.renameAlbum(load("album-b"), "iOS offline")
                vault.removeAlbumObjects(load("album-b"), listOf(objectWith(BETA)))
                vault.setAlbumMembership(load("album-b"), objectWith(ALPHA), true)
                vault.createAlbum("iOS offline created")
                offline()
                save("offline.snapshot", snapshot())
            }
            "offline-restart" -> {
                check(load("offline.snapshot").contentEquals(snapshot())) { "offline state changed across process restart" }
                offline()
                check(load("offline.snapshot").contentEquals(snapshot())) { "failed network retry changed the catalog" }
                originals(setOf(ALPHA, BETA))
            }
            "final" -> {
                synchronize()
                name("album-a", "Android offline")
                name("album-b", "iOS offline")
                members("album-a", setOf(ALPHA))
                members("album-b", setOf(ALPHA))
                members("retained", setOf(ALPHA, BETA))
                val albums = vault.albums()
                check(albums.map { it.name }.toSet() == setOf("Android offline", "iOS offline", "Retained originals", "Android offline created", "iOS offline created"))
                check(albums.filter { it.name.endsWith("offline created") }.all { it.memberCount == 0L })
                originals(setOf(ALPHA, BETA))
                val message = sync.status.value.message.orEmpty()
                check(message.contains("Up to date.")) { "sync is not complete: $message" }
                save("final.snapshot", snapshot())
            }
            else -> error("unknown live album phase: $phase")
        }
    }

    private suspend fun synchronize() {
        check(sync.syncNow()) { "network sync failed: ${sync.status.value}" }
    }

    private suspend fun offline() {
        check(!sync.syncNow()) { "offline phase unexpectedly reached the relay" }
        check(sync.status.value.failure != null) { "offline failure was not reported" }
    }

    private suspend fun import(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        val path = "$root/original.txt"
        files.write(path, bytes)
        files.input(path) {
            terminal(vault.beginImport(it, ImportRequest("text/plain", true, 4,
                knownLength = bytes.size.toLong(), originalFilename = "public-fixture.txt")))
        }
        return objectWith(text)
    }

    private suspend fun terminal(operation: Long) {
        try {
            val result = withTimeout(90_000) {
                var progress = vault.poll(operation)
                while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
                progress
            }
            check(result.status == 0) { "native operation failed: ${result.status}" }
        } finally { vault.closeOperation(operation) }
    }

    private suspend fun objectWith(text: String): ByteArray = vault.page(ObjectQuery()).objects.single {
        vault.readRange(it.objectId, 0, it.plaintextSize.toInt()).decodeToString() == text
    }.objectId

    private suspend fun originals(expected: Set<String>) {
        val page = vault.page(ObjectQuery())
        check(page.totalCount == expected.size.toLong()) { "unexpected original count: ${page.totalCount}" }
        check(page.objects.map { vault.readRange(it.objectId, 0, it.plaintextSize.toInt()).decodeToString() }.toSet() == expected)
    }

    private suspend fun members(key: String, expected: Set<String>) {
        val id = load(key)
        val page = vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = id))
        check(page.totalCount == expected.size.toLong()) { "$key has ${page.totalCount} members; expected ${expected.size}" }
        check(page.objects.map { vault.readRange(it.objectId, 0, it.plaintextSize.toInt()).decodeToString() }.toSet() == expected)
        check(vault.albums().single { it.albumId.contentEquals(id) }.memberCount == page.totalCount)
    }

    private suspend fun remember(key: String, title: String) = save(key, vault.albums().single { it.name == title }.albumId)

    private suspend fun name(key: String, expected: String) {
        check(vault.albums().single { it.albumId.contentEquals(load(key)) }.name == expected) { "$key name did not converge to $expected" }
    }

    private suspend fun snapshot(): ByteArray {
        val rows = mutableListOf<String>()
        for (album in vault.albums().sortedBy { it.id }) {
            val page = vault.page(ObjectQuery(QueryScope.ALBUM, scopeId = album.albumId))
            rows += "${album.id}|${album.name}|${album.memberCount}|${page.objects.map { it.id }.sorted().joinToString(",")}"
        }
        return rows.joinToString("\n").encodeToByteArray()
    }

    private fun save(name: String, bytes: ByteArray) = files.write("$root/$name", bytes)
    private fun load(name: String): ByteArray = files.read("$root/$name")

    private companion object {
        const val PASSWORD = "Public-album-network-acceptance-fixture-20261017"
        const val ALPHA = "Public Android album fixture alpha."
        const val BETA = "Public iOS album fixture beta."
    }
}
