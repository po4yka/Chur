package dev.po4yka.chur.android

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.StreamKind
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Runs album transactions, signed operation export, and persistence through Android JNI. */
@RunWith(AndroidJUnit4::class)
class AlbumOrganizationNativeTest {
    @Test
    fun albumSelectionRemovalIsAtomicAndPersistsWithoutDeletingOriginals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "album-native-${UUID.randomUUID()}").apply { mkdirs() }
        val vaultRoot = File(root, "vault").apply { mkdirs() }
        val password = "album-native-test-password".encodeToByteArray()
        var runtime = 0L
        var session = 0L
        try {
            runtime = ChurVault.openRuntime(vaultRoot.absolutePath)
            session = ChurVault.activateCreation(ChurVault.beginCreation(runtime, password))
            val identity = ChurVault.sharingIdentity(session)
            val initialHead = ChurVault.syncHeads(session).single()
            assertArrayEquals(identity.deviceId, initialHead.deviceId)

            val albumA = ChurVault.createAlbum(session, "First album")
            // The first album also declares its collection to the signed log.
            val createOperations = ChurVault.ownSyncOperations(session, initialHead.sequence)
            assertEquals(2, createOperations.size)
            assertTrue(createOperations.all { it.record.isNotEmpty() })
            val createdHead = ChurVault.syncHeads(session).single()
            assertEquals(initialHead.sequence + 2uL, createdHead.sequence)
            assertEquals(createdHead.sequence, createOperations.last().sequence)

            ChurVault.renameAlbum(session, albumA, "Renamed album")
            val renameOperation = ChurVault.ownSyncOperations(session, createdHead.sequence).single()
            assertTrue(renameOperation.record.isNotEmpty())
            assertEquals(createdHead.sequence + 1uL, renameOperation.sequence)
            val albumB = ChurVault.createAlbum(session, "Other album")

            val firstBytes = png(Color.RED)
            val secondBytes = png(Color.BLUE)
            val first = importImage(session, File(root, "first.png"), firstBytes)
            val second = importImage(session, File(root, "second.png"), secondBytes)
            ChurVault.setAlbumMembership(session, albumA, first, true)
            ChurVault.placeAlbumObjects(session, albumB, "", null, null, listOf(first, second), false)
            assertMembers(session, albumA, listOf(first))
            assertMembers(session, albumB, listOf(first, second))

            val beforeFailure = ChurVault.query(session, ObjectQuery())
            val beforeHead = ChurVault.syncHeads(session).single().sequence
            try {
                ChurVault.removeAlbumObjects(session, albumA, listOf(first, second))
                fail("a selection with an absent membership must fail")
            } catch (failure: ChurFailure) {
                assertEquals(ChurStatus.CONFLICT, failure.status)
            }
            assertMembers(session, albumA, listOf(first))
            assertMembers(session, albumB, listOf(first, second))
            assertEquals(beforeFailure.catalogGeneration, ChurVault.query(session, ObjectQuery()).catalogGeneration)
            assertEquals(beforeHead, ChurVault.syncHeads(session).single().sequence)
            assertTrue(ChurVault.ownSyncOperations(session, beforeHead).isEmpty())

            ChurVault.setAlbumMembership(session, albumA, second, true)
            val beforeSuccess = ChurVault.query(session, ObjectQuery()).catalogGeneration
            ChurVault.removeAlbumObjects(session, albumA, listOf(first, second))
            assertEquals(beforeSuccess + 1L, ChurVault.query(session, ObjectQuery()).catalogGeneration)
            assertRetained(session, albumA, albumB, first, second, firstBytes, secondBytes)
            val persistedHead = ChurVault.syncHeads(session).single().sequence

            ChurVault.lock(session, LockReason.USER)
            ChurVault.closeSession(session)
            session = 0L
            ChurVault.closeRuntime(runtime)
            runtime = 0L
            runtime = ChurVault.openRuntime(vaultRoot.absolutePath)
            session = ChurVault.unlockWithPassword(runtime, password)
            assertRetained(session, albumA, albumB, first, second, firstBytes, secondBytes)
            assertEquals(persistedHead, ChurVault.syncHeads(session).single().sequence)
            assertArrayEquals(renameOperation.record,
                ChurVault.ownSyncOperations(session, createdHead.sequence).first().record)
        } finally {
            if (session != 0L) ChurVault.closeSession(session)
            if (runtime != 0L) ChurVault.closeRuntime(runtime)
            password.fill(0)
            root.deleteRecursively()
        }
    }

    private fun assertRetained(
        session: Long,
        albumA: ByteArray,
        albumB: ByteArray,
        first: ByteArray,
        second: ByteArray,
        firstBytes: ByteArray,
        secondBytes: ByteArray,
    ) {
        assertMembers(session, albumA, emptyList())
        assertMembers(session, albumB, listOf(first, second))
        val summaries = ChurVault.albums(session)
        assertEquals("Renamed album", summaries.single { it.albumId.contentEquals(albumA) }.name)
        assertEquals(0L, summaries.single { it.albumId.contentEquals(albumA) }.memberCount)
        assertEquals(2L, summaries.single { it.albumId.contentEquals(albumB) }.memberCount)
        assertEquals(2L, ChurVault.query(session, ObjectQuery()).totalCount)
        for ((id, bytes) in listOf(first to firstBytes, second to secondBytes)) {
            val reader = ChurVault.openReader(session, id, StreamKind.ORIGINAL)
            try {
                assertEquals(bytes.size.toLong(), ChurVault.readerSize(reader))
                assertArrayEquals(bytes, ChurVault.readRange(reader, 0, bytes.size))
            } finally { ChurVault.closeReader(reader) }
        }
    }

    private fun assertMembers(session: Long, album: ByteArray, expected: List<ByteArray>) {
        val page = ChurVault.query(session, ObjectQuery(QueryScope.ALBUM, scopeId = album))
        assertEquals(expected.size.toLong(), page.totalCount)
        assertEquals(expected.size, page.objects.size)
        expected.forEach { id -> assertTrue(page.objects.any { it.objectId.contentEquals(id) }) }
    }

    private fun importImage(session: Long, source: File, bytes: ByteArray): ByteArray {
        source.writeBytes(bytes)
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val operation = ChurVault.beginImport(session, descriptor.fd, ImportRequest(
                contentType = "image/png", seekable = true, mediaClass = 1,
                width = 2, height = 2, knownLength = bytes.size.toLong(),
                captureTimeMs = 1_700_000_000_000L, originalFilename = source.name,
            ))
            try {
                val deadline = SystemClock.uptimeMillis() + 60_000L
                var progress = ChurVault.poll(operation)
                while (!progress.terminal && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(20)
                    progress = ChurVault.poll(operation)
                }
                assertTrue("native import must finish", progress.terminal)
                assertEquals(ChurStatus.OK, progress.status)
                requireNotNull(progress.importedObjectId())
            } finally { ChurVault.closeOperation(operation) }
        }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            java.io.ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
    }
}
