package dev.po4yka.chur.app

/**
 * The platform half of the device key slot, `docs/security/KEY_SLOTS.md` §4.
 *
 * It is an interface for the same reason [ExportSink] is: the two platforms
 * have no common answer. Android's Keystore performs the AEAD itself, which is
 * what this interface describes; Apple's Keychain holds a secret and Rust
 * performs the AEAD, which needs no platform call during unlock and so needs
 * nothing here.
 *
 * ADR-0041 is why `rootSecret` appears at all: the Keystore key is
 * non-exportable, so the cipher runs here and the plaintext has to reach it. An
 * implementation must not keep it, must not log it, and must not write it
 * anywhere but the platform cipher.
 */
interface DeviceUnlock {
    /** Whether this platform has a device slot mechanism at all. */
    val available: Boolean

    /**
     * Wraps the vault root under a fresh platform key.
     *
     * @return the 12-byte GCM nonce and the 48 wrapped bytes.
     */
    suspend fun wrap(
        alias: ByteArray,
        aad: ByteArray,
        rootSecret: ByteArray,
    ): Pair<ByteArray, ByteArray>

    /**
     * Unwraps the vault root, or returns `null` when this slot is not this
     * device's.
     *
     * A `null` is not an error: the material lists every enrolled slot across
     * every identity the registry admits, so a caller walks them and most do
     * not belong to the key this device holds.
     */
    suspend fun unwrap(
        alias: ByteArray,
        aad: ByteArray,
        gcmNonce: ByteArray,
        wrappedRootSecret: ByteArray,
    ): ByteArray?

    /**
     * Asks the device owner to authenticate, the device authentication of
     * `DESIGN.md` §17.1 step 2, with the platform prompt [wrap] uses.
     *
     * [strict] is the device-slot policy of `KEY_SLOTS.md` §1 in force: under
     * it only biometry passes, so the device unlock code that
     * `THREAT_MODEL.md` A2 assumes an adversary knows does not. A binding with
     * no way to ask answers [OwnerCheck.NOT_SET_UP], so the setup it guards
     * stays blocked rather than open.
     *
     * @throws dev.po4yka.chur.ffi.ChurFailure when the prompt ends in any way
     * other than a pass or a cancel.
     */
    suspend fun confirmOwner(strict: Boolean): OwnerCheck = OwnerCheck.NOT_SET_UP
}

/** How the device authentication of [DeviceUnlock.confirmOwner] ended. */
enum class OwnerCheck {
    /** The owner authenticated. */
    CONFIRMED,

    /** The owner dismissed the prompt. */
    CANCELLED,

    /**
     * The device has no factor the policy admits: no screen lock, or under
     * the strict policy no enrolled biometric. Nothing was asked.
     */
    NOT_SET_UP,
}

/** The binding for a platform with no device slot, which is the default. */
object NoDeviceUnlock : DeviceUnlock {
    override val available: Boolean = false

    override suspend fun wrap(
        alias: ByteArray,
        aad: ByteArray,
        rootSecret: ByteArray,
    ): Pair<ByteArray, ByteArray> = throw UnsupportedOperationException("no device slot here")

    override suspend fun unwrap(
        alias: ByteArray,
        aad: ByteArray,
        gcmNonce: ByteArray,
        wrappedRootSecret: ByteArray,
    ): ByteArray? = null
}
