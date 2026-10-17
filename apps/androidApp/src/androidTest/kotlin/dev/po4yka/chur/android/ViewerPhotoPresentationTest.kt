package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.app.vault.ViewerStillState
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

    private fun present(audio: Boolean = false, test: (Fixture) -> Unit) {
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
                        ChurTheme(dark = true) {
                            ViewerScreen(projection, listOf(projection), emptyMap(), false, {}, {}, null,
                                if (f.hasImage.value) f.image else null, false, true, {},
                                onBack = {}, onLock = {}, onPanic = {}, onToggleFavorite = {}, onEditTags = {},
                                onExport = {}, onDelete = {}, onToggleDetail = {}, stillState = f.stillState.value,
                                onVerifyStill = { f.verifications++ })
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(f.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            test(f)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private inner class Fixture {
        var verifications = 0
        val stillState = mutableStateOf(ViewerStillState.Loading)
        val hasImage = mutableStateOf(false)
        val image = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2453A6.toInt()) }.asImageBitmap()
        lateinit var view: ComposeView
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
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
