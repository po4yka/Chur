package dev.po4yka.chur.android

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.NoPrivacyCover
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.vault.VaultState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real loopback framing, application controller, encrypted catalog and JNI. */
@RunWith(AndroidJUnit4::class)
class DeviceControlBridgeAndroidTest {
    @Test fun hierarchyImportRetryTagsTrashAndNotesRoundTrip() = fixture { controller, bridge, _ ->
        Client(bridge.start()).use { client ->
            val parent = id()
            val child = id()
            client.call("album_create", "id" to parent, "name" to "Desktop folder")
            client.call("album_create", "id" to child, "name" to "Nested folder")
            assertEquals(child, client.call("album_create", "id" to child, "name" to "Nested folder").getString("id"))
            client.call("album_move", "album" to child, "parent" to parent)
            val objectId = id()
            val bytes = "Public desktop bridge fixture".toByteArray()
            val imported = client.stream("import", bytes, "object_id" to objectId,
                "name" to "source.txt", "content_type" to "text/plain", "length" to bytes.size)
            assertEquals(objectId, imported.getString("id"))
            assertEquals(objectId, client.call("import_status", "object" to objectId).getString("id"))
            assertTrue(client.call("import", "object_id" to objectId, "name" to "source.txt",
                "content_type" to "text/plain", "length" to bytes.size).getBoolean("reused"))
            assertEquals(1, controller.vault.page(ObjectQuery()).objects.size)
            assertEquals(bytes.size.toLong(), client.call("export_begin", "object" to objectId).getLong("size"))
            val exported = client.call("export_read", "offset" to 0, "size" to bytes.size)
            assertArrayEquals(bytes, Base64.decode(exported.getString("bytes"), Base64.NO_WRAP))
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(digest, client.call("export_finish").getString("sha256"))
            client.call("album_member", "album" to child, "object" to objectId, "member" to true)
            val tag = client.call("tag_create", "name" to "Original tag").getString("id")
            client.call("tag_rename", "tag" to tag, "name" to "Renamed tag")
            client.call("object_tag", "tag" to tag, "object" to objectId, "tagged" to true)
            assertEquals("Renamed tag", client.call("show", "object" to objectId)
                .getJSONArray("tags").getJSONObject(0).getString("name"))
            client.call("tag_delete", "tag" to tag)
            client.call("object_trash", "object" to objectId)
            assertEquals(1, client.call("list", "scope" to "trash").getJSONArray("objects").length())
            assertEquals(objectId, client.call("import_status", "object" to objectId).getString("id"))
            client.call("object_restore", "object" to objectId)
            client.call("object_trash", "object" to objectId)
            client.call("trash_restore")
            assertEquals(1, client.call("list").getJSONArray("objects").length())
            client.call("object_trash", "object" to objectId)
            client.call("object_delete", "object" to objectId)
            assertEquals(0, client.call("list", "scope" to "trash").getJSONArray("objects").length())

            // Escaping this maximum-sized native note in one JSON frame would exceed the frame bound.
            val body = "\n".repeat(262_144).toByteArray()
            var note = client.stream("note_create", body, "title" to "Streamed note",
                "pinned" to false, "length" to body.size)
            val noteId = note.getString("id")
            assertEquals(body.size, note.getInt("body_size"))
            val restored = ByteArray(body.size)
            for (offset in body.indices step 65_536) {
                val chunk = client.call("note_read", "note" to noteId, "revision" to note.getLong("revision"),
                    "offset" to offset, "size" to minOf(65_536, body.size - offset))
                Base64.decode(chunk.getString("bytes"), Base64.NO_WRAP).copyInto(restored, offset)
            }
            assertArrayEquals(body, restored)
            val previousRevision = note.getLong("revision")
            note = client.stream("note_update", "Searchable bridge body".toByteArray(),
                "note" to noteId, "revision" to previousRevision, "title" to "Updated note",
                "pinned" to false, "length" to 22)
            assertEquals(1, client.call("notes", "terms" to "Searchable").getJSONArray("notes").length())
            assertTrue(client.raw("note_trash", "note" to noteId, "revision" to previousRevision).has("error"))
            note = client.call("note_pin", "note" to noteId, "revision" to note.getLong("revision"), "pinned" to true)
            assertTrue(note.getBoolean("pinned"))
            note = client.call("note_trash", "note" to noteId, "revision" to note.getLong("revision"))
            assertEquals(1, client.call("notes", "scope" to "trash").getJSONArray("notes").length())
            note = client.call("note_restore", "note" to noteId, "revision" to note.getLong("revision"))
            note = client.call("note_trash", "note" to noteId, "revision" to note.getLong("revision"))
            client.call("note_delete", "note" to noteId, "revision" to note.getLong("revision"))
            assertEquals(0, client.call("notes", "scope" to "trash").getJSONArray("notes").length())
        }
    }

