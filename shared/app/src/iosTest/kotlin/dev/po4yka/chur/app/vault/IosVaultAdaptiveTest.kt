@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIViewController
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.navigationItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosVaultAdaptiveTest {
    @Test
    fun resize_moves_the_same_viewer_and_album_leases_without_retiring_or_committing_back() {
        val chrome = IosVaultChrome()
        val created = mutableMapOf<IosVaultScreen, UIViewController>()
        val retired = mutableListOf<IosVaultScreen>()
        val popped = mutableListOf<IosVaultScreen>()
        val visible = mutableListOf<Set<IosVaultScreen>>()
        chrome.configureScreens(factory = { UIViewController().also { content -> created[it] = content } },
            onPop = { popped += it }, onRetire = { retired += it })
        chrome.onVisibleScreens = { visible += it }
        val root = IosVaultScreen.Root(VaultDestination.ALBUMS)
        val album = IosVaultScreen.Album("album")
        val viewer = IosVaultScreen.Viewer(7)
        chrome.updateGeometry(390.0, 844.0)
        chrome.reconcile(1, listOf(root, album, viewer), VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions(), Color.Blue, false)
        val albumController = created.getValue(album)
        val viewerController = created.getValue(viewer)
        val count = created.size
        assertEquals(setOf(viewer), visible.last())
        chrome.updateGeometry(900.0, 700.0, divisions = listOf(AdaptiveRegion(450.0, 0.0, 0.0, 700.0)))
        assertTrue(chrome.dualPane)
        assertEquals(albumController, chrome.navigationControllers[VaultDestination.ALBUMS.ordinal].topViewController)
        assertEquals(viewerController, chrome.detailNavigation.topViewController)
        assertEquals(setOf(album, viewer), visible.last())
        assertFalse(albumController.view.accessibilityElementsHidden)
        assertFalse(viewerController.view.accessibilityElementsHidden)
        assertFalse(chrome.tabBar.hidden)
        assertTrue(chrome.detailNavigation.interactivePopGestureRecognizer?.enabled != true)
        chrome.updateGeometry(390.0, 844.0)
        assertFalse(chrome.dualPane)
        assertEquals(viewerController, chrome.navigationControllers[VaultDestination.ALBUMS.ordinal].topViewController)
        assertTrue(chrome.detailNavigation.viewControllers.isNullOrEmpty())
        assertEquals(setOf(viewer), visible.last())
        assertEquals(count, created.size)
        assertTrue(retired.isEmpty())
        assertTrue(popped.isEmpty())
        chrome.clear()
        assertEquals(count, retired.size)
        assertTrue(chrome.presentationController.view.hidden)
        assertTrue(chrome.presentationController.view.accessibilityElementsHidden)
        chrome.updateGeometry(900.0, 700.0)
        assertTrue(chrome.presentationController.view.hidden)
        assertTrue(chrome.detailNavigation.viewControllers.isNullOrEmpty())
    }

    @Test
    fun expanded_library_and_viewer_have_independent_bars_and_both_retire_before_lock() {
        val chrome = IosVaultChrome()
        var imports = 0
        var info = 0
        var locks = 0
        chrome.configureScreens(factory = { UIViewController() }, onPop = {}, onRetire = {})
        chrome.updateGeometry(900.0, 700.0)
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS), IosVaultScreen.Viewer(7)),
            VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions().copy(
            onCreateAlbum = { imports++ }, onLock = {
                assertTrue(chrome.presentationController.view.hidden)
                assertTrue(chrome.detailNavigation.viewControllers.isNullOrEmpty())
                locks++
            }), Color.Blue, false)
        chrome.updateViewer(IosViewerPanelState(1, 7, "media", "Private image", false, false, false, true),
            IosViewerPanelActions(onBack = {}, onTouch = {}, onInfo = { info++ }, onFavorite = {},
                onExport = {}, onTags = {}, onDelete = {}, onRestore = {}), Color.Blue)
        assertEquals("Albums", chrome.navigationBar.topItem?.title)
        assertEquals("Private image", chrome.detailNavigation.navigationBar.topItem?.title)
        chrome.performPrimary()
        assertEquals(1, imports)
        val item = chrome.detailNavigation.topViewController!!.navigationItem.rightBarButtonItems!!
            .filterIsInstance<UIBarButtonItem>().single { it.title == "Info" }
        val sender = UIButton.buttonWithType(UIButtonTypeSystem)
        sender.sendAction(item.primaryAction!!)
        assertEquals(1, info)
        chrome.setPrivateModalActive(true)
        sender.sendAction(item.primaryAction!!)
        assertEquals(1, info)
        chrome.lockNow()
        assertEquals(1, locks)
        sender.sendAction(item.primaryAction!!)
        assertEquals(1, info)
    }

    private fun actions() = VaultActions(
        onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
        onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
        onAddRecoverySlot = {}, onChangePassword = {},
    )
}
