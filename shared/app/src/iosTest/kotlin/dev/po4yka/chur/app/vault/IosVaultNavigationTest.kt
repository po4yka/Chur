@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import platform.UIKit.UINavigationController
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.navigationItem
import platform.UIKit.hidesBottomBarWhenPushed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosVaultNavigationTest {
    @Test
    fun genuine_native_containers_reconcile_album_ancestry_and_keep_viewer_identity_when_paging() {
        val scene = Scene()
        val root = IosVaultScreen.Root(VaultDestination.ALBUMS)
        val parent = IosVaultScreen.Album("parent")
        val child = IosVaultScreen.Album("child")
        val viewer = IosVaultScreen.Viewer(1L)
        scene.show(listOf(root, parent, child), VaultDestination.ALBUMS)
        val nav = scene.chrome.navigationControllers[VaultDestination.ALBUMS.ordinal]
        assertEquals(4, scene.chrome.controller.viewControllers!!.size)
        assertTrue(scene.chrome.controller.viewControllers!!.all { it is UINavigationController })
        assertEquals(listOf(root, parent, child).map(scene.controllers::getValue), nav.viewControllers)
        scene.show(listOf(root, parent, child, viewer), VaultDestination.ALBUMS)
        val viewerController = nav.topViewController
        val creations = scene.created.size
        scene.show(listOf(root, parent, child, viewer), VaultDestination.ALBUMS)
        // ObjC getters can create a new Kotlin wrapper for the same native
        // controller. UIViewController's NSObject equality checks its identity.
        assertTrue(viewerController!!.isEqual(nav.topViewController))
        assertEquals(creations, scene.created.size)
        assertTrue(nav.navigationBarHidden)
        assertTrue(scene.chrome.tabBar.hidden)
        assertTrue(viewerController!!.hidesBottomBarWhenPushed)
        assertTrue(nav.interactivePopGestureRecognizer!!.enabled)
        if (ios26()) assertFalse(nav.interactiveContentPopGestureRecognizer!!.enabled)
        assertTrue(scene.controllers.getValue(child).view.accessibilityElementsHidden)
        scene.chrome.clear()
    }

    @Test
    fun completed_native_pops_commit_viewer_close_then_each_album_parent_once() {
        val scene = Scene()
        val root = IosVaultScreen.Root(VaultDestination.ALBUMS)
        val parent = IosVaultScreen.Album("parent")
        val child = IosVaultScreen.Album("child")
        val viewer = IosVaultScreen.Viewer(17L)
        scene.show(listOf(root, parent, child, viewer), VaultDestination.ALBUMS)
        val nav = scene.chrome.navigationControllers[VaultDestination.ALBUMS.ordinal]
        scene.pop(nav)
        assertEquals(listOf<IosVaultScreen>(child), scene.popped)
        assertEquals(listOf<IosVaultScreen>(viewer), scene.retired)
        assertFalse(nav.navigationBarHidden)
        assertFalse(scene.chrome.tabBar.hidden)
        scene.pop(nav)
        scene.pop(nav)
        assertEquals(listOf(child, parent, root), scene.popped)
        assertEquals(listOf(viewer, child, parent), scene.retired)
        assertEquals(listOf(scene.controllers.getValue(root)), nav.viewControllers)
        scene.chrome.clear()
    }

    @Test
    fun cancelled_interactive_delegate_sequence_keeps_owner_stack_and_viewer_alive() {
        val scene = Scene()
        val root = IosVaultScreen.Root(VaultDestination.ALBUMS)
        val album = IosVaultScreen.Album("album")
        val viewer = IosVaultScreen.Viewer(2L)
        scene.show(listOf(root, album, viewer), VaultDestination.ALBUMS)
        val nav = scene.chrome.navigationControllers[VaultDestination.ALBUMS.ordinal]
        val original = nav.topViewController!!
        // UIKit announces the prospective target, then re-shows the original
        // top when an edge gesture is cancelled. No shared pop may run early.
        scene.chrome.navigationController(nav, willShowViewController = scene.controllers.getValue(album), animated = true)
        assertTrue(scene.chrome.transitionInFlight)
        assertTrue(scene.popped.isEmpty())
        assertTrue(scene.retired.isEmpty())
        scene.chrome.navigationController(nav, didShowViewController = original, animated = true)
        assertFalse(scene.chrome.transitionInFlight)
        assertTrue(scene.popped.isEmpty())
        assertTrue(scene.retired.isEmpty())
        assertTrue(original.isEqual(nav.topViewController))
        assertTrue(nav.navigationBarHidden)
        assertTrue(scene.chrome.tabBar.hidden)
        scene.chrome.clear()
    }

    @Test
    fun busy_and_selection_block_native_pop_but_leave_lock_available() {
        val scene = Scene()
        val root = IosVaultScreen.Root(VaultDestination.ALBUMS)
        val album = IosVaultScreen.Album("album")
        scene.show(listOf(root, album), VaultDestination.ALBUMS)
        val nav = scene.chrome.navigationControllers[VaultDestination.ALBUMS.ordinal]
        scene.chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions(), Color.Blue, true)
        assertFalse(nav.interactivePopGestureRecognizer!!.enabled)
        scene.pop(nav)
        assertEquals(2, nav.viewControllers!!.size)
        assertTrue(scene.popped.isEmpty())
        scene.chrome.update(VaultUiState(destination = VaultDestination.ALBUMS, selectedCount = 1), actions(), Color.Blue, false)
        assertFalse(nav.interactivePopGestureRecognizer!!.enabled)
        scene.pop(nav)
        assertEquals(2, nav.viewControllers!!.size)
        assertTrue(scene.popped.isEmpty())
        scene.chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions(), Color.Blue, false)
        assertTrue(nav.interactivePopGestureRecognizer!!.enabled)
        if (ios26()) assertTrue(nav.interactiveContentPopGestureRecognizer!!.enabled)
        var locked = false
        scene.chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions().copy(onLock = { locked = true }), Color.Blue, true)
        scene.chrome.lockNow()
        assertTrue(locked)
        assertTrue(scene.chrome.controller.view.hidden)
    }

    @Test
    fun pending_query_suspends_native_commands_and_update_resumes_without_changing_presentation() {
        val scene = Scene()
        var destinations = 0
        var scopes = 0
        var locks = 0
        val callbacks = actions().copy(onDestination = { destinations++ }, onShowAllMedia = { scopes++ }, onLock = { locks++ })
        val state = VaultUiState()
        scene.chrome.update(state, callbacks, Color.Blue, false)
        val nav = scene.chrome.navigationControllers.first()
        val title = nav.topViewController!!.navigationItem.title
        val selection = scene.chrome.controller.selectedIndex
        scene.chrome.suspendNavigation()
        assertEquals(title, nav.topViewController!!.navigationItem.title)
        assertEquals(selection, scene.chrome.controller.selectedIndex)
        assertFalse(nav.interactivePopGestureRecognizer!!.enabled)
        assertFalse(scene.chrome.tabBar.userInteractionEnabled)
        scene.chrome.tabBarController(scene.chrome.controller,
            shouldSelectViewController = scene.chrome.navigationControllers.last())
        assertEquals(0, destinations)
        // Browse callbacks captured before suspension also honor the guard.
        val menu = nav.topViewController!!.navigationItem.rightBarButtonItems!!
            .filterIsInstance<platform.UIKit.UIBarButtonItem>().mapNotNull { it.menu }.single()
        val item = menu.children.filterIsInstance<platform.UIKit.UIAction>().first { it.title == "All media" }
        val sender = platform.UIKit.UIButton.buttonWithType(platform.UIKit.UIButtonTypeSystem)
        sender.sendAction(item)
        assertEquals(0, scopes)
        scene.chrome.update(state, callbacks, Color.Blue, false)
        assertTrue(scene.chrome.tabBar.userInteractionEnabled)
        scene.chrome.tabBarController(scene.chrome.controller,
            shouldSelectViewController = scene.chrome.navigationControllers.last())
        assertEquals(1, destinations)
        sender.sendAction(item)
        assertEquals(0, scopes, "suspended callbacks stay retired after resume")
        val resumedMenu = nav.topViewController!!.navigationItem.rightBarButtonItems!!
            .filterIsInstance<platform.UIKit.UIBarButtonItem>().mapNotNull { it.menu }.single()
        sender.sendAction(resumedMenu.children.filterIsInstance<platform.UIKit.UIAction>().first { it.title == "All media" })
        assertEquals(1, scopes)
        scene.chrome.suspendNavigation()
        scene.chrome.lockNow()
        assertEquals(1, locks)
        assertTrue(scene.chrome.controller.view.hidden)
    }

    @Test
    fun tabs_request_shared_navigation_without_changing_canonical_selection() {
        val scene = Scene()
        val requests = mutableListOf<VaultDestination>()
        val callbacks = actions().copy(onDestination = { requests += it })
        scene.chrome.update(VaultUiState(), callbacks, Color.Blue, false)
        val settings = scene.chrome.navigationControllers[VaultDestination.SETTINGS.ordinal]
        assertFalse(scene.chrome.tabBarController(scene.chrome.controller, shouldSelectViewController = settings))
        assertEquals(listOf(VaultDestination.SETTINGS), requests)
        assertEquals(VaultDestination.LIBRARY.ordinal.toULong(), scene.chrome.controller.selectedIndex)
        scene.show(listOf(IosVaultScreen.Root(VaultDestination.SETTINGS)), VaultDestination.SETTINGS)
        assertEquals(VaultDestination.SETTINGS.ordinal.toULong(), scene.chrome.controller.selectedIndex)
        scene.chrome.update(VaultUiState(destination = VaultDestination.SETTINGS), callbacks, Color.Blue, true)
        assertFalse(scene.chrome.tabBarController(scene.chrome.controller,
            shouldSelectViewController = scene.chrome.navigationControllers.first()))
        assertEquals(listOf(VaultDestination.SETTINGS), requests)
        scene.chrome.clear()
    }

    @Test
    fun lock_retires_all_private_controllers_before_callback_and_old_delegate_cannot_pop_fresh_session() {
        val scene = Scene()
        val root = IosVaultScreen.Root(VaultDestination.ALBUMS)
        val album = IosVaultScreen.Album("private-id")
        val viewer = IosVaultScreen.Viewer(9L)
        scene.show(listOf(root, album, viewer), VaultDestination.ALBUMS)
        val nav = scene.chrome.navigationControllers[VaultDestination.ALBUMS.ordinal]
        val oldAlbum = scene.controllers.getValue(album)
        val oldTop = nav.topViewController!!
        scene.created.forEach { it.second.navigationItem.title = "Private title" }
        val prior = scene.created.map { it.second }
        var locked = false
        scene.chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions().copy(onLock = {
            assertTrue(scene.chrome.controller.view.hidden)
            assertTrue(scene.chrome.controller.view.accessibilityElementsHidden)
            assertTrue(scene.chrome.controller.viewControllers.isNullOrEmpty())
            assertTrue(scene.chrome.navigationControllers.all { it.viewControllers.isNullOrEmpty() })
            assertTrue(prior.all { it.view.hidden && it.view.accessibilityElementsHidden && it.navigationItem.title == null })
            assertEquals(scene.created.map { it.first }.toSet(), scene.retired.toSet())
            locked = true
        }), Color.Blue, false)
        scene.chrome.navigationController(nav, willShowViewController = oldAlbum, animated = true)
        scene.chrome.lockNow()
        assertTrue(locked)
        assertFalse(scene.chrome.transitionInFlight)
        scene.configure()
        scene.show(listOf(root, album, viewer), VaultDestination.ALBUMS)
        assertTrue(scene.chrome.controller.view.hidden)
        assertTrue(scene.chrome.controller.viewControllers.isNullOrEmpty())
        scene.show(listOf(root), VaultDestination.ALBUMS, generation = 2L)
        scene.chrome.navigationController(nav, willShowViewController = oldAlbum, animated = true)
        scene.chrome.navigationController(nav, didShowViewController = oldTop, animated = true)
        assertTrue(scene.popped.isEmpty())
        assertEquals(1, nav.viewControllers!!.size)
        assertFalse(scene.chrome.controller.view.hidden)
        assertNull(oldAlbum.navigationItem.title)
        scene.chrome.clear()
    }

    @Test
    fun explicit_new_presentation_can_restore_same_repository_generation_but_stale_reconcile_cannot() {
        val scene = Scene()
        scene.chrome.clear()
        scene.configure()
        scene.show(listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY)
        assertTrue(scene.chrome.controller.viewControllers.isNullOrEmpty())
        scene.chrome.beginPresentation(1L)
        scene.configure()
        scene.show(listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY)
        assertFalse(scene.chrome.controller.view.hidden)
        assertEquals(4, scene.chrome.controller.viewControllers!!.size)
        scene.chrome.clear()
    }

    private class Scene {
        val chrome = IosVaultChrome()
        val controllers = mutableMapOf<IosVaultScreen, UIViewController>()
        val created = mutableListOf<Pair<IosVaultScreen, UIViewController>>()
        val popped = mutableListOf<IosVaultScreen>()
        val retired = mutableListOf<IosVaultScreen>()

        init {
            configure()
            show(listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY)
        }

        fun configure() {
            chrome.configureScreens(factory = { screen ->
                UIViewController().also { controllers[screen] = it; created += screen to it }
            }, onPop = { popped += it }, onRetire = { retired += it })
        }

        fun show(screens: List<IosVaultScreen>, destination: VaultDestination, generation: Long = 1L) {
            chrome.reconcile(generation, screens, destination, animated = false)
            chrome.update(VaultUiState(destination = destination), actions(), Color.Blue, false)
        }

        fun pop(nav: UINavigationController) {
            nav.popViewControllerAnimated(false)
            // UIKit need not send appearance callbacks when a unit-test
            // controller is not window-backed. Deliver the completion only
            // after the actual native stack operation; duplicate delivery is safe.
            chrome.navigationController(nav, didShowViewController = nav.topViewController!!, animated = false)
        }
    }

    companion object {
        private fun ios26(): Boolean =
            (UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) >= 26

        private fun actions() = VaultActions(
            onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
            onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
            onAddRecoverySlot = {}, onChangePassword = {},
        )
    }
}