    @Test fun catalogAndDetailPagesStayBelowFrameLimitAndRejectChangedSnapshots() = fixture { controller, bridge, root ->
        val objectId = importFixture(controller, root)
        repeat(80) { index ->
            val name = index.toString().padStart(3, '0') + "x".repeat(4_000)
            controller.vault.createAlbum(name)
            val tag = controller.vault.createTag(name)
            controller.vault.setObjectTag(tag, objectId.hexBytes(), true)
        }
        Client(bridge.start()).use { client ->
            for ((op, key) in listOf("albums" to "albums", "tags" to "tags", "show" to "tags")) {
                var cursor = ""
                val seen = mutableSetOf<String>()
                var pages = 0
                do {
                    val page = client.call(op, "object" to objectId, "limit" to 500, "cursor" to cursor)
                    assertTrue(page.toString().toByteArray().size <= 262_144)
                    val rows = page.getJSONArray(key)
                    repeat(rows.length()) { assertTrue(seen.add(rows.getJSONObject(it).getString("id"))) }
                    cursor = page.optString("cursor").takeUnless { it == "null" }.orEmpty()
                    pages++
                } while (cursor.isNotEmpty())
                assertEquals(80, seen.size)
                assertTrue(pages > 1)
            }
            val cursor = client.call("albums", "limit" to 1).getString("cursor")
            client.call("album_create", "id" to id(), "name" to "New catalog generation")
            assertEquals("CURSOR_STALE", client.raw("albums", "cursor" to cursor).getString("error"))
        }
    }

