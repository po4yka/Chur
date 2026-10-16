package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImportGenerationHostTest {
    @Test
    fun a_source_fetched_after_session_replacement_is_closed_without_importing_or_opening_the_rest() = withVault { controller, root ->
        val oldGeneration = (controller.vaultState.value as VaultState.Unlocked).generation
        var opened = 0
        var closed = 0
        val outcomes = controller.importAll(MediaImporter(NoDecoder), 2, null, expectedGeneration = oldGeneration) { _, prepare ->
            opened++
            assertTrue(prepare(0, 0))
            controller.vault.lock(LockReason.USER)
            controller.vault.unlock(PASSWORD.encodeToByteArray())
            assertEquals(false, prepare(1, 1))
            picked(root) { closed++ }
        }
        assertEquals(listOf(MediaImporter.Outcome.Refused(ChurStatus.VAULT_LOCKED)), outcomes)
        assertEquals(1, opened)
        assertEquals(1, closed)
        assertEquals(0L, controller.vault.page(ObjectQuery()).totalCount)
        assertEquals(null, controller.notice.value)
    }

    @Test
    fun a_stale_direct_import_releases_its_source_before_any_decoder_or_native_write() = withVault { controller, root ->
        val oldGeneration = (controller.vaultState.value as VaultState.Unlocked).generation
        controller.vault.lock(LockReason.USER)
        controller.vault.unlock(PASSWORD.encodeToByteArray())
        var closed = 0
        val outcome = MediaImporter(NoDecoder).import(controller.vault, picked(root) { closed++ }, expectedGeneration = oldGeneration)
        assertEquals(MediaImporter.Outcome.Refused(ChurStatus.VAULT_LOCKED), outcome)
        assertEquals(1, closed)
        assertEquals(0L, controller.vault.page(ObjectQuery()).totalCount)
    }

    @Test
    fun native_import_derivative_and_album_writes_refuse_an_old_generation_under_the_repository_lock() = withVault { controller, _ ->
        val oldGeneration = (controller.vaultState.value as VaultState.Unlocked).generation
        controller.vault.lock(LockReason.USER)
        controller.vault.unlock(PASSWORD.encodeToByteArray())
        val request = ImportRequest("application/octet-stream", true, 4, 0, 0, 0, 0, null, null)
        assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> {
            controller.vault.beginImport(-1, request, expectedGeneration = oldGeneration)
        }.status)
        assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> {
            controller.vault.putDerived(ByteArray(16), StreamKind.THUMBNAIL, 1, 1, byteArrayOf(1), expectedGeneration = oldGeneration)
        }.status)
        assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> {
            controller.vault.placeAlbumObjects(null, "Stale destination", null, null, emptyList(), false, expectedGeneration = oldGeneration)
        }.status)
        assertTrue(controller.vault.albums().isEmpty())
        assertEquals(0L, controller.vault.page(ObjectQuery()).totalCount)
    }

    private fun withVault(test: suspend (ChurController, File) -> Unit) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-import-generation-${System.nanoTime()}").apply { mkdirs() }
        val controller = ChurController(storageRoot = File(root, "vault").absolutePath, privacy = NoPrivacyCover,
            exports = NoExports, clock = { 1_700_000_000_000L })
        try {
            controller.start()
            controller.create(PASSWORD, offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            test(controller, root)
        } finally {
            controller.shutdown()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private fun picked(root: File, closed: () -> Unit): PickedMedia {
        val source = File(root, "public-source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val input = RandomAccessFile(source, "r")
        val descriptor = input.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(input.fd)
        return PickedMedia(descriptor, true, source.length(), "application/octet-stream", "public-source.bin", null, null,
            { input.close(); closed() }, importAsFile = true)
    }

    private object NoDecoder : MediaCodec {
        override fun probe(media: PickedMedia): ProbedMedia? = error("a file must not reach a decoder")
        override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? =
            error("a file must not produce derivatives")
    }
    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }
    private companion object { const val PASSWORD = "Incoming-share-generation-password" }
}
