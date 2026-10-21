package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
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
import kotlin.test.assertTrue

/**
 * `DECOY_VAULT.md` §10: one identity holds a device slot.
 *
 * A device unlock opens the first identity whose slot verifies, so a second
 * device slot let the registry order choose the vault. The second enrolment is
 * refused before the platform wraps anything, and the refusal reads as any
 * device unlock that is not available.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceSlotIdentityHostTest {
    @Test
    fun a_second_identity_cannot_enroll_a_device_slot() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-slot-identity-${System.nanoTime()}")
        root.mkdirs()
        var wraps = 0
        val unlock = object : DeviceUnlock {
            override val available = true

            override suspend fun wrap(
                alias: ByteArray,
                aad: ByteArray,
                rootSecret: ByteArray,
            ): Pair<ByteArray, ByteArray> {
                wraps += 1
                return ByteArray(12) { 1 } to ByteArray(48) { 2 }
            }

            override suspend fun unwrap(
                alias: ByteArray,
                aad: ByteArray,
                gcmNonce: ByteArray,
                wrappedRootSecret: ByteArray,
            ): ByteArray? = null
        }
        var strict = false
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
            withTimeout(10_000) { controller.slots.first { slots -> slots.any { it.slotType == 2 } } }
            assertEquals(1, wraps)

            controller.createSecondIdentity()
            withTimeout(10_000) { controller.route.first { it == AppRoute.CreateVault } }
            controller.create("234567890123", offerRecovery = false)
            withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            controller.enrollDeviceSlot()

            val expected = deviceSlotCopy(ChurStatus.PLATFORM_KEY_UNAVAILABLE)
            assertEquals(expected, deviceSlotCopy(ChurStatus.PLATFORM_KEY_INVALIDATED))
            withTimeout(10_000) { controller.notice.first { it?.text == expected } }
            assertEquals(1, wraps, "the platform wrapped a second root")
            assertTrue(controller.vault.slots().none { it.slotType == 2 })
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
