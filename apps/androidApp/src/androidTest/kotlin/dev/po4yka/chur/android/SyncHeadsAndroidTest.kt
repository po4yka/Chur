package dev.po4yka.chur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.ffi.ChurVault
import dev.po4yka.chur.ffi.LockReason
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Calls the new authenticated head export through Android JNI. */
@RunWith(AndroidJUnit4::class)
class SyncHeadsAndroidTest {
    @Test
    fun androidJniReadsTheAuthenticatedLocalHead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "sync-head-test-${UUID.randomUUID()}")
        root.mkdirs()
        val runtime = ChurVault.openRuntime(root.absolutePath)
        var session = 0L
        try {
            session = ChurVault.activateCreation(ChurVault.beginCreation(runtime, "head-test-password".encodeToByteArray()))
            val identity = ChurVault.sharingIdentity(session)
            val head = ChurVault.syncHeads(session).single()
            assertArrayEquals(identity.deviceId, head.deviceId)
            assertEquals(1uL, head.sequence)
            ChurVault.lock(session, LockReason.USER)
        } finally {
            if (session != 0L) ChurVault.closeSession(session)
            ChurVault.closeRuntime(runtime)
            root.deleteRecursively()
        }
    }
}
