package dev.po4yka.chur.ffi

import java.nio.ByteBuffer

/**
 * A direct `ByteBuffer`, which JNI reaches without copying.
 *
 * A heap buffer would be copied on every call, which for a range read doubles
 * the peak memory `docs/interop/MEDIA_PIPELINE.md` §12 bounds. The JNI adapter
 * refuses a non-direct buffer rather than copying silently.
 */
actual class ChurBuffer actual constructor(
    capacity: Int,
) {
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

    /**
     * Overwrites the buffer with zeroes through one reused scratch chunk.
     *
     * A direct buffer has no bulk-fill primitive on the JVM, and allocating a
     * same-size array per wipe would put an 8 MiB transient heap array behind
     * every thumbnail range read and a 16 MiB one behind every revocation,
     * exactly the churn `docs/security/PLAINTEXT_LIFECYCLE.md` §1 avoids by
     * reusing one bounded buffer. The write loop itself stays at memory-bandwidth
     * speed because `put` on a direct buffer is an intrinsic.
     */
    actual fun clear() {
        val wipe = buffer.duplicate()
        wipe.position(0)
        var remaining = wipe.capacity()
        while (remaining > 0) {
            val chunk = minOf(remaining, ZERO_CHUNK.size)
            wipe.put(ZERO_CHUNK, 0, chunk)
            remaining -= chunk
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

    private companion object {
        /** One zero-filled scratch slice shared by every wipe. */
        val ZERO_CHUNK = ByteArray(WIPE_CHUNK_BYTES)
    }
}

/** Wipe granularity of [ChurBuffer.clear]. */
private const val WIPE_CHUNK_BYTES = 64 * 1024
