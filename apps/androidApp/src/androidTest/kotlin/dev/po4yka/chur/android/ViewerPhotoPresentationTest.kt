package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
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
    }

    @Test
    fun missing_optional_audio_artwork_keeps_its_audio_fallback_after_loading_finishes() = present(audio = true) { f ->
        f.main { f.stillState.value = ViewerStillState.Unavailable }
        assertTrue(f.await { f.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "audio-artwork-placeholder" } })
        assertFalse("Decrypting" in f.labels())
        assertFalse("Preview unavailable" in f.labels())
    }

    @Test
    fun info_scrolls_to_the_last_tag_without_covering_viewer_actions_in_landscape_at_double_font_scale() =
        present(info = true) { f ->
            assertTrue(f.await { f.nodes().any { SemanticsActions.ScrollBy in it.config } })
            val scroll = f.nodes().first { SemanticsActions.ScrollBy in it.config }
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("long metadata must overflow its bounded viewport", range.maxValue() > 0f)
            f.main { assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 100_000f) == true) }
            assertTrue(f.await { range.value() > 0f })
            val lastTag = f.nodes().first { "Last public tag" in it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
            assertTrue(lastTag.boundsInRoot.height > 0f)
            assertTrue(lastTag.boundsInRoot.bottom <= scroll.boundsInRoot.bottom + 1f)
            val back = f.nodes().first { "Back" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertTrue(back.boundsInRoot.bottom <= scroll.boundsInRoot.top)
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
            val f = Fixture()
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
                    addView(f.view, FrameLayout.LayoutParams(((if (info) 640 else 320) * density).toInt(), ((if (info) 320 else 640) * density).toInt()))
                })
            }
            test(f)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private inner class Fixture {
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

    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (i in 0 until childCount) addAll(getChildAt(i).roots())
    }
}
