package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.notes.InMemoryNoteStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `ANDROID.md` §9.3: a device-slot policy change replaces the enrolled slot.
 *
 * The toggle used to write only the setting. A convenient slot then still
 * accepted the device unlock code while the row read "biometrics only". The
 * fake records the policy each wrap ran under, which is the policy the
 * Keystore key would be generated with.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceSlotPolicyHostTest {
    @Test
    fun switching_to_strict_replaces_the_convenient_slot_and_a_cancel_keeps_it() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-slot-policy-${System.nanoTime()}")
        root.mkdirs()
        var strict = false
        var cancel = false
        val wrappedStrict = mutableListOf<Boolean>()
        val unlock = object : DeviceUnlock {
            override val available = true

            override suspend fun wrap(
                alias: ByteArray,
                aad: ByteArray,
                rootSecret: ByteArray,
            ): Pair<ByteArray, ByteArray> {
                if (cancel) throw ChurFailure(ChurStatus.CANCELLED, "the device slot")
                wrappedStrict += strict
                return ByteArray(12) { 1 } to ByteArray(48) { 2 }
            }

            override suspend fun unwrap(
                alias: ByteArray,
                aad: ByteArray,
                gcmNonce: ByteArray,
                wrappedRootSecret: ByteArray,
            ): ByteArray? = null
        }
        val controller = ChurController(
            storageRoot = root.absolutePath,
            privacy = NoPrivacyCover,
            exports = NoExports,
            deviceUnlock = unlock,
            deviceSlotPolicy = DeviceSlotPolicySetting({ strict }, { strict = it }),
            clock = { 1_700_000_000_000L },
            notes = InMemoryNoteStore(),
        )
        try {
            controller.start()
            controller.create("123456789012", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            controller.enrollDeviceSlot()
            val convenient = withTimeout(10_000) {
                controller.slots.first { slots -> slots.any { it.slotType == 2 } }
            }.single { it.slotType == 2 }

            // A cancelled prompt leaves the slot, the setting and the row as they were.
            cancel = true
            controller.toggleDeviceSlotPolicy()
            assertEquals(userCopy(ChurStatus.CANCELLED), withTimeout(10_000) { controller.notice.first { it != null } }?.text)
            assertFalse(controller.deviceSlotStrict.value)
            assertFalse(strict)
            assertEquals(listOf(convenient), controller.vault.slots().filter { it.slotType == 2 })

            cancel = false
            controller.toggleDeviceSlotPolicy()
            withTimeout(10_000) { controller.deviceSlotStrict.first { it } }
            // The host JVM has no AndroidKeyStore, so deleting the old key fails
            // after the descriptor has dropped its slot. The vault then holds only
            // the strict slot, and the row must report that. The message this
            // leaves is not asserted.
            val replaced = controller.vault.slots().filter { it.slotType == 2 }
            assertEquals(listOf(false, true), wrappedStrict)
            assertEquals(1, replaced.size)
            assertNotEquals(convenient, replaced.single())
            assertTrue(strict)
            controller.vault.shutdown()
        } finally {
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
}
