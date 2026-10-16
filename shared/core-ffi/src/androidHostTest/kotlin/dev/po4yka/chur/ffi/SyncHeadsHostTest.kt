package dev.po4yka.chur.ffi

import dev.po4yka.chur.core.model.ChurStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The production Kotlin decoder consumes actual C ABI bytes through JNI. */
class SyncHeadsHostTest {
    @Test
    fun only_an_unlocked_verified_head_advances_and_rejected_staging_does_not() {
        val root = Files.createTempDirectory("chur-sync-heads-").toFile()
        val runtime = ChurVault.openRuntime(root.absolutePath)
        var session = 0L
        try {
            session = ChurVault.activateCreation(ChurVault.beginCreation(runtime, "head-test-password".encodeToByteArray()))
            val identity = ChurVault.sharingIdentity(session)
            val first = ChurVault.syncHeads(session).single()
            assertContentEquals(identity.deviceId, first.deviceId)
            assertEquals(1uL, first.sequence)

            ChurVault.stageSync(runtime, identity.vaultId, SyncRecordKind.OPERATION, 1, byteArrayOf(1, 2, 3))
            val report = ChurVault.processSync(session, 2)
            assertEquals(1L, report.rejected)
            val after = ChurVault.syncHeads(session).single()
            assertContentEquals(first.deviceId, after.deviceId)
            assertEquals(first.sequence, after.sequence)

            ChurVault.lock(session, LockReason.USER)
            val failure = assertFailsWith<ChurFailure> { ChurVault.syncHeads(session) }
            assertEquals(ChurStatus.VAULT_LOCKED, failure.status)
        } finally {
            if (session != 0L) ChurVault.closeSession(session)
            ChurVault.closeRuntime(runtime)
            root.deleteRecursively()
        }
    }
}
