package dev.po4yka.chur.ffi

/**
 * A bounded native buffer the caller owns.
 *
 * `docs/interop/FFI_CONTRACT.md` §7 makes the data plane caller-allocated: Rust
 * writes authenticated plaintext into a buffer the host provides, validity ends
 * when the host reuses or frees it, and Rust retains no pointer after return.
 * §6 forbids a whole-file `ByteArray`, which is why this exists at all rather
 * than the boundary taking a Kotlin array.
 *
 * On the JVM it wraps a direct `ByteBuffer`, so JNI reaches its bytes without a
 * copy. On Kotlin/Native it wraps a native allocation, so cinterop reaches them
 * the same way. Both must be released, and [withChurBuffer] is how: a buffer
 * that held plaintext is cleared and freed on a path that runs even when the
 * body throws.
 */
expect class ChurBuffer(capacity: Int) {
    /** The buffer's capacity in bytes. */
    val capacityBytes: Int

    /** Copies the first `length` bytes out into a Kotlin array. */
    fun copyOut(length: Int): ByteArray

    /** Copies a Kotlin array in; the buffer must be large enough. */
    fun copyIn(bytes: ByteArray)

    /** Overwrites the buffer with zeroes. */
    fun clear()

    /** Frees the native allocation, where the platform has one to free. */
    fun release()
}

/**
 * Runs [body] with a buffer of `capacity` bytes and releases it afterwards.
 *
 * The buffer is cleared before it is released, which
 * `docs/security/PLAINTEXT_LIFECYCLE.md` §1 asks of a bounded media buffer:
 * overwrite and reuse rather than rely on the allocator.
 */
inline fun <T> withChurBuffer(capacity: Int, body: (ChurBuffer) -> T): T {
    val buffer = ChurBuffer(capacity)
    try {
        return body(buffer)
    } finally {
        buffer.clear()
        buffer.release()
    }
}
