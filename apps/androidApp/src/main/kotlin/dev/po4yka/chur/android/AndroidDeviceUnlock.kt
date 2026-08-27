package dev.po4yka.chur.android

import dev.po4yka.chur.app.DeviceUnlock
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.core.platformkeys.DeviceSlot
import dev.po4yka.chur.core.platformkeys.DeviceSlotException
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
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
 */
class AndroidDeviceUnlock(private val policy: DeviceSlotPolicy = DeviceSlotPolicy.CONVENIENT) :
    DeviceUnlock {
    override val available: Boolean = true

    override fun wrap(
        alias: ByteArray,
        aad: ByteArray,
        rootSecret: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val slot = DeviceSlot(alias)
        slot.provision(policy)
        return try {
            val wrapped = slot.wrap(rootSecret, aad)
            wrapped.gcmNonce to wrapped.wrappedRootSecret
        } catch (cause: DeviceSlotException) {
            // The key exists and the slot does not, which is a state nothing
            // can open. Removing it leaves the vault as it was.
            slot.destroy()
            throw cause
        }
    }

    override fun unwrap(
        alias: ByteArray,
        aad: ByteArray,
        gcmNonce: ByteArray,
        wrappedRootSecret: ByteArray,
    ): ByteArray? {
        val slot = DeviceSlot(alias)
        if (!slot.isProvisioned()) return null
        return try {
            slot.unwrap(KeystoreWrapped(gcmNonce, wrappedRootSecret), aad)
        } catch (cause: DeviceSlotException) {
            // A slot that belongs to another identity fails the tag, which is
            // not an error here: the caller walks every enrolled slot.
            if (cause.status == ChurStatus.AUTHENTICATION_FAILED) null else throw cause
        }
    }
}
