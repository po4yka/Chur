@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.ffi

import dev.po4yka.chur.native.ChurJpegXlInfoV1
import dev.po4yka.chur.native.chur_handle_tVar
import dev.po4yka.chur.native.chur_jpeg_xl_open
import dev.po4yka.chur.native.chur_jpeg_xl_open_fd
import dev.po4yka.chur.native.chur_jpeg_xl_render
import dev.po4yka.chur.native.chur_jpeg_xl_close
import kotlinx.cinterop.*

actual class JpegXlDecoder private constructor(
    private var handle: ULong,
    actual val width: Int,
    actual val height: Int,
    actual val frameCount: Int,
    actual val loopCount: Int,
) {
    actual fun renderFrame(index: Int, maxEdge: Int): JpegXlFrame {
        check(handle != 0uL) { "JPEG XL decoder is closed" }
        require(index >= 0)
        val (w, h) = jpegXlDimensions(width, height, maxEdge)
        val pixels = ChurBuffer(w * h * 4)
        try {
            return memScoped {
                val duration = alloc<UIntVar>()
                ChurFailure.check(chur_jpeg_xl_render(handle, index.toUInt(), maxEdge.toUInt(), pixels.pointer, pixels.capacityBytes.convert(), duration.ptr), "jpeg xl render")
                JpegXlFrame(w, h, duration.value.toInt(), pixels)
            }
        } catch (failure: Throwable) {
            pixels.clear()
            pixels.release()
            throw failure
        }
    }
    actual fun close() {
        if (handle == 0uL) return
        val closing = handle
        handle = 0uL
        ChurFailure.check(chur_jpeg_xl_close(closing), "jpeg xl close")
    }
    actual companion object {
        actual fun open(descriptor: Int): JpegXlDecoder = memScoped {
            requireJpegXlCapability()
            val handle = alloc<chur_handle_tVar>()
            val info = alloc<ChurJpegXlInfoV1>()
            ChurFailure.check(chur_jpeg_xl_open_fd(descriptor, handle.ptr, info.ptr), "jpeg xl open")
            JpegXlDecoder(handle.value, info.width.toInt(), info.height.toInt(), info.frame_count.toInt(), info.loop_count.toInt())
        }
        actual fun open(original: ChurBuffer, length: Int): JpegXlDecoder {
            require(length in 1..original.capacityBytes)
            return memScoped {
                requireJpegXlCapability()
                val handle = alloc<chur_handle_tVar>()
                val info = alloc<ChurJpegXlInfoV1>()
                ChurFailure.check(chur_jpeg_xl_open(original.pointer, length.convert(), handle.ptr, info.ptr), "jpeg xl open")
                JpegXlDecoder(handle.value, info.width.toInt(), info.height.toInt(), info.frame_count.toInt(), info.loop_count.toInt())
            }
        }
    }
}
