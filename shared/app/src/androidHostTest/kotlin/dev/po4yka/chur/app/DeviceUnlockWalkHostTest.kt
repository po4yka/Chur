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

/**
 * One invalidated slot must not stop device unlock for the others.
 *
 * The walk used to end at the first slot that threw, so an invalidated key
 * ahead of a working one locked the user out of the device unlock.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceUnlockWalkHostTest {
    @Test
    fun an_invalidated_slot_does_not_hide_a_working_one() = runBlocking {
        val outcome = walk(failFirst = 1)
        assertEquals(AppRoute.Vault, outcome)
    }

    @Test
    fun when_no_slot_opens_the_most_severe_failure_is_reported() = runBlocking {
        val outcome = walk(failFirst = 2)
        assertEquals(userCopy(ChurStatus.PLATFORM_KEY_INVALIDATED), outcome)
    }

    @Test
    fun severity_ranks_invalidation_first_and_a_bad_tag_last() {
        val bad = ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "tag")
        val gone = ChurFailure(ChurStatus.PLATFORM_KEY_UNAVAILABLE, "absent")
        val invalid = ChurFailure(ChurStatus.PLATFORM_KEY_INVALIDATED, "invalid")
        assertEquals(gone, moreSevere(bad, gone))
        assertEquals(invalid, moreSevere(gone, invalid))
        assertEquals(invalid, moreSevere(invalid, bad))
        assertEquals(bad, moreSevere(null, bad))
    }

    /**
     * Enrolls two device slots, locks, and unlocks through the device with the
     * first [failFirst] unwraps refused: the first as invalidated, any later
     * one as a bad tag. Returns the route reached or the form error shown.
     */
    private suspend fun walk(failFirst: Int): Any? {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = File(System.getProperty("java.io.tmpdir"), "chur-device-walk-${System.nanoTime()}")
        root.mkdirs()
        var vaultRoot: ByteArray? = null
        var unwraps = 0
        val unlock = object : DeviceUnlock {
            override val available = true

            override suspend fun wrap(
                alias: ByteArray,
                aad: ByteArray,
                rootSecret: ByteArray,
            ): Pair<ByteArray, ByteArray> {
                vaultRoot = rootSecret.copyOf()
                return ByteArray(12) { 1 } to ByteArray(48) { 2 }
            }

            override suspend fun unwrap(
                alias: ByteArray,
                aad: ByteArray,
                gcmNonce: ByteArray,
                wrappedRootSecret: ByteArray,
            ): ByteArray? {
                unwraps += 1
                if (unwraps == 1 && failFirst >= 1) {
                    throw ChurFailure(ChurStatus.PLATFORM_KEY_INVALIDATED, "the device slot")
                }
                if (unwraps <= failFirst) {
                    throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "the device slot")
                }
                return vaultRoot?.copyOf()
            }
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
            withTimeout(10_000) { controller.slots.first { slots -> slots.count { it.slotType == 2 } == 1 } }
            controller.enrollDeviceSlot()
            withTimeout(10_000) { controller.slots.first { slots -> slots.count { it.slotType == 2 } == 2 } }
            controller.lock()
            withTimeout(10_000) { controller.route.first { it == AppRoute.PublicShell } }
            controller.goTo(AppRoute.Unlock)
            controller.unlockWithDevice()
            return if (failFirst < 2) {
                withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            } else {
                withTimeout(10_000) { controller.formError.first { it != null } }
            }.also {
                assertEquals(minOf(failFirst + 1, 2), unwraps)
                controller.vault.shutdown()
            }
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
