package dev.po4yka.chur.ffi

actual class JpegXlDecoder private constructor(
    private var handle: Long,
    actual val width: Int,
    actual val height: Int,
    actual val frameCount: Int,
    actual val loopCount: Int,
) {
    actual fun renderFrame(index: Int, maxEdge: Int): JpegXlFrame {
        check(handle != 0L) { "JPEG XL decoder is closed" }
        val (w, h) = jpegXlDimensions(width, height, maxEdge)
        val pixels = ChurBuffer(w * h * 4)
        try {
            val duration = IntArray(1)
            ChurFailure.check(ChurJni.jpegXlRender(handle, index, maxEdge, pixels.buffer, duration), "jpeg xl render")
            return JpegXlFrame(w, h, duration[0], pixels)
        } catch (failure: Throwable) {
            pixels.clear()
            pixels.release()
            throw failure
        }
    }
    actual fun close() {
        if (handle == 0L) return
        val closing = handle
        handle = 0L
        ChurFailure.check(ChurJni.jpegXlClose(closing), "jpeg xl close")
    }
    actual companion object {
        actual fun open(descriptor: Int): JpegXlDecoder = opened { handle, info ->
            ChurJni.jpegXlOpenFd(descriptor, handle, info)
        }
        actual fun open(original: ChurBuffer, length: Int): JpegXlDecoder {
            require(length in 1..original.capacityBytes)
            return opened { handle, info -> ChurJni.jpegXlOpen(original.buffer, length, handle, info) }
        }
        private inline fun opened(call: (LongArray, IntArray) -> Int): JpegXlDecoder {
            requireJpegXlCapability()
            val handle = LongArray(1)
            val info = IntArray(4)
            ChurFailure.check(call(handle, info), "jpeg xl open")
            return JpegXlDecoder(handle[0], info[0], info[1], info[2], info[3])
        }
    }
}
