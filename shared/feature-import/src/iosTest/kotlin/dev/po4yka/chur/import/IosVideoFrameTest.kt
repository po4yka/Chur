@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercise the UIImage after the Copy-rule CGImage reference is released. */
class IosVideoFrameTest {
    @Test
    fun repeated_video_probes_and_derivatives_keep_valid_frames_across_cancelled_attempts() {
        val path = NSTemporaryDirectory() + "chur-video-frame-" + NSUUID().UUIDString + ".mp4"
        val bytes = assertNotNull(NSData.create(base64EncodedString = RED_VIDEO, options = 0u))
        assertTrue(bytes.writeToFile(path, atomically = true))
        val codec = IosMediaCodec()
        try {
            repeat(8) {
                autoreleasepool {
                    val source = assertNotNull(codec.open(NSURL.fileURLWithPath(path), "Red video.mp4"))
                    try {
                        val probe = assertNotNull(codec.probe(source))
                        assertEquals(MediaBounds.CLASS_VIDEO, probe.mediaClass)
                        assertEquals(64 to 32, probe.width to probe.height)
                        for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER)) {
                            assertNull(codec.derive(source, probe, kind) { true })
                            val frame = assertNotNull(codec.derive(source, probe, kind) { false })
                            try {
                                val encoded = frame.bytes.toVideoFrameData()
                                val decoded = assertNotNull(UIImage.imageWithData(encoded))
                                assertEquals(64 to 32, decoded.size.useContents { width.toInt() to height.toInt() })
                                assertEquals(64 to 32, frame.width to frame.height)
                            } finally { frame.bytes.fill(0) }
                        }
                    } finally { source.close() }
                }
            }
        } finally { unlink(path) }
    }

    private companion object {
        // Generated solid-red 64 x 32 Baseline H.264, 30 frames, one second.
        // Tiny 16 x 8 H.264 is valid to ffmpeg but Apple rejects its decoder size.
        const val RED_VIDEO =
            "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAObbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAA" +
            "AAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAA" +
            "AsZ0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAA" +
            "AAAAAAAAAAAAAABAAAAAAEAAAAAgAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAI+bWRpYQAAACBtZGhk" +
            "AAAAAAAAAAAAAAAAAAA8AAAAPABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAAB6W1p" +
            "bmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAalzdGJsAAAAuXN0c2QA" +
            "AAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAIABIAAAASAAAAAAAAAABFExhdmM2My4xLjEwMiBsaWJ4" +
            "MjY0AAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAF2dCwArZBFsBEAAAAwAQAAADA8DxImSAAQAFaMuDyyAAAAAQcGFzcAAA" +
            "AAEAAAABAAAAFGJ0cnQAAAAAAAAdoAAAAAAAAAAYc3R0cwAAAAAAAAABAAAAHgAAAgAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxz" +
            "dHNjAAAAAAAAAAEAAAABAAAAHgAAAAEAAACMc3RzegAAAAAAAAAAAAAAHgAAApIAAAAKAAAACgAAAAoAAAAKAAAACgAAAAoAAAAK" +
            "AAAACgAAAAoAAAAKAAAACgAAAAoAAAAKAAAACgAAAAoAAAAKAAAACgAAAAoAAAAKAAAACgAAAAoAAAAKAAAACgAAAAoAAAAKAAAA" +
            "CgAAAAoAAAAKAAAACgAAABRzdGNvAAAAAAAAAAEAAAPLAAAAYXVkdGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFw" +
            "cGwAAAAAAAAAAAAAAAAsaWxzdAAAACSpdG9vAAAAHGRhdGEAAAABAAAAAExhdmY2My4xLjEwMgAAAAhmcmVlAAADvG1kYXQAAAJx" +
            "BgX//23cRem95tlIt5Ys2CDZI+7veDI2NCAtIGNvcmUgMTY1IHIzMjIyIGIzNTYwNWEgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVj" +
            "IC0gQ29weWxlZnQgMjAwMy0yMDI1IC0gaHR0cDovL3d3dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9" +
            "MCByZWY9MyBkZWJsb2NrPTE6MDowIGFuYWx5c2U9MHgxOjB4MTExIG1lPWhleCBzdWJtZT03IHBzeT0xIHBzeV9yZD0xLjAwOjAu" +
            "MDAgbWl4ZWRfcmVmPTEgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0xIDh4OGRjdD0wIGNxbT0wIGRlYWR6b25lPTIx" +
            "LDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PS0yIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90" +
            "aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBi" +
            "ZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAga2V5aW50X21pbj0yNSBzY2VuZWN1dD00MCBpbnRyYV9yZWZyZXNoPTAgcmNf" +
            "bG9va2FoZWFkPTQwIHJjPWNyZiBtYnRyZWU9MSBjcmY9MjMuMCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQg" +
            "aXBfcmF0aW89MS40MCBhcT0xOjEuMDAAgAAAABlliIQL8RigACRzHAAEQ2OAAJrUnJyddddeAAAABkGaOBfiYAAAAAZBmlQF+JgA" +
            "AAAGQZpgL8TAAAAABkGagC/EwAAAAAZBmqAvxMAAAAAGQZrAL8TAAAAABkGa4C/EwAAAAAZBmwAvxMAAAAAGQZsgL8TAAAAABkGb" +
            "QC/EwAAAAAZBm2AvxMAAAAAGQZuAL8TAAAAABkGboC/EwAAAAAZBm8AvxMAAAAAGQZvgL8TAAAAABkGaAC/EwAAAAAZBmiAvxMAA" +
            "AAAGQZpAL8TAAAAABkGaYC/EwAAAAAZBmoAvxMAAAAAGQZqgL8TAAAAABkGawC/EwAAAAAZBmuAvxMAAAAAGQZsAL8TAAAAABkGb" +
            "IC/EwAAAAAZBm0AvxMAAAAAGQZtgL8TAAAAABkGbgCvEwAAAAAZBm6AnxMA="
    }
}

private fun ByteArray.toVideoFrameData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
