package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.ffi.LockReason
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Actual JNI, SQLCipher and encoded MP4; no test replacement for the native boundary. */
class PlaybackPositionRepositoryHostTest {
    @Test
    fun resume_survives_reopen_and_old_generation_cannot_write_after_reunlock(): Unit = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "chur-playback-position-${System.nanoTime()}").apply { mkdirs() }
        val password = "playback-position-test-password".encodeToByteArray()
        var vault = VaultRepository(File(root, "vault").path, { 1_700_000_000_000L })
        try {
            vault.start(); vault.create(password, false)
            val fixture = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.take(6)
                .map { File(it, "tests/media/hevc-main10/sdr10-hvc1.mp4") }.first { it.isFile }
            val objectId = FileInputStream(fixture).use { input ->
                val fd = input.fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(input.fd)
                val operation = vault.beginImport(fd, ImportRequest("video/mp4", true, 2, width = 192, height = 96,
                    durationMs = 1250, knownLength = fixture.length(), originalFilename = "public-fixture.mp4"))
                try {
                    withTimeout(30_000) {
                        var id: ByteArray? = null
                        while (id == null) {
                            val progress = vault.poll(operation)
                            if (progress.terminal) {
                                assertEquals(0, progress.status)
                                id = assertNotNull(progress.importedObjectId())
                            } else delay(10)
                        }
                        assertNotNull(id)
                    }
                } finally { vault.closeOperation(operation) }
            }
            val old = (vault.state.value as VaultState.Unlocked).generation
            assertEquals(0L, vault.playbackPosition(objectId, old))
            vault.savePlaybackPosition(objectId, 500, 1250, old)
            assertEquals(500L, vault.playbackPosition(objectId, old))
            vault.lock(LockReason.PANIC)
            assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> { vault.playbackPosition(objectId, old) }.status)
            vault.unlock(password)
            val current = (vault.state.value as VaultState.Unlocked).generation
            assertEquals(ChurStatus.VAULT_LOCKED, assertFailsWith<ChurFailure> {
                vault.savePlaybackPosition(objectId, 900, 1250, old)
            }.status)
            assertEquals(500L, vault.playbackPosition(objectId, current))
            vault.shutdown()
            vault = VaultRepository(File(root, "vault").path, { 1_700_000_000_000L })
            vault.start(); vault.unlock(password)
            val reopened = (vault.state.value as VaultState.Unlocked).generation
            assertEquals(500L, vault.playbackPosition(objectId, reopened))
            vault.savePlaybackPosition(objectId, 0, 1250, reopened)
            assertEquals(0L, vault.playbackPosition(objectId, reopened))
        } finally {
            vault.shutdown()
            password.fill(0)
            root.deleteRecursively()
        }
    }
}
