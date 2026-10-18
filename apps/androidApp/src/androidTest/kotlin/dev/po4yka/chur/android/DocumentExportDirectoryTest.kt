package dev.po4yka.chur.android

import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.ffi.ChurFailure
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real DocumentsContract calls and file descriptors, in storage owned only by this test. */
@RunWith(AndroidJUnit4::class)
class DocumentExportDirectoryTest {
    @Test
    fun livePhotoResourcesRestoreThroughRealSafAndCleanAllOutputsOnCancelOrLock() = withProvider { provider, resolver ->
        kotlinx.coroutines.runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val source = File(context.cacheDir, "export-scratch/live-saf-${System.nanoTime()}.churlive")
            source.parentFile!!.mkdirs()
            InstrumentationRegistry.getInstrumentation().context.assets.open("live-photo-native/native.churlive").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val vaultRoot = File(context.cacheDir, "live-saf-vault-${System.nanoTime()}")
            val vault = dev.po4yka.chur.vault.VaultRepository(vaultRoot.path, System::currentTimeMillis)
            try {
                vault.start()
                vault.create("live photo SAF test password".encodeToByteArray(), false)
                val codec = dev.po4yka.chur.imports.AndroidMediaCodec(context)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.exports", source)
                val picked = requireNotNull(codec.open(uri))
                val table = requireNotNull(picked.livePhoto)
                val result = dev.po4yka.chur.app.MediaImporter(codec).import(vault, picked)
                assertTrue(result is dev.po4yka.chur.app.MediaImporter.Outcome.Imported)
                val objectId = (result as dev.po4yka.chur.app.MediaImporter.Outcome.Imported).objectId
                val directory = DocumentExportDirectory.open(resolver, provider.tree)
                try {
                    val count = dev.po4yka.chur.app.restoreLivePhotoResources(vault, objectId, directory)
                    assertEquals(table.resources.size, count)
                } finally { directory.close() }
                val files = provider.root.listFiles()!!.filter { it.isFile }
                assertEquals(table.resources.size + 1, files.size)
                assertTrue(files.any { it.readBytes().contentEquals(source.readBytes()) })
                val original = source.readBytes()
                for (resource in table.resources) {
                    val bytes = original.copyOfRange(resource.offset.toInt(), (resource.offset + resource.byteLength).toInt())
                    assertTrue(files.any { it.readBytes().contentEquals(bytes) })
                }
                files.forEach { assertTrue(it.delete()) }
                File(provider.root, "keep.bin").writeText("keep")
                var cancelled = false
                val cancelledDirectory = DocumentExportDirectory.open(resolver, provider.tree)
                try {
                    try {
                        dev.po4yka.chur.app.restoreLivePhotoResources(vault, objectId, cancelledDirectory,
                            cancelled = { cancelled }, onProgress = { _, _ -> cancelled = true })
                        fail("cancelled restoration succeeded")
                    } catch (failure: ChurFailure) {
                        assertEquals(dev.po4yka.chur.core.model.ChurStatus.CANCELLED, failure.status)
                    }
                } finally { cancelledDirectory.close() }
                assertEquals(listOf("keep.bin"), provider.root.list()!!.toList())
                val lockedDirectory = DocumentExportDirectory.open(resolver, provider.tree)
                try {
                    try {
                        dev.po4yka.chur.app.restoreLivePhotoResources(vault, objectId, lockedDirectory,
                            onProgress = { _, _ -> kotlinx.coroutines.runBlocking { vault.lock(dev.po4yka.chur.ffi.LockReason.USER) } })
                        fail("locked restoration succeeded")
                    } catch (_: ChurFailure) { }
                } finally { lockedDirectory.close() }
                assertEquals("keep", File(provider.root, "keep.bin").readText())
                assertEquals(listOf("keep.bin"), provider.root.list()!!.toList())
            } finally { vault.shutdown(); source.delete(); vaultRoot.deleteRecursively() }
        }
    }

    @Test
    fun nestedExportsKeepExistingFilesAndResolveDuplicateAndUnsafeNames() = withProvider { provider, resolver ->
        provider.addDirectory("Album")
        File(provider.root, "keep.bin").writeText("keep")
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            val album = root.createDirectory("Album")
            val child = requireNotNull(album.createDirectory("../nested/name"))
            repeat(2) { index ->
                val destination = requireNotNull(child.createFile("a/b.bin", "application/octet-stream", 1))
                write(destination.descriptor, byteArrayOf(index.toByte()))
                destination.publish()
                destination.close()
            }
            child.close()
            album.close()
        } finally { root.close() }
        val nested = File(provider.root, "Album (2)/_nested_name")
        assertTrue(nested.isDirectory)
        assertArrayEquals(byteArrayOf(0), File(nested, "a_b.bin").readBytes())
        assertArrayEquals(byteArrayOf(1), File(nested, "a_b (2).bin").readBytes())
        assertEquals("keep", File(provider.root, "keep.bin").readText())
        assertTrue(File(provider.root, "Album").isDirectory)
        assertFalse(provider.root.walkTopDown().any { "chur-partial-" in it.name })
    }

    @Test
    fun longUnicodeNamesFitRealFilesystemComponentsWithoutSplitSurrogates() = withProvider { provider, resolver ->
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            listOf("密".repeat(120), "🔐".repeat(120), "truncated-\uD83D").forEach { name ->
                val folder = root.createDirectory(name)
                repeat(2) {
                    val destination = requireNotNull(folder.createFile(name, "application/octet-stream", 1))
                    write(destination.descriptor, byteArrayOf(1))
                    destination.publish()
                }
                folder.close()
            }
        } finally { root.close() }
        provider.root.walkTopDown().filter { it != provider.root }.forEach {
            assertTrue(it.name.toByteArray(Charsets.UTF_8).size <= 180)
            assertFalse(it.name.last().isHighSurrogate())
        }
        assertEquals(6, provider.root.walkTopDown().count { it.isFile })
    }

    @Test
    fun discardAndRootCloseRemoveOnlyUnpublishedFiles() = withProvider { provider, resolver ->
        File(provider.root, "keep.bin").writeText("keep")
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        val album = root.createDirectory("Album")
        val complete = requireNotNull(album.createFile("complete.bin", "application/octet-stream", 1))
        write(complete.descriptor, byteArrayOf(1))
        complete.publish()
        val discarded = requireNotNull(album.createFile("discard.bin", "application/octet-stream", 1))
        discarded.discard()
        val unfinished = requireNotNull(album.createFile("unfinished.bin", "application/octet-stream", 2))
        write(unfinished.descriptor, byteArrayOf(2))
        assertTrue(File(provider.root, "Album").listFiles()!!.any { "chur-partial-" in it.name })

        root.close()
        root.close()

        assertEquals(listOf("complete.bin"), File(provider.root, "Album").list()!!.toList())
        assertEquals("keep", File(provider.root, "keep.bin").readText())
        assertTrue(provider.root.exists())
        failure { root.createDirectory("after-close") }
    }

    @Test
    fun openAndRenameFailuresLeaveNoSuccessNamedPartialFile() = withProvider { provider, resolver ->
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            provider.refuseOpen = true
            failure { root.createFile("open.bin", "application/octet-stream", 3) }
            assertTrue(provider.root.list()!!.isEmpty())
            provider.refuseOpen = false
            val destination = root.createFile("rename.bin", "application/octet-stream", 3)
            write(destination.descriptor, byteArrayOf(1, 2, 3))
            provider.refuseRename = true
            failure { destination.publish() }
            assertFalse(File(provider.root, "rename.bin").exists())
            assertTrue(provider.root.list()!!.single().contains("chur-partial-"))
            destination.discard()
            assertTrue(provider.root.list()!!.isEmpty())
        } finally { root.close() }
    }

    @Test
    fun missingAbortSupportIsRejectedBeforeAnyWrite() = withProvider { provider, resolver ->
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            provider.fileFlags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            failure { root.createFile("no-rename.bin", "application/octet-stream", 3) }
            assertEquals(0, provider.opens)
            assertTrue(provider.root.list()!!.isEmpty())
        } finally { root.close() }
    }

    @Test
    fun aCancellationAtThePublicationBoundaryCanDiscardThatResult() = withProvider { provider, resolver ->
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            val destination = root.createFile("cancelled.bin", "application/octet-stream", 1)
            write(destination.descriptor, byteArrayOf(1))
            destination.publish()
            assertTrue(File(provider.root, "cancelled.bin").exists())
            destination.discard()
            destination.discard()
            assertTrue(provider.root.list()!!.isEmpty())
        } finally { root.close() }
    }

    @Test
    fun invalidationDoesNotCloseTheDescriptorBeforeTheNativeOwnerCanDuplicateIt() = withProvider { provider, resolver ->
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        val destination = root.createFile("locked.bin", "application/octet-stream", 1)
        root.invalidate()
        // This is the same duplication boundary used by the native export entry point.
        write(destination.descriptor, byteArrayOf(1))
        failure { destination.publish() }
        assertFalse(File(provider.root, "locked.bin").exists())
        destination.discard()
        root.close()
        assertTrue(provider.root.list()!!.isEmpty())
    }

    @Test
    fun aProviderReturningAnExistingDocumentCannotOverwriteOrDeleteIt() = withProvider { provider, resolver ->
        val existing = provider.addFile("keep.bin", "keep")
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            provider.existingCreateId = existing
            failure { root.createFile("keep.bin", "application/octet-stream", 3) }
            assertEquals("keep", File(provider.root, "keep.bin").readText())
            assertEquals(0, provider.opens)
        } finally { root.close() }
    }

    @Test
    fun providerPermissionRevocationIsAnExportFailure() = withProvider { provider, resolver ->
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            provider.refuseCreate = true
            try {
                root.createDirectory("Album")
                fail("expected refused grant")
            } catch (failure: ChurFailure) {
                assertEquals(dev.po4yka.chur.core.model.ChurStatus.PERMISSION_DENIED, failure.status)
            }
            assertTrue(provider.root.list()!!.isEmpty())
        } finally { root.close() }
    }

    @Test
    fun aNonSeekableProviderDescriptorCanReceiveAndPublishAStream() = withProvider { provider, resolver ->
        provider.pipeWrites = true
        val root = DocumentExportDirectory.open(resolver, provider.tree)
        try {
            val bytes = ByteArray(32_768) { (it % 251).toByte() }
            val destination = root.createFile("stream.bin", "application/octet-stream", bytes.size.toLong())
            ParcelFileDescriptor.fromFd(destination.descriptor).use {
                assertTrue(OsConstants.S_ISFIFO(Os.fstat(it.fileDescriptor).st_mode))
            }
            write(destination.descriptor, bytes)
            destination.publish()
            provider.awaitWrites()
            assertArrayEquals(bytes, File(provider.root, "stream.bin").readBytes())
        } finally { root.close() }
    }

    private fun write(descriptor: Int, bytes: ByteArray) {
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.fromFd(descriptor)).use { it.write(bytes) }
    }

    private fun failure(action: () -> Unit) {
        try { action(); fail("expected export failure") } catch (_: ChurFailure) { }
    }

    private fun withProvider(test: (TestDocumentsProvider, ContentResolver) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = TestDocumentsProvider(File(context.cacheDir, "album-export-test-${System.nanoTime()}").apply { mkdir() })
        provider.attachInfo(context, ProviderInfo().apply {
            authority = TestDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        try { test(provider, ContentResolver.wrap(provider)) } finally { provider.root.deleteRecursively() }
    }
}

private class TestDocumentsProvider(val root: File) : DocumentsProvider() {
    private val files = linkedMapOf("root" to root)
    private var nextId = 0
    var refuseOpen = false
    var refuseCreate = false
    var refuseRename = false
    var existingCreateId: String? = null
    var opens = 0
    var pipeWrites = false
    private val writes = mutableListOf<Thread>()
    var fileFlags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
    val tree get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    override fun onCreate() = true

    fun addFile(name: String, contents: String): String {
        val id = "doc-${nextId++}"
        files[id] = File(root, name).apply { writeText(contents) }
        return id
    }

    fun addDirectory(name: String): String {
        val id = "doc-${nextId++}"
        files[id] = File(root, name).apply { check(mkdir()) }
        return id
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (refuseCreate) throw SecurityException("revoked test grant")
        existingCreateId?.let { return it }
        val file = File(files.getValue(parentDocumentId), displayName)
        check(!file.exists())
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) check(file.mkdir()) else check(file.createNewFile())
        val id = "doc-${nextId++}"
        files[id] = file
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (refuseRename) throw java.io.FileNotFoundException("rename refused")
        val file = files.getValue(documentId)
        val target = File(file.parentFile, displayName)
        check(!target.exists())
        check(file.renameTo(target))
        files[documentId] = target
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        check(documentId != "root")
        check(files.getValue(documentId).delete())
        files.remove(documentId)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (refuseOpen) throw java.io.FileNotFoundException("open refused")
        opens++
        if (pipeWrites) {
            val pipe = ParcelFileDescriptor.createPipe()
            val output = files.getValue(documentId).outputStream()
            val reader = Thread {
                output.use { target -> ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.copyTo(target) } }
            }
            writes.add(reader)
            reader.start()
            return pipe[1]
        }
        return ParcelFileDescriptor.open(files.getValue(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    fun awaitWrites() { writes.forEach { it.join(5_000); check(!it.isAlive) } }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
    )).apply { newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, "root").add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root") }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection, listOf(documentId))

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        cursor(projection, files.filterValues { it.parentFile == files.getValue(parentDocumentId) }.keys.toList())

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        files.getValue(documentId).canonicalPath.startsWith(files.getValue(parentDocumentId).canonicalPath + "/")

    private fun cursor(projection: Array<out String>?, ids: List<String>): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return MatrixCursor(columns).apply {
            ids.forEach { id ->
                val file = files.getValue(id)
                addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_FLAGS -> if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else fileFlags
                    else -> null
                } })
            }
        }
    }

    companion object { const val AUTHORITY = "dev.po4yka.chur.tests.album-export" }
}
