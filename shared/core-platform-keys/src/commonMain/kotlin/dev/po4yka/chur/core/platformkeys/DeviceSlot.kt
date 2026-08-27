package dev.po4yka.chur.core.platformkeys

import dev.po4yka.chur.core.model.ChurStatus

/**
 * The per-vault device-slot policy of `docs/security/KEY_SLOTS.md` section 1.
 *
 * The choice is shown at device-slot creation and never changes silently: it
 * decides whether the device unlock code is a working vault credential, which
 * `THREAT_MODEL.md` section 4 records under A2 and A8.
 */
public enum class DeviceSlotPolicy {
    /**
     * The default. Biometry or the device credential opens the slot, so the
     * device unlock code is a vault credential.
     */
    CONVENIENT,

    /**
     * Biometry only, invalidated when the biometric set changes. The only
     * configuration that resists an adversary who knows the unlock code.
     */
    STRICT,
}

/**
 * A platform slot failure, already mapped to a stable code.
 *
 * `docs/ERROR_MODEL.md` requires the platform layer to normalize before a
 * feature sees it and forbids a feature branching on message text. The detail
 * here is a compile-time constant for a developer log: no alias, no vault
 * identity, and no key bytes reach it.
 */
public class DeviceSlotException(
    /** The stable code. */
    public val status: ChurStatus,
    /** A constant description. No caller input reaches it. */
    public val detail: String,
    cause: Throwable? = null,
) : Exception("$status: $detail", cause)

/**
 * A platform-held key slot.
 *
 * One instance manages one slot identity, named by an opaque identifier the
 * Rust CSPRNG produced. The identifier is not derived from a vault, a user, or
 * a role, so the platform key store discloses nothing about which identity it
 * belongs to; `KEY_SLOTS.md` section 4 and section 5 both require that.
 *
 * The two platforms hold different things, so this common surface stops where
 * they diverge. Android holds a non-exportable AES-256-GCM key and performs the
 * AEAD itself, so its actual adds `wrap` and `unwrap`. Apple holds a random
 * `DeviceUnlockSecret` and lets Rust perform the AEAD, so its actual adds
 * `releaseSecret`. Pretending both fit one method would hide exactly the
 * difference `KEY_SLOT_BODIES_V1.md` section 5 and section 6 encode.
 */
public expect class DeviceSlot
/**
 * Names a slot.
 *
 * @param identifier 16 to 64 opaque bytes from the Rust CSPRNG.
 * @throws IllegalArgumentException when the identifier is outside that range.
 */
constructor(identifier: ByteArray) {
    /**
     * Creates the platform key or secret under the given policy.
     *
     * @throws DeviceSlotException when the platform refuses, is unavailable, or
     * has no enrolled factor for the requested policy.
     */
    public fun provision(policy: DeviceSlotPolicy)

    /** Whether the platform still holds material for this slot. */
    public fun isProvisioned(): Boolean

    /** Removes the platform key or secret. Removing an absent one is not an error. */
    public fun destroy()
}

/**
 * The opaque platform name of a slot.
 *
 * A hexadecimal rendering of the identifier under one fixed prefix. It is a
 * presentation of bytes the CSPRNG chose and carries no vault, user, or role.
 */
internal fun platformAlias(identifier: ByteArray): String {
    require(identifier.size in 16..64) {
        "a slot identifier is 16 to 64 bytes"
    }
    return buildString {
        append("dev.po4yka.chur.slot.")
        identifier.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private const val HEX = "0123456789abcdef"
