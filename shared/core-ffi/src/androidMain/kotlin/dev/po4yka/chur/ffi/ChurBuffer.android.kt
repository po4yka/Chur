package dev.po4yka.chur.ffi

import java.nio.ByteBuffer

/**
 * A direct `ByteBuffer`, which JNI reaches without copying.
 *
 * A heap buffer would be copied on every call, which for a range read doubles
 * the peak memory `docs/interop/MEDIA_PIPELINE.md` §12 bounds. The JNI adapter
 * refuses a non-direct buffer rather than copying silently.
 */
actual class ChurBuffer actual constructor(capacity: Int) {
    internal val buffer: ByteBuffer = ByteBuffer.allocateDirect(capacity)

    actual val capacityBytes: Int get() = buffer.capacity()

    actual fun copyOut(length: Int): ByteArray {
        val bytes = ByteArray(length)
        buffer.duplicate().apply {
            position(0)
            get(bytes, 0, length)
        }
        return bytes
    }

    actual fun copyIn(bytes: ByteArray) {
        buffer.duplicate().apply {
            position(0)
            put(bytes)
        }
    }

    actual fun clear() {
        buffer.duplicate().apply {
            position(0)
            put(ByteArray(buffer.capacity()))
        }
    }

    /**
     * A direct buffer is freed by the garbage collector, not by the caller.
     *
     * The JVM has no portable free for one, so the release is the reference
     * going away. [clear] runs first, so the window in which its bytes survive
     * collection carries zeroes.
     */
    actual fun release() {
        // Intentionally empty; see the documentation above.
    }
}
