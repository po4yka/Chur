package dev.po4yka.chur.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurApp
import dev.po4yka.chur.app.notes.NotesScreen
import dev.po4yka.chur.app.vault.PrivateNotesRoute
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.notes.FileNoteStore
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual private screen and JNI with public synthetic fixtures, never user notes. */
@RunWith(AndroidJUnit4::class)
class PrivateNotesAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun encrypted_attachment_renders_from_native_storage_and_lock_removes_its_pixels_and_labels() {
        val id = ByteArray(16) { (it + 1).toByte() }
        val hex = id.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(30, 160, 130)) }
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
        }
        bitmap.recycle()
        try {
            present(prepare = { vault ->
                val generation = (vault.state.value as VaultState.Unlocked).generation
                vault.importPrivateNotePackage(generation, "Public attachment sample",
                    "# Local image\n\n![Public sample image](attachment:$hex)\n\n" +
                        "Text with ![Inline reference image][local] after it.\n\n" +
                        "[local]: attachment:$hex\n\n![Remote image](https://example.invalid/image.png)",
                    dev.po4yka.chur.ffi.PrivateNoteFormat.MARKDOWN,
                    listOf(dev.po4yka.chur.ffi.PrivateNoteAttachmentData(id, "fixture.png", "image/png", bytes)))
            }) { fixture ->
                fixture.clickText("Public attachment sample")
                fixture.clickText("Preview")
                assertTrue("the managed image is decoded by the actual platform renderer", fixture.await {
                    fixture.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Public sample image") == true && it.size.width > 0 && it.size.height > 0 }
                })
                assertTrue("reference-style inline images retain their own alt text and decode from the same native attachment", fixture.await {
                    fixture.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Inline reference image") == true && it.size.width > 0 && it.size.height > 0 }
                })
                assertTrue(fixture.await { fixture.hasText("only managed attachments are loaded") })
                assertTrue(fixture.savedState().isEmpty())
                fixture.capture("private-note-managed-image-public-fixture.png")
                assertFalse(fixture.publicFile.readText().contains("fixture.png"))
                runBlocking { fixture.vault.lock(LockReason.PANIC) }
                assertTrue(fixture.await { fixture.hasText("Public fixture note") })
                assertFalse(fixture.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Public sample image") == true })
                runBlocking { fixture.vault.unlock(PASSWORD.encodeToByteArray()) }
                val note = runBlocking { fixture.vault.privateNotes(fixture.generation).notes.single() }
                org.junit.Assert.assertArrayEquals(bytes, runBlocking { fixture.vault.readPrivateNoteAttachment(fixture.generation, note.noteId, id) })
                fixture.clickText("Public attachment sample")
                fixture.clickText("Preview")
                assertTrue(fixture.await { fixture.nodes().any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Public sample image") == true
                } })
                val originalSource = runBlocking { fixture.vault.privateNote(fixture.generation, note.noteId).body }
                // Remove through the production controller while the same preview stays open.
                fixture.fieldAction("private-note-editor-scroll") {
                    assertTrue(it.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 20_000f) == true)
                }
                fixture.clickText("Remove file")
                fixture.clickText("Remove")
                assertTrue("removing a native attachment invalidates its existing inline previews", fixture.await {
                    fixture.hasText("Image unavailable") && fixture.nodes().none {
                        val labels = it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        "Public sample image" in labels || "Inline reference image" in labels
                    }
                })
                val detached = runBlocking { fixture.vault.privateNote(fixture.generation, note.noteId) }
                assertEquals(originalSource, detached.body)
                assertTrue(runBlocking { fixture.vault.privateNoteAttachments(fixture.generation, note.noteId).isEmpty() })
                fixture.capture("private-note-detached-image-public-fixture.png")
                fixture.clickText("Version history")
                val previousRevision = detached.revision - 1
                fixture.clickText("Revision $previousRevision · Public attachment sample · 1 files")
                fixture.clickText("Restore this version")
                fixture.clickText("Restore version")
                assertTrue(fixture.await { fixture.hasText("Attachments (1/32)") })
                fixture.clickText("Preview")
                assertTrue("history restores the original bytes into the actual image renderer", fixture.await {
                    fixture.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Public sample image") == true }
                })
                org.junit.Assert.assertArrayEquals(bytes, runBlocking { fixture.vault.readPrivateNoteAttachment(fixture.generation, note.noteId, id) })
                assertEquals(originalSource, runBlocking { fixture.vault.privateNote(fixture.generation, note.noteId).body })
                assertTrue(fixture.savedState().isEmpty())
                fixture.capture("private-note-restored-image-public-fixture.png")

            }
        } finally { bytes.fill(0) }
    }

    @Test
    fun explicit_save_routes_to_native_notes_without_changing_public_notes_or_saved_state() = present { fixture ->
        fixture.create("Public synthetic title", "Public synthetic body for native notes")
        assertTrue(fixture.await { fixture.hasText("Unsaved changes are cleared") })
        assertTrue(fixture.savedState().isEmpty())
        fixture.clickTag("private-note-save")
        assertTrue(fixture.await { fixture.hasText("Saved in this vault.") })
        val notes = runBlocking { fixture.vault.privateNotes(fixture.generation).notes }
        assertEquals(1, notes.size)
        assertEquals("Public synthetic title", notes.single().title)
        assertEquals("Public synthetic body for native notes", runBlocking {
            fixture.vault.privateNote(fixture.generation, notes.single().noteId).body
        })
        fixture.fieldAction("private-note-editor-scroll") {
            assertTrue(it.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 20_000f) == true)
        }
        fixture.fieldAction("note-folder") { assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(androidx.compose.ui.text.AnnotatedString("Projects")) == true) }
        fixture.fieldAction("note-tags") { assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(androidx.compose.ui.text.AnnotatedString("todo\nPlan, do")) == true) }
        fixture.clickText("Apply organization")
        assertTrue(fixture.await { runBlocking { fixture.vault.privateNoteOrganization(fixture.generation, notes.single().noteId).folder == "Projects" } })
        assertEquals(listOf("Plan, do", "todo"), runBlocking { fixture.vault.privateNoteOrganization(fixture.generation, notes.single().noteId).tags })
        fixture.clickDescription("Back")
        assertTrue(fixture.await { fixture.hasText("Public synthetic title") })
        assertTrue(fixture.await { fixture.hasText("Folder: Projects") })
        fixture.clickText("Folders and tags")
        fixture.fieldAction("note-filter-folder") { assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(androidx.compose.ui.text.AnnotatedString("Projects")) == true) }
        fixture.fieldAction("note-filter-tag") { assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(androidx.compose.ui.text.AnnotatedString("todo")) == true) }
        fixture.clickText("Apply filters")
        assertTrue(fixture.await { fixture.hasText("Public synthetic title") })
        fixture.capture("private-notes-organization-public-fixture.png")
        fixture.fieldAction("note-filter-tag") { assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(androidx.compose.ui.text.AnnotatedString("absent")) == true) }
        fixture.clickText("Apply filters")
        assertTrue(fixture.await { fixture.hasText("No matching private notes.") })
        fixture.clickText("Clear filters")
        assertTrue(fixture.await { fixture.hasText("Public synthetic title") })
        assertEquals(listOf(PUBLIC_NOTE), runBlocking { fixture.publicStore.all() })
        assertFalse(fixture.publicFile.readText().contains("Public synthetic title"))
        assertTrue(fixture.savedState().isEmpty())
        fixture.capture("private-notes-list-public-fixture.png")
    }

    @Test
    fun accessibility_and_hardware_cut_do_not_copy_or_delete_private_draft_and_explicit_paste_works() = present { fixture ->
        val body = "Synthetic editable fixture"
        fixture.create("Public clipboard fixture", body)
        assertTrue("the body text is applied before selecting it", fixture.await {
            fixture.fieldText("private-note-body") == body
        })
        val clipboard = fixture.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        fixture.main { clipboard.setPrimaryClip(ClipData.newPlainText("public fixture", "PUBLIC_PASTE_FIXTURE")) }
        fixture.fieldAction("private-note-body") { node ->
            assertTrue(node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true)
            val select = node.config.getOrNull(SemanticsActions.SetSelection)?.action
            assertNotNull("the editable body supports selection", select)
            checkNotNull(select).invoke(0, body.length, false)
        }
        assertTrue("the entire body is selected before testing clipboard actions", fixture.await {
            fixture.nodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "private-note-body" &&
                    it.config.getOrNull(SemanticsProperties.TextSelectionRange) == TextRange(0, body.length)
            }
        })
        fixture.fieldAction("private-note-body") { node ->
            assertFalse(node.config.getOrNull(SemanticsActions.CopyText)?.action?.invoke() ?: false)
            assertFalse(node.config.getOrNull(SemanticsActions.CutText)?.action?.invoke() ?: false)
        }
        assertTrue(fixture.await { fixture.fieldText("private-note-body") == "Synthetic editable fixture" })
        fixture.hardwareCut()
        assertEquals("Synthetic editable fixture", fixture.fieldText("private-note-body"))
        fixture.main { assertEquals("PUBLIC_PASTE_FIXTURE", clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
        fixture.fieldAction("private-note-body") { node ->
            assertTrue(node.config.getOrNull(SemanticsActions.PasteText)?.action?.invoke() == true)
        }
        assertTrue(fixture.await { fixture.fieldText("private-note-body").contains("PUBLIC_PASTE_FIXTURE") })
        fixture.assertNoLearningInput()
    }

    @Test
    fun locking_drops_unsaved_draft_and_reunlock_opens_the_list_without_restoring_text() = present { fixture ->
        fixture.create("Unsaved synthetic fixture", "UNSAVED_NOTE_FIXTURE_7e05c3")
        assertTrue(fixture.savedState().isEmpty())
        runBlocking { fixture.vault.lock(LockReason.PANIC) }
        assertTrue(fixture.await { fixture.hasText("Public fixture note") })
        assertFalse(fixture.nodes().any { "UNSAVED_NOTE_FIXTURE_7e05c3" in it.config.toString() })
        runBlocking { fixture.vault.unlock(PASSWORD.encodeToByteArray()) }
        assertTrue(fixture.await { fixture.hasText("No private notes yet") })
        assertFalse(fixture.nodes().any { "UNSAVED_NOTE_FIXTURE_7e05c3" in it.config.toString() })
        assertTrue(runBlocking { fixture.vault.privateNotes(fixture.generation).notes.isEmpty() })
    }

    private fun present(fontScale: Float = 1f, prepare: suspend (VaultRepository) -> Unit = {}, test: (Fixture) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "private-notes-ui-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").path, { System.currentTimeMillis() })
        val publicFile = File(root, "public/notes.json")
        val publicStore = FileNoteStore(publicFile.path)
        runBlocking { publicStore.put(PUBLIC_NOTE); vault.start(); vault.create(PASSWORD.encodeToByteArray(), false); prepare(vault) }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val fixture = Fixture(activity, vault, publicFile, publicStore)
        try {
            fixture.main {
                val density = activity.resources.displayMetrics.density
                val config = Configuration(activity.resources.configuration).apply { screenWidthDp = 320; screenHeightDp = 640; this.fontScale = fontScale }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(config) }
                fixture.view = ComposeView(context).apply {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale), LocalConfiguration provides config,
                            LocalSaveableStateRegistry provides fixture.registry) {
                            val state by vault.state.collectAsState()
                            ChurApp(runGate(activity), if (state is VaultState.Unlocked) AppRoute.Vault else AppRoute.PublicShell) {
                                if (state is VaultState.Unlocked) PrivateNotesRoute(vault, (state as VaultState.Unlocked).generation,
                                    onClose = {}, onLock = { fixture.scope.launch { vault.lock(LockReason.USER) } },
                                    onPanic = { fixture.scope.launch { vault.lock(LockReason.PANIC) } }, onTouch = {}, onExport = {}, systemBack = { _, _ -> })
                                else NotesScreen(listOf(PUBLIC_NOTE), "", {}, {}, {}, {})
                            }
                        }
                    }
                }
                activity.addContentView(fixture.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
            }
            test(fixture)
        } finally {
            fixture.main { (fixture.view.parent as? ViewGroup)?.removeView(fixture.view); fixture.view.disposeComposition(); activity.finish() }
            fixture.scope.cancel()
            runBlocking { vault.shutdown() }
            root.deleteRecursively()
        }
    }

    private inner class Fixture(val activity: MainActivity, val vault: VaultRepository, val publicFile: File, val publicStore: FileNoteStore) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val registry = SaveableStateRegistry(null) { true }
        val generation get() = (vault.state.value as VaultState.Unlocked).generation
        lateinit var view: ComposeView
        fun main(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else instrumentation.runOnMainSync(action) }
        fun savedState(): Map<String, List<Any?>> { var saved = emptyMap<String, List<Any?>>() ; main { saved = registry.performSave() }; return saved }
        fun await(condition: () -> Boolean): Boolean { val until = SystemClock.uptimeMillis() + 5_000; while (SystemClock.uptimeMillis() < until) { if (condition()) return true; SystemClock.sleep(50) }; return condition() }
        fun nodes(merged: Boolean = false): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            main {
                // Compose dialogs own a separate window, outside the fixture's ComposeView.
                val dialogRoots = WindowInspector.getGlobalWindowViews()
                    .filter { it !== activity.window.decorView }.flatMap(::roots)
                result = (roots(view) + dialogRoots).flatMap {
                    it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged)
                }
            }
            return result
        }
        fun hasText(text: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { label -> text in label.text } == true }
        fun clickDescription(text: String) {
            fun clickable() = nodes(merged = true).firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true &&
                    SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config
            }
            assertTrue("enabled button with description: $text", await { clickable() != null })
            main { assertTrue(requireNotNull(clickable()).config[SemanticsActions.OnClick].action?.invoke() == true) }
        }
        fun clickText(text: String) {
            fun clickable() = nodes(merged = true).firstOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { label -> label.text == text } == true &&
                    SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config
            }
            assertTrue("enabled button or row: $text", await { clickable() != null })
            main { assertTrue(requireNotNull(clickable()).config[SemanticsActions.OnClick].action?.invoke() == true) }
        }
        fun clickTag(tag: String) {
            assertTrue(await { nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag && SemanticsProperties.Disabled !in it.config } })
            fieldAction(tag) { assertTrue(it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true) }
        }
        fun fieldAction(tag: String, action: (SemanticsNode) -> Unit) { assertTrue(await { nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }); main { action(nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) } }
        fun fieldText(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        fun create(title: String, body: String) {
            clickDescription("New private note")
            fieldAction("private-note-title") { assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(androidx.compose.ui.text.AnnotatedString(title)) == true) }
            fieldAction("private-note-body") { assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(androidx.compose.ui.text.AnnotatedString(body)) == true) }
        }
        fun hardwareCut() {
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT))
            instrumentation.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X, 0, KeyEvent.META_CTRL_ON))
            instrumentation.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_X, 0, KeyEvent.META_CTRL_ON))
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT))
            instrumentation.waitForIdleSync()
        }
        fun assertNoLearningInput() {
            main {
                val info = EditorInfo()
                val connection = flatten(view).asSequence().mapNotNull { it.onCreateInputConnection(info) }.firstOrNull()
                assertNotNull("a focused private field must own a native input connection", connection)
                assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            }
        }
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val position = IntArray(2); main { view.getLocationOnScreen(position) }
            val crop = Bitmap.createBitmap(screen, position[0], position[1], view.width.coerceAtMost(screen.width - position[0]), view.height.coerceAtMost(screen.height - position[1]))
            val output = File(activity.getExternalFilesDir(null), name)
            output.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            crop.recycle(); screen.recycle()
        }
    }
    private fun roots(view: View): List<ViewRootForTest> = flatten(view).filterIsInstance<ViewRootForTest>()
    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
    private companion object {
        const val PASSWORD = "Private-notes-android-fixture-password"
        val PUBLIC_NOTE = Note("public-fixture", "Public fixture note", "Public JSON stays independent", 1L)
    }
}
