package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real application, native vault and platform chrome; run on a synthetic test device. */
@RunWith(AndroidJUnit4::class)
class GlassChromeIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val controller: ChurController get() = ChurHost.of(activity).controller

    @Before
    fun openVault() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(await { controller.vaultState.value !is VaultState.Starting })
        main {
            when (controller.vaultState.value) {
                is VaultState.NoVault -> {
                    controller.goTo(AppRoute.CreateVault)
                    controller.create(PASSWORD, offerRecovery = false)
                }
                is VaultState.Unlocked -> controller.goTo(AppRoute.Vault)
                else -> {
                    controller.goTo(AppRoute.Unlock)
                    controller.unlock(PASSWORD)
                }
            }
        }
        assertTrue("the real test vault opens: ${controller.formError.value}", await(60_000) {
            controller.vaultState.value is VaultState.Unlocked && controller.route.value == AppRoute.Vault
        })
        assertTrue(await { has("Browse") && has("Lock now") })
        assertSecure()
    }

    @After
    fun finish() {
        if (!::activity.isInitialized) return
        main { controller.panic() }
        await { controller.vaultState.value !is VaultState.Unlocked }
        main { activity.finish() }
    }

    @Test
    fun tabs_browse_and_selection_preserve_commands_then_lock_removes_private_chrome() {
        listOf("Albums", "Search", "Settings", "Library").forEach { label ->
            click(label)
            assertTrue("tab selection reaches shared state: $label", await {
                var selected = false
                main { selected = nodes().any { label in it.labels && it.config.getOrNull(SemanticsProperties.Selected) == true } }
                selected
            })
        }
        click("Browse")
        click("Recently added")
        assertTrue(await { has("Order: Recently added") })
        click("Back")
        val importedId = importPhoto()
        assertTrue("the imported photo has an accessible selection action", await {
            var selected = false
            main {
                nodes().firstOrNull { SemanticsActions.OnLongClick in it.config && "Photo" in it.labels }
                    ?.let { selected = it.config[SemanticsActions.OnLongClick].action?.invoke() == true }
            }
            selected
        })
        assertTrue(await { has("1 selected") && has("More") && has("Lock now") })
        val previousGeneration = (controller.vaultState.value as VaultState.Unlocked).generation
        main {
            assertEquals(AppRoute.Vault, controller.route.value)
            // A controller caller may replace a session without leaving this route.
            controller.unlock(PASSWORD)
        }
        assertTrue("a fresh JNI session reloads the imported object on the same route", await(60_000) {
            val opened = controller.vaultState.value as? VaultState.Unlocked
            opened != null && opened.generation != previousGeneration && controller.route.value == AppRoute.Vault &&
                controller.page.value.objects.any { it.objectId.contentEquals(importedId) }
        })
        assertTrue("the new session has its own header and drops the old selection", await {
            has("Library") && has("Browse") && has("Lock now") && !has("1 selected") && !has("More")
        })
        assertSecure()
        click("Lock now")
        assertPrivateChromeGone()
        main {
            assertTrue(controller.page.value.objects.isEmpty())
            assertTrue(controller.albums.value.isEmpty())
        }
    }

    @Test
    fun selection_bottom_panel_replaces_tabs_and_cancellation_preserves_selection() {
        importPhoto()
        importPhoto()
        selectFirstTile()
        assertSelectionPanel()
        var replacement = -1
        main {
            val retainedExport = nodes().single { "Export" in it.labels && SemanticsActions.OnClick in it.config }
                .config[SemanticsActions.OnClick].action!!
            val tiles = nodes().filter { "Photo" in it.labels && SemanticsActions.OnLongClick in it.config }
            val previous = tiles.first { it.config.getOrNull(SemanticsProperties.Selected) == true }
            val next = tiles.first { it.config.getOrNull(SemanticsProperties.Selected) == false }
            replacement = next.id
            // A → A+B → B keeps the count while all events precede composition.
            assertTrue(next.config[SemanticsActions.OnClick].action?.invoke() == true)
            assertTrue(previous.config[SemanticsActions.OnClick].action?.invoke() == true)
            assertTrue(retainedExport())
        }
        assertTrue(await {
            var replaced = false
            main { replaced = nodes().any { it.id == replacement && it.config.getOrNull(SemanticsProperties.Selected) == true } }
            replaced && has("1 selected")
        })
        assertFalse("the retained export must not target the replacement selection", has("Export original"))
        main {
            val export = nodes().single { "Export" in it.labels && SemanticsActions.OnClick in it.config }
            val add = nodes().single { "Add to album" in it.labels && SemanticsActions.OnClick in it.config }
            val other = nodes().first { "Photo" in it.labels && SemanticsActions.OnLongClick in it.config &&
                it.config.getOrNull(SemanticsProperties.Selected) == false }
            assertTrue(export.config[SemanticsActions.OnClick].action?.invoke() == true)
            // The dialog owns input immediately, before its window is rendered.
            assertTrue(other.config[SemanticsActions.OnClick].action?.invoke() == true)
            assertTrue(add.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        assertTrue(await { has("Export original") && has("Lock and hide") })
        assertTrue(has("1 selected"))
        assertFalse("a queued second command must not open another dialog", has("Find destination"))
        click("Cancel")
        assertTrue(await { !has("Cancel") })
        assertSelectionPanel()
        click("Add to album")
        assertTrue(await { has("Find destination") && has("Lock and hide") })
        click("Cancel")
        assertTrue(await { !has("Cancel") })
        assertSelectionPanel()
        click("Move to Trash")
        assertTrue(await { has("Move to Trash?") && has("Lock and hide") })
        click("Cancel")
        assertTrue(await { !has("Cancel") })
        assertSelectionPanel()
        click("More")
        assertTrue(await { has("Select loaded items") && has("Tags") && has("Lock and hide") })
        main { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(await { !has("Select loaded items") })
        assertSelectionPanel()
        click("Clear selection")
        assertTrue(await { has("Browse") && has("Albums") && has("Search") && has("Settings") })
        assertFalse(has("1 selected"))
        selectFirstTile()
        main { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(await { has("Browse") && !has("1 selected") })
        selectFirstTile()
        click("More")
        click("Lock and hide")
        assertPrivateChromeGone()
    }

    @Test
    fun active_import_blocks_selection_actions_back_and_deselecting_the_last_tile() {
        importPhoto()
        selectFirstTile()
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        // Hold an actual source open at the platform boundary, then let the
        // real PNG/native import complete. No operation state is fabricated.
        val importer = thread(name = "selection-real-import") {
            try {
                importPhoto {
                    opening.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(opening.await(20, TimeUnit.SECONDS))
            assertTrue(await(waitForIdle = false) {
                var blocked = false
                main { blocked = controller.activeOperation.value != null &&
                    listOf("Export", "Add to album", "Move to Trash", "More", "Clear selection").all { label ->
                        nodes().any { label in it.labels && SemanticsProperties.Disabled in it.config }
                    } }
                blocked
            })
            main {
                val tile = nodes().first { SemanticsActions.OnLongClick in it.config && "Photo" in it.labels }
                assertTrue(tile.config[SemanticsActions.OnClick].action?.invoke() == true)
                assertTrue(tile.config[SemanticsActions.OnLongClick].action?.invoke() == true)
                activity.onBackPressedDispatcher.onBackPressed()
                val lock = nodes().first { "Lock now" in it.labels && SemanticsActions.OnClick in it.config }
                assertFalse(SemanticsProperties.Disabled in lock.config)
            }
            assertTrue(await(waitForIdle = false) { has("1 selected") && !has("Browse") && controller.route.value == AppRoute.Vault })
            assertSecure()
        } finally {
            release.countDown()
            importer.join(60_000)
        }
        assertFalse("the real import finishes after release", importer.isAlive)
        failure.get()?.let { throw it }
        assertTrue(await { controller.activeOperation.value == null })
        assertSelectionPanel()
    }

    @Test
    fun selection_album_dialog_keeps_panic_available_and_lock_drops_the_panel() {
        importPhoto()
        selectFirstTile()
        click("Add to album")
        assertTrue(await { has("Find destination") && has("Lock and hide") })
        assertSecure()
        click("Lock and hide")
        assertPrivateChromeGone()
        assertFalse(has("Add to album"))
        assertFalse(has("Move to Trash"))
    }

    @Test
    fun library_search_keeps_filters_viewer_back_and_rejects_retained_input_during_private_barriers() {
        val token = "librarysearch${System.nanoTime()}"
        val first = importPhoto("$token-alpha.png")
        val second = importPhoto("$token-beta.png")
        setSearch("Search library", token)
        assertPageIds(first, second)
        main {
            val input = searchNode("Search library").config[SemanticsActions.SetText].action!!
            val oldTile = nodes().first { "Photo" in it.labels && SemanticsActions.OnLongClick in it.config }
            assertTrue(input(AnnotatedString("$token-no-match-before-frame")))
            assertTrue(oldTile.config[SemanticsActions.OnClick].action?.invoke() == true)
            assertTrue(oldTile.config[SemanticsActions.OnLongClick].action?.invoke() == true)
        }
        assertTrue("queued tile events cannot escape the edited query", await {
            searchValue("Search library") == "$token-no-match-before-frame" &&
                controller.page.value.objects.isEmpty() && !has("Info") && !has("1 selected")
        })
        setSearch("Search library", token)
        assertPageIds(first, second)
        click("Videos")
        assertTrue(await { controller.page.value.objects.isEmpty() && has("No matching items. Try another word or remove a filter.") })
        click("Clear search")
        assertTrue(await { searchValue("Search library") == "" })
        click("All")
        assertTrue(await { controller.page.value.objects.any { it.objectId.contentEquals(first) } })
        click("Search")
        setSearch("Search", token)
        assertPageIds(first, second)
        click("Library")
        assertTrue(await { searchValue("Search library") == "" })
        setSearch("Search library", "$token-alpha")
        assertPageIds(first)
        click("Photo")
        assertTrue(await { has("Info") && !has("Search library") })
        click("Back")
        assertTrue(await { has("Search library") && !has("Info") && searchValue("Search library") == "$token-alpha" })
        assertPageIds(first)
        lateinit var retained: (AnnotatedString) -> Boolean
        main {
            retained = searchNode("Search library").config[SemanticsActions.SetText].action!!
            val tile = nodes().single { "Photo" in it.labels && SemanticsActions.OnLongClick in it.config }
            assertTrue(tile.config[SemanticsActions.OnLongClick].action?.invoke() == true)
            assertTrue(retained(AnnotatedString("selection-must-ignore")))
        }
        assertSelectionPanel()
        main {
            val field = nodes().single { "Search library" in it.labels && SemanticsProperties.EditableText in it.config }
            assertTrue("selection disables search input without dropping its text", SemanticsProperties.Disabled in field.config)
        }
        assertEquals("$token-alpha", searchValue("Search library"))
        assertPageIds(first)
        click("Clear selection")
        click("Browse")
        click("Tags…")
        assertTrue(await { has("Close") && has("New tag") })
        main { assertTrue(retained(AnnotatedString("dialog-must-ignore"))) }
        assertEquals("$token-alpha", searchValue("Search library"))
        click("Close")
        assertPageIds(first)
        click("Lock now")
        main { assertTrue(retained(AnnotatedString("locked-must-ignore"))) }
        assertPrivateChromeGone()
        assertTrue(controller.page.value.objects.isEmpty())
    }

    @Test
    fun album_search_filters_native_memberships_and_does_not_retarget_a_retired_scope() {
        val token = "albumsearch${System.nanoTime()}"
        val inside = importPhoto("$token-shared-inside.png")
        val other = importPhoto("$token-other.png")
        val outside = importPhoto("$token-shared-outside.png")
        val name = "Album $token"
        main { controller.createAlbum(name) }
        assertTrue(await { controller.albums.value.any { it.name == name } })
        val album = controller.albums.value.single { it.name == name }
        main { controller.placeObjectsInAlbum(album.albumId, listOf(inside, other)) }
        assertTrue(await { controller.albums.value.any { it.name == name && it.memberCount == 2L } })
        click("Albums")
        // The real vault retains earlier synthetic albums between device runs.
        // Find this destination through its visible organizer filter instead
        // of assuming its lazy row is already in the large-text viewport.
        setSearch("Find album", name)
        click(name)
        assertTrue(await { has("Search album") })
        assertPageIds(inside, other)
        setSearch("Search album", "$token-shared")
        assertPageIds(inside)
        assertFalse(controller.page.value.objects.any { it.objectId.contentEquals(outside) })
        setSearch("Search album", "$token-no-match")
        assertTrue(await { controller.page.value.objects.isEmpty() && has("No matching items. Try another word or remove a filter.") })
        click("Clear search")
        assertPageIds(inside, other)
        assertEquals("", searchValue("Search album"))
        lateinit var retained: (AnnotatedString) -> Boolean
        main { retained = searchNode("Search album").config[SemanticsActions.SetText].action!! }
        click("Back")
        assertTrue(await { !has("Search album") && has("New album") })
        main { assertTrue(retained(AnnotatedString("retired-album"))) }
        click("Library")
        assertTrue(await { searchValue("Search library") == "" })
        setSearch("Search library", "$token-shared")
        assertPageIds(inside, outside)
        assertSecure()
    }

    @Test
    fun panic_is_accessible_and_drops_glass_chrome_before_returning_to_public_shell() {
        click("Albums")
        main {
            val lock = nodes().first { "Lock now" in it.labels && SemanticsActions.CustomActions in it.config }
            assertTrue(lock.config[SemanticsActions.CustomActions].single { it.label == "Lock immediately" }.action())
            assertEquals("panic changes the route synchronously", AppRoute.PublicShell, controller.route.value)
        }
        assertPrivateChromeGone()
    }

    @Test
    fun background_and_resume_keep_secure_window_and_do_not_restore_glass_or_private_navigation() {
        importPhoto()
        selectFirstTile()
        assertSelectionPanel()
        main { assertTrue(activity.moveTaskToBack(true)) }
        assertTrue(await { controller.vaultState.value !is VaultState.Unlocked && controller.route.value != AppRoute.Vault })
        assertSecure()
        // singleTask resumes this instance through onNewIntent; there is no new
        // Activity creation for startActivitySync's launch monitor to observe.
        main {
            activity.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        assertTrue("the existing task resumes", await {
            var resumed = false
            main { resumed = activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            resumed
        })
        assertPrivateChromeGone()
        assertEquals(AppRoute.PublicShell, controller.route.value)
    }

    private fun assertPrivateChromeGone() {
        assertTrue("vault closes and its source/effect subtree leaves composition", await {
            controller.vaultState.value !is VaultState.Unlocked && controller.route.value != AppRoute.Vault &&
                !has("Lock now") && !has("Browse") && !has("1 selected")
        })
        assertFalse(has("More"))
    }

    private fun assertSecure() = main {
        assertTrue("glass must preserve FLAG_SECURE",
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    private fun searchNode(label: String) = nodes().single { label in it.labels && SemanticsActions.SetText in it.config }

    private fun searchValue(label: String): String? {
        var value: String? = null
        main { value = nodes().firstOrNull { label in it.labels && SemanticsProperties.EditableText in it.config }
            ?.config?.getOrNull(SemanticsProperties.EditableText)?.text }
        return value
    }

    private fun setSearch(label: String, value: String) {
        assertTrue(await {
            var ready = false
            main { ready = nodes().any { label in it.labels && SemanticsActions.SetText in it.config &&
                SemanticsProperties.Disabled !in it.config } }
            ready
        })
        main {
            val field = searchNode(label)
            assertFalse(SemanticsProperties.Disabled in field.config)
            assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
        assertTrue(await { searchValue(label) == value })
    }

    private fun assertPageIds(vararg expected: ByteArray) {
        val ids = expected.map { it.toList() }.toSet()
        assertTrue("the real scoped native query returns exactly the expected objects", await {
            controller.page.value.objects.map { it.objectId.toList() }.toSet() == ids &&
                controller.page.value.totalCount == ids.size.toLong()
        })
    }

    private fun selectFirstTile() {
        assertTrue("a real media tile starts selection", await {
            var selected = false
            main {
                nodes().firstOrNull { SemanticsActions.OnLongClick in it.config && "Photo" in it.labels }
                    ?.let { selected = it.config[SemanticsActions.OnLongClick].action?.invoke() == true }
            }
            selected
        })
        assertTrue(await { has("1 selected") && has("Clear selection") })
    }

    private fun assertSelectionPanel() {
        // Controller completion can precede the Compose frame that releases
        // input. Wait for the rendered controls, including selection exit.
        assertTrue("the idle selection panel publishes enabled actions", await {
            var ready = false
            main {
                val snapshot = nodes()
                ready = snapshot.any { "1 selected" in it.labels } &&
                    listOf("Export", "Add to album", "Move to Trash", "More", "Clear selection").all { label ->
                        snapshot.firstOrNull { label in it.labels && SemanticsActions.OnClick in it.config }
                            ?.let { SemanticsProperties.Disabled !in it.config } == true
                    }
            }
            ready
        })
        assertFalse("destination tabs are replaced during selection", has("Albums") || has("Search") || has("Settings"))
        main {
            val header = nodes().first { "1 selected" in it.labels }
            val density = activity.resources.displayMetrics.density
            listOf("Export", "Add to album", "Move to Trash", "More").forEach { label ->
                val action = nodes().first { label in it.labels && SemanticsActions.OnClick in it.config }
                assertTrue("$label is below the selection header", action.boundsInWindow.top > header.boundsInWindow.bottom)
                assertTrue("$label retains a 48dp touch target", action.boundsInWindow.height >= 48f * density - 1f)
                assertFalse("$label is enabled while idle", SemanticsProperties.Disabled in action.config)
                val text = nodes(mergingEnabled = false).first {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { part -> part.text == label } == true
                }
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
                layouts.forEach { layout ->
                    assertEquals("$label is fully laid out", label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                    for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                    for (offset in label.indices) {
                        val glyph = layout.getBoundingBox(offset)
                        assertTrue("$label glyph $offset $glyph fits horizontally in ${text.boundsInWindow}; layout=${layout.size}",
                            glyph.left >= -1f && glyph.right <= text.boundsInWindow.width + 1f)
                        assertTrue("$label glyph $offset $glyph fits vertically in ${text.boundsInWindow}; layout=${layout.size}",
                            glyph.top >= -1f && glyph.bottom <= text.boundsInWindow.height + 1f)
                    }
                }
            }
        }
        assertSecure()
    }

    private fun importPhoto(filename: String = "glass-chrome-synthetic.png", beforeOpen: (() -> Unit)? = null): ByteArray {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "export-scratch/$filename")
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            val codec = AndroidMediaCodec(context.contentResolver)
            val outcome = runBlocking { controller.importMedia(MediaImporter(codec)) { beforeOpen?.invoke(); codec.open(uri) } }
            assertTrue("generated pixels import through the real native vault: $outcome", outcome is MediaImporter.Outcome.Imported)
            main { controller.reportImport(null) }
            assertTrue(await { controller.page.value.objects.isNotEmpty() })
            return (outcome as MediaImporter.Outcome.Imported).objectId.copyOf()
        } finally { file.delete() }
    }

    private fun click(label: String) {
        assertTrue("control exists: $label", await { has(label) })
        main {
            val node = nodes().first { label in it.labels && SemanticsActions.OnClick in it.config }
            assertFalse(SemanticsProperties.Disabled in node.config)
            assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        instrumentation.waitForIdleSync()
    }

    private fun has(label: String): Boolean {
        var found = false
        main { found = nodes().any { label in it.labels } }
        return found
    }

    private fun nodes(mergingEnabled: Boolean = true) = WindowInspector.getGlobalWindowViews().flatMap { roots(it) }
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = mergingEnabled) }

    private fun roots(view: View): List<ViewRootForTest> = when {
        view is ViewRootForTest -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
        else -> emptyList()
    }

    private val SemanticsNode.labels: List<String>
        get() = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(timeout: Long = 10_000, waitForIdle: Boolean = true, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (waitForIdle) instrumentation.waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(40)
        }
        return condition()
    }

    private companion object {
        const val PASSWORD = "BackNavigationTest-password"
    }
}
