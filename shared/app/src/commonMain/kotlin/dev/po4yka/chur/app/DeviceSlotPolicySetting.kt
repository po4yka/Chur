package dev.po4yka.chur.app

import kotlin.concurrent.Volatile

/**
 * The per-vault device-slot policy of `KEY_SLOTS.md` section 1, as the
 * application holds it.
 *
 * Section 1 makes the policy a setting shown at device-slot creation and names
 * the two modes: convenient, the default, and strict, which is the only
 * configuration that resists an adversary who knows the device unlock code. The
 * setting stores which one the user picked, and the platform binding reads it
 * when it creates a slot. A slot keeps the policy it was created under, so
 * [ChurController.toggleDeviceSlotPolicy] re-enrolls the open vault's device
 * slot on a change rather than leaving it to answer the old policy.
 *
 * The choice is device-wide rather than per identity, so every identity reads
 * the same one, `DECOY_VAULT.md` §10. It is written only from an open vault,
 * so a host keeps it outside every backup: Android in `noBackupFilesDir`, with
 * the app-lock and auto-lock choices, `DISCREET_MODE.md`. [unset] keeps the
 * choice in memory, which is what tests and a host without a device slot use.
 */
class DeviceSlotPolicySetting(
    private val reader: () -> Boolean,
    private val writer: (Boolean) -> Unit,
) {
    /**
     * The policy a slot swap enrolls under, before it is stored.
     *
     * A swap must enroll under the new policy, but storing that policy first
     * let a kill inside the swap show a policy that no slot was under.
     */
    @Volatile
    private var enrolling: Boolean? = null

    /** Whether the device slot requires biometry only. */
    fun read(): Boolean = enrolling ?: reader()

    /** Records the choice for this and every later launch. */
    fun write(value: Boolean) {
        writer(value)
    }

    /**
     * Runs [body] with [read] answering [value] and the stored choice left
     * alone, so the platform binding enrolls under [value] before any caller
     * stores it.
     */
    suspend fun <T> enrollingUnder(value: Boolean, body: suspend () -> T): T {
        enrolling = value
        try {
            return body()
        } finally {
            enrolling = null
        }
    }

    companion object {
        /** The default binding: convenient, nothing persisted. */
        fun unset(): DeviceSlotPolicySetting = DeviceSlotPolicySetting({ false }, {})
    }
}
