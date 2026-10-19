@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.ffi.AlbumSummary
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIButton
import platform.UIKit.UISearchController
import platform.UIKit.UITextAutocapitalizationType.UITextAutocapitalizationTypeNone
import platform.UIKit.UITextAutocorrectionType.UITextAutocorrectionTypeNo
import platform.UIKit.UITextInlinePredictionType.UITextInlinePredictionTypeNo
import platform.UIKit.UITextSpellCheckingType.UITextSpellCheckingTypeNo
import platform.UIKit.UINavigationItemSearchBarPlacement.UINavigationItemSearchBarPlacementStacked
import platform.UIKit.UIViewController
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.accessibilityLabel
import platform.UIKit.navigationItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosVaultSearchTest {
    @Test
    fun native_search_uses_current_compose_results_and_preserves_controller_on_each_keystroke() {
        val queries = mutableListOf<String>()
        val callbacks = actions().copy(onSearch = { queries += it })
        val chrome = prepare(callbacks)
        val native = search(chrome)
        val updater = native.searchResultsUpdater!!
        val field = native.searchBar.searchTextField
        assertFalse(native.obscuresBackgroundDuringPresentation)
        assertFalse(native.hidesNavigationBarDuringPresentation)
        assertNull(native.searchResultsController)
        assertFalse(chrome.navigationBar.topItem!!.hidesSearchBarWhenScrolling)
        assertEquals(UINavigationItemSearchBarPlacementStacked, chrome.navigationBar.topItem!!.preferredSearchBarPlacement)
        native.searchBar.text = "p"
        updater.updateSearchResultsForSearchController(native)
        chrome.update(VaultUiState(searchTerms = "p"), callbacks, Color.Blue, false)
        assertEquals(native, search(chrome))
        assertEquals(field, search(chrome).searchBar.searchTextField)
        native.searchBar.text = "private"
        updater.updateSearchResultsForSearchController(native)
        chrome.update(VaultUiState(searchTerms = "private"), callbacks, Color.Blue, false)
        updater.updateSearchResultsForSearchController(native)
        assertEquals(listOf("p", "private"), queries)
        assertEquals(native, search(chrome))
        assertNotNull(chrome.navigationBar.topItem!!.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>()
            .mapNotNull { it.customView as? UIButton }.single { it.accessibilityLabel == "Lock now" })
        chrome.clear()
    }

    @Test
    fun query_loading_preserves_typing_but_navigation_suspension_retires_the_delegate() {
        val queries = mutableListOf<String>()
        val callbacks = actions().copy(onSearch = { queries += it })
        val chrome = prepare(callbacks)
        val native = search(chrome)
        val updater = native.searchResultsUpdater!!
        chrome.suspendNavigation(preserveSearch = true)
        assertEquals(native, search(chrome))
        native.searchBar.text = "still typing"
        updater.updateSearchResultsForSearchController(native)
        assertEquals(listOf("still typing"), queries)
        chrome.update(VaultUiState(searchTerms = "still typing"), callbacks, Color.Blue, false)
        assertEquals(native, search(chrome))
        chrome.suspendNavigation()
        assertRetired(native)
        native.searchBar.text = "old query"
        updater.updateSearchResultsForSearchController(native)
        chrome.update(VaultUiState(searchTerms = "still typing"), callbacks, Color.Blue, false)
        assertFalse(native == search(chrome))
        assertEquals(listOf("still typing"), queries)
        chrome.clear()
    }

    @Test
    fun cancel_clears_shared_terms_and_same_screen_reentry_rejects_old_delegate() {
        val queries = mutableListOf<String>()
        val callbacks = actions().copy(onSearch = { queries += it })
        val chrome = prepare(callbacks)
        chrome.update(VaultUiState(searchTerms = "private query"), callbacks, Color.Blue, false)
        val native = search(chrome)
        val updater = native.searchResultsUpdater!!
        val delegate = native.searchBar.delegate!!
        delegate.searchBarCancelButtonClicked(native.searchBar)
        assertEquals(listOf(""), queries)
        assertRetired(native)
        assertNull(chrome.navigationBar.topItem!!.searchController)
        chrome.update(VaultUiState(), callbacks, Color.Blue, false)
        assertFalse(native == search(chrome))
        native.searchBar.text = "stale"
        updater.updateSearchResultsForSearchController(native)
        delegate.searchBarCancelButtonClicked(native.searchBar)
        assertFalse(delegate.searchBarShouldBeginEditing(native.searchBar))
        assertEquals(listOf(""), queries)
        chrome.clear()
    }

    @Test
    fun selection_modal_operation_and_trash_remove_native_search_and_do_not_revive_copies() {
        for (barrier in listOf("selection", "modal", "busy", "operation", "trash")) {
            var updates = 0
            val callbacks = actions().copy(onSearch = { updates++ })
            val chrome = prepare(callbacks)
            val native = search(chrome)
            val updater = native.searchResultsUpdater!!
            val delegate = native.searchBar.delegate!!
            val state = when (barrier) {
                "selection" -> VaultUiState(selectedCount = 1, selectionIds = setOf("A"))
                "operation" -> VaultUiState(operation = ActiveOperation(1, "export"))
                "trash" -> VaultUiState(trashOpen = true, libraryScopeTitle = "Trash")
                else -> VaultUiState()
            }
            chrome.update(state, callbacks, Color.Blue, barrier == "busy", covered = barrier == "modal")
            assertRetired(native)
            assertNull(chrome.navigationBar.topItem!!.searchController)
            native.searchBar.text = "blocked"
            updater.updateSearchResultsForSearchController(native)
            delegate.searchBarCancelButtonClicked(native.searchBar)
            assertEquals(0, updates, barrier)
            chrome.update(VaultUiState(), callbacks, Color.Blue, false)
            updater.updateSearchResultsForSearchController(native)
            assertFalse(delegate.searchBarShouldBeginEditing(native.searchBar))
            assertEquals(0, updates, "$barrier after unblock")
            assertFalse(native == search(chrome))
            chrome.clear()
        }
    }

    @Test
    fun viewer_tab_and_album_scope_changes_retire_private_text_before_navigation() {
        var updates = 0
        val callbacks = actions().copy(onSearch = { updates++ })
        val chrome = prepare(callbacks)
        chrome.update(VaultUiState(searchTerms = "private"), callbacks, Color.Blue, false)
        val library = search(chrome)
        val libraryUpdater = library.searchResultsUpdater!!
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY), IosVaultScreen.Viewer(3)),
            VaultDestination.LIBRARY, animated = false)
        assertRetired(library)
        library.searchBar.text = "stale"
        libraryUpdater.updateSearchResultsForSearchController(library)
        assertEquals(0, updates)
        val album = AlbumSummary(ByteArray(16) { 3 }, 0, "Album")
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS), IosVaultScreen.Album(album.id)),
            VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS, openAlbum = album, searchTerms = "album query"),
            callbacks, Color.Blue, false)
        val nativeAlbum = search(chrome)
        assertEquals("Search album", nativeAlbum.searchBar.placeholder)
        assertEquals("album query", nativeAlbum.searchBar.text)
        assertFalse(chrome.tabBarController(chrome.controller, shouldSelectViewController = chrome.navigationControllers.first()))
        assertRetired(nativeAlbum)
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS)), VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), callbacks, Color.Blue, false)
        assertNull(chrome.navigationBar.topItem!!.searchController)
        chrome.clear()
    }

    @Test
    fun cancelled_native_pop_restores_search_with_new_delegate_and_keeps_shared_query() {
        val chrome = prepare()
        val album = AlbumSummary(ByteArray(16) { 4 }, 0, "Album")
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS), IosVaultScreen.Album(album.id)),
            VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS, openAlbum = album, searchTerms = "album query"),
            actions(), Color.Blue, false)
        val nav = chrome.navigationControllers[VaultDestination.ALBUMS.ordinal]
        val top = nav.topViewController!!
        val previous = search(chrome)
        val updater = previous.searchResultsUpdater!!
        val parent = nav.viewControllers!!.first() as UIViewController
        chrome.navigationController(nav, willShowViewController = parent, animated = true)
        assertRetired(previous)
        chrome.navigationController(nav, willShowViewController = top, animated = true)
        chrome.navigationController(nav, didShowViewController = top, animated = true)
        val restored = search(chrome)
        assertFalse(previous == restored)
        assertEquals("album query", restored.searchBar.text)
        previous.searchBar.text = "stale"
        updater.updateSearchResultsForSearchController(previous)
        assertEquals("album query", restored.searchBar.text)
        chrome.clear()
    }

    @Test
    fun lock_clears_native_field_and_old_same_generation_visit_cannot_search_again() {
        var queries = 0
        var locks = 0
        val callbacks = actions().copy(onSearch = { queries++ }, onLock = { locks++ })
        val chrome = prepare(callbacks)
        chrome.update(VaultUiState(searchTerms = "secret search"), callbacks, Color.Blue, false)
        val previous = search(chrome)
        val updater = previous.searchResultsUpdater!!
        val delegate = previous.searchBar.delegate!!
        chrome.lockNow()
        assertEquals(1, locks)
        assertRetired(previous)
        assertTrue(chrome.controller.view.hidden)
        assertTrue(chrome.controller.view.accessibilityElementsHidden)
        chrome.beginPresentation(1)
        configure(chrome)
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY, animated = false)
        chrome.update(VaultUiState(), callbacks, Color.Blue, false)
        previous.searchBar.text = "stale visit"
        updater.updateSearchResultsForSearchController(previous)
        delegate.searchBarCancelButtonClicked(previous.searchBar)
        assertEquals(0, queries)
        assertEquals("", search(chrome).searchBar.text.orEmpty())
        chrome.clear()
    }

    @Test
    fun equal_title_tag_scopes_revoke_the_old_native_delegate() {
        val queries = mutableListOf<String>()
        val callbacks = actions().copy(onSearch = { queries += it })
        val chrome = prepare(callbacks)
        val firstScope = BrowseSearchScope(VaultDestination.LIBRARY, null, "tag-A", false, false, false, false)
        val state = VaultUiState(libraryScopeTitle = "Same name", searchContext = firstScope)
        chrome.update(state, callbacks, Color.Blue, false)
        val first = search(chrome)
        val updater = first.searchResultsUpdater!!
        chrome.update(state.copy(searchContext = firstScope.copy(tag = "tag-B")), callbacks, Color.Blue, false)
        assertRetired(first)
        first.searchBar.text = "old scope"
        updater.updateSearchResultsForSearchController(first)
        assertTrue(queries.isEmpty())
        val second = search(chrome)
        second.searchBar.text = "new scope"
        second.searchResultsUpdater!!.updateSearchResultsForSearchController(second)
        assertEquals(listOf("new scope"), queries)
        chrome.clear()
    }

    @Test
    fun native_search_keyboard_disables_private_text_assistance() {
        val chrome = prepare()
        val field = search(chrome).searchBar.searchTextField
        assertEquals(UITextAutocapitalizationTypeNone, field.autocapitalizationType)
        assertEquals(UITextAutocorrectionTypeNo, field.autocorrectionType)
        assertEquals(UITextSpellCheckingTypeNo, field.spellCheckingType)
        assertEquals(UITextInlinePredictionTypeNo, field.inlinePredictionType)
        assertNull(field.textContentType)
        chrome.clear()
    }

    private fun assertRetired(native: UISearchController) {
        assertEquals("", native.searchBar.text.orEmpty())
        assertNull(native.searchResultsUpdater)
        assertNull(native.searchBar.delegate)
        assertFalse(native.active)
        assertFalse(native.searchBar.searchTextField.isFirstResponder)
        assertTrue(native.searchBar.hidden)
        assertTrue(native.searchBar.accessibilityElementsHidden)
        assertTrue(native.view.accessibilityElementsHidden)
    }
    private fun search(chrome: IosVaultChrome) = assertNotNull(chrome.navigationBar.topItem!!.searchController)
    private fun actions() = VaultActions(onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
        onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {}, onAddRecoverySlot = {}, onChangePassword = {})
    private fun configure(chrome: IosVaultChrome) = chrome.configureScreens({ UIViewController() }, {}, {})
    private fun prepare(callbacks: VaultActions = actions()) = IosVaultChrome().also {
        configure(it)
        it.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY, animated = false)
        it.update(VaultUiState(), callbacks, Color.Blue, false)
    }
}
