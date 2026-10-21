@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.ffi

import dev.po4yka.chur.native.chur_vault_present
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.memcmp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the actual native allocation used by the C ABI. No replacement buffer. */
class ChurBufferIosTest {
    @Test
    fun bulk_copy_round_trips_all_byte_values_at_the_reader_window_size() {
        val input = ByteArray(1_048_576) { (it % 256).toByte() }
        try {
            withChurBuffer(input.size) { buffer ->
                buffer.copyIn(input)
                assertEquals(input.size, buffer.capacityBytes)
                assertContentEquals(input, buffer.copyOut(input.size))
                assertContentEquals(input.copyOf(257), buffer.copyOut(257))
                buffer.clear()
                assertNativeZero(buffer)
                assertContentEquals(ByteArray(input.size), buffer.copyOut(input.size))
            }
        } finally { input.fill(0) }
    }

    @Test
    fun partial_and_empty_copies_preserve_the_native_tail_and_check_bounds() {
        withChurBuffer(257) { buffer ->
            val canary = ByteArray(257) { 0x5a }
            buffer.copyIn(canary)
            buffer.copyIn(byteArrayOf(-128, -1, 0, 127))
            val expected = canary.copyOf().also { byteArrayOf(-128, -1, 0, 127).copyInto(it) }
            buffer.copyIn(byteArrayOf())
            assertContentEquals(byteArrayOf(), buffer.copyOut(0))
            assertContentEquals(expected, buffer.copyOut(257))
            assertFailsWith<IllegalArgumentException> { buffer.copyOut(-1) }
            assertFailsWith<IllegalArgumentException> { buffer.copyOut(258) }
            assertFailsWith<IllegalArgumentException> { buffer.copyIn(ByteArray(258)) }
            assertContentEquals(expected, buffer.copyOut(257), "A rejected copy must not change native bytes")
            buffer.clear()
            assertNativeZero(buffer)
        }
    }

    @Test
    fun zero_capacity_is_valid_and_negative_capacity_is_refused() {
        assertFailsWith<IllegalArgumentException> { ChurBuffer(-1) }
        withChurBuffer(0) { buffer ->
            assertEquals(0, buffer.capacityBytes)
            buffer.copyIn(byteArrayOf())
            assertContentEquals(byteArrayOf(), buffer.copyOut(0))
            buffer.clear()
            assertFailsWith<IllegalArgumentException> { buffer.copyOut(1) }
            assertFailsWith<IllegalArgumentException> { buffer.copyIn(byteArrayOf(1)) }
        }
    }

    @Test
    fun rust_writes_directly_to_the_same_native_backing_without_touching_the_tail() {
        val root = NSTemporaryDirectory() + "chur-buffer-native-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val runtime = ChurVault.openRuntime(root)
        try {
            withChurBuffer(17) { buffer ->
                buffer.copyIn(ByteArray(17) { 0x5a })
                assertEquals(0, chur_vault_present(runtime.toULong(), buffer.pointer))
                val result = buffer.copyOut(17)
                assertEquals(0, result[0].toInt(), "An empty real runtime contains no vault")
                assertTrue(result.drop(1).all { it == 0x5a.toByte() }, "The Rust scalar write must not touch adjacent bytes")
                buffer.clear()
                assertNativeZero(buffer)
            }
        } finally {
            ChurVault.closeRuntime(runtime)
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private fun assertNativeZero(buffer: ChurBuffer) {
        val zero = ByteArray(buffer.capacityBytes)
        if (zero.isNotEmpty()) zero.usePinned {
            assertEquals(0, memcmp(buffer.pointer, it.addressOf(0), zero.size.toULong()), "The whole native allocation must be cleared")
        }
    }
}
