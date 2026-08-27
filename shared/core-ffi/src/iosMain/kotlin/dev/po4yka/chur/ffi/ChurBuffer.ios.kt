package dev.po4yka.chur.ffi

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.set

/**
 * A native allocation cinterop reaches without copying.
 *
 * The element type is `UByteVar` because that is what `chur.h` declares and
 * what cinterop therefore expects; a `ByteVar` buffer would need a reinterpret
 * at every call site.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ChurBuffer actual constructor(capacity: Int) {
    internal val pointer: CPointer<UByteVar> = nativeHeap.allocArray(capacity)
    internal val size: Int = capacity

    actual val capacityBytes: Int get() = size

    actual fun copyOut(length: Int): ByteArray = ByteArray(length) { index -> pointer[index].toByte() }

    actual fun copyIn(bytes: ByteArray) {
        require(bytes.size <= size) { "the buffer is smaller than the bytes" }
        for (index in bytes.indices) {
            pointer[index] = bytes[index].toUByte()
        }
    }

    actual fun clear() {
        for (index in 0 until size) {
            pointer[index] = 0u
        }
    }

    actual fun release() {
        nativeHeap.free(pointer.rawValue)
    }
}
