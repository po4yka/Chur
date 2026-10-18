package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.VaultActions
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.VaultShell
import dev.po4yka.chur.app.vault.VaultUiState
import dev.po4yka.chur.app.vault.ContentView
import dev.po4yka.chur.app.vault.LibraryTile
import dev.po4yka.chur.app.vault.MoveAlbumDialog
import dev.po4yka.chur.app.vault.loadedObjectSelection
import dev.po4yka.chur.app.vault.albumParent
import dev.po4yka.chur.app.vault.albumPath
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectProjection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public fictional catalog through production VaultShell; no real private album names. */
@RunWith(AndroidJUnit4::class)
class AlbumNavigationPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun direct_children_and_clickable_ancestors_navigate_by_id_and_root_returns_to_albums() = present { f ->
        f.open(f.root)
        assertTrue(f.await { f.tag(f.childTag(f.child)) != null })
        assertTrue(f.tag(f.childTag(f.leaf)) == null)
        assertTrue(f.tag(f.childTag(f.otherRoot)) == null)
        f.clickTag(f.childTag(f.child))
        assertTrue(f.await { f.currentId() == f.child.id && f.tag(f.childTag(f.leaf)) != null })
        f.clickTag(f.childTag(f.leaf))
        assertTrue(f.await { f.tag(f.currentTag(f.leaf)) != null })
        f.revealAncestor(f.root)
        f.clickTag(f.ancestorTag(f.root))
        assertTrue(f.await { f.currentId() == f.root.id })
        f.clickTag("album-breadcrumb-root")
        assertTrue(f.await { f.currentId() == null })
        assertEquals(VaultDestination.ALBUMS, f.state.value.destination)
        assertTrue(f.tag("album-breadcrumb-root") == null)
    }

    @Test
    fun android_back_clears_selection_then_visits_each_parent_before_leaving_the_tab() = present { f ->
        f.open(f.leaf)
        f.main { f.state.value = f.state.value.copy(selectedCount = 2) }
        assertTrue(f.await { f.tag("album-breadcrumb-root") == null })
        f.back()
        assertTrue(f.await { f.state.value.selectedCount == 0 && f.currentId() == f.leaf.id })
        assertTrue(f.await { f.tag(f.currentTag(f.leaf)) != null })
        f.back()
        assertTrue(f.await { f.currentId() == f.child.id })
        f.back()
        assertTrue(f.await { f.currentId() == f.root.id })
        f.back()
        assertTrue(f.await { f.currentId() == null && f.state.value.destination == VaultDestination.ALBUMS })
        f.back()
        assertTrue(f.await { f.state.value.destination == VaultDestination.LIBRARY })
        assertEquals(listOf("selection", "parent", "parent", "parent", "tab:LIBRARY"), f.steps)
    }

    @Test
    fun narrow_double_font_path_keeps_full_long_names_scrollable_and_disambiguates_duplicate_names() =
        present(fontScale = 2f, longNames = true) { f ->
            f.open(f.leaf)
            assertTrue(f.await { f.tag(f.currentTag(f.leaf)) != null })
            f.assertFullText("album-breadcrumb-root", "Albums")
            f.assertCurrentSemantics(f.leaf)
            f.revealAncestor(f.root)
            f.assertFullText(f.ancestorTag(f.root), f.root.name)
            f.revealGlyph(f.ancestorTag(f.root), 0)
            f.revealGlyph(f.ancestorTag(f.root), f.root.name.lastIndex)
            f.capture("album-path-long-ancestor-public-320-font2.png")
            f.revealAncestor(f.child)
            f.assertFullText(f.ancestorTag(f.child), f.child.name)
            f.clickTag(f.ancestorTag(f.child))
            assertTrue(f.await { f.currentId() == f.child.id })
            // The leaf has the same name as its parent; the tag identifies the actual child.
            assertEquals(f.child.name, f.leaf.name)
            f.clickTag(f.childTag(f.leaf))
            assertTrue(f.await { f.currentId() == f.leaf.id })
            f.assertCurrentSemantics(f.leaf)
            f.capture("album-path-current-public-320-font2.png")
        }

    @Test
    fun live_catalog_rename_move_and_removal_replace_the_path_and_lock_saves_no_private_path() = present { f ->
        f.open(f.leaf)
        val renamed = f.child.copy(name = "Renamed public ancestor")
        f.updateCatalog(f.state.value.albums.map { if (it.id == renamed.id) renamed else it })
        f.revealAncestor(renamed)
        f.assertFullText(f.ancestorTag(renamed), renamed.name)
        val moved = f.leaf.copy(parentId = f.otherRoot.albumId)
        f.updateCatalog(f.state.value.albums.map { if (it.id == moved.id) moved else it })
        assertTrue(f.await { f.tag(f.currentTag(moved)) != null })
        f.revealAncestor(f.otherRoot)
        assertTrue(f.tag(f.ancestorTag(renamed)) == null)
        f.clickTag(f.ancestorTag(f.otherRoot))
        assertTrue(f.await { f.currentId() == f.otherRoot.id })
        f.updateCatalog(f.state.value.albums.filterNot { it.id == f.otherRoot.id || it.id == moved.id })
        assertTrue(f.await { f.currentId() == null && f.tag("album-breadcrumb-root") == null })
        f.open(f.root)
        f.assertNoSavedPath()
        f.main { f.actions.onLock() }
        assertTrue(f.await { f.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("album-breadcrumb") == true } })
        f.assertNoSavedPath()
        f.main { f.shown.value = true }
        assertTrue(f.await { f.nodes().isNotEmpty() })
        assertTrue(f.tag("album-breadcrumb-root") == null)
        f.assertNoSavedPath()
    }

    @Test
    fun move_picker_uses_full_paths_and_search_to_choose_the_correct_duplicate_at_double_font() =
        present(fontScale = 2f) { f ->
            val family = album(10, "Family")
            val work = album(11, "Work")
            val familyYear = album(12, "2026", family)
            val workYear = album(13, "2026", work)
            val source = album(14, "To move")
            val child = album(15, "Source child", source)
            f.main {
                f.state.value = f.state.value.copy(albums = listOf(family, work, familyYear, workYear, source, child))
                f.moving.value = source
            }
            assertTrue(f.await { f.tag("album-move-filter") != null })
            f.enterDestination("2026")
            f.revealMoveDestination(1, familyYear)
            f.assertFullText("album-move-${familyYear.id}", "Family / 2026")
            f.revealMoveDestination(2, workYear)
            f.assertFullText("album-move-${workYear.id}", "Work / 2026")
            f.main {
                assertEquals(listOf("Move album to Work / 2026"),
                    f.tagOnMain("album-move-${workYear.id}")!!.config.getOrNull(SemanticsProperties.ContentDescription))
            }
            f.enterDestination("Work / 2026")
            assertTrue(f.await { f.tag("album-move-${familyYear.id}") == null &&
                f.tag("album-move-${workYear.id}") != null })
            f.clickTag("album-move-${workYear.id}")
            assertEquals(workYear.id, f.chosenParent?.id)
        }

    @Test
    fun selection_menu_states_loaded_scope_when_more_than_one_page_exists() = present { f ->
        val all = (1..300).map { id -> ObjectProjection(
            byteArrayOf((id ushr 8).toByte(), id.toByte()), byteArrayOf(), 1, 0, 0,
            false, 1, 1, 1, 0, false, 0, 0, false,
        ) }
        f.main {
            f.page.value = ObjectPage(all.take(128), 300, 1, byteArrayOf(1))
            f.selection = setOf(all.first().id)
            f.state.value = f.state.value.copy(destination = VaultDestination.LIBRARY,
                tiles = all.take(128).map { LibraryTile(it, null, it.id in f.selection) },
                mediaView = ContentView.LIST, selectedCount = 1, canLoadMore = true)
        }
        f.clickText("More")
        assertTrue(f.await { f.button("Select loaded items") != null })
        assertTrue(f.button("Select all") == null)
        f.clickText("Select loaded items")
        assertTrue(f.await { f.selection.size == 128 && f.state.value.selectedCount == 128 })
        assertFalse(all[128].id in f.selection)
        f.main {
            f.page.value = f.page.value.copy(objects = all.take(256), nextCursor = byteArrayOf(2))
            f.state.value = f.state.value.copy(tiles = all.take(256).map {
                LibraryTile(it, null, it.id in f.selection)
            })
        }
        f.clickText("More")
        f.clickText("Select loaded items")
        assertTrue(f.await { f.selection.size == 256 && f.state.value.selectedCount == 256 })
        assertFalse(all[256].id in f.selection)
    }

    private fun present(fontScale: Float = 1f, longNames: Boolean = false, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val f = Fixture(activity, fontScale, longNames)
        try {
            f.main {
                val density = activity.resources.displayMetrics.density
                val config = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320; screenHeightDp = 640; this.fontScale = fontScale
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(config) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale), LocalConfiguration provides config,
                            LocalSaveableStateRegistry provides f.registry) {
                            ChurTheme(dark = false) {
                                if (f.shown.value) VaultShell(f.state.value, f.actions,
                                    systemBack = { enabled, back -> BackHandler(enabled, back) })
                                f.moving.value?.let { album -> MoveAlbumDialog(f.state.value.albums, album,
                                    onChoose = { f.chosenParent = it; f.moving.value = null },
                                    onDismiss = { f.moving.value = null }) }
                            }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(f.view, FrameLayout.LayoutParams((320 * density).toInt(), (640 * density).toInt()))
                })
            }
            test(f)
        } finally { f.main { f.view.disposeComposition(); activity.finish() } }
    }

    private inner class Fixture(val activity: MainActivity, val fontScale: Float, longNames: Boolean) {
        val root = album(1, if (longNames) "Public ancestor with a very long complete name — family holidays and recordings" else "Public Root")
        val child = album(2, if (longNames) "Repeated public album name" else "Public Child", root)
        val leaf = album(3, if (longNames) child.name else "Public Leaf", child)
        val otherRoot = album(4, "Another public Root")
        val all = listOf(root, child, leaf, otherRoot)
        val state = mutableStateOf(VaultUiState(destination = VaultDestination.ALBUMS, albums = all))
        val shown = mutableStateOf(true)
        val moving = mutableStateOf<AlbumSummary?>(null)
        var chosenParent: AlbumSummary? = null
        val page = mutableStateOf(ObjectPage(emptyList(), 0, 1, null))
        var selection = emptySet<String>()
        val registry = SaveableStateRegistry(emptyMap()) { true }
        val steps = mutableListOf<String>()
        lateinit var view: ComposeView
        val actions = VaultActions(onDestination = {
            steps += "tab:$it"; state.value = state.value.copy(destination = it, openAlbum = null, selectedCount = 0)
        }, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {}, onOpenAlbum = { navigate(it) },
            onCloseAlbum = {
                steps += "parent"
                val parent = albumParent(state.value.albums, state.value.openAlbum?.id)
                state.value = state.value.copy(openAlbum = parent, selectedCount = 0)
            }, onAlbumRoot = { state.value = state.value.copy(destination = VaultDestination.ALBUMS, openAlbum = null, selectedCount = 0) },
            onCreateAlbum = {}, onLock = { state.value = VaultUiState(); shown.value = false }, onVerifyAll = {},
            onAddRecoverySlot = {}, onChangePassword = {},
            onSelectLoadedItems = {
                selection = loadedObjectSelection(page.value)
                state.value = state.value.copy(selectedCount = selection.size,
                    tiles = state.value.tiles.map { it.copy(selected = it.projection.id in selection) })
            },
            onClearSelection = { steps += "selection"; state.value = state.value.copy(selectedCount = 0) })

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
        fun nodes(): List<SemanticsNode> { var result = emptyList<SemanticsNode>(); main { result = nodesOnMain(false) }; return result }
        fun nodesOnMain(merged: Boolean) = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged) }
        fun tag(value: String): SemanticsNode? { var node: SemanticsNode? = null; main { node = tagOnMain(value) }; return node }
        fun tagOnMain(value: String) = nodesOnMain(true).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun ancestorTag(album: AlbumSummary) = "album-breadcrumb-${album.id}"
        fun currentTag(album: AlbumSummary) = "album-breadcrumb-current-${album.id}"
        fun childTag(album: AlbumSummary) = "album-child-${album.id}"
        fun currentId() = state.value.openAlbum?.id
        fun navigate(album: AlbumSummary) { state.value = state.value.copy(openAlbum = album, selectedCount = 0) }
        fun open(album: AlbumSummary) { main { navigate(album) }; settleFrames() }
        fun updateCatalog(albums: List<AlbumSummary>) {
            main {
                val current = state.value.openAlbum?.id?.let { id -> albums.firstOrNull { it.id == id } }
                    ?.takeIf { albumPath(albums, it.id).isNotEmpty() }
                state.value = state.value.copy(albums = albums, openAlbum = current)
            }
            settleFrames()
        }
        fun clickTag(value: String) {
            assertTrue("tag must be reachable: $value", await { tag(value) != null })
            main {
                val node = requireNotNull(tagOnMain(value))
                assertFalse(SemanticsProperties.Disabled in node.config)
                assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            }
            settleFrames()
        }
        fun enterDestination(value: String) {
            main { assertTrue(requireNotNull(tagOnMain("album-move-filter")).config
                .getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true) }
            settleFrames()
        }
        fun revealMoveDestination(index: Int, album: AlbumSummary) {
            main { assertTrue(requireNotNull(tagOnMain("album-move-destinations")).config
                .getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(index) == true) }
            settleFrames()
            assertTrue(await { tag("album-move-${album.id}") != null })
        }
        fun button(text: String): SemanticsNode? {
            var result: SemanticsNode? = null
            main { result = nodesOnMain(true).singleOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                    SemanticsActions.OnClick in node.config
            } }
            return result
        }
        fun clickText(text: String) {
            assertTrue("$text must be reachable", await { button(text) != null })
            val node = requireNotNull(button(text))
            main { assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true) }
            settleFrames()
        }
        fun back() {
            settleFrames()
            main {
                assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                activity.onBackPressedDispatcher.onBackPressed()
            }
            settleFrames()
        }
        fun revealAncestor(album: AlbumSummary) {
            val index = albumPath(state.value.albums, state.value.openAlbum?.id).indexOfFirst { it.id == album.id }
            assertTrue(index >= 0)
            main { assertTrue(requireNotNull(tagOnMain("album-breadcrumb-path")).config
                .getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(index) == true) }
            assertTrue(await { tag(ancestorTag(album)) != null })
            settleFrames()
        }
        private fun textOnMain(tag: String) = nodesOnMain(false).first { node ->
            SemanticsActions.GetTextLayoutResult in node.config &&
                generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        }
        private fun layout(node: SemanticsNode): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
            return layouts.single()
        }
        fun assertFullText(tag: String, value: String) = main {
            val text = textOnMain(tag)
            val result = layout(text)
            assertEquals(fontScale, result.layoutInput.density.fontScale, 0.01f)
            assertEquals(value, result.layoutInput.text.text)
            assertEquals(value.length, result.getLineEnd(result.lineCount - 1, visibleEnd = true))
            for (line in 0 until result.lineCount) assertFalse("ancestor/action must not ellipsize", result.isLineEllipsized(line))
            assertFalse("text must not overflow vertically", result.didOverflowHeight)
        }
        fun revealGlyph(tag: String, offset: Int) {
            main {
                val node = textOnMain(tag)
                val box = layout(node).getBoundingBox(offset)
                val viewport = requireNotNull(tagOnMain("album-breadcrumb-path")).boundsInWindow
                val left = node.positionInWindow.x + box.left
                val right = node.positionInWindow.x + box.right
                val delta = when { left < viewport.left -> left - viewport.left; right > viewport.right -> right - viewport.right; else -> 0f }
                if (delta != 0f) assertTrue(requireNotNull(tagOnMain("album-breadcrumb-path")).config
                    .getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(delta, 0f) == true)
            }
            assertTrue("glyph $offset must be reachable through horizontal scrolling", await {
                var visible = false
                main {
                    val node = textOnMain(tag)
                    val box = layout(node).getBoundingBox(offset)
                    val viewport = requireNotNull(tagOnMain("album-breadcrumb-path")).boundsInWindow
                    visible = node.positionInWindow.x + box.left >= viewport.left - 1f &&
                        node.positionInWindow.x + box.right <= viewport.right + 1f &&
                        node.positionInWindow.y + box.top >= viewport.top - 1f &&
                        node.positionInWindow.y + box.bottom <= viewport.bottom + 1f
                }
                visible
            })
            settleFrames()
        }
        fun assertCurrentSemantics(album: AlbumSummary) = main {
            val node = requireNotNull(tagOnMain(currentTag(album)))
            assertTrue(node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(album.name) == true)
            assertEquals("Current album", node.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(true, node.config.getOrNull(SemanticsProperties.Selected))
            assertFalse(SemanticsActions.OnClick in node.config)
        }
        fun assertNoSavedPath() = main {
            val saved = registry.performSave().toString()
            for (album in all) {
                assertFalse("album names must not enter SavedState", saved.contains(album.name))
                assertFalse("album IDs must not enter SavedState", saved.contains(album.id))
            }
        }
        fun settleFrames() {
            val ready = CountDownLatch(1)
            main { view.postOnAnimation { view.postOnAnimation { ready.countDown() } } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        fun capture(name: String) {
            settleFrames()
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val location = IntArray(2); main { view.getLocationOnScreen(location) }
            val crop = Bitmap.createBitmap(screen, location[0], location[1], view.width.coerceAtMost(screen.width - location[0]),
                view.height.coerceAtMost(screen.height - location[1]))
            File(activity.getExternalFilesDir(null), name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            crop.recycle(); screen.recycle()
        }
    }
    private fun View.roots(): List<ViewRootForTest> = listOfNotNull(this as? ViewRootForTest) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).roots() } else emptyList()
    private fun album(number: Int, name: String, parent: AlbumSummary? = null) =
        AlbumSummary(ByteArray(16) { number.toByte() }, 0, name, parent?.albumId)
}
