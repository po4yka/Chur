package dev.po4yka.chur.android

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.QuerySort
import dev.po4yka.chur.ffi.PrivateNote
import dev.po4yka.chur.ffi.PrivateNoteAction
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-enabled command channel for the currently open vault, for `adb forward` only.
 *
 * It listens on an abstract-namespace UNIX socket with a random name, not on TCP:
 * any local app can connect to a loopback port. The kernel reports the peer UID,
 * and only ADB ([isAdbPeer]) is served, so an app that learns the session link
 * still cannot connect.
 */
internal class DeviceControlBridge(
    private val context: Context,
    private val controller: ChurController,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val frameTimeoutMs: Long = FRAME_TIMEOUT_MS,
    private val acceptPeer: (Int) -> Boolean = ::isAdbPeer,
) {
    data class Pairing(val socket: String, val code: String) {
        val link: String get() = "chur://device-control/v3?socket=$socket#$code"
    }

    init { require(idleTimeoutMs > 0 && handshakeTimeoutMs > 0 && frameTimeoutMs > 0) }

    private val random = SecureRandom()
    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing = _pairing.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private class Session(val listener: LocalServerSocket, val pairing: Pairing, val generation: Long) {
        val job = SupervisorJob()
        @Volatile var lastActivityAt = SystemClock.elapsedRealtime()
        @Volatile var client: LocalSocket? = null
        @Volatile var peer: Peer? = null
    }

    @Volatile private var session: Session? = null

    @Synchronized fun start(): Pairing {
        val unlocked = controller.vaultState.value as? VaultState.Unlocked
            ?: error("VAULT_LOCKED")
        session?.let {
            if (it.generation == unlocked.generation) return it.pairing
            stop(it)
        }
        // A random name: another app cannot bind it first and take the forward.
        val name = "chur-dc-" + ByteArray(8).also(random::nextBytes).hex()
        val listener = LocalServerSocket(name)
        val current = Session(listener, Pairing(name, ByteArray(16).also(random::nextBytes).hex()),
            unlocked.generation)
        session = current
        _pairing.value = current.pairing
        thread(name = "chur-device-control-expiry", isDaemon = true) {
            while (session === current) {
                val now = SystemClock.elapsedRealtime()
                if (now - current.lastActivityAt >= idleTimeoutMs ||
                    (controller.vaultState.value as? VaultState.Unlocked)?.generation != current.generation
                ) {
                    stop(current)
                    break
                }
                current.peer?.expire(now)
                Thread.sleep(100)
            }
        }
        thread(name = "chur-device-control", isDaemon = true) {
            while (session === current) {
                var socket: LocalSocket? = null
                try {
                    val accepted = listener.accept()
                    synchronized(this) {
                        if (session !== current) { accepted.close(); return@thread }
                    }
                    val uid = try { accepted.peerCredentials.uid } catch (_: IOException) { -1 }
                    if (!acceptPeer(uid)) {
                        accepted.close()
                        continue
                    }
                    socket = accepted
                    synchronized(this) {
                        if (session !== current) { accepted.close(); return@thread }
                        current.client = accepted
                    }
                    accepted.use { serve(it, current) }
                } catch (_: IOException) {
                    if (session !== current) break
                } finally {
                    synchronized(this) {
                        if (current.client === socket) {
                            current.client = null
                            current.peer = null
                            if (session === current) _connected.value = false
                        }
                    }
                }
            }
        }
        return current.pairing
    }

    @Synchronized fun stop() { session?.let(::stop) }

    @Synchronized private fun stop(current: Session) {
        if (session !== current) return
        session = null
        // Connected goes first: a watcher that sees the pairing end must not still
        // see a connected computer.
        _connected.value = false
        _pairing.value = null
        current.job.cancel()
        // Closing a LocalServerSocket does not wake a blocked accept(). A connection
        // does: the accept thread sees the ended session and exits.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(current.pairing.socket)) } }
        runCatching { current.listener.close() }
        current.client?.hangUp()
    }

    private fun requireCurrent(current: Session) {
        current.job.ensureActive()
        check(session === current &&
            (controller.vaultState.value as? VaultState.Unlocked)?.generation == current.generation)
    }

    private fun serve(socket: LocalSocket, current: Session) {
        val connectionJob = Job(current.job)
        try { serveConnected(socket, current, connectionJob) }
        finally { connectionJob.cancel() }
    }

    private fun serveConnected(socket: LocalSocket, current: Session, connectionJob: Job) {
        val peer = Peer(socket, frameTimeoutMs, handshakeTimeoutMs,
            expired = { connectionJob.cancel() },
            activity = { current.lastActivityAt = SystemClock.elapsedRealtime() })
        current.peer = peer
        var export: ExportSession? = null
        val hello = try { peer.receive(handshakeTimeoutMs) } catch (_: Exception) { return }
        val supplied = hello.optString("code").toByteArray(Charsets.UTF_8)
        val expected = current.pairing.code.toByteArray(Charsets.UTF_8)
        if (hello.optString("op") != "pair" || hello.optInt("version") != 2 ||
            !MessageDigest.isEqual(supplied, expected)
        ) {
            runCatching { peer.send(JSONObject().put("error", "PAIRING_REJECTED")) }
            return
        }
        try {
            val identity = runBlocking(connectionJob + Dispatchers.Default) {
                requireCurrent(current)
                requireNotNull(controller.vault.syncIdentity())
            }
            peer.send(JSONObject().put("ok", true).put("version", 2)
                .put("vault_id", identity.vaultId.hex()))
        } catch (_: Exception) { return }
        peer.authenticated = true
        current.lastActivityAt = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (session !== current) return
            _connected.value = true
        }
        try { while (session === current) {
            val request = try { peer.receive() } catch (_: Exception) { break }
            val response = try {
                runBlocking(connectionJob + Dispatchers.Main) {
                    requireCurrent(current)
                    if (request.optString("op") == "import") execute(peer, request, current)
                    else withContext(Dispatchers.Default) {
                        requireCurrent(current)
                        when (request.optString("op")) {
                            "export_begin" -> {
                                export?.close()
                                export = null
                                val id = request.getString("object").idBytes()
                                val reader = controller.vault.leaseReader(id)
                                try {
                                    val info = controller.vault.readerContentInfo(reader)
                                    require(info.complete && info.plaintextSize >= 0)
                                    val name = controller.vault.detail(id).filename
                                    export = ExportSession(reader, info.plaintextSize)
                                    JSONObject().put("size", info.plaintextSize).put("filename", name)
                                } catch (failure: Exception) {
                                    controller.vault.releaseReader(reader)
                                    throw failure
                                }
                            }
                            "export_read" -> requireNotNull(export).read(request)
                            "export_finish" -> {
                                val result = requireNotNull(export).finish()
                                export?.close()
                                export = null
                                result
                            }
                            else -> execute(peer, request, current)
                        }
                    }
                }
            } catch (_: CancellationException) { break
            } catch (failure: ChurFailure) {
                JSONObject().put("error", failure.status.name)
            } catch (failure: CursorStale) {
                JSONObject().put("error", "CURSOR_STALE")
            } catch (_: Exception) {
                JSONObject().put("error", "INVALID_REQUEST_OR_OPERATION_FAILED")
            }
            if (session !== current) break
            try { peer.send(response) } catch (_: IOException) { break }
        } } finally { export?.close() }
    }

    private suspend fun execute(peer: Peer, request: JSONObject, current: Session): JSONObject {
        requireCurrent(current)
        val vault = controller.vault
        return when (request.getString("op")) {
            "list" -> {
                val scope = QueryScope.valueOf(request.optString("scope", "timeline").uppercase())
                val sort = QuerySort.valueOf(request.optString("sort", "capture_desc").uppercase())
                val page = vault.page(ObjectQuery(
                    scope = scope,
                    sort = sort,
                    limit = request.optInt("limit", 100).also { require(it in 1..500) },
                    scopeId = request.optString("id").takeIf { it.isNotEmpty() }?.idBytes(),
                    terms = request.optString("terms").takeIf { it.isNotEmpty() },
                    cursor = request.optString("cursor").takeIf { it.isNotEmpty() }?.hexBytes(42),
                ))
                JSONObject().put("total", page.totalCount)
                    .put("cursor", page.nextCursor?.hex())
                    .put("objects", JSONArray().also { rows ->
                        page.objects.forEach { item ->
                            rows.put(JSONObject().put("id", item.id)
                                .put("media_kind", item.mediaKind)
                                .put("size", item.plaintextSize)
                                .put("capture_time_ms", item.captureTimeMs)
                                .put("favorite", item.favorite)
                                .put("thumbnail_ready", item.thumbnailReady))
                        }
                    })
            }
            "albums" -> paged(request, "albums", vault.albums().map {
                JSONObject().put("id", it.id).put("name", it.name).put("count", it.memberCount)
                    .put("parent", it.parentId?.hex()).put("position", it.position)
            })
            "tags" -> paged(request, "tags", vault.tags().map {
                JSONObject().put("id", it.id).put("name", it.name)
            })
            "show", "import_status" -> {
                val detail = vault.detail(request.getString("object").idBytes())
                val metadata = JSONObject().put("id", request.getString("object"))
                    .put("filename", detail.filename).put("caption", detail.caption)
                    .put("content_type", detail.contentType).put("size", detail.plaintextSize)
                    .put("capture_time_ms", detail.captureTimeMs).put("width", detail.width)
                    .put("height", detail.height).put("duration_ms", detail.durationMs)
                if (request.getString("op") == "import_status") metadata
                else paged(request, "tags", detail.tags.map { (id, name) ->
                    JSONObject().put("id", id.hex()).put("name", name)
                }, metadata)
            }
            "object_trash", "object_restore", "object_delete", "trash_empty", "trash_restore", "damaged_delete" -> {
                when (request.getString("op")) {
                    "object_trash" -> vault.delete(request.getString("object").idBytes())
                    "object_restore" -> vault.restoreAll(listOf(request.getString("object").idBytes()))
                    "object_delete" -> vault.permanentlyDelete(request.getString("object").idBytes())
                    "trash_empty" -> vault.emptyTrash()
                    "trash_restore" -> vault.restoreTrash()
                    "damaged_delete" -> vault.deleteDamagedFiles()
                }
                controller.refreshExternalChanges(albums = true)
                JSONObject().put("ok", true)
            }
            "tag_rename", "tag_delete" -> {
                val id = request.getString("tag").idBytes()
                if (request.getString("op") == "tag_rename") vault.renameTag(id, request.boundedLabel())
                else vault.deleteTag(id)
                controller.refreshExternalChanges(tags = true)
                JSONObject().put("ok", true)
            }
            "notes" -> {
                val scope = request.optString("scope", "active")
                require(scope in setOf("active", "trash"))
                val page = vault.privateNotes(current.generation, request.optString("terms"),
                    scope == "trash", request.optInt("limit", 50).also { require(it in 1..100) }.coerceAtMost(80),
                    request.optString("cursor").takeIf { it.isNotEmpty() }?.hexBytes(65))
                JSONObject().put("cursor", page.nextCursor?.hex()).put("notes", JSONArray().also { rows ->
                    page.notes.forEach { note ->
                        rows.put(JSONObject().put("id", note.id).put("revision", note.revision)
                            .put("title", note.title).put("preview", note.preview)
                            .put("pinned", note.pinned).put("created_ms", note.createdMs)
                            .put("updated_ms", note.updatedMs).put("trashed_at_ms", note.trashedAtMs))
                    }
                })
            }
            "note_show", "note_read" -> {
                val note = vault.privateNote(current.generation, request.getString("note").idBytes())
                if (request.getString("op") == "note_show") note.metadata()
                else {
                    require(note.revision == request.getLong("revision"))
                    val body = note.body.toByteArray(Charsets.UTF_8)
                    try {
                        val offset = request.getInt("offset")
                        val size = request.getInt("size")
                        require(offset >= 0 && size in 1..65_536 && offset <= body.size && size <= body.size - offset)
                        val bytes = body.copyOfRange(offset, offset + size)
                        try { JSONObject().put("offset", offset)
                            .put("bytes", Base64.encodeToString(bytes, Base64.NO_WRAP)) }
                        finally { bytes.fill(0) }
                    } finally { body.fill(0) }
                }
            }
            "note_create", "note_update" -> {
                val length = request.getInt("length")
                require(length in 0..262_144)
                val title = request.getString("title")
                require(title.toByteArray(Charsets.UTF_8).size <= 256)
                val body = ByteArray(length)
                try {
                    var at = 0
                    while (at < length) {
                        requireCurrent(current)
                        val bytes = peer.readRange(at.toLong(), minOf(length - at, 65_536))
                        try { bytes.copyInto(body, at); at += bytes.size } finally { bytes.fill(0) }
                    }
                    requireCurrent(current)
                    vault.savePrivateNote(current.generation,
                        if (request.getString("op") == "note_update") request.getString("note").idBytes() else null,
                        if (request.getString("op") == "note_update") request.getLong("revision") else 0,
                        title, body.decodeToString(throwOnInvalidSequence = true), request.getBoolean("pinned")).metadata()
                } finally { body.fill(0) }
            }
            "note_pin", "note_trash", "note_restore", "note_delete" -> {
                val action = when (request.getString("op")) {
                    "note_pin" -> if (request.getBoolean("pinned")) PrivateNoteAction.PIN else PrivateNoteAction.UNPIN
                    "note_trash" -> PrivateNoteAction.TRASH
                    "note_restore" -> PrivateNoteAction.RESTORE
                    else -> PrivateNoteAction.PURGE
                }
                vault.changePrivateNote(current.generation, request.getString("note").idBytes(),
                    request.getLong("revision"), action)?.metadata() ?: JSONObject().put("ok", true)
            }
            "album_create" -> {
                val id = vault.createAlbum(request.boundedLabel(), requestedId = request.getString("id").idBytes())
                controller.refreshExternalChanges(albums = true)
                JSONObject().put("id", id.hex())
            }
            "album_rename" -> {
                vault.renameAlbum(request.getString("album").idBytes(), request.boundedLabel())
                controller.refreshExternalChanges(albums = true)
                JSONObject().put("ok", true)
            }
            "album_delete" -> {
                vault.deleteAlbum(request.getString("album").idBytes())
                controller.refreshExternalChanges(albums = true)
                JSONObject().put("ok", true)
            }
            "album_move" -> {
                vault.moveAlbum(request.getString("album").idBytes(),
                    request.optString("parent").takeIf { it.isNotEmpty() }?.idBytes(),
                    request.optString("before").takeIf { it.isNotEmpty() }?.idBytes())
                controller.refreshExternalChanges(albums = true)
                JSONObject().put("ok", true)
            }
            "album_reorder" -> {
                vault.moveAlbumMember(request.getString("album").idBytes(),
                    request.getString("object").idBytes(),
                    request.optString("before").takeIf { it.isNotEmpty() }?.idBytes())
                controller.refreshExternalChanges(albums = true)
                JSONObject().put("ok", true)
            }
            "tag_create" -> {
                val id = vault.createTag(request.boundedLabel())
                controller.refreshExternalChanges(tags = true)
                JSONObject().put("id", id.hex())
            }
            "album_member" -> {
                vault.setAlbumMembership(request.getString("album").idBytes(),
                    request.getString("object").idBytes(), request.getBoolean("member"))
                controller.refreshExternalChanges(albums = true)
                JSONObject().put("ok", true)
            }
            "object_tag" -> {
                vault.setObjectTag(request.getString("tag").idBytes(),
                    request.getString("object").idBytes(), request.getBoolean("tagged"))
                controller.refreshExternalChanges()
                JSONObject().put("ok", true)
            }
            "favorite" -> {
                vault.setFavorite(request.getString("object").idBytes(), request.getBoolean("favorite"))
                controller.refreshExternalChanges()
                JSONObject().put("ok", true)
            }
            "batch" -> {
                val action = request.getString("action")
                require(action in setOf("album-add", "album-remove", "tag-add", "tag-remove",
                    "favorite", "unfavorite", "trash", "restore", "delete"))
                val target = if (action.startsWith("album-") || action.startsWith("tag-")) {
                    request.getString("target").idBytes()
                } else null
                val objects = request.getJSONArray("objects")
                require(objects.length() in 1..100)
                val ids = (0 until objects.length()).map { objects.getString(it).idBytes() }
                val rows = JSONArray()
                ids.forEach { id ->
                    requireCurrent(current)
                    val row = JSONObject().put("id", id.hex())
                    try {
                        when (action) {
                            "album-add", "album-remove" ->
                                vault.setAlbumMembership(requireNotNull(target), id, action == "album-add")
                            "tag-add", "tag-remove" ->
                                vault.setObjectTag(requireNotNull(target), id, action == "tag-add")
                            "favorite", "unfavorite" -> vault.setFavorite(id, action == "favorite")
                            "trash" -> vault.delete(id)
                            "restore" -> vault.restoreAll(listOf(id))
                            "delete" -> vault.permanentlyDelete(id)
                        }
                        row.put("ok", true)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: ChurFailure) {
                        row.put("error", failure.status.name)
                    } catch (_: Exception) {
                        row.put("error", "OPERATION_FAILED")
                    }
                    rows.put(row)
                }
                controller.refreshExternalChanges(albums = action.startsWith("album-"))
                JSONObject().put("results", rows)
            }
            "import" -> importFile(peer, request)
            else -> throw IllegalArgumentException("unknown operation")
        }
    }

    private suspend fun importFile(peer: Peer, request: JSONObject): JSONObject {
        val requestedId = request.getString("object_id").idBytes()
        val name = request.boundedFilename()
        val length = request.getLong("length")
        require(length >= 0)
        val contentType = request.getString("content_type")
        require(contentType.length in 1..127 && !contentType.contains('\n'))
        val existing = try {
            withContext(Dispatchers.Default) { controller.vault.detail(requestedId) }
        } catch (failure: ChurFailure) {
            if (failure.status != ChurStatus.NOT_FOUND) throw failure
            null
        }
        if (existing != null) {
            require(existing.filename == name && existing.plaintextSize == length)
            return JSONObject().put("id", requestedId.hex()).put("reused", true)
        }
        val source = RemoteSource(ByteArray(16).also(random::nextBytes).hex(), name, contentType,
            length) { offset, size -> peer.readRange(offset, size) }
        val uri = DeviceImportProvider.mount(context, source)
        try {
            val codec = AndroidMediaCodec(context)
            val outcome = controller.importMedia(MediaImporter(codec), requestedObjectId = requestedId) { codec.open(uri) }
            return when (outcome) {
                is MediaImporter.Outcome.Imported -> JSONObject().put("id", outcome.objectId.hex())
                    .put("derivatives", outcome.derivatives)
                    .put("previews_skipped", outcome.previewsSkipped)
                    .put("preview_warning", outcome.previewWarning?.name)
                is MediaImporter.Outcome.Refused -> JSONObject().put("error", outcome.status.name)
                is MediaImporter.Outcome.TooLarge -> JSONObject().put("error", "RESOURCE_LIMIT_EXCEEDED")
                MediaImporter.Outcome.Unreadable -> JSONObject().put("error", "UNREADABLE_SOURCE")
                null -> JSONObject().put("error", "BUSY")
            }
        } finally {
            DeviceImportProvider.unmount(source)
            controller.refreshExternalChanges()
        }
    }

    private fun PrivateNote.metadata(): JSONObject {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val bodySize = try { exportSize - titleBytes.size - 2 } finally { titleBytes.fill(0) }
        return JSONObject().put("id", id).put("revision", revision).put("title", title).put("pinned", pinned)
            .put("created_ms", createdMs).put("updated_ms", updatedMs)
            .put("trashed_at_ms", trashedAtMs).put("body_size", bodySize)
    }

    private class CursorStale : IllegalArgumentException()

    /** Offset pages are bound to the complete ordered snapshot, so edits cannot silently skip rows. */
    private fun paged(request: JSONObject, key: String, items: List<JSONObject>,
        response: JSONObject = JSONObject()): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        items.forEach { item -> digest.update(item.toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        val fingerprint = digest.digest().hex()
        val cursor = request.optString("cursor")
        val start = if (cursor.isEmpty()) 0 else {
            val parts = cursor.split(':')
            require(parts.size == 2)
            if (parts[0] != fingerprint) throw CursorStale()
            parts[1].toInt().also { require(it in 0..items.size) }
        }
        val limit = request.optInt("limit", 100).also { require(it in 1..500) }
        val rows = JSONArray()
        response.put("total", items.size).put(key, rows)
        var size = response.toString().toByteArray(Charsets.UTF_8).size + 128
        var at = start
        while (at < items.size && at - start < limit) {
            val itemSize = items[at].toString().toByteArray(Charsets.UTF_8).size + 1
            if (size + itemSize > 240_000) break
            rows.put(items[at++]); size += itemSize
        }
        require(at > start || start == items.size)
        return response.put("cursor", if (at < items.size) "$fingerprint:$at" else JSONObject.NULL)
    }

    private fun JSONObject.boundedLabel(): String = getString("name").also {
        require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 4_096)
        require('\u0000' !in it)
    }

    private fun JSONObject.boundedFilename(): String = getString("name").also {
        require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 255)
        require('/' !in it && '\\' !in it && '\u0000' !in it)
    }

    private fun String.idBytes(): ByteArray = hexBytes(16)

    private fun String.hexBytes(expected: Int): ByteArray {
        require(length == expected * 2 && all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(expected) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private inner class ExportSession(private val reader: Long, private val size: Long) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var offset = 0L

        fun read(request: JSONObject): JSONObject {
            val at = request.getLong("offset")
            val length = request.getInt("size")
            require(at == offset && length in 1..65_536 && length <= size - offset)
            val bytes = controller.vault.readLeased(reader, offset, length)
            require(bytes.size == length)
            digest.update(bytes)
            offset += length
            return JSONObject().put("offset", at)
                .put("bytes", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }

        fun finish(): JSONObject {
            require(offset == size)
            return JSONObject().put("size", size).put("sha256", digest.digest().hex())
        }

        fun close() = controller.vault.releaseReader(reader)
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val FRAME_TIMEOUT_MS = 60_000L
    }

    /** Both reads and writes have an absolute deadline; slow trickles cannot renew it. */
    private class Peer(private val socket: LocalSocket, private val frameTimeoutMs: Long,
        handshakeTimeoutMs: Long, private val expired: () -> Unit, private val activity: () -> Unit) {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        private val handshakeDeadline = SystemClock.elapsedRealtime() + handshakeTimeoutMs
        @Volatile private var deadline = Long.MAX_VALUE
        @Volatile var authenticated = false

        fun expire(now: Long) {
            if (now >= deadline || (!authenticated && now >= handshakeDeadline)) {
                expired()
                socket.hangUp()
            }
        }

        private fun readFully(bytes: ByteArray) {
            var at = 0
            while (at < bytes.size) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw SocketTimeoutException("frame deadline")
                socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                val count = input.read(bytes, at, bytes.size - at)
                if (count < 0) throw IOException("unexpected end of frame")
                at += count
            }
        }

        fun receive(timeout: Long = frameTimeoutMs): JSONObject {
            deadline = SystemClock.elapsedRealtime() + timeout
            try {
                val header = ByteArray(4)
                readFully(header)
                val length = header.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
                if (length !in 1..262_144) throw IOException("invalid frame")
                val body = ByteArray(length)
                try {
                    readFully(body)
                    return JSONObject(body.decodeToString(throwOnInvalidSequence = true)).also {
                        if (authenticated) activity()
                    }
                } finally { body.fill(0) }
            } finally { deadline = Long.MAX_VALUE }
        }

        @Synchronized fun send(message: JSONObject) {
            val bytes = message.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > 262_144) throw IOException("oversized frame")
            deadline = SystemClock.elapsedRealtime() + frameTimeoutMs
            try {
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
                if (authenticated) activity()
            } finally { bytes.fill(0); deadline = Long.MAX_VALUE }
        }

        @Synchronized fun readRange(offset: Long, size: Int): ByteArray {
            send(JSONObject().put("op", "read").put("offset", offset).put("size", size))
            val response = receive()
            if (response.optString("op") != "data") throw IOException("source unavailable")
            val bytes = Base64.decode(response.getString("bytes"), Base64.NO_WRAP)
            if (bytes.size != size) throw IOException("short source read")
            return bytes
        }
    }
}

/**
 * Close a connection so that the peer sees the end at once. A plain close does not
 * wake a thread of this process that is blocked in a read of the same socket.
 */
private fun LocalSocket.hangUp() {
    runCatching { shutdownInput() }
    runCatching { shutdownOutput() }
    runCatching { close() }
}

/**
 * The peers that `adb forward` connects as: the shell UID, and root under `adb root`.
 * Root already has full access to the device, so accepting it adds no reach.
 */
internal fun isAdbPeer(uid: Int): Boolean = uid == Process.SHELL_UID || uid == Process.ROOT_UID
