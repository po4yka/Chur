package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ContextThemeWrapper
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.AlbumExportDialog
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.ffi.AlbumSummary
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic presentation only: no private vault is created, opened, or changed. */
@RunWith(AndroidJUnit4::class)
class AlbumExportPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun emptyFilteredAlbumCanExportAndTheWarningRequiresAnExplicitChoice() = present("Summer", 1f) { harness ->
        assertTrue("an empty filtered page still offers the whole album", await {
            harness.button("Export album") != null
        })
        harness.main {
            harness.state.value = harness.state.value.copy(operation = ActiveOperation(1, "album-export"))
        }
        assertTrue("export must be disabled during another operation", await {
            harness.button("Export album")?.config?.contains(SemanticsProperties.Disabled) == true
        })
        harness.main { harness.state.value = harness.state.value.copy(operation = null) }
        assertTrue(await { harness.button("Export album")?.config?.contains(SemanticsProperties.Disabled) == false })
        harness.click("Export album")
        assertTrue(await { harness.textContaining("Encrypted archive…") != null })
        harness.click("Unencrypted folder")
        assertTrue("the warning must explain the plaintext boundary", await {
            harness.textContaining("remain unencrypted outside the vault") != null
        })
        assertEquals(0, harness.chosen)
        harness.click("Cancel")
        assertTrue(await { harness.button("Choose folder") == null })
        assertEquals(0, harness.chosen)
        harness.click("Export album")
        assertTrue(await { harness.textContaining("Encrypted archive…") != null })
        harness.click("Unencrypted folder")
        harness.click("Choose folder")
        assertTrue(await { harness.chosen == 1 && harness.button("Choose folder") == null })
        assertEquals(harness.album.id, harness.requestedAlbum?.id)
        harness.capture("album-export-shell-320.png", dialog = false)
    }

    @Test
    fun narrowPhoneAtDoubleFontScaleKeepsTheActionAndFullWarningReachable() =
        present("Family archive — summer holidays and private recordings ".repeat(3), 2f) { harness ->
            assertEquals("the synthetic resource context must use the requested scale", 2f,
                harness.view.context.resources.configuration.fontScale, 0.01f)
            assertTrue(await { harness.button("Export album") != null })
            harness.capture("album-export-shell-320-font2.png", dialog = false)
            harness.assertTextFits("Export album")
            harness.assertFontScale("Export album", 2f)
            harness.assertTextFits(harness.album.name, allowEllipsis = true)
            harness.click("Export album")
        assertTrue(await { harness.textContaining("Encrypted archive…") != null })
        harness.click("Unencrypted folder")
            assertTrue(await { harness.button("Choose folder") != null })
            harness.constrainDialog()
            harness.capture("album-export-warning-320-font2.png", dialog = true)
            harness.assertTextFits("Choose folder")
            harness.assertFontScale("Choose folder", 2f)
            harness.assertTextFits("Cancel")
            harness.assertWarningReachable()
            harness.click("Cancel")
            assertEquals(0, harness.chosen)
        }

    private fun present(name: String, fontScale: Float, test: (Harness) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            val harness = Harness(activity, name)
            harness.main {
                val density = activity.resources.displayMetrics.density
                val configuration = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320
                    screenHeightDp = 640
                    this.fontScale = fontScale
                }
                // Dialog creates its own Android view. Its resource context must have
                // the same configuration as the shell's composition locals.
                val context = ContextThemeWrapper(activity, activity.theme).apply {
                    applyOverrideConfiguration(configuration)
                }
                harness.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(
                            LocalDensity provides Density(density, fontScale),
                            LocalConfiguration provides configuration,
                        ) {
                            ChurTheme(dark = false) {
                                VaultShell(harness.state.value, harness.actions, systemBack = { _, _ -> })
                                if (harness.confirming.value) AlbumExportDialog(
                                    harness.album,
                                    onConfirm = { harness.chosen++; harness.confirming.value = false },
                                    onDismiss = { harness.confirming.value = false },
                                )
                            }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(harness.view, FrameLayout.LayoutParams((320 * density).roundToInt(), (640 * density).roundToInt()))
                })
            }
            test(harness)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private inner class Harness(val activity: MainActivity, name: String) {
        val album = AlbumSummary(ByteArray(16) { 7 }, 0, name)
        // No tile matches this filter. The export action still applies to the complete album.
        val state = mutableStateOf(VaultUiState(openAlbum = album, searchTerms = "no matching item", kinds = 1))
        val confirming = mutableStateOf(false)
        var chosen = 0
        var requestedAlbum: AlbumSummary? = null
        lateinit var view: ComposeView
        val actions = VaultActions(
            onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
            onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
            onAddRecoverySlot = {}, onChangePassword = {},
            onExportAlbum = { requestedAlbum = it; confirming.value = true },
        )

        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

        private fun nodes(merged: Boolean): List<SemanticsNode> = WindowInspector.getGlobalWindowViews()
            .flatMap { it.composeRoots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged) }

        fun button(text: String): SemanticsNode? {
            var found: SemanticsNode? = null
            main { found = nodes(true).firstOrNull { text in it.labels && SemanticsActions.OnClick in it.config } }
            return found
        }

        fun textContaining(text: String): SemanticsNode? {
            var found: SemanticsNode? = null
            main { found = nodes(false).firstOrNull { node -> node.labels.any { text in it } } }
            return found
        }

        fun click(text: String) {
            assertTrue("$text must be reachable", await { button(text) != null })
            var clicked = false
            main {
                val node = nodes(true).first { text in it.labels && SemanticsActions.OnClick in it.config }
                assertFalse("$text is disabled", SemanticsProperties.Disabled in node.config)
                clicked = node.config[SemanticsActions.OnClick].action?.invoke() == true
            }
            assertTrue(clicked)
        }

        fun assertTextFits(text: String, allowEllipsis: Boolean = false) {
            main {
                val node = nodes(false).first { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                        SemanticsActions.GetTextLayoutResult in node.config
                }
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                assertTrue("$text has no layout", layouts.isNotEmpty())
                layouts.forEach {
                    assertTrue("Text overflows vertically: $text; layout=${it.size}, bounds=${node.boundsInWindow}",
                        !it.didOverflowHeight || (allowEllipsis && it.lineCount == 1 && it.isLineEllipsized(0)))
                    assertTrue("Text is clipped vertically: $text", it.size.height <= node.boundsInWindow.height + 1f)
                }
            }
        }

        fun assertFontScale(text: String, expected: Float) = main {
            val node = nodes(false).first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                    SemanticsActions.GetTextLayoutResult in node.config
            }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertEquals("$text must use the requested text scale", expected,
                it.layoutInput.density.fontScale, 0.01f) }
        }

        fun assertWarningReachable() {
            val warning = requireNotNull(textContaining("remain unencrypted outside the vault"))
            val text = warning.labels.single()
            assertFontScale(text, 2f)
            var scrollNode: SemanticsNode? = null
            main {
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(warning.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                scrollNode = generateSequence(warning) { it.parent }
                    .firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
                val fits = layouts.all { !it.didOverflowHeight && it.size.height <= warning.boundsInWindow.height + 1f }
                assertTrue("Warning must fit or be scrollable", scrollNode != null || fits)
                scrollNode?.let { node ->
                    val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                    if (range.maxValue() > 0f) {
                        assertTrue(node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, range.maxValue()) == true)
                    }
                }
            }
            scrollNode?.let { node ->
                assertTrue("the final warning lines must scroll into view", await {
                    var atEnd = false
                    main {
                        val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
                        atEnd = range.value() >= range.maxValue() - 1f
                    }
                    atEnd
                })
                capture("album-export-warning-end-320-font2.png", dialog = true)
            }
            // The warning's final statement must remain represented in the accessible text.
            assertTrue(text.endsWith("including copies completed before cancellation."))
        }

        fun constrainDialog() {
            main {
                val density = activity.resources.displayMetrics.density
                val root = dialogRoot()
                val attributes = root.layoutParams as WindowManager.LayoutParams
                activity.windowManager.updateViewLayout(root, WindowManager.LayoutParams().apply {
                    copyFrom(attributes)
                    width = (320 * density).roundToInt()
                    height = (640 * density).roundToInt()
                })
            }
            instrumentation.waitForIdleSync()
        }

        fun capture(name: String, dialog: Boolean) {
            instrumentation.waitForIdleSync()
            val bounds = Rect()
            val frames = CountDownLatch(1)
            main {
                val target = if (dialog) dialogRoot() else view
                assertTrue("render target must have size", target.width > 0 && target.height > 0)
                target.postOnAnimation { target.postOnAnimation { frames.countDown() } }
                val position = IntArray(2)
                target.getLocationOnScreen(position)
                bounds.set(position[0], position[1], position[0] + target.width, position[1] + target.height)
            }
            assertTrue("the synthetic screen must render", frames.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            // Capture the compositor's real output. No FLAG_SECURE or other production
            // protection is changed; an obscured capture fails instead of proving a render.
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue(bounds.intersect(0, 0, screen.width, screen.height))
            val bitmap = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
            val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) colors.add(bitmap.getPixel(x, y))
            assertTrue("the synthetic capture must contain rendered content", colors.size > 16)
            val output = File(requireNotNull(activity.getExternalFilesDir(null)), name)
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (bitmap !== screen) bitmap.recycle()
            screen.recycle()
        }

        private fun dialogRoot(): View = WindowInspector.getGlobalWindowViews().single { root ->
            root.composeRoots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                .any { node -> node.labels.any { "remain unencrypted outside the vault" in it } }
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(30)
        }
        return condition()
    }

    private val SemanticsNode.labels: List<String> get() =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun View.composeRoots(): List<ViewRootForTest> = listOfNotNull(this as? ViewRootForTest) +
        ((this as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { group.getChildAt(it).composeRoots() } }
            ?: emptyList())
}
