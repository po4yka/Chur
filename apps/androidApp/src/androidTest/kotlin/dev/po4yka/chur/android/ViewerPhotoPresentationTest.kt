package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.app.vault.ViewerStillState
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public fixtures check viewer failures and controls without a private vault. */
@RunWith(AndroidJUnit4::class)
class ViewerPhotoPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun an_exhausted_photo_request_replaces_loading_with_a_terminal_message() = present { f ->
        assertTrue(f.await { "Decrypting" in f.labels() })
        f.main { f.stillState.value = ViewerStillState.Unavailable }
        assertTrue(f.await { "Preview unavailable" in f.labels() })
        assertFalse("Decrypting" in f.labels())
        assertTrue(f.labels().contains("Info"))
        f.main {
            val verify = f.nodes().first { "Verify files" in it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
            assertTrue(verify.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        assertTrue(f.verifications == 1)
        f.capture("viewer-photo-unavailable-public.png")
    }

    @Test
    fun missing_optional_audio_artwork_keeps_its_audio_fallback_after_loading_finishes() = present(audio = true) { f ->
        f.main { f.stillState.value = ViewerStillState.Unavailable }
        assertTrue(f.await { f.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "audio-artwork-placeholder" } })
        assertFalse("Decrypting" in f.labels())
        assertFalse("Preview unavailable" in f.labels())
    }

    @Test
    fun info_scrolls_to_the_last_tag_without_covering_viewer_actions_in_a_short_viewport_at_double_font_scale() =
        present(info = true) { f ->
            assertTrue(f.await { f.nodes().any {
                SemanticsActions.ScrollBy in it.config &&
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.let { max -> max > 0f } == true
            } })
            val scroll = f.nodes().first { SemanticsActions.ScrollBy in it.config }
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("long metadata must overflow its bounded viewport", range.maxValue() > 0f)
            f.main { assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 100_000f) == true) }
            assertTrue(f.await { range.value() > 0f })
            assertTrue(f.await {
                val lastTag = f.nodes().first { "Last public tag" in it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
                lastTag.boundsInRoot.height > 0f && lastTag.boundsInRoot.bottom <= scroll.boundsInRoot.bottom + 1f
            })
            val back = f.nodes().first { "Back" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertTrue(back.boundsInRoot.bottom <= scroll.boundsInRoot.top)
            f.capture("viewer-photo-info-short-font2-public.png")
        }

    @Test
    fun photo_zoom_has_adjustable_scale_pan_reset_and_a_keyboard_path() = present { f ->
        f.main { f.hasImage.value = true; f.stillState.value = ViewerStillState.Ready }
        assertTrue(f.await { f.nodes().any { SemanticsProperties.ProgressBarRangeInfo in it.config } })
        fun photo() = f.nodes().first { SemanticsProperties.ProgressBarRangeInfo in it.config }
        fun action(label: String) = f.main {
            assertTrue(photo().config[SemanticsActions.CustomActions].first { it.label == label }.action())
        }
        action("Zoom in")
        assertTrue(f.await { photo().config[SemanticsProperties.ProgressBarRangeInfo].current == 1.5f })
        action("Pan right")
        f.main { assertTrue(photo().config[SemanticsActions.SetProgress].action?.invoke(5f) == true) }
        assertTrue(f.await { photo().config[SemanticsProperties.StateDescription] == "Zoom 500%" })
        f.capture("viewer-photo-zoom500-public.png")
        action("Reset zoom")
        assertTrue(f.await { photo().config[SemanticsProperties.ProgressBarRangeInfo].current == 1f })
        f.main {
            assertTrue(photo().config[SemanticsActions.RequestFocus].action?.invoke() == true)
            assertTrue(f.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_EQUALS)))
            f.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_EQUALS))
        }
        assertTrue(f.await { photo().config[SemanticsProperties.ProgressBarRangeInfo].current == 1.5f })
        f.main {
            assertTrue(f.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
            f.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertTrue(f.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0)))
            f.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0))
        }
        assertTrue(f.await { photo().config[SemanticsProperties.ProgressBarRangeInfo].current == 1f })
        assertTrue("accessible controls report user activity", f.interactions > 0)
    }

    private fun present(audio: Boolean = false, info: Boolean = false, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val f = Fixture(activity)
            f.main {
                val density = activity.resources.displayMetrics.density
                val projection = ObjectProjection(objectId = ByteArray(16) { 1 }, primaryStreamId = ByteArray(16) { 2 },
                    mediaKind = if (audio) 3 else 1, captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false,
                    plaintextSize = 1_024, width = if (audio) 0 else 400, height = if (audio) 0 else 300,
                    durationMs = if (audio) 65_000 else 0, favorite = false, state = 1, integritySummary = 4, thumbnailReady = false)
                f.view = ComposeView(activity).apply {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, if (info) 2f else 1f)) {
                            ChurTheme(dark = true) {
                                ViewerScreen(projection, listOf(projection), emptyMap(), false, {}, {}, if (info) f.detail else null,
                                    if (f.hasImage.value) f.image else null, info, true, {},
                                    onTouch = { f.interactions++ }, onBack = {}, onLock = {}, onPanic = {}, onToggleFavorite = {}, onEditTags = {},
                                    onExport = {}, onDelete = {}, onToggleDetail = {}, stillState = f.stillState.value,
                                    onVerifyStill = { f.verifications++ })
                            }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    // This is a short viewport in the actual window, not a simulated landscape display.
                    val height = if (info) minOf((320 * density).toInt(), activity.windowManager.currentWindowMetrics.bounds.height())
                        else FrameLayout.LayoutParams.MATCH_PARENT
                    addView(f.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height))
                })
            }
            test(f)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private inner class Fixture(val activity: MainActivity) {
        var verifications = 0
        var interactions = 0
        val stillState = mutableStateOf(ViewerStillState.Loading)
        val hasImage = mutableStateOf(false)
        val detail = ObjectDetail(0, 0, false, 400, 300, 0, 1_024, "image/jpeg",
            "Public filename ".repeat(20), "Public caption. ".repeat(100), listOf(ByteArray(16) to "Last public tag"))
        val image = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2453A6.toInt()) }.asImageBitmap()
        lateinit var view: ComposeView
        fun main(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else instrumentation.runOnMainSync(action)
        }
        fun nodes(): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            main { result = view.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) } }
            return result
        }
        fun labels() = nodes().flatMap {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } +
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        fun capture(name: String) {
            val ready = CountDownLatch(1)
            val bounds = Rect()
            main {
                view.invalidateTree()
                view.postOnAnimation {
                    view.invalidateTree()
                    view.postOnAnimation {
                        val point = IntArray(2)
                        view.getLocationOnScreen(point)
                        bounds.set(point[0], point[1], point[0] + view.width, point[1] + view.height)
                        ready.countDown()
                    }
                }
            }
            assertTrue("the public fixture must draw before capture", ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val visible = Rect()
            main { assertTrue("the public fixture must be visible", view.getGlobalVisibleRect(visible)) }
            assertTrue("the fixture must fit inside its actual window", visible == bounds)
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue("capture bounds must fit inside the screenshot",
                    bounds.width() > 0 && bounds.height() > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                        bounds.right <= screen.width && bounds.bottom <= screen.height)
                val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until crop.height step 4) for (x in 0 until crop.width step 4) colors += crop.getPixel(x, y)
                    assertTrue("the public fixture must render visible content", colors.size > 16)
                    File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use {
                        assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { if (crop !== screen) crop.recycle() }
            } finally { screen.recycle() }
        }

        fun await(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                if (condition()) return true
                SystemClock.sleep(30)
            }
            return condition()
        }
    }

    private fun View.invalidateTree() {
        invalidate()
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).invalidateTree()
    }

    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (i in 0 until childCount) addAll(getChildAt(i).roots())
    }
}
