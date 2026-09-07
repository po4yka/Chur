package dev.po4yka.chur.android

import androidx.fragment.app.FragmentActivity
import dev.po4yka.chur.app.DeviceUnlock
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.core.platformkeys.DeviceSlot
import dev.po4yka.chur.core.platformkeys.DeviceSlotException
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
import dev.po4yka.chur.core.platformkeys.DeviceSlotPrompt
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.core.platformkeys.KeystoreWrapped

/**
 * The Android Keystore half of the device slot, `KEY_SLOTS.md` §4.
 *
 * The alias is Rust's opaque bytes, so this class holds no slot identity of its
 * own: it constructs a [DeviceSlot] from what the descriptor stored and lets
 * the Keystore decide whether the key exists.
 *
 * `wrap` provisions the key first. A slot whose key already exists is a slot
 * being enrolled twice, and §4 makes that a refusal rather than a silent
 * replacement: replacing the key would leave the stored ciphertext unopenable.
 *
 * Both operations authorize with the user before the Keystore will run them,
 * which is what the key of §4 is built to require. `BiometricPrompt` needs a
 * `FragmentActivity`, and this reads the attached one rather than holding it:
 * `docs/interop/FFI_CONTRACT.md` §8.1 says "there is no per-scene vault
 * state", so the session outlives the window and the platform recreates the
 * window under it. A held activity would hand the prompt a destroyed one.
 *
 * `docs/ERROR_MODEL.md` requires the platform layer to normalize before a
 * feature sees it, so a [DeviceSlotException] is converted here rather than
 * left to travel as itself. A slot exception carries a [ChurStatus] already,
 * and that status is what the feature receives.
 */
class AndroidDeviceUnlock(
    private val host: () -> FragmentActivity?,
    private val policy: DeviceSlotPolicy = DeviceSlotPolicy.CONVENIENT,
) : DeviceUnlock {
    override val available: Boolean = true

    override suspend fun wrap(
        alias: ByteArray,
        aad: ByteArray,
        rootSecret: ByteArray,
    ): Pair<ByteArray, ByteArray> = normalized {
        val activity = activity()
        val slot = DeviceSlot(alias)
        slot.provision(policy)
        try {
            val wrapped = slot.wrap(activity, policy, prompt(activity), rootSecret, aad)
            wrapped.gcmNonce to wrapped.wrappedRootSecret
        } catch (cause: DeviceSlotException) {
            // The key exists and the slot does not, which is a state nothing
            // can open. Removing it leaves the vault as it was. A refused or
            // cancelled authorization lands here too, and it must: a key kept
            // after an abandoned enrolment is a key no descriptor names.
            slot.destroy()
            throw cause
        }
    }

    override suspend fun unwrap(
        alias: ByteArray,
        aad: ByteArray,
        gcmNonce: ByteArray,
        wrappedRootSecret: ByteArray,
    ): ByteArray? = normalized {
        val activity = activity()
        val slot = DeviceSlot(alias)
        if (!slot.isProvisioned()) return@normalized null
        try {
            slot.unwrap(
                activity,
                policy,
                prompt(activity),
                KeystoreWrapped(gcmNonce, wrappedRootSecret),
                aad,
            )
        } catch (cause: DeviceSlotException) {
            // A slot that belongs to another identity fails the tag, which is
            // not an error here: the caller walks every enrolled slot.
            if (cause.status == ChurStatus.AUTHENTICATION_FAILED) null else throw cause
        }
    }

    /**
     * The window `BiometricPrompt` needs, or a refusal when none is attached.
     *
     * Nothing can ask a person to authorize a key while no window is on
     * screen. `PLATFORM_KEY_UNAVAILABLE` is the closest registered status:
     * `docs/ERROR_MODEL.md` defines it for a factor that is absent, unenrolled
     * or locked out rather than for a missing window, and it carries the retry
     * a caller needs once a window is attached again.
     */
    private fun activity(): FragmentActivity =
        host() ?: throw ChurFailure(ChurStatus.PLATFORM_KEY_UNAVAILABLE, "the device slot")

    /** The words the prompt shows, from the host's own resources. */
    private fun prompt(activity: FragmentActivity) = DeviceSlotPrompt(
        title = activity.getString(R.string.device_slot_title),
        subtitle = activity.getString(R.string.device_slot_subtitle),
        cancel = activity.getString(R.string.device_slot_cancel),
    )

    /** Turns a platform refusal into the boundary failure a feature handles. */
    private inline fun <T> normalized(body: () -> T): T = try {
        body()
    } catch (cause: DeviceSlotException) {
        throw ChurFailure(cause.status, "the device slot")
    }
}
