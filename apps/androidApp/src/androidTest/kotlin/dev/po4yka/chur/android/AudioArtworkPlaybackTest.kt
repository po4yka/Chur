package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Media3 controls and encrypted playback, rather than a fake player. */
@RunWith(AndroidJUnit4::class)
class AudioArtworkPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun artworkStaysBehindAccessibleNativeControlsBeforeAndDuringPlayback() = runBlocking {
        val root = File(context.cacheDir, "audio-art-playback-${System.nanoTime()}").apply { mkdirs() }
        val source = File(context.cacheDir, "export-scratch/audio-playback-${System.nanoTime()}.mp3")
        source.parentFile?.mkdirs()
        instrumentation.context.assets.open("audio-art/playback-art.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        var activity: MainActivity? = null
        var view: ComposeView? = null
        try {
            vault.start()
            vault.create("audio playback artwork test password".encodeToByteArray(), false)
            val codec = AndroidMediaCodec(context)
            val picked = requireNotNull(codec.open(leasedExportUri(context, source)))
            val outcome = MediaImporter(codec).import(vault, picked) as MediaImporter.Outcome.Imported
            val bytes = vault.readDerived(outcome.objectId, StreamKind.SCREEN_PREVIEW)
            val decoded = try { requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) } finally { bytes.fill(0) }
            val image = decoded.asImageBitmap()
            val playback = PlaybackSource(vault, outcome.objectId, "audio/mpeg", source.length())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = requireNotNull(activity)
            instrumentation.runOnMainSync {
                view = ComposeView(host).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        ChurTheme(dark = true) {
                            VaultPlayer(playback, Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                                image, controlsVisible = true, controlsPinned = true,
                                onControlsVisibleChange = {}, controlsPadding = PaddingValues(), touches = 0)
                        }
                    }
                }
                host.setContentView(FrameLayout(host).apply {
                    val density = resources.displayMetrics.density
                    addView(view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            val content = requireNotNull(view)
            assertTrue("native audio Play control is reachable", await {
                onMain { control(content)?.let { it.isShown && it.isEnabled && it.isClickable && it.contentDescription?.contains("Play") == true } == true }
            })
            assertTrue("audio must not create a video surface over its artwork", onMain {
                content.views().none { it is SurfaceView || it is TextureView }
            })
            assertNativeArtworkOff(content)
            captureAndAssertCover(content, host, "audio-art-native-before-play.png")
            assertTrue(onMain { requireNotNull(control(content)).performClick() })
            assertTrue("the actual player starts and offers Pause", await {
                onMain { control(content)?.let { it.isShown && it.isEnabled && it.contentDescription?.contains("Pause") == true } == true }
            })
            assertTrue("private audio must advance beyond its loading state", await {
                onMain {
                    val native = content.views().single { it.javaClass.name == "androidx.media3.ui.PlayerView" }
                    val player = native.javaClass.getMethod("getPlayer").invoke(native)
                    player != null && player.javaClass.getMethod("isPlaying").invoke(player) == true &&
                        (player.javaClass.getMethod("getCurrentPosition").invoke(player) as Long) >= 200L
                }
            })
            assertNativeArtworkOff(content)
            captureAndAssertCover(content, host, "audio-art-native-playing.png")
            assertTrue("Pause remains clickable above the cover", onMain { requireNotNull(control(content)).performClick() })
        } finally {
            instrumentation.runOnMainSync { view?.disposeComposition(); activity?.finish() }
            vault.shutdown()
            source.delete()
            root.deleteRecursively()
        }
    }

    private fun control(root: View): View? = root.views().firstOrNull {
        it.id != View.NO_ID && runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() == "exo_play_pause"
    }

    private fun assertNativeArtworkOff(root: View) = onMain {
        // Media3 is an implementation dependency of shared:app. Inspect its
        // public getter without adding a second dependency to the test app.
        val player = root.views().single { it.javaClass.name == "androidx.media3.ui.PlayerView" }
        val off = player.javaClass.getField("ARTWORK_DISPLAY_MODE_OFF").getInt(null)
        val mode = player.javaClass.getMethod("getArtworkDisplayMode").invoke(player)
        assertEquals("Media3 must not decode embedded art from the original", off, mode)
    }

    private fun View.views(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).views() } else emptyList()

    private fun <T> onMain(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(50)
        }
        return condition()
    }

    private fun captureAndAssertCover(view: View, activity: MainActivity, name: String) {
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { view.postOnAnimation { view.postOnAnimation { frame.countDown() } } }
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val bitmap = onMain {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        try {
            var redSamples = 0
            for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 8) {
                for (x in 8 until bitmap.width - 8 step 8) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > 30 && Color.red(pixel) > Color.green(pixel) * 3 && Color.red(pixel) > Color.blue(pixel) * 3) redSamples++
                }
            }
            assertTrue("the real PlayerView must not cover the synthetic artwork with black pixels", redSamples > 30)
            File(activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle() }
    }
}
