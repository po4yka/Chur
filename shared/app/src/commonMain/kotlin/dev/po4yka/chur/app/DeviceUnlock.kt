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
    fun wrap(alias: ByteArray, aad: ByteArray, rootSecret: ByteArray): Pair<ByteArray, ByteArray>

    /**
     * Unwraps the vault root, or returns `null` when this slot is not this
     * device's.
     *
     * A `null` is not an error: the material lists every enrolled slot across
     * every identity the registry admits, so a caller walks them and most do
     * not belong to the key this device holds.
     */
    fun unwrap(
        alias: ByteArray,
        aad: ByteArray,
        gcmNonce: ByteArray,
        wrappedRootSecret: ByteArray,
    ): ByteArray?
}

/** The binding for a platform with no device slot, which is the default. */
object NoDeviceUnlock : DeviceUnlock {
    override val available: Boolean = false

    override fun wrap(
        alias: ByteArray,
        aad: ByteArray,
        rootSecret: ByteArray,
    ): Pair<ByteArray, ByteArray> = throw UnsupportedOperationException("no device slot here")

    override fun unwrap(
        alias: ByteArray,
        aad: ByteArray,
        gcmNonce: ByteArray,
        wrappedRootSecret: ByteArray,
    ): ByteArray? = null
}
