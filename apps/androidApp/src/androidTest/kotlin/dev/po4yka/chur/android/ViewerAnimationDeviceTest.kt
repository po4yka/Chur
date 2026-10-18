@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.po4yka.chur.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.vault.AndroidViewerAnimation
import dev.po4yka.chur.app.vault.openViewerAnimation
import dev.po4yka.chur.ffi.ChurBuffer
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerAnimationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun gif_and_webp_render_different_frames_and_stop_before_original_is_wiped() {
        for (name in listOf("animated.gif", "animated.webp")) {
            val bytes = instrumentation.context.assets.open("media-formats/$name").use { it.readBytes() }
            val original = ChurBuffer(bytes.size)
            original.copyIn(bytes)
            bytes.fill(0)
            var decoder: AndroidViewerAnimation? = null
            val bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
            try {
                var first = 0
                instrumentation.runOnMainSync {
                    decoder = openViewerAnimation(original) as? AndroidViewerAnimation
                    val drawable = assertNotNullDecoder(decoder)
                    drawable.setBounds(0, 0, 96, 48)
                    drawable.start()
                    drawable.draw(Canvas(bitmap))
                    first = bitmap.getPixel(48, 24)
                }
                // Software Canvas advances the actual native GIF/WebP decoder on draw.
                SystemClock.sleep(130)
                instrumentation.runOnMainSync {
                    assertNotNullDecoder(decoder).draw(Canvas(bitmap))
                    assertNotEquals("$name must animate", first, bitmap.getPixel(48, 24))
                    decoder?.close()
                    assertNull(decoder?.drawable)
                    decoder?.close()
                }
            } finally {
                instrumentation.runOnMainSync { decoder?.close() }
                bitmap.recycle()
                original.clear()
                original.release()
            }
        }
    }

    @Test fun static_and_unsupported_apng_keep_the_still_fallback() {
        for (name in listOf("still.png", "animated.png")) {
            val bytes = instrumentation.context.assets.open("media-formats/$name").use { it.readBytes() }
            val original = ChurBuffer(bytes.size)
            original.copyIn(bytes)
            bytes.fill(0)
            try { instrumentation.runOnMainSync { assertNull(openViewerAnimation(original)) } }
            finally { original.clear(); original.release() }
        }
    }

    private fun assertNotNullDecoder(decoder: AndroidViewerAnimation?) =
        requireNotNull(decoder?.drawable) { "The native viewer must recognize the animated image" }
}
