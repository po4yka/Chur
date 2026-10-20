package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The production sync completion effect reads real native catalog edits into the current UI scope. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncCatalogRefreshHostTest {
    @Test
    fun a_sync_refresh_updates_the_open_album_and_organizer_without_navigation() = withVault { controller, root ->
        val first = importFile(controller, root, "first")
        val second = importFile(controller, root, "second")
        val album = controller.vault.createAlbum("Before receive")
        controller.vault.setAlbumMembership(album, first, true)
        controller.loadAlbums()
        controller.load(ObjectQuery(QueryScope.ALBUM, scopeId = album))
        withTimeout(10_000) {
            controller.albums.first { it.singleOrNull()?.name == "Before receive" }
            controller.page.first { it.totalCount == 1L }
        }
        val generation = generation(controller)
        // The receive path commits into this same native repository; no controller action loads it.
        val completed = controller.withSyncCatalogRefresh(generation) {
            controller.vault.renameAlbum(album, "After receive")
            controller.vault.removeAlbumObjects(album, listOf(first))
            controller.vault.setAlbumMembership(album, second, true)
            true
        }
        assertTrue(completed)
        assertEquals("After receive", controller.albums.value.single().name)
        assertEquals(1L, controller.albums.value.single().memberCount)
        assertEquals(listOf(second.toList()), controller.page.value.objects.map { it.objectId.toList() })
    }

    @Test
    fun a_partial_receive_is_visible_when_the_remaining_network_work_fails() = withVault { controller, _ ->
        val album = controller.vault.createAlbum("Before receive")
        controller.loadAlbums()
        withTimeout(10_000) { controller.albums.first { it.isNotEmpty() } }
        val failure = ChurFailure(ChurStatus.NETWORK_FAILURE, "Public fixture connection failed")
        val reported = assertFailsWith<ChurFailure> {
            controller.withSyncCatalogRefresh(generation(controller)) {
                controller.vault.renameAlbum(album, "Committed before failure")
                throw failure
            }
        }
        assertSame(failure, reported)
        assertEquals("Committed before failure", controller.albums.value.single().name)
        assertEquals(controller.vault.page(ObjectQuery()).catalogGeneration, controller.page.value.catalogGeneration)
    }

    @Test
    fun a_sync_finishing_after_lock_cannot_restore_private_projections() = withVault { controller, _ ->
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val generation = generation(controller)
        val pending = launch {
            controller.withSyncCatalogRefresh(generation) {
                controller.vault.createAlbum("Old private album")
                reached.complete(Unit)
                release.await()
            }
        }
        withTimeout(10_000) { reached.await() }
        controller.lock()
        withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
        release.complete(Unit)
        pending.join()
        assertTrue(controller.albums.value.isEmpty())
        assertTrue(controller.page.value.objects.isEmpty())
        assertEquals(0L, controller.page.value.catalogGeneration)
    }

    @Test
    fun an_old_sync_completion_cannot_refresh_the_replacement_session() = withVault { controller, _ ->
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldGeneration = generation(controller)
        val pending = launch {
            controller.withSyncCatalogRefresh(oldGeneration) {
                reached.complete(Unit)
                release.await()
            }
        }
        withTimeout(10_000) { reached.await() }
        controller.lock()
        withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
        controller.goTo(AppRoute.Unlock)
        controller.unlock(PASSWORD)
        withTimeout(10_000) {
            controller.route.first { it == AppRoute.Vault }
            controller.page.first { it.catalogGeneration > 0 }
        }
        assertTrue(generation(controller) != oldGeneration)
        val album = controller.vault.createAlbum("Current session projection")
        controller.loadAlbums()
        withTimeout(10_000) { controller.albums.first { it.singleOrNull()?.name == "Current session projection" } }
        val before = controller.page.value
        controller.vault.renameAlbum(album, "Must wait for this session's refresh")
        release.complete(Unit)
        pending.join()
        assertEquals("Current session projection", controller.albums.value.single().name)
        assertEquals(before.catalogGeneration, controller.page.value.catalogGeneration)
    }

    private fun generation(controller: ChurController) =
        (controller.vaultState.value as VaultState.Unlocked).generation

    private suspend fun importFile(controller: ChurController, root: File, name: String): ByteArray {
        val source = File(root, name).apply { writeText("Public fixture $name") }
        return RandomAccessFile(source, "r").use { file ->
            val descriptor = file.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(file.fd)
            val operation = controller.vault.beginImport(descriptor,
                ImportRequest("text/plain", true, 4, knownLength = source.length(), originalFilename = name))
            try {
                val result = withTimeout(10_000) {
                    var result = controller.vault.poll(operation)
                    while (!result.terminal) { delay(10); result = controller.vault.poll(operation) }
                    result
                }
                assertEquals(0, result.status)
                assertNotNull(result.importedObjectId())
            } finally { controller.vault.closeOperation(operation) }
        }
    }

    private fun withVault(test: suspend kotlinx.coroutines.CoroutineScope.(ChurController, File) -> Unit): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-sync-refresh-${System.nanoTime()}").apply { mkdirs() }
        val controller = ChurController(root.absolutePath, NoPrivacyCover, NoExports,
            clock = { 1_700_000_000_000L }, notes = InMemoryNoteStore())
        try {
            controller.start()
            controller.create(PASSWORD, offerRecovery = false)
            withTimeout(10_000) {
                controller.route.first { it == AppRoute.Vault }
                controller.page.first { it.catalogGeneration > 0 }
            }
            test(controller, root)
        } finally {
            controller.vault.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }

    private companion object { const val PASSWORD = "Sync-catalog-refresh-password" }
}
