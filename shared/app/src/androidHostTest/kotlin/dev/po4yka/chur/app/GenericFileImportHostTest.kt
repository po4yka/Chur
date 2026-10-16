package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.DOCUMENT_TEXT_PREVIEW_MAX_BYTES
import dev.po4yka.chur.app.vault.decodeDocumentText
import dev.po4yka.chur.app.vault.readDocumentPrefix
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenericFileImportHostTest {
    private val rejectingCodec = object : MediaCodec {
        override fun probe(media: PickedMedia): ProbedMedia? = error("explicit files must not reach a media decoder")
        override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? =
            error("an opaque file has no generated media derivatives")
    }

    @Test
    fun empty_unknown_length_invalid_type_and_misleading_media_files_round_trip_without_a_decoder() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-generic-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").absolutePath, { 1_700_000_000_000L })
        try {
            vault.start()
            vault.create("generic-file-test-password".encodeToByteArray(), offerRecovery = false)
            val cases = listOf(
                Triple(byteArrayOf(), "", "empty.txt"),
                Triple(byteArrayOf(0, 1, 2, 0xff.toByte()), "not a mime type", "unknown.bin"),
                Triple("not an image".encodeToByteArray(), "image/jpeg", "photo.jpg"),
                Triple("not a video".encodeToByteArray(), "video/mp4", "movie.mp4"),
            )
            for ((index, case) in cases.withIndex()) {
                val (bytes, hint, name) = case
                val source = File(root, "source-$index").apply { writeBytes(bytes) }
                var closed = false
                val outcome = RandomAccessFile(source, "r").use { input ->
                    val picked = PickedMedia(descriptor(input), true, null, hint, name, null, null,
                        { closed = true }, importAsFile = true)
                    assertIs<MediaImporter.Outcome.Imported>(MediaImporter(rejectingCodec).import(vault, picked))
                }
                assertTrue(closed)
                assertEquals(0, outcome.derivatives)
                val detail = vault.detail(outcome.objectId)
                assertEquals(name, detail.filename)
                assertEquals(bytes.size.toLong(), detail.plaintextSize)
                assertEquals(if (index < 2) "application/octet-stream" else hint, detail.contentType)
                assertEquals(0, detail.width)
                assertEquals(0, detail.height)
                val projection = vault.page(ObjectQuery(kinds = 8)).objects.first { it.objectId.contentEquals(outcome.objectId) }
                assertEquals(4, projection.mediaKind)
                if (bytes.isEmpty()) {
                    val prefix = readDocumentPrefix(vault, (vault.state.value as VaultState.Unlocked).generation,
                        projection, DOCUMENT_TEXT_PREVIEW_MAX_BYTES)
                    assertEquals(0, prefix.bytes.size)
                    assertEquals(0L, prefix.totalSize)
                }
                val output = File(root, "out-$index")
                RandomAccessFile(output, "rw").use { target ->
                    val operation = vault.beginExport(outcome.objectId, descriptor(target))
                    try {
                        var progress = vault.poll(operation)
                        while (!progress.terminal) { delay(50); progress = vault.poll(operation) }
                        assertEquals(0, progress.status)
                    } finally { vault.closeOperation(operation) }
                }
                assertContentEquals(bytes, output.readBytes())
            }
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    @Test
    fun text_reads_a_bounded_authenticated_prefix_and_rejects_an_old_session() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-text-preview-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").absolutePath, { 1_700_000_000_000L })
        try {
            vault.start()
            vault.create("text-preview-test-password".encodeToByteArray(), offerRecovery = false)
            val bytes = "a".repeat(DOCUMENT_TEXT_PREVIEW_MAX_BYTES - 1).encodeToByteArray() + "€tail".encodeToByteArray()
            val source = File(root, "source").apply { writeBytes(bytes) }
            val imported = RandomAccessFile(source, "r").use { input ->
                assertIs<MediaImporter.Outcome.Imported>(MediaImporter(rejectingCodec).import(vault,
                    PickedMedia(descriptor(input), true, source.length(), "text/plain", "note.txt", null, null, {}, importAsFile = true)))
            }
            val generation = (vault.state.value as VaultState.Unlocked).generation
            val projection = vault.page(ObjectQuery()).objects.first { it.objectId.contentEquals(imported.objectId) }
            val prefix = readDocumentPrefix(vault, generation, projection, DOCUMENT_TEXT_PREVIEW_MAX_BYTES)
            try {
                assertEquals(DOCUMENT_TEXT_PREVIEW_MAX_BYTES, prefix.bytes.size)
                assertTrue(prefix.truncated)
                val preview = assertNotNull(decodeDocumentText(prefix.bytes, prefix.truncated))
                assertEquals(DOCUMENT_TEXT_PREVIEW_MAX_BYTES - 1, preview.text.length)
            } finally { prefix.bytes.fill(0) }
            vault.lock(LockReason.USER)
            assertEquals(ChurStatus.SESSION_EXPIRED,
                assertFailsWith<ChurFailure> { readDocumentPrefix(vault, generation, projection, DOCUMENT_TEXT_PREVIEW_MAX_BYTES) }.status)
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    private fun descriptor(file: RandomAccessFile): Int =
        file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
}
