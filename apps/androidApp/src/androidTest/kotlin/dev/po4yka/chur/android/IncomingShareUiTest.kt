package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

import android.os.ParcelFileDescriptor
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.IncomingShareInbox
import dev.po4yka.chur.app.IncomingSharePrompt
import dev.po4yka.chur.app.IncomingShareSource
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.NoPrivacyCover
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Production share confirmation at 320 dp and double font. Every displayed value is a public fixture. */
@RunWith(AndroidJUnit4::class)
class IncomingShareUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun public_gate_shows_only_count_and_discard_closes_the_unopened_batch() = present(publicGate = true) { f ->
        assertTrue(f.await { f.has("Shared with Chur") })
        val copy = "2 shared items are ready. Open your vault to choose where to import. Originals stay in the source app."
        f.assertTextFits(copy)
        assertFalse(f.has(ALBUM_LAST))
        assertFalse(f.has("public-shared-source.bin"))
        assertEquals(0, f.source.opened)
        f.capture("incoming-share-public-320-font2.png")
        f.click("Open vault")
        assertEquals(AppRoute.Unlock, f.controller.route.value)
        assertEquals(0, f.source.opened)
        f.click("Discard")
        assertTrue(f.await { f.inbox.state.value == null })
        assertEquals(1, f.source.closed)
        assertEquals(0, f.source.opened)
    }

    @Test
    fun private_destination_scrolls_and_import_places_every_item_into_the_confirmed_album() = present { f ->
        assertTrue(f.await { f.has("Import shared items") })
        f.assertTextFits("Import shared items")
        f.assertPrivateStateNotSaved()
        f.capture("incoming-share-destination-top-public-320-font2.png")
        f.reveal(ALBUM_LAST)
        f.assertTextFits(ALBUM_LAST)
        f.capture("incoming-share-destination-bottom-public-320-font2.png")
        f.click(ALBUM_LAST)
        f.assertPrivateStateNotSaved()
        assertEquals(0, f.source.opened)
        f.click("Import")
        assertTrue(f.await { f.inbox.state.value == null })
        assertTrue(f.await { runBlocking { f.controller.vault.page(ObjectQuery(scope = QueryScope.ALBUM, scopeId = f.lastAlbum)).totalCount == 2L } })
        assertEquals(2, f.source.opened)
        assertEquals(2, f.source.itemsClosed)
        assertEquals(1, f.source.closed)
        assertTrue(f.inputFile.exists())
    }

    @Test
    fun cancel_releases_the_batch_without_opening_sources_or_saving_a_private_destination() = present { f ->
        assertTrue(f.await { f.has("Import shared items") })
        f.click(ALBUM_LAST)
        f.assertPrivateStateNotSaved()
        f.click("Cancel")
        assertTrue(f.await { f.inbox.state.value == null })
        assertEquals(1, f.source.closed)
        assertEquals(0, f.source.opened)
        assertEquals(0L, runBlocking { f.controller.vault.page(ObjectQuery()).totalCount })
    }

    @Test
    fun all_items_is_the_default_destination_and_does_not_add_album_membership() = present { f ->
        assertTrue(f.await { f.has("All items") })
        f.click("All items")
        f.assertPrivateStateNotSaved()
        f.click("Import")
        assertTrue(f.await { f.inbox.state.value == null })
        assertTrue(f.await { runBlocking { f.controller.vault.page(ObjectQuery()).totalCount == 2L } })
        assertEquals(0L, runBlocking { f.controller.vault.page(ObjectQuery(scope = QueryScope.ALBUM, scopeId = f.lastAlbum)).totalCount })
        assertEquals(2, f.source.itemsClosed)
        assertEquals(1, f.source.closed)
    }

    private fun present(publicGate: Boolean = false, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val f = Fixture(activity)
        try {
            runBlocking {
                f.controller.start()
                f.controller.create("Incoming share UI fixture password", offerRecovery = false)
                withTimeout(10_000) { f.controller.route.first { it == AppRoute.Vault } }
                for (index in 1..16) f.lastAlbum = f.controller.vault.createAlbum(if (index == 16) ALBUM_LAST else "Public destination $index")
                f.controller.loadAlbums()
                withTimeout(10_000) { f.controller.albums.first { it.size == 16 } }
            }
            f.main {
                assertTrue(f.inbox.offer(f.source))
                val density = activity.resources.displayMetrics.density
                val config = Configuration(activity.resources.configuration).apply { screenWidthDp = 320; screenHeightDp = 640; fontScale = 2f }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(config) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        val route by f.controller.route.collectAsState()
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalConfiguration provides config,
                            LocalSaveableStateRegistry provides f.registry) {
                            ChurTheme(dark = false) {
                                IncomingSharePrompt(f.inbox, f.controller, if (publicGate) AppRoute.PublicShell else route, MediaImporter(NoDecoder))
                            }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(f.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            f.frames()
            test(f)
        } finally {
            f.main { f.dispose(); f.inbox.discard(); activity.finish() }
            assertTrue("fixture activity must finish before the next test", f.await { activity.isDestroyed })
            runBlocking { f.controller.shutdown() }
            f.root.deleteRecursively()
        }
    }

    private inner class Fixture(val activity: MainActivity) {
        lateinit var view: ComposeView
        val root = File(activity.noBackupFilesDir, "incoming-share-ui-${System.nanoTime()}").apply { mkdirs() }
        val inputFile = File(root, "public-shared-source.bin").apply { writeBytes("Public UI fixture".encodeToByteArray()) }
        val controller = ChurController(storageRoot = File(root, "vault").absolutePath, privacy = NoPrivacyCover,
            exports = NoExports, clock = { System.currentTimeMillis() })
        val inbox = IncomingShareInbox()
        val registry = SaveableStateRegistry(emptyMap()) { true }
        var lastAlbum = byteArrayOf()
        val source = CountingSource(inputFile)
        fun dispose() { if (::view.isInitialized) view.disposeComposition() }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                if (condition()) return true
                SystemClock.sleep(30)
            }
            return condition()
        }
        private fun nodes(merged: Boolean = true) = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged) }
        private fun find(label: String) = nodes().firstOrNull { label in it.labels }
        fun has(label: String): Boolean { var found = false; main { found = find(label) != null }; return found }
        fun enabled(label: String): Boolean { var enabled = false; main { enabled = find(label)?.config?.contains(SemanticsProperties.Disabled) == false }; return enabled }
        fun reveal(label: String) {
            assertTrue("missing control: $label", await { has(label) })
            var target: Pair<Int, Float>? = null
            main {
                val node = requireNotNull(find(label))
                val scroll = generateSequence(node.parent) { it.parent }.firstOrNull { SemanticsActions.ScrollBy in it.config }
                if (scroll != null) {
                    val viewport = scroll.boundsInWindow
                    val top = node.positionInWindow.y
                    val bottom = top + node.size.height
                    val delta = when { top < viewport.top -> top - viewport.top; bottom > viewport.bottom -> bottom - viewport.bottom; else -> 0f }
                    val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                    target = scroll.id to (range.value() + delta).coerceIn(0f, range.maxValue())
                    if (abs(delta) > 1f) assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta) == true)
                }
            }
            target?.let { (id, wanted) -> assertTrue(await {
                var done = false
                main { done = nodes(false).firstOrNull { it.id == id }?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    ?.let { abs(it.value() - wanted.coerceAtMost(it.maxValue())) <= 1f } == true }
                done
            }) }
            frames()
        }
        fun click(label: String) {
            reveal(label)
            main {
                val node = requireNotNull(find(label))
                assertFalse("control is disabled: $label", SemanticsProperties.Disabled in node.config)
                assertTrue("control must be fully visible: $label", node.boundsInWindow.width + 2f >= node.size.width && node.boundsInWindow.height + 2f >= node.size.height)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            frames()
        }
        fun assertTextFits(label: String) = main {
            val node = nodes(false).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertEquals(2f, layout.layoutInput.density.fontScale, 0.01f)
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                for (offset in label.indices) {
                    val glyph = layout.getBoundingBox(offset)
                    assertTrue("text must fit: $label", glyph.left >= -1f && glyph.right <= node.size.width + 1f && glyph.top >= -1f && glyph.bottom <= node.size.height + 1f)
                }
            }
        }
        fun assertPrivateStateNotSaved() = main {
            assertTrue("private destination state must not be saved", registry.performSave().isEmpty())
        }
        fun frames(count: Int = 2) {
            val ready = CountDownLatch(1)
            main {
                val choreographer = Choreographer.getInstance()
                var remaining = count
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (--remaining == 0) ready.countDown() else choreographer.postFrameCallback(this)
                    }
                }
                choreographer.postFrameCallback(callback)
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
        }
        fun capture(name: String) {
            // Wait for the platform dialog enter animation as well as Compose.
            frames(20)
            val bounds = Rect()
            main {
                val target = WindowInspector.getGlobalWindowViews().lastOrNull { it.roots().isNotEmpty() } ?: view
                val position = IntArray(2); target.getLocationOnScreen(position)
                bounds.set(position[0], position[1], position[0] + target.width, position[1] + target.height)
            }
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue(bounds.intersect(0, 0, screen.width, screen.height))
                val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until crop.height step 4) for (x in 0 until crop.width step 4) colors += crop.getPixel(x, y)
                    assertTrue("public fixture must render", colors.size > 16)
                    File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { if (crop !== screen) crop.recycle() }
            } finally { screen.recycle() }
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
    private class CountingSource(private val inputFile: File) : IncomingShareSource {
        var opened = 0
        var closed = 0
        var itemsClosed = 0
        override val count = 2
        override suspend fun open(index: Int, prepare: (Long, Long) -> Boolean): PickedMedia? {
            if (!prepare(0, 0)) return null
            opened++
            // Force a busy recomposition before the source arrives. Hiding the
            // dialog must not dispose the scope that owns this import.
            delay(250)
            if (!prepare(0, 0)) return null
            val fd = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
            return PickedMedia(fd.fd, true, inputFile.length(), "application/octet-stream", inputFile.name, null, null,
                { fd.close(); itemsClosed++ }, importAsFile = true)
        }
        override fun close() { closed++ }
    }
    private object NoDecoder : MediaCodec {
        override fun probe(media: PickedMedia): ProbedMedia? = error("explicit UI fixture files do not reach a decoder")
        override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? =
            error("explicit UI fixture files do not generate previews")
    }
    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }
    private companion object { const val ALBUM_LAST = "Public destination 16 with a longer name" }
}
