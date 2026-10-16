package dev.po4yka.chur.app

import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A system lock must survive a device authorization that answers later. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemControlLockHostTest {
    @Test
    fun panic_during_device_unlock_rejects_the_late_key_and_keeps_private_routes_closed() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-system-lock-${System.nanoTime()}")
        root.mkdirs()
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        // The platform test double returns the real root of this temporary
        // vault. Keep that same array so the assertion checks its disposal.
        var releasedRoot: ByteArray? = null
        val device = object : DeviceUnlock {
            override val available = true

            override suspend fun wrap(
                alias: ByteArray,
                aad: ByteArray,
                rootSecret: ByteArray,
            ): Pair<ByteArray, ByteArray> {
                releasedRoot = rootSecret.copyOf()
                return ByteArray(12) { 1 } to ByteArray(48) { 2 }
            }

            override suspend fun unwrap(
                alias: ByteArray,
                aad: ByteArray,
                gcmNonce: ByteArray,
                wrappedRootSecret: ByteArray,
            ): ByteArray? {
                asked.complete(Unit)
                answer.await()
                return releasedRoot
            }
        }
        val controller = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            deviceUnlock = device,
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
        )
        try {
            controller.start()
            controller.create("System-control-lock-password", offerRecovery = false)
            withTimeout(TIMEOUT_MS) { controller.route.first { it == AppRoute.Vault } }
            controller.enrollDeviceSlot()
            withTimeout(TIMEOUT_MS) { controller.slots.first { slots -> slots.any { it.slotType == 2 } } }
            controller.lock()
            withTimeout(TIMEOUT_MS) { controller.route.first { it == AppRoute.PublicShell } }

            controller.goTo(AppRoute.Unlock)
            controller.unlockWithDevice()
            withTimeout(TIMEOUT_MS) { asked.await() }
            assertTrue(releasedRoot!!.any { it != 0.toByte() })

            controller.panic()
            withTimeout(TIMEOUT_MS) { controller.route.first { it == AppRoute.PublicShell } }
            assertIs<VaultState.Locked>(controller.vaultState.value)

            val routes = mutableListOf<AppRoute>()
            val observedRoutes = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                controller.route.collect { routes += it }
            }
            try {
                // A successful native unlock really runs after the lock.
                // Its stale epoch must close that session before any private
                // route or private projection can be restored.
                answer.complete(Unit)
                withTimeout(TIMEOUT_MS) { controller.unlocking.first { !it } }

                assertIs<VaultState.Locked>(controller.vaultState.value)
                assertEquals(AppRoute.PublicShell, controller.route.value)
                assertEquals(listOf<AppRoute>(AppRoute.PublicShell), routes)
                assertTrue(releasedRoot!!.all { it == 0.toByte() }, "the late root was retained")
                assertTrue(controller.albums.value.isEmpty())
                assertTrue(controller.slots.value.isEmpty())
                assertNull(controller.recoveryPhrase.value)
                assertNull(controller.formError.value)
            } finally {
                observedRoutes.cancel()
            }
        } finally {
            answer.complete(Unit)
            withTimeout(TIMEOUT_MS) { controller.unlocking.first { !it } }
            controller.vault.shutdown()
            releasedRoot?.fill(0)
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(
            displayName: String,
            contentType: String,
            target: ExportTarget,
            uri: String?,
        ): ExportSink.Destination? = null
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
