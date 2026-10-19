@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import dev.po4yka.chur.app.ActiveOperation
import platform.UIKit.UIAction
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIViewController
import platform.UIKit.UIColor
import platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleDark
import platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.accessibilityLabel
import platform.UIKit.navigationItem
import platform.UIKit.toolbarItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosViewerPanelTest {
    @Test
    fun shell_renders_before_and_after_native_viewer_retirement() {
        val chrome = prepare()
        assertEquals("Library", chrome.navigationBar.topItem!!.title)
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY), IosVaultScreen.Viewer(1)),
            VaultDestination.LIBRARY, animated = false)
        chrome.updateViewer(panel("first"), viewer(), Color.Blue)
        assertEquals("Photo", chrome.navigationBar.topItem!!.title)
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY, animated = false)
        chrome.update(VaultUiState(), shell(), Color.Blue, false)
        assertEquals("Library", chrome.navigationBar.topItem!!.title)
        assertFalse(chrome.navigationControllers.first().navigationBarHidden)
        assertTrue(chrome.navigationControllers.first().toolbarHidden)
        chrome.clear()
    }

    @Test
    fun viewer_privacy_contrast_is_dark_white_and_native_pop_or_cancel_restores_correct_ladder() {
        val chrome = prepare(viewer = true)
        chrome.updateViewer(panel("first"), viewer(), Color.Blue)
        val nav = chrome.navigationControllers.first()
        val viewerController = nav.topViewController!!
        val root = nav.viewControllers!!.first() as UIViewController
        fun lockButton() = viewerController.navigationItem.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>()
            .mapNotNull { it.customView as? UIButton }.single { it.accessibilityLabel == "Lock now" }
        assertEquals(UIUserInterfaceStyleDark, nav.navigationBar.overrideUserInterfaceStyle)
        assertEquals(UIUserInterfaceStyleDark, nav.toolbar.overrideUserInterfaceStyle)
        assertTrue(lockButton().tintColor!!.isEqual(UIColor.whiteColor))
        chrome.updateViewer(panel("first").copy(visible = false), viewer(), Color.Blue)
        assertTrue(lockButton().tintColor!!.isEqual(UIColor.whiteColor))
        // Prospective pop switches to the shell's style; cancellation announces
        // Viewer again and must restore its privacy control's contrast.
        chrome.navigationController(nav, willShowViewController = root, animated = true)
        assertEquals(UIUserInterfaceStyleUnspecified, nav.navigationBar.overrideUserInterfaceStyle)
        assertTrue(nav.navigationBar.tintColor!!.isEqual(UIColor(red = 0.0, green = 0.0, blue = 1.0, alpha = 1.0)))
        chrome.navigationController(nav, willShowViewController = viewerController, animated = true)
        chrome.navigationController(nav, didShowViewController = viewerController, animated = true)
        assertEquals(UIUserInterfaceStyleDark, nav.navigationBar.overrideUserInterfaceStyle)
        assertTrue(lockButton().tintColor!!.isEqual(UIColor.whiteColor))
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY, animated = false)
        chrome.update(VaultUiState(), shell(), Color.Blue, false)
        assertEquals(UIUserInterfaceStyleUnspecified, nav.navigationBar.overrideUserInterfaceStyle)
        assertEquals(UIUserInterfaceStyleUnspecified, nav.toolbar.overrideUserInterfaceStyle)
        assertTrue(nav.navigationBar.tintColor!!.isEqual(UIColor(red = 0.0, green = 0.0, blue = 1.0, alpha = 1.0)))
        chrome.clear()
        assertTrue(chrome.navigationControllers.all { it.navigationBar.overrideUserInterfaceStyle == UIUserInterfaceStyleUnspecified })
    }

    @Test
    fun selection_replaces_system_tabs_with_toolbar_and_clear_restores_tab_layout() {
        val chrome = prepare()
        val nav = chrome.navigationControllers.first()
        assertFalse(chrome.controller.tabBarHidden)
        assertTrue(nav.toolbarHidden)
        chrome.update(VaultUiState(selectedCount = 2), shell(), Color.Blue, false)
        assertTrue(chrome.controller.tabBarHidden)
        assertTrue(chrome.tabBar.accessibilityElementsHidden)
        assertFalse(nav.toolbarHidden)
        assertTrue(nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>().any { it.title == "Export" })
        chrome.update(VaultUiState(), shell(), Color.Blue, false)
        assertFalse(chrome.controller.tabBarHidden)
        assertFalse(chrome.tabBar.accessibilityElementsHidden)
        assertTrue(nav.toolbarHidden)
        assertNull(nav.topViewController!!.toolbarItems)
        chrome.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY), IosVaultScreen.Viewer(1)),
            VaultDestination.LIBRARY, animated = false)
        chrome.updateViewer(panel("first"), viewer(), Color.Blue)
        assertTrue(chrome.controller.tabBarHidden)
        val viewerController = nav.topViewController!!
        val root = nav.viewControllers!!.first() as UIViewController
        chrome.navigationController(nav, willShowViewController = root, animated = true)
        chrome.navigationController(nav, willShowViewController = viewerController, animated = true)
        chrome.navigationController(nav, didShowViewController = viewerController, animated = true)
        assertTrue(chrome.controller.tabBarHidden)
        chrome.clear()
        assertTrue(chrome.controller.tabBarHidden)
    }

    @Test
    fun selection_toolbar_projects_actions_and_busy_freezes_retained_callbacks() {
        var exports = 0
        var additions = 0
        var deletions = 0
        val chrome = prepare()
        val callbacks = shell().copy(onExportSelection = { exports++ },
            onAddSelectionToAlbum = { additions++ }, onDeleteSelection = { deletions++ })
        chrome.update(VaultUiState(selectedCount = 2), callbacks, Color.Blue, false)
        val nav = chrome.navigationControllers.first()
        val items = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
        assertFalse(nav.toolbarHidden)
        assertEquals("2 selected", nav.topViewController!!.navigationItem.title)
        assertEquals(listOf("Export", "Add to album", "Move to Trash", "More"), items.mapNotNull { it.title })
        assertEquals(1, nav.topViewController!!.navigationItem.rightBarButtonItems!!.size)
        val export = items.single { it.title == "Export" }.primaryAction!!
        val add = items.single { it.title == "Add to album" }.primaryAction!!
        val delete = items.single { it.title == "Move to Trash" }.primaryAction!!
        send(add); send(delete); send(export)
        assertEquals(listOf(1, 1, 1), listOf(exports, additions, deletions))
        chrome.update(VaultUiState(selectedCount = 2), callbacks, Color.Blue, true)
        assertTrue(nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>().all { !it.enabled })
        send(add); send(delete); send(export)
        assertEquals(listOf(1, 1, 1), listOf(exports, additions, deletions))
        chrome.update(VaultUiState(), callbacks, Color.Blue, false)
        send(add); send(delete)
        assertEquals(1, additions)
        assertEquals(1, deletions)
        assertTrue(nav.toolbarHidden)
        assertNull(nav.topViewController!!.toolbarItems)
        chrome.clear()
    }

    @Test
    fun active_selection_operation_blocks_toolbar_and_retained_actions_without_modal_busy_report() {
        var exports = 0
        var deletions = 0
        var locks = 0
        val chrome = prepare()
        val actions = shell().copy(onExportSelection = { exports++ }, onDeleteSelection = { deletions++ }, onLock = { locks++ })
        chrome.update(VaultUiState(selectedCount = 2), actions, Color.Blue, false)
        val nav = chrome.navigationControllers.first()
        val delete = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "Move to Trash" }.primaryAction!!
        val export = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "Export" }.primaryAction!!
        chrome.update(VaultUiState(selectedCount = 2, operation = ActiveOperation(1, "export")), actions, Color.Blue, false)
        assertTrue(nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>().all { !it.enabled })
        send(delete); send(export)
        assertEquals(0, exports)
        assertEquals(0, deletions)
        assertFalse(nav.interactivePopGestureRecognizer!!.enabled)
        chrome.lockNow()
        assertEquals(1, locks)
    }

    @Test
    fun equal_count_selection_changes_and_returning_to_same_items_retire_every_action_copy() {
        val calls = mutableListOf<String>()
        val chrome = prepare()
        fun publish(id: String) {
            chrome.update(VaultUiState(selectedCount = 1, selectionIds = setOf(id)), shell().copy(
                onExportSelection = { calls += "$id export" },
                onDeleteSelection = { calls += "$id delete" },
                onTagSelection = { calls += "$id tags" },
            ), Color.Blue, false)
        }
        publish("A")
        val first = selectionActions(chrome)
        first.forEach(::send)
        assertEquals(listOf("A export", "A delete", "A tags"), calls)
        publish("B")
        val second = selectionActions(chrome)
        first.forEach(::send)
        assertEquals(3, calls.size)
        second.forEach(::send)
        assertEquals(listOf("B export", "B delete", "B tags"), calls.takeLast(3))
        publish("A")
        first.forEach(::send)
        second.forEach(::send)
        assertEquals(6, calls.size)
        selectionActions(chrome).forEach(::send)
        assertEquals(listOf("A export", "A delete", "A tags"), calls.takeLast(3))
        chrome.clear()
    }

    @Test
    fun selection_busy_modal_and_query_suspension_retire_actions_after_unblocking() {
        for (barrier in listOf("busy", "modal", "operation", "query")) {
            var calls = 0
            val chrome = prepare()
            val state = VaultUiState(selectedCount = 1, selectionIds = setOf("A"))
            val actions = shell().copy(onExportSelection = { calls++ }, onDeleteSelection = { calls++ },
                onTagSelection = { calls++ })
            chrome.update(state, actions, Color.Blue, false)
            val before = selectionActions(chrome)
            when (barrier) {
                "query" -> chrome.suspendNavigation()
                "operation" -> chrome.update(state.copy(operation = ActiveOperation(1, "export")), actions, Color.Blue, false)
                else -> chrome.update(state, actions, Color.Blue, barrier == "busy", covered = barrier == "modal")
            }
            before.forEach(::send)
            val blocked = selectionActions(chrome)
            blocked.forEach(::send)
            assertEquals(0, calls, barrier)
            chrome.update(state, actions, Color.Blue, false)
            before.forEach(::send)
            blocked.forEach(::send)
            assertEquals(0, calls, "$barrier retained after unblock")
            selectionActions(chrome).forEach(::send)
            assertEquals(3, calls, "$barrier fresh after unblock")
            chrome.clear()
        }
    }

    @Test
    fun trash_selection_has_precise_actions_and_clear_then_lock_retires_them() {
        var restores = 0
        var deletions = 0
        var clears = 0
        var locks = 0
        val chrome = prepare()
        val actions = shell().copy(onRestoreSelection = { restores++ }, onDeleteSelection = { deletions++ },
            onClearSelection = { clears++ }, onLock = { locks++ })
        val state = VaultUiState(selectedCount = 1, selectionIds = setOf("A"), trashOpen = true)
        chrome.update(state, actions, Color.Blue, false)
        val nav = chrome.navigationControllers.first()
        val items = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
        assertEquals(listOf("Restore", "Delete permanently", "More"), items.mapNotNull { it.title })
        assertEquals("1 selected", nav.topViewController!!.navigationItem.title)
        assertEquals("Clear selection", (nav.topViewController!!.navigationItem.leftBarButtonItem!!.customView as UIButton).accessibilityLabel)
        val restore = items.single { it.title == "Restore" }.primaryAction!!
        val delete = items.single { it.title == "Delete permanently" }.primaryAction!!
        val menu = items.single { it.title == "More" }.menu!!.children.filterIsInstance<UIAction>()
        assertEquals(listOf("Lock and hide", "Select loaded items"), menu.map { it.title })
        send(restore); send(delete)
        assertEquals(listOf(1, 1), listOf(restores, deletions))
        chrome.goBack()
        assertEquals(1, clears)
        chrome.update(state.copy(selectedCount = 0, selectionIds = emptySet()), actions, Color.Blue, false)
        send(restore); send(delete)
        assertEquals(listOf(1, 1), listOf(restores, deletions))
        assertFalse(chrome.controller.tabBarHidden)
        assertTrue(nav.toolbarHidden)
        chrome.update(state, actions, Color.Blue, false)
        val last = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "Restore" }.primaryAction!!
        chrome.lockNow()
        send(last); menu.forEach(::send)
        assertEquals(1, locks)
        assertEquals(1, restores)
        assertTrue(chrome.controller.view.hidden)
        assertTrue(chrome.controller.view.accessibilityElementsHidden)
    }

    @Test
    fun stale_generation_cannot_publish_or_clear_a_reused_viewer_token() {
        val chrome = prepare(viewer = true)
        chrome.updateViewer(panel("old"), viewer(), Color.Blue)
        chrome.beginPresentation(2)
        chrome.configureScreens({ UIViewController() }, {}, {})
        chrome.reconcile(2, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY), IosVaultScreen.Viewer(1)),
            VaultDestination.LIBRARY, animated = false)
        chrome.update(VaultUiState(), shell(), Color.Blue, false)
        chrome.updateViewer(panel("fresh").copy(generation = 2, title = "Fresh"), viewer(), Color.Blue)
        chrome.clearViewer(1, 1)
        chrome.updateViewer(panel("old"), viewer(), Color.Blue)
        assertEquals("Fresh", chrome.navigationBar.topItem!!.title)
        assertFalse(chrome.navigationControllers.first().toolbarHidden)
        chrome.clear()
    }

    @Test
    fun paging_retires_exact_native_actions_and_visits_cannot_reopen_after_lock() {
        var favorites = 0
        var tags = 0
        var locks = 0
        val chrome = prepare(viewer = true, actions = shell().copy(onLock = { locks++ }))
        val actions = viewer().copy(onFavorite = { favorites++ }, onTags = { tags++ })
        chrome.updateViewer(panel("first"), actions, Color.Blue)
        val nav = chrome.navigationControllers.first()
        val firstFavorite = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "Add to favorites" }.primaryAction!!
        val firstMenu = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "More" }.menu!!.children.filterIsInstance<UIAction>()
        send(firstFavorite)
        assertEquals(1, favorites)
        chrome.updateViewer(panel("second"), actions, Color.Blue)
        send(firstFavorite); firstMenu.forEach(::send)
        assertEquals(1, favorites)
        assertEquals(0, tags)
        assertEquals(0, locks)
        chrome.lockNow()
        assertEquals(1, locks)
        assertTrue(chrome.controller.view.hidden)
        assertTrue(chrome.controller.view.accessibilityElementsHidden)
        assertTrue(nav.toolbarHidden)
        chrome.updateViewer(panel("second"), actions, Color.Blue)
        assertTrue(chrome.controller.view.hidden)
        assertTrue(nav.viewControllers.isNullOrEmpty())
    }

    @Test
    fun viewer_busy_and_auto_hidden_controls_keep_lock_and_retire_toolbar() {
        var favorites = 0
        var info = 0
        var locks = 0
        val chrome = prepare(viewer = true, actions = shell().copy(onLock = { locks++ }))
        val actions = viewer().copy(onFavorite = { favorites++ }, onInfo = { info++ })
        chrome.updateViewer(panel("first"), actions, Color.Blue)
        val nav = chrome.navigationControllers.first()
        val favorite = nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "Add to favorites" }.primaryAction!!
        chrome.update(VaultUiState(), shell().copy(onLock = { locks++ }), Color.Blue, true)
        chrome.updateViewer(panel("first").copy(busy = true), actions, Color.Blue)
        send(favorite)
        assertEquals(0, favorites)
        assertTrue(nav.topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>().all { !it.enabled })
        chrome.updateViewer(panel("first").copy(visible = false), actions, Color.Blue)
        assertFalse(nav.navigationBarHidden)
        assertTrue(nav.toolbarHidden)
        assertNull(nav.topViewController!!.navigationItem.title)
        assertNull(nav.topViewController!!.navigationItem.leftBarButtonItem)
        assertEquals(1, nav.topViewController!!.navigationItem.rightBarButtonItems!!.size)
        assertNull(nav.topViewController!!.toolbarItems)
        chrome.lockNow()
        assertEquals(1, locks)
        assertEquals(0, info)
    }

    @Test
    fun viewer_detail_publication_blocks_controls_and_pop_before_shared_busy_effect_arrives() {
        var backs = 0
        var exports = 0
        var infos = 0
        var locks = 0
        val chrome = prepare(viewer = true, actions = shell().copy(onLock = { locks++ }))
        val actions = viewer().copy(onBack = { backs++ }, onExport = { exports++ }, onInfo = { infos++ })
        chrome.updateViewer(panel("first"), actions, Color.Blue)
        val nav = chrome.navigationControllers.first()
        val top = nav.topViewController!!
        val retainedBack = top.navigationItem.leftBarButtonItem!!.primaryAction!!
        val retainedExport = top.toolbarItems!!.filterIsInstance<UIBarButtonItem>().single { it.title == "Export" }.primaryAction!!
        // No chrome.update(navigationBusy=true): this is the route's actual
        // publication ordering when the Compose detail first becomes visible.
        chrome.updateViewer(panel("first").copy(infoVisible = true), actions, Color.Blue)
        assertFalse(top.navigationItem.leftBarButtonItem!!.enabled)
        assertTrue(top.toolbarItems!!.filterIsInstance<UIBarButtonItem>().all { !it.enabled })
        assertFalse(nav.interactivePopGestureRecognizer!!.enabled)
        val close = top.navigationItem.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>().single { it.title == "Hide info" }
        assertTrue(close.enabled)
        send(retainedBack); send(retainedExport)
        send(top.navigationItem.leftBarButtonItem!!.primaryAction!!)
        send(top.toolbarItems!!.filterIsInstance<UIBarButtonItem>().single { it.title == "Export" }.primaryAction!!)
        assertEquals(0, backs)
        assertEquals(0, exports)
        send(close.primaryAction!!)
        assertEquals(1, infos)
        chrome.lockNow()
        assertEquals(1, locks)
        assertTrue(nav.viewControllers.isNullOrEmpty())
    }

    @Test
    fun detail_info_close_is_available_while_back_and_toolbar_are_blocked_and_transition_fences_actions() {
        var closed = 0
        var exported = 0
        val chrome = prepare(viewer = true)
        val actions = viewer().copy(onInfo = { closed++ }, onExport = { exported++ })
        chrome.update(VaultUiState(), shell(), Color.Blue, true)
        chrome.updateViewer(panel("first").copy(infoVisible = true), actions, Color.Blue)
        val nav = chrome.navigationControllers.first()
        val top = nav.topViewController!!
        assertFalse(top.navigationItem.leftBarButtonItem!!.enabled)
        val close = top.navigationItem.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "Hide info" }
        assertTrue(close.enabled)
        send(close.primaryAction!!)
        assertEquals(1, closed)
        chrome.update(VaultUiState(), shell(), Color.Blue, false)
        chrome.updateViewer(panel("first"), actions, Color.Blue)
        val export = top.toolbarItems!!.filterIsInstance<UIBarButtonItem>().single { it.title == "Export" }.primaryAction!!
        chrome.navigationController(nav, willShowViewController = top, animated = true)
        send(export)
        assertEquals(0, exported)
        chrome.navigationController(nav, didShowViewController = top, animated = true)
        send(export)
        assertEquals(1, exported)
        chrome.clear()
    }

    private fun selectionActions(chrome: IosVaultChrome): List<UIAction> {
        val items = chrome.navigationControllers.first().topViewController!!.toolbarItems!!.filterIsInstance<UIBarButtonItem>()
        return listOf(
            items.single { it.title == "Export" }.primaryAction!!,
            items.single { it.title == "Move to Trash" }.primaryAction!!,
            items.single { it.title == "More" }.menu!!.children.filterIsInstance<UIAction>().single { it.title == "Tags" },
        )
    }
    private fun send(action: UIAction) = UIButton.buttonWithType(UIButtonTypeSystem).sendAction(action)
    private fun panel(id: String) = IosViewerPanelState(1, 1, id, "Photo", favorite = false, trashed = false, busy = false, visible = true)
    private fun viewer() = IosViewerPanelActions({}, {}, {}, {}, {}, {}, {}, {})
    private fun shell() = VaultActions(onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
        onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {}, onAddRecoverySlot = {}, onChangePassword = {})
    private fun prepare(viewer: Boolean = false, actions: VaultActions = shell()): IosVaultChrome = IosVaultChrome().also {
        it.configureScreens({ UIViewController() }, {}, {})
        it.reconcile(1, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)) +
            if (viewer) listOf(IosVaultScreen.Viewer(1)) else emptyList(), VaultDestination.LIBRARY, animated = false)
        it.update(VaultUiState(), actions, Color.Blue, false)
    }
}
