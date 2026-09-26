package dev.po4yka.chur.app

import dev.po4yka.chur.core.platformkeys.DeviceSlot
import dev.po4yka.chur.core.platformkeys.DeviceSlotException
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
import dev.po4yka.chur.core.platformkeys.appleDeviceSlotIds
import dev.po4yka.chur.core.platformkeys.newAppleSlotIdentifier
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

/** The Keychain authorizes release; Rust checks the released secret against the vault. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal class IosAppleDeviceUnlock : AppleDeviceUnlock {
    override val available: Boolean
        get() = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
    private var context: LAContext? = null

    override fun newItemId(): ByteArray = keychain { newAppleSlotIdentifier() }

    override fun itemIds(): List<ByteArray> = keychain { appleDeviceSlotIds() }

    override fun storeSecret(itemId: ByteArray, secret: ByteArray, strict: Boolean) {
        val policy = if (strict) LAPolicyDeviceOwnerAuthenticationWithBiometrics
        else LAPolicyDeviceOwnerAuthentication
        if (!LAContext().canEvaluatePolicy(policy, error = null)) {
            throw ChurFailure(ChurStatus.PLATFORM_KEY_UNAVAILABLE, "Apple Keychain")
        }
        keychain {
            DeviceSlot(itemId).storeSecret(
                if (strict) DeviceSlotPolicy.STRICT else DeviceSlotPolicy.CONVENIENT,
                secret,
            )
        }
    }

    override fun beginUnlock() {
        endUnlock()
        context = LAContext().apply { localizedReason = "Open your Chur vault" }
    }

    override fun releaseSecret(itemId: ByteArray): ByteArray =
        keychain { DeviceSlot(itemId).releaseSecret(checkNotNull(context)) }

    override fun endUnlock() {
        context?.invalidate()
        context = null
    }

    private inline fun <T> keychain(block: () -> T): T = try {
        block()
    } catch (failure: DeviceSlotException) {
        throw ChurFailure(failure.status, "Apple Keychain")
    }
}
