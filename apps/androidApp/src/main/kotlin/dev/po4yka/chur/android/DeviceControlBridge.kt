package dev.po4yka.chur.android

import android.content.Context
import android.util.Base64
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.QuerySort
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A user-enabled, loopback-only command channel for the currently open vault. */
internal class DeviceControlBridge(
    private val context: Context,
    private val controller: ChurController,
) {
    data class Pairing(val port: Int, val code: String)

    private val random = SecureRandom()
    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing = _pairing.asStateFlow()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    @Synchronized fun start(): Pairing {
        check(controller.vaultState.value is VaultState.Unlocked)
        _pairing.value?.let { return it }
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val code = ByteArray(16).also(random::nextBytes).hex()
        val current = Pairing(listener.localPort, code)
        server = listener
        _pairing.value = current
        thread(name = "chur-device-control", isDaemon = true) {
            while (server === listener) {
                try {
                    val socket = listener.accept()
                    client = socket
                    socket.use { serve(it, current) }
                } catch (_: IOException) {
                    if (server !== listener) break
                } finally {
                    client = null
                }
            }
        }
        return current
    }

    @Synchronized fun stop() {
        if (_pairing.value != null) controller.cancelActiveOperation()
        _pairing.value = null
        server?.close()
        server = null
        client?.close()
        client = null
    }

    private fun serve(socket: Socket, current: Pairing) {
        socket.soTimeout = 10_000
        val peer = Peer(socket)
        var export: ExportSession? = null
        val hello = try { peer.receive() } catch (_: Exception) { return }
        val supplied = hello.optString("code").toByteArray(Charsets.UTF_8)
        val expected = current.code.toByteArray(Charsets.UTF_8)
        if (hello.optString("op") != "pair" || hello.optInt("version") != 1 ||
            !MessageDigest.isEqual(supplied, expected)
        ) {
            peer.send(JSONObject().put("error", "PAIRING_REJECTED"))
            return
        }
        peer.send(JSONObject().put("ok", true).put("version", 1))
        socket.soTimeout = 60_000
        try { while (server != null && controller.vaultState.value is VaultState.Unlocked) {
            val request = try { peer.receive() } catch (_: Exception) { break }
            val response = try {
                if (controller.vaultState.value !is VaultState.Unlocked) {
                    throw IllegalStateException("VAULT_LOCKED")
                }
                runBlocking(Dispatchers.Main) {
                    if (request.optString("op") == "import") execute(peer, request)
                    else withContext(Dispatchers.Default) {
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
                            else -> execute(peer, request)
                        }
                    }
                }
            } catch (failure: ChurFailure) {
                JSONObject().put("error", failure.status.name)
            } catch (_: Exception) {
                JSONObject().put("error", "INVALID_REQUEST_OR_OPERATION_FAILED")
            }
            try { peer.send(response) } catch (_: IOException) { break }
        } } finally { export?.close() }
    }

    private suspend fun execute(peer: Peer, request: JSONObject): JSONObject {
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
            "albums" -> JSONObject().put("albums", JSONArray().also { rows ->
                vault.albums().forEach { rows.put(JSONObject().put("id", it.id)
                    .put("name", it.name).put("count", it.memberCount)
                    .put("parent", it.parentId?.hex()).put("position", it.position)) }
            })
            "tags" -> JSONObject().put("tags", JSONArray().also { rows ->
                vault.tags().forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            "show" -> {
                val detail = vault.detail(request.getString("object").idBytes())
                JSONObject().put("filename", detail.filename)
                    .put("caption", detail.caption)
                    .put("content_type", detail.contentType)
                    .put("size", detail.plaintextSize)
                    .put("capture_time_ms", detail.captureTimeMs)
                    .put("width", detail.width)
                    .put("height", detail.height)
                    .put("duration_ms", detail.durationMs)
                    .put("tags", JSONArray().also { rows ->
                        detail.tags.forEach { (id, name) ->
                            rows.put(JSONObject().put("id", id.hex()).put("name", name))
                        }
                    })
            }
            "album_create" -> {
                val id = vault.createAlbum(request.boundedLabel())
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
                    "favorite", "unfavorite"))
                val target = if (action.startsWith("album-") || action.startsWith("tag-")) {
                    request.getString("target").idBytes()
                } else null
                val objects = request.getJSONArray("objects")
                require(objects.length() in 1..100)
                val ids = (0 until objects.length()).map { objects.getString(it).idBytes() }
                val rows = JSONArray()
                ids.forEach { id ->
                    val row = JSONObject().put("id", id.hex())
                    try {
                        when (action) {
                            "album-add", "album-remove" ->
                                vault.setAlbumMembership(requireNotNull(target), id, action == "album-add")
                            "tag-add", "tag-remove" ->
                                vault.setObjectTag(requireNotNull(target), id, action == "tag-add")
                            "favorite", "unfavorite" -> vault.setFavorite(id, action == "favorite")
                        }
                        row.put("ok", true)
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
        val name = request.boundedFilename()
        val length = request.getLong("length")
        require(length >= 0)
        val contentType = request.getString("content_type")
        require(contentType.length in 1..127 && !contentType.contains('\n'))
        val source = RemoteSource(ByteArray(16).also(random::nextBytes).hex(), name, contentType,
            length) { offset, size -> peer.readRange(offset, size) }
        val uri = DeviceImportProvider.mount(context, source)
        try {
            val codec = AndroidMediaCodec(context.contentResolver)
            val outcome = controller.importMedia(MediaImporter(codec)) { codec.open(uri) }
            return when (outcome) {
                is MediaImporter.Outcome.Imported -> JSONObject().put("id", outcome.objectId.hex())
                    .put("derivatives", outcome.derivatives)
                    .put("previews_skipped", outcome.previewsSkipped)
                is MediaImporter.Outcome.Refused -> JSONObject().put("error", outcome.status)
                is MediaImporter.Outcome.TooLarge -> JSONObject().put("error", "RESOURCE_LIMIT_EXCEEDED")
                MediaImporter.Outcome.Unreadable -> JSONObject().put("error", "UNREADABLE_SOURCE")
                null -> JSONObject().put("error", "BUSY")
            }
        } finally {
            DeviceImportProvider.unmount(source)
            controller.refreshExternalChanges()
        }
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

    private class Peer(socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())

        fun receive(): JSONObject {
            val length = input.readInt()
            if (length !in 1..262_144) throw IOException("invalid frame")
            val body = ByteArray(length)
            input.readFully(body)
            return JSONObject(String(body, Charsets.UTF_8))
        }

        @Synchronized fun send(message: JSONObject) {
            val bytes = message.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > 262_144) throw IOException("oversized frame")
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
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
