package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.effects.Mosaic
import dev.po4yka.chur.app.effects.mosaic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android software canvas coverage for both public effect entry points. */
@RunWith(AndroidJUnit4::class)
class MosaicRenderingTest {
    @Test
    fun resizing_the_same_modifier_replaces_its_buffers_and_does_not_retain_old_pixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val color = mutableStateOf(Color.Red)
        val drawRightHalf = mutableStateOf(false)
        // Keep this exact Modifier instance attached while its layout size and draw content change.
        val effect = Modifier.fillMaxSize()
            .mosaic(minBlockSize = 8.dp, maxBlockSize = 8.dp, featherEdges = false)
            .drawBehind {
                val contentWidth = if (drawRightHalf.value) size.width / 2f else size.width
                val contentLeft = if (drawRightHalf.value) size.width / 2f else 0f
                drawRect(
                    color.value, topLeft = Offset(contentLeft, 0f), size = Size(contentWidth, size.height),
                )
            }
        lateinit var view: ComposeView
        lateinit var parent: FrameLayout
        try {
            instrumentation.runOnMainSync {
                view = ComposeView(activity).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent { Box(effect) }
                }
                parent = FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(128, 128))
                }
                activity.setContentView(parent)
            }
            val square = capture(view, parent, 224, 160)
            assertEquals(128, view.width)
            assertEquals(128, view.height)
            assertEquals(android.graphics.Color.RED, square.getPixel(64, 96))
            instrumentation.runOnMainSync {
                color.value = Color.Blue
                drawRightHalf.value = true
                view.layoutParams = FrameLayout.LayoutParams(192, 64)
            }
            val rectangle = capture(view, parent, 224, 160)
            assertEquals(192, view.width)
            assertEquals(64, view.height)
            assertEquals(0, rectangle.getPixel(8, 8))
            // This point exceeds the old 128 px width and requires a resized raster buffer.
            assertEquals(android.graphics.Color.BLUE, rectangle.getPixel(184, 8))
            assertEquals(0, rectangle.getPixel(8, 80))
            assertEquals(0, rectangle.getPixel(200, 8))
            val pixels = IntArray(rectangle.width * rectangle.height)
            rectangle.getPixels(pixels, 0, rectangle.width, 0, 0, rectangle.width, rectangle.height)
            assertTrue(
                "No pixel from the previous red content may remain",
                pixels.none { it == android.graphics.Color.RED },
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun both_apis_render_current_content_and_optional_feather_on_a_software_canvas() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val color = mutableStateOf(Color.Red)
        val wrapped = mutableStateOf(false)
        val feather = mutableStateOf(false)
        lateinit var view: ComposeView
        try {
            instrumentation.runOnMainSync {
                view = ComposeView(activity).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        if (wrapped.value) {
                            Mosaic(
                                Modifier.fillMaxSize(), minBlockSize = 8.dp, maxBlockSize = 8.dp,
                                featherEdges = feather.value,
                            ) {
                                Box(Modifier.fillMaxSize().background(color.value))
                            }
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .mosaic(minBlockSize = 8.dp, maxBlockSize = 8.dp, featherEdges = feather.value)
                                    .background(color.value),
                            )
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(128, 128))
                })
            }
            val modifierImage = capture(view)
            assertEquals(android.graphics.Color.RED, modifierImage.getPixel(64, 64))
            assertEquals(android.graphics.Color.RED, modifierImage.getPixel(0, 64))
            instrumentation.runOnMainSync { color.value = Color.Transparent }
            assertEquals(0, capture(view).getPixel(64, 64))
            instrumentation.runOnMainSync {
                wrapped.value = true
                feather.value = true
                color.value = Color.Blue
            }
            val composableImage = capture(view)
            assertEquals(android.graphics.Color.BLUE, composableImage.getPixel(64, 64))
            assertTrue(android.graphics.Color.alpha(composableImage.getPixel(0, 64)) in 1..254)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun capture(
        composeView: ComposeView,
        target: View = composeView,
        width: Int = 128,
        height: Int = 128,
    ): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            composeView.postOnAnimation { composeView.postOnAnimation { drawn.countDown() } }
        }
        assertTrue("Compose must draw two frames", drawn.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { target.draw(Canvas(bitmap)) }
        return bitmap
    }
}
