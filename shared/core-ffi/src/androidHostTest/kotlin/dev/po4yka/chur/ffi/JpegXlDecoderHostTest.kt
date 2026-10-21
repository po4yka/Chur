package dev.po4yka.chur.ffi

import dev.po4yka.chur.core.model.ChurStatus
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Calls all four JPEG XL JNI methods against the real native library. */
class JpegXlDecoderHostTest {
    private fun fixture(name: String): File = File(
        checkNotNull(System.getenv("CHUR_JPEG_XL_FIXTURES")) { "CHUR_JPEG_XL_FIXTURES is required" }, name,
    )

    @Test
    fun fd_and_direct_buffer_decode_the_same_pixels_without_changing_source_offset() {
        RandomAccessFile(fixture("still-container.jxl"), "r").use { source ->
            source.seek(7)
            val descriptor = source.fd.javaClass.getDeclaredField("fd").run { isAccessible = true; getInt(source.fd) }
            val decoder = JpegXlDecoder.open(descriptor)
            try {
                assertEquals(7L, source.filePointer)
                assertEquals(96, decoder.width)
                assertEquals(48, decoder.height)
                assertEquals(1, decoder.frameCount)
                val frame = decoder.renderFrame(0, 24)
                try {
                    assertEquals(24, frame.width)
                    assertEquals(12, frame.height)
                    assertEquals(0, frame.durationMs)
                    assertContentEquals(byteArrayOf(-1, 0, 0, -1), frame.pixels.copyOut(4))
                } finally { frame.close() }
                assertEquals(7L, source.filePointer)
            } finally { decoder.close(); decoder.close() }
        }
        val bytes = fixture("still-codestream.jxl").readBytes()
        withChurBuffer(bytes.size) { input ->
            input.copyIn(bytes)
            val decoder = JpegXlDecoder.open(input)
            // The decoder owns its state and retains no input pointer.
            input.clear()
            try {
                val frame = decoder.renderFrame(0, 96)
                try { assertContentEquals(byteArrayOf(-1, 0, 0, -1), frame.pixels.copyOut(4)) }
                finally { frame.close() }
            } finally { decoder.close() }
        }
        bytes.fill(0)
    }

    @Test
    fun animation_delays_and_bounded_failure_statuses_cross_jni() {
        val bytes = fixture("animated.jxl").readBytes()
        withChurBuffer(bytes.size) { input ->
            input.copyIn(bytes)
            val decoder = JpegXlDecoder.open(input)
            try {
                assertEquals(3, decoder.frameCount)
                assertEquals(0, decoder.loopCount)
                for ((index, delay) in listOf(80, 120, 160).withIndex()) {
                    val frame = decoder.renderFrame(index, 48)
                    try { assertEquals(delay, frame.durationMs) } finally { frame.close() }
                }
                assertFailsWith<ChurFailure> { decoder.renderFrame(3, 48) }
                assertFailsWith<IllegalArgumentException> { decoder.renderFrame(0, 4097) }
            } finally { decoder.close() }
            assertFailsWith<IllegalStateException> { decoder.renderFrame(0, 48) }
        }
        bytes.fill(0)
        for (name in listOf("malformed.jxl", "truncated.jxl", "oversized.jxl")) {
            val content = fixture(name).readBytes()
            withChurBuffer(content.size) { input ->
                input.copyIn(content)
                assertFailsWith<ChurFailure> { JpegXlDecoder.open(input) }
            }
            content.fill(0)
        }
    }

    @Test
    fun invalid_raw_jni_arguments_fail_closed_and_short_out_arrays_do_not_leak() {
        val handle = LongArray(1)
        val info = IntArray(4)
        assertEquals(ChurStatus.INVALID_INPUT.value, ChurJni.jpegXlOpen(ByteBuffer.allocate(12), 12, handle, info))
        assertEquals(ChurStatus.INVALID_INPUT.value, ChurJni.jpegXlOpenFd(-1, handle, info))
        assertEquals(ChurStatus.INVALID_INPUT.value, ChurJni.jpegXlClose(0))
        assertEquals(ChurStatus.INVALID_INPUT.value, ChurJni.jpegXlRender(Long.MAX_VALUE, 0, 1, ByteBuffer.allocateDirect(4), IntArray(1)))
        val bytes = fixture("still-codestream.jxl").readBytes()
        withChurBuffer(bytes.size) { input ->
            input.copyIn(bytes)
            assertEquals(ChurStatus.INVALID_INPUT.value, ChurJni.jpegXlOpen(input.buffer, bytes.size, LongArray(0), info))
            assertEquals(ChurStatus.INVALID_INPUT.value, ChurJni.jpegXlOpen(input.buffer, bytes.size, handle, IntArray(0)))
            assertEquals(0L, handle[0])
            assertEquals(0, ChurJni.jpegXlOpen(input.buffer, bytes.size, handle, info))
            assertTrue(handle[0] != 0L)
            assertEquals(0, ChurJni.jpegXlClose(handle[0]))
            assertEquals(0, ChurJni.jpegXlClose(handle[0]))
            assertEquals(ChurStatus.SESSION_EXPIRED.value, ChurJni.jpegXlRender(handle[0], 0, 1, ByteBuffer.allocateDirect(4), IntArray(1)))
        }
        bytes.fill(0)
    }
}
