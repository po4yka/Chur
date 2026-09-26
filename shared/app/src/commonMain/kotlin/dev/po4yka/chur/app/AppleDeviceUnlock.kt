package dev.po4yka.chur.app

/** The Keychain half of an Apple device slot. Rust owns the vault key. */
interface AppleDeviceUnlock {
    val available: Boolean

    fun newItemId(): ByteArray
    fun itemIds(): List<ByteArray>
    fun storeSecret(itemId: ByteArray, secret: ByteArray, strict: Boolean)
    fun releaseSecret(itemId: ByteArray): ByteArray

    /** Reuse one platform authorization only within this unlock attempt. */
    fun beginUnlock()
    fun endUnlock()
}

object NoAppleDeviceUnlock : AppleDeviceUnlock {
    override val available = false

    override fun newItemId(): ByteArray = error("no Apple device slot")
    override fun itemIds(): List<ByteArray> = emptyList()
    override fun storeSecret(itemId: ByteArray, secret: ByteArray, strict: Boolean): Unit =
        error("no Apple device slot")
    override fun releaseSecret(itemId: ByteArray): ByteArray = error("no Apple device slot")
    override fun beginUnlock() = Unit
    override fun endUnlock() = Unit
}
