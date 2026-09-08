package dev.po4yka.chur.app

/**
 * The per-vault device-slot policy of `KEY_SLOTS.md` section 1, as the
 * application holds it.
 *
 * Section 1 makes the policy a setting shown at device-slot creation and names
 * the two modes: convenient, the default, and strict, which is the only
 * configuration that resists an adversary who knows the device unlock code. The
 * setting stores which one the user picked; the platform binding reads it at
 * enroll and at every unlock, so a change applies to the next slot operation.
 *
 * The choice says nothing about whether a vault exists, so it is public-shell
 * state: a host keeps it in a plain file beside the notes, by the same
 * reasoning `FileNoteStore` states. [unset] keeps the choice in memory, which
 * is what tests and a host without a device slot use.
 */
class DeviceSlotPolicySetting(
    private val reader: () -> Boolean,
    private val writer: (Boolean) -> Unit,
) {
    /** Whether the device slot requires biometry only. */
    fun read(): Boolean = reader()

    /** Records the choice for this and every later launch. */
    fun write(value: Boolean) {
        writer(value)
    }

    companion object {
        /** The default binding: convenient, nothing persisted. */
        fun unset(): DeviceSlotPolicySetting = DeviceSlotPolicySetting({ false }, {})
    }
}
