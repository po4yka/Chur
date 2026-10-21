package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class VideoPlaybackPositionIosTest {
    @Test fun resume_survives_reunlock_rejects_stale_writes_and_restarts_near_the_end(): Unit = runBlocking {
        withIosImportedVideo(iosLongGopVfrVideo) { session, originalSha256 ->
            val firstGeneration = assertIs<VaultState.Unlocked>(session.vault.state.value).generation
            assertEquals(0L, session.vault.playbackPosition(session.objectId, firstGeneration))
            session.vault.savePlaybackPosition(session.objectId, 31_000L, 64_000L, firstGeneration)
            assertEquals(31_000L, session.vault.playbackPosition(session.objectId, firstGeneration))
            session.vault.lock(LockReason.USER)
            session.vault.unlock("Video-acceptance-native-test".encodeToByteArray())
            val nextGeneration = assertIs<VaultState.Unlocked>(session.vault.state.value).generation
            assertNotEquals(firstGeneration, nextGeneration)
            val saved = session.vault.playbackPosition(session.objectId, nextGeneration)
            assertEquals(31_000L, saved)
            val native = session.player()
            try {
                native.ready()
                native.seek(iosLongGopVfrVideo, videoResumePosition(saved, 64_000L) / 1_000.0)
            } finally { native.close() }

            val expired = assertFailsWith<ChurFailure> {
                session.vault.savePlaybackPosition(session.objectId, 55_000L, 64_000L, firstGeneration)
            }
            assertEquals(ChurStatus.VAULT_LOCKED, expired.status)
            assertEquals(31_000L, session.vault.playbackPosition(session.objectId, nextGeneration))
            session.vault.savePlaybackPosition(session.objectId, 62_500L, 64_000L, nextGeneration)
            assertEquals(0L, videoResumePosition(session.vault.playbackPosition(session.objectId, nextGeneration), 64_000L))
            session.verifyExportAndLock(originalSha256)
        }
    }
}
