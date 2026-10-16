package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.ExternalArchiveScreen
import dev.po4yka.chur.app.ExternalArchiveSelection
import dev.po4yka.chur.app.NoPrivacyCover
import dev.po4yka.chur.app.notes.PublicSettingsScreen
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.SelectiveExportDialog
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Public fictional selections through production UI. Native archive output has separate tests. */
@RunWith(AndroidJUnit4::class)
class ExternalArchivePresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun public_entry_selects_files_without_a_vault_and_cancelled_destination_releases_the_selection() = present { f ->
        f.click("Encrypt external files")
        assertEquals(AppRoute.EncryptFiles, f.controller.route.value)
        assertTrue(f.controller.vaultState.value is VaultState.NoVault)
        assertFalse(f.enabled("Create encrypted archive"))
        f.click("Choose files")
        assertTrue(f.await { f.has("3 files selected") })
        f.reveal("Create encrypted archive")
        f.assertTextFits("Create encrypted archive")
        f.capture("external-archive-selection-public-320-font2.png")
        f.click("Create encrypted archive")
        // The dialog has a separate Android window. Wait for its semantic fields.
        assertTrue(f.await { f.tag("archive-password") != null && f.tag("archive-password-repeat") != null })
        f.setText("archive-password", PUBLIC_PASSWORD)
        f.setText("archive-password-repeat", "public mismatch")
        assertTrue(f.await { f.has("The passwords do not match.") })
        assertFalse(f.enabled("Save to Files"))
        f.reveal("The passwords do not match.")
        f.assertTextFits("The passwords do not match.")
        f.capture("external-archive-password-public-320-font2.png")
        f.click("Cancel")
        assertTrue(f.await { f.tag("archive-password") == null })
        f.click("Create encrypted archive")
        assertTrue(f.await { f.emptyField("archive-password") && f.emptyField("archive-password-repeat") })
        f.setText("archive-password", PUBLIC_PASSWORD)
        f.setText("archive-password-repeat", PUBLIC_PASSWORD)
        assertTrue(f.await { f.enabled("Save to Files") && f.enabled("Share") })
        f.click("Save to Files")
        assertEquals(1, f.destinations)
        assertTrue(f.await { f.closedSelections == 1 && !f.has("3 files selected") })
        assertEquals(0, f.openedSources)
        assertTrue(f.controller.vaultState.value is VaultState.NoVault)
        f.assertNoSavedCredential()
        f.click("Choose files")
        assertTrue(f.await { f.has("3 files selected") })
        f.main { f.show.value = false }
        assertTrue(f.await { f.closedSelections == 2 })
    }

    @Test
    fun external_file_password_dialog_clears_on_share_cancel_and_disposal_without_saved_credentials() = present(dialog = true) { f ->
        assertTrue(f.await { f.tag("archive-password") != null })
        f.setText("archive-password", PUBLIC_PASSWORD)
        f.setText("archive-password-repeat", PUBLIC_PASSWORD)
        assertTrue(f.await { f.enabled("Share") })
        f.main {
            val action = f.findOnMain("Share")!!.config[SemanticsActions.OnClick].action!!
            action(); action()
        }
        assertEquals(listOf(PUBLIC_PASSWORD to ExportTarget.SHARE), f.choices)
        assertTrue(f.await { f.emptyField("archive-password") && f.emptyField("archive-password-repeat") })
        assertFalse(f.enabled("Save to Files"))
        f.assertNoSavedCredential()
        f.setText("archive-password", PUBLIC_PASSWORD)
        f.click("Cancel")
        assertTrue(f.await { f.tag("archive-password") == null })
        f.main { f.show.value = true }
        assertTrue(f.await { f.emptyField("archive-password") })
        f.setText("archive-password", PUBLIC_PASSWORD)
        f.main { f.show.value = false }
        assertTrue(f.await { f.tag("archive-password") == null })
        f.main { f.show.value = true }
        assertTrue(f.await { f.emptyField("archive-password") && f.emptyField("archive-password-repeat") })
        assertFalse(f.enabled("Share"))
        f.assertNoSavedCredential()
    }

    private fun present(dialog: Boolean = false, test: (Fixture) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "external-archive-ui-${System.nanoTime()}").apply { mkdirs() }
        // This controller has no vault or user data. It does not change the app host's privacy policy.
        val controller = ChurController(root.path, NoPrivacyCover, NoExports, clock = System::currentTimeMillis)
        runBlocking { controller.start() }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val f = Fixture(activity, controller, dialog)
        try {
            f.main {
                val density = activity.resources.displayMetrics.density
                val config = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320; screenHeightDp = 640; fontScale = 2f
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(config) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalConfiguration provides config,
                            LocalSaveableStateRegistry provides f.registry) {
                            ChurTheme(dark = false) {
                                when {
                                    !f.show.value -> Unit
                                    dialog -> SelectiveExportDialog("3 external files", externalFiles = true,
                                        onChoose = { password, target -> f.choices += password to target },
                                        onDismiss = { f.show.value = false })
                                    !f.external.value -> PublicSettingsScreen(onBack = {}, onOpenVault = {},
                                        onEncryptExternalFiles = { controller.goTo(AppRoute.PublicSettings); controller.openExternalArchive(); f.external.value = true })
                                    else -> ExternalArchiveScreen(controller,
                                        selectFiles = { answer -> answer(ExternalArchiveSelection(3,
                                            open = { _, _ -> f.openedSources++; error("UI fixture must not open sources") },
                                            close = { f.closedSelections++ })) },
                                        chooseDestination = { answer -> f.destinations++; answer(null) })
                                }
                            }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(f.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            test(f)
        } finally {
            f.main { f.show.value = false; activity.finish() }
            instrumentation.waitForIdleSync()
            runBlocking { controller.shutdown() }
            root.deleteRecursively()
        }
    }

    private inner class Fixture(val activity: MainActivity, val controller: ChurController, val dialogOnly: Boolean) {
        val show = mutableStateOf(true)
        val external = mutableStateOf(false)
        val registry = SaveableStateRegistry(emptyMap()) { true }
        val choices = mutableListOf<Pair<String, ExportTarget>>()
        var destinations = 0
        var closedSelections = 0
        var openedSources = 0
        lateinit var view: ComposeView
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
        private fun targetOnMain(): View = WindowInspector.getGlobalWindowViews().firstOrNull { root ->
            root.roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                .any { it.config.getOrNull(SemanticsProperties.TestTag) == "archive-password" }
        } ?: view
        private fun nodes(merged: Boolean = true) = targetOnMain().roots()
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged) }
        fun findOnMain(label: String) = nodes().firstOrNull { label in it.labels }
        fun has(label: String): Boolean { var found = false; main { found = findOnMain(label) != null }; return found }
        fun tag(value: String): SemanticsNode? {
            var found: SemanticsNode? = null
            main { found = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value } }
            return found
        }
        fun enabled(label: String): Boolean { var result = false; main { result = findOnMain(label)?.config?.contains(SemanticsProperties.Disabled) == false }; return result }
        fun emptyField(tag: String) = this.tag(tag)?.config?.getOrNull(SemanticsProperties.EditableText)?.text == ""
        fun setText(tag: String, value: String) = main {
            val field = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
        fun assertNoSavedCredential() = main {
            val saved = registry.performSave()
            if (dialogOnly) {
                assertTrue("the password dialog must save no state", saved.isEmpty())
            } else {
                // rememberScrollState saves one nonnegative Int offset per registry entry.
                // Reject text, credentials, file metadata, URIs, and arbitrary objects.
                val types = saved.values.map { values -> values.map { it?.javaClass?.simpleName ?: "null" } }
                assertTrue("only integer scroll offsets may be saved; types=$types", saved.values.all { values ->
                    values.size == 1 && (values.single() as? Int)?.let { it >= 0 } == true
                })
            }
        }
        fun reveal(label: String) {
            assertTrue("missing control: $label", await { has(label) })
            var target: Float? = null
            main {
                val node = requireNotNull(findOnMain(label))
                val scroll = generateSequence(node.parent) { it.parent }.firstOrNull { SemanticsActions.ScrollBy in it.config }
                if (scroll != null) {
                    val viewport = scroll.boundsInWindow
                    val top = node.positionInWindow.y
                    val bottom = top + node.size.height
                    val delta = when { top < viewport.top -> top - viewport.top; bottom > viewport.bottom -> bottom - viewport.bottom; else -> 0f }
                    val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                    target = (range.value() + delta).coerceIn(0f, range.maxValue())
                    if (abs(delta) > 1f) assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta) == true)
                }
            }
            target?.let { wanted -> assertTrue(await {
                var done = false
                main { done = nodes(false).firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
                    ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { abs(it.value() - wanted.coerceAtMost(it.maxValue())) <= 1f } == true }
                done
            }) }
            frames()
        }
        fun click(label: String) {
            reveal(label)
            main { assertTrue(requireNotNull(findOnMain(label)).config[SemanticsActions.OnClick].action?.invoke() == true) }
        }
        fun assertTextFits(label: String) = main {
            val node = nodes(false).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertEquals(2f, layout.layoutInput.density.fontScale, 0.01f)
                assertFalse("text must not clip: $label", layout.didOverflowHeight)
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineLeft(line) >= -1f)
                    assertTrue(layout.getLineRight(line) <= node.boundsInWindow.width + 1f)
                }
            }
        }
        private fun frames() {
            val ready = CountDownLatch(1)
            // A dialog view can detach between callbacks. Choreographer belongs to
            // the main thread and keeps both frame callbacks alive across dismissal.
            main {
                val choreographer = Choreographer.getInstance()
                choreographer.postFrameCallback {
                    choreographer.postFrameCallback { ready.countDown() }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        fun capture(name: String) {
            frames()
            val bounds = Rect()
            main { val target = targetOnMain(); val point = IntArray(2); target.getLocationOnScreen(point)
                bounds.set(point[0], point[1], point[0] + target.width, point[1] + target.height) }
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
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }
    private companion object { const val PUBLIC_PASSWORD = "public fictional archive password" }
}
