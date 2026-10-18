package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.LibraryTile
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hardware-rendered production chrome over generated pixels, separate from real-vault lock tests. */
@RunWith(AndroidJUnit4::class)
class GlassMaterialPresentationTest {
    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun bottom_material_samples_changing_content_on_the_real_android_renderer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val controller = ChurHost.of(activity).controller
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val image = mutableStateOf(red.asImageBitmap())
        lateinit var view: ComposeView
        var attached = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun frames() {
            val frame = CountDownLatch(1)
            main { view.postOnAnimation { view.postOnAnimation { frame.countDown() } } }
            assertTrue(frame.await(5, TimeUnit.SECONDS))
            // Allow the real source/effect draw and the existing content entrance to settle.
            SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
        }
        fun capture(name: String): Bitmap {
            frames()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            return screenshot
        }
        try {
            main { controller.panic() }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var public = false
            while (!public && SystemClock.uptimeMillis() < deadline) {
                main {
                    public = controller.vaultState.value !is VaultState.Unlocked &&
                        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
                }
                SystemClock.sleep(40)
            }
            assertTrue("only generated preview pixels may be captured; never clear FLAG_SECURE", public)
            main {
                view = ComposeView(activity).apply {
                    setContent {
                        ChurTheme(dark = false) {
                            val tiles = (1..40).map { id ->
                                LibraryTile(ObjectProjection(byteArrayOf(id.toByte()), byteArrayOf(), 1, 0, 0,
                                    false, 1024, 32, 32, 0, false, 0, 0, false), image.value)
                            }
                            VaultShell(VaultUiState(tiles = tiles), VaultActions(
                                onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
                                onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
                                onAddRecoverySlot = {}, onChangePassword = {},
                            ), systemBack = { _, _ -> })
                        }
                    }
                }
                activity.setContentView(view)
                attached = true
            }
            frames()
            assertTrue("exercise Haze's hardware path", view.isHardwareAccelerated)
            val first = capture("glass-material-red.png")
            main { image.value = blue.asImageBitmap() }
            val second = capture("glass-material-blue.png")
            try {
                // Sample within the bottom bar, above its labels and the system navigation area.
                val density = activity.resources.displayMetrics.density
                assertEquals("changing thumbnails must preserve the fixed header",
                    first.getPixel(first.width / 2, (60 * density).toInt()),
                    second.getPixel(second.width / 2, (60 * density).toInt()))
                val bottomInset = activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                val barTop = first.height - bottomInset - (80 * density).toInt()
                var changed = 0
                for (y in barTop + 2 until barTop + (10 * density).toInt()) {
                    for (x in 8 until first.width - 8) {
                        val before = first.getPixel(x, y)
                        val after = second.getPixel(x, y)
                        if (kotlin.math.abs(Color.red(before) - Color.red(after)) +
                            kotlin.math.abs(Color.blue(before) - Color.blue(after)) > 20) changed++
                    }
                }
                assertTrue("glass must sample live content behind the bar, not an opaque tint: changed=$changed", changed > first.width)
            } finally { first.recycle(); second.recycle() }
        } finally {
            main {
                if (attached) {
                    // Detach before disposal: an attached ComposeView may
                    // recreate its composition during the next measure.
                    activity.setContentView(View(activity))
                    view.disposeComposition()
                }
                activity.finish()
            }
            // Compose/RenderThread can retain these ImageBitmaps after the
            // Activity finish request. Let their backing bitmaps be collected.
        }
    }
}
