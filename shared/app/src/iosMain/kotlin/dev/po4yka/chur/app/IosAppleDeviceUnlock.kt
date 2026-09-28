package dev.po4yka.chur.app

import dev.po4yka.chur.core.platformkeys.DeviceSlot
import dev.po4yka.chur.core.platformkeys.DeviceSlotException
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
import dev.po4yka.chur.core.platformkeys.appleDeviceSlotIds
import dev.po4yka.chur.core.platformkeys.newAppleSlotIdentifier
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
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

    /**
     * Evaluates the Local Authentication policy the Keychain item of a slot
     * would carry under [strict], with no item behind it.
     *
     * A policy the device cannot evaluate has no factor set up: no passcode,
     * or under the strict policy no enrolled biometry. The strict prompt
     * hides the passcode fallback, which that policy does not admit.
     */
    override suspend fun confirmOwner(strict: Boolean): OwnerCheck {
        val policy = if (strict) LAPolicyDeviceOwnerAuthenticationWithBiometrics
        else LAPolicyDeviceOwnerAuthentication
        val owner = LAContext()
        if (strict) owner.localizedFallbackTitle = ""
        if (!owner.canEvaluatePolicy(policy, error = null)) return OwnerCheck.NOT_SET_UP
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { owner.invalidate() }
            owner.evaluatePolicy(policy, localizedReason = "Confirm it's you to see a new recovery phrase") { passed, error ->
                when {
                    passed -> continuation.resume(OwnerCheck.CONFIRMED)
                    // The user, the app or the system dismissed the prompt.
                    error?.code in setOf(LAErrorUserCancel, LAErrorAppCancel, LAErrorSystemCancel) ->
                        continuation.resume(OwnerCheck.CANCELLED)
                    else -> continuation.resumeWithException(
                        ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "Apple device owner"),
                    )
                }
            }
        }
    }

    private inline fun <T> keychain(block: () -> T): T = try {
        block()
    } catch (failure: DeviceSlotException) {
        throw ChurFailure(failure.status, "Apple Keychain")
    }
}