    @Test fun stoppedQueuedCommandCannotMutateAndOldWorkerCannotClearNewPairing() = fixture { controller, bridge, root ->
        val objectId = importFixture(controller, root)
        Client(bridge.start()).use { old ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                old.send("favorite", "object" to objectId, "favorite" to true)
                bridge.stop()
                val newPairing = bridge.start()
                Client(newPairing).use { fresh ->
                    release.countDown()
                    assertEquals(0, fresh.call("list", "scope" to "favorites").getJSONArray("objects").length())
                    assertEquals(newPairing, bridge.pairing.value)
                    assertTrue(bridge.connected.value)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun handshakeHasAnAbsoluteDeadlineAndIdleExpiresWhileReading() = fixture { controller, original, _ ->
        original.stop()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val short = DeviceControlBridge(context, controller, idleTimeoutMs = 2_000,
            handshakeTimeoutMs = 350, frameTimeoutMs = 5_000)
        try {
            Socket("127.0.0.1", short.start().port).use { socket ->
                socket.soTimeout = 2_000
                val output = DataOutputStream(socket.getOutputStream())
                val started = android.os.SystemClock.elapsedRealtime()
                output.writeInt(100); output.writeByte('{'.code); output.flush()
                delay(250)
                output.writeByte(' '.code); output.flush()
                try { assertEquals(-1, socket.getInputStream().read()) }
                catch (_: java.net.SocketException) { /* An unread partial frame may close with TCP reset. */ }
                assertTrue(android.os.SystemClock.elapsedRealtime() - started < 1_000)
            }
        } finally { short.stop() }
        val idle = DeviceControlBridge(context, controller, idleTimeoutMs = 300,
            handshakeTimeoutMs = 5_000, frameTimeoutMs = 5_000)
        try {
            Client(idle.start()).use { client ->
                withTimeout(2_000) { idle.pairing.first { it == null } }
                assertFalse(idle.connected.value)
                // Expiry closes a socket even while its receiver is blocked for a much longer frame.
                try { client.call("list"); fail("expired socket accepted a command") }
                catch (_: java.io.IOException) { }
            }
        } finally { idle.stop() }
    }

    private fun fixture(block: suspend (ChurController, DeviceControlBridge, File) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "device-control-test-${System.nanoTime()}").apply { mkdirs() }
        val controller = ChurController(File(root, "vault").path, NoPrivacyCover, NoExports, clock = { System.currentTimeMillis() })
        val bridge = DeviceControlBridge(context, controller)
        try {
            controller.start()
            withContext(Dispatchers.Main) { controller.create("Synthetic device bridge password", offerRecovery = false) }
            withTimeout(30_000) { controller.route.first { it == AppRoute.Vault } }
            assertTrue(controller.vaultState.value is VaultState.Unlocked)
            block(controller, bridge, root)
        } finally { bridge.stop(); controller.shutdown(); root.deleteRecursively() }
    }

    private suspend fun importFixture(controller: ChurController, root: File): String {
        val input = File(root, "original.txt").apply { writeText("Public bridge test original") }
        return ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val operation = controller.vault.beginImport(descriptor.fd, ImportRequest("text/plain", true, 4,
                knownLength = input.length(), originalFilename = input.name))
            try {
                withTimeout(30_000) {
                    var progress = controller.vault.poll(operation)
                    while (!progress.terminal) { delay(20); progress = controller.vault.poll(operation) }
                    assertEquals(0, progress.status)
                    requireNotNull(progress.importedObjectId()).joinToString("") { "%02x".format(it.toInt() and 255) }
                }
            } finally { controller.vault.closeOperation(operation) }
        }
    }

    private class Client(pairing: DeviceControlBridge.Pairing) : AutoCloseable {
        private val socket = Socket("127.0.0.1", pairing.port).apply { soTimeout = 15_000 }
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        init {
            val hello = call("pair", "version" to 2, "code" to pairing.code)
            assertEquals(32, hello.getString("vault_id").length)
        }
        fun send(op: String, vararg fields: Pair<String, Any>) {
            val request = JSONObject().put("op", op)
            fields.forEach { (key, value) -> request.put(key, value) }
            val bytes = request.toString().toByteArray()
            assertTrue(bytes.size <= 262_144)
            output.writeInt(bytes.size); output.write(bytes); output.flush()
        }
        private fun receive(): JSONObject {
            val length = input.readInt()
            assertTrue(length in 1..262_144)
            return JSONObject(ByteArray(length).also(input::readFully).toString(Charsets.UTF_8))
        }
        fun raw(op: String, vararg fields: Pair<String, Any>): JSONObject { send(op, *fields); return receive() }
        fun call(op: String, vararg fields: Pair<String, Any>): JSONObject = raw(op, *fields).also {
            assertFalse("$op: ${it.optString("error")}", it.has("error"))
        }
        fun stream(op: String, bytes: ByteArray, vararg fields: Pair<String, Any>): JSONObject {
            send(op, *fields)
            while (true) {
                val response = receive()
                if (response.optString("op") != "read") {
                    assertFalse("$op: ${response.optString("error")}", response.has("error"))
                    return response
                }
                val offset = response.getLong("offset").toInt()
                val size = response.getInt("size")
                send("data", "bytes" to Base64.encodeToString(bytes.copyOfRange(offset, offset + size), Base64.NO_WRAP))
            }
        }
        override fun close() = socket.close()
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }

    private fun id() = UUID.randomUUID().toString().replace("-", "")
    private fun String.hexBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
