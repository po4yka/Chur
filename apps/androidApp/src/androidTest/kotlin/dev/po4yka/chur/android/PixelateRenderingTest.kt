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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.effects.Pixelate
import dev.po4yka.chur.app.effects.pixelate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android software canvas coverage for both public effect entry points. */
@RunWith(AndroidJUnit4::class)
class PixelateRenderingTest {
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
                            Pixelate(
                                Modifier.fillMaxSize(), minBlockSize = 8.dp, maxBlockSize = 8.dp,
                                featherEdges = feather.value,
                            ) {
                                Box(Modifier.fillMaxSize().background(color.value))
                            }
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .pixelate(minBlockSize = 8.dp, maxBlockSize = 8.dp, featherEdges = feather.value)
                                    .background(color.value),
                            )
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(128, 128))
                })
            }
            fun capture(): Bitmap {
                val drawn = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    view.postOnAnimation { view.postOnAnimation { drawn.countDown() } }
                }
                assertTrue("Compose must draw two frames", drawn.await(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
                return bitmap
            }
            val modifierImage = capture()
            assertEquals(android.graphics.Color.RED, modifierImage.getPixel(64, 64))
            assertEquals(android.graphics.Color.RED, modifierImage.getPixel(0, 64))
            instrumentation.runOnMainSync { color.value = Color.Transparent }
            assertEquals(0, capture().getPixel(64, 64))
            instrumentation.runOnMainSync {
                wrapped.value = true
                feather.value = true
                color.value = Color.Blue
            }
            val composableImage = capture()
            assertEquals(android.graphics.Color.BLUE, composableImage.getPixel(64, 64))
            assertTrue(android.graphics.Color.alpha(composableImage.getPixel(0, 64)) in 1..254)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
