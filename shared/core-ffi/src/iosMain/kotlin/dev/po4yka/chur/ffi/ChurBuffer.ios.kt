package dev.po4yka.chur.ffi

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.posix.memset_s

/**
 * A native allocation cinterop reaches without copying.
 *
 * The element type is `UByteVar` because that is what `chur.h` declares and
 * what cinterop therefore expects; a `ByteVar` buffer would need a reinterpret
 * at every call site.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ChurBuffer actual constructor(capacity: Int) {
    internal val size: Int = capacity.also { require(it >= 0) { "the buffer capacity is negative" } }
    /**
     * The native allocation itself, for a platform API that reads it in place,
     * such as the viewer's image decoder. Use it only inside [withChurBuffer],
     * which clears and frees it afterwards.
     */
    // A zero-capacity buffer has a valid allocation but exposes no readable bytes.
    val pointer: CPointer<UByteVar> = nativeHeap.allocArray(maxOf(size, 1))

    actual val capacityBytes: Int get() = size

    actual fun copyOut(length: Int): ByteArray {
        val source = pointer
        require(length in 0..size) { "the copy exceeds the buffer" }
        val result = ByteArray(length)
        if (length > 0) result.usePinned { memcpy(it.addressOf(0), source, length.toULong()) }
        return result
    }

    actual fun copyIn(bytes: ByteArray) {
        val destination = pointer
        require(bytes.size <= size) { "the buffer is smaller than the bytes" }
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(destination, it.addressOf(0), bytes.size.toULong()) }
    }

    actual fun clear() {
        val destination = pointer
        // C11 Annex K memset_s cannot be optimized away, including before free.
        // The allocation is live and both sizes are its validated byte capacity.
        if (size > 0) check(memset_s(destination, size.toULong(), 0, size.toULong()) == 0) {
            "the buffer could not be cleared"
        }
    }

    actual fun release() {
        nativeHeap.free(pointer.rawValue)
    }
}
