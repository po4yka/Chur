@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import dev.po4yka.chur.ffi.ObjectProjection
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import platform.CoreGraphics.CGRectMake
import platform.UIKit.*
import platform.UIKit.UIBarButtonItemStyle.UIBarButtonItemStylePlain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosPrivateModalTest {
    @Test
    fun retiring_a_modal_conceals_native_content_and_releases_all_private_closures() {
        val lease = IosPrivateModalLease(7L, 3L)
        val native = UIViewController()
        lease.controller = native
        lease.body = {}
        lease.guard = { true }
        lease.onDismiss = {}
        lease.panic = {}
        lease.afterDismiss = {}
        lease.retire()
        lease.retire()
        assertFalse(lease.active)
        assertNull(lease.body)
        assertNull(lease.guard)
        assertNull(lease.onDismiss)
        assertNull(lease.panic)
        assertNull(lease.afterDismiss)
        assertTrue(native.view.hidden)
        assertTrue(native.view.accessibilityElementsHidden)
        assertFalse(native.view.userInteractionEnabled)
    }

    @Test
    fun retained_native_controller_does_not_retain_a_retired_private_payload() {
        val retained = retiredPayload()
        GC.collect()
        GC.collect()
        assertNull(retained.second.get())
        assertTrue(retained.first.view.hidden)
    }

    private class PrivatePayload { var value = "private modal projection" }
    private fun retiredPayload(): Pair<UIViewController, WeakReference<PrivatePayload>> = autoreleasepool {
        val payload = PrivatePayload()
        val weak = WeakReference(payload)
        val lease = IosPrivateModalLease(1L, 0L)
        val native = UIViewController()
        lease.controller = native
        lease.body = { payload.value }
        lease.guard = { payload.value.isNotEmpty() }
        lease.onDismiss = { payload.value = "dismissed" }
        lease.afterDismiss = { payload.value = "action" }
        lease.retire()
        native to weak
    }

    @Test
    fun native_modal_container_owns_safe_area_and_keyboard_constraints_once() {
        val child = UIViewController()
        val modal = IosPrivateModalController(child)
        val root = modal.view
        assertEquals(modal.objcPtr(), assertNotNull(child.parentViewController).objcPtr())
        assertFalse(child.view.translatesAutoresizingMaskIntoConstraints)
        val constraints = root.constraints.filterIsInstance<NSLayoutConstraint>()
            .filter { it.firstItem.objcPtr() == child.view.objcPtr() }
        assertEquals(4, constraints.size)
        assertTrue(constraints.all { it.active })
        assertEquals(3, constraints.count { it.secondItem.objcPtr() == root.safeAreaLayoutGuide.objcPtr() })
        assertEquals(1, constraints.count { it.secondItem.objcPtr() == root.keyboardLayoutGuide.objcPtr() })
    }

    @Test
    fun native_sheet_has_system_detents_grabber_and_no_passthrough_dimming() {
        val native = UIViewController()
        configurePrivateModal(native, UIViewController(), PrivateModalPresentation.Sheet, null)
        assertEquals(UIModalPresentationPageSheet, native.modalPresentationStyle)
        assertTrue(native.view.accessibilityViewIsModal())
        val sheet = assertNotNull(native.sheetPresentationController)
        assertEquals(listOf(UISheetPresentationControllerDetentIdentifierMedium,
            UISheetPresentationControllerDetentIdentifierLarge),
            sheet.detents.filterIsInstance<UISheetPresentationControllerDetent>().map { it.identifier })
        assertTrue(sheet.prefersGrabberVisible)
        assertNull(sheet.largestUndimmedDetentIdentifier)
    }

    @Test
    fun popover_uses_the_real_bar_item_and_blocks_sibling_passthrough() {
        val native = UIViewController()
        val anchor = UIBarButtonItem(title = "Info", style = UIBarButtonItemStylePlain, target = null, action = null)
        configurePrivateModal(native, UIViewController(), PrivateModalPresentation.Popover, anchor)
        assertEquals(UIModalPresentationPopover, native.modalPresentationStyle)
        assertTrue(native.view.accessibilityViewIsModal())
        val popover = assertNotNull(native.popoverPresentationController)
        // Objective-C getters may return a different Kotlin wrapper of the same object.
        assertEquals(anchor.objcPtr(), assertNotNull(popover.sourceItem).objcPtr())
        assertTrue(popover.passthroughViews.isNullOrEmpty())
        assertEquals(2, popover.adaptiveSheetPresentationController.detents.size)
        assertNull(popover.adaptiveSheetPresentationController.largestUndimmedDetentIdentifier)
    }

    @Test
    fun missing_bar_anchor_uses_a_bounded_presenter_rect() {
        val presenter = UIViewController()
        presenter.view.setFrame(CGRectMake(0.0, 0.0, 800.0, 1000.0))
        val native = UIViewController()
        configurePrivateModal(native, presenter, PrivateModalPresentation.Popover, null)
        val popover = assertNotNull(native.popoverPresentationController)
        assertEquals(presenter.view.objcPtr(), assertNotNull(popover.sourceView).objcPtr())
        popover.sourceRect.useContents {
            assertEquals(400.0, origin.x)
            assertEquals(500.0, origin.y)
            assertEquals(1.0, size.width)
            assertEquals(1.0, size.height)
        }
        assertEquals(0uL, popover.permittedArrowDirections)
    }

    @Test
    fun compact_popover_uses_a_sheet_instead_of_presenting_an_unanchored_phone_popover() {
        val parent = UIViewController()
        val presenter = UIViewController()
        parent.addChildViewController(presenter)
        parent.setOverrideTraitCollection(
            UITraitCollection.traitCollectionWithHorizontalSizeClass(UIUserInterfaceSizeClassCompact),
            forChildViewController = presenter,
        )
        presenter.didMoveToParentViewController(parent)
        assertEquals(UIUserInterfaceSizeClassCompact, presenter.traitCollection.horizontalSizeClass)
        val native = UIViewController()
        configurePrivateModal(native, presenter, PrivateModalPresentation.Popover, null)
        assertEquals(UIModalPresentationPageSheet, native.modalPresentationStyle)
        assertNotNull(native.sheetPresentationController)
    }

    @Test
    fun modal_registration_synchronously_blocks_retained_native_commands_until_shared_state_is_updated() {
        var imports = 0
        val chrome = IosVaultChrome()
        prepare(chrome)
        val state = VaultUiState(destination = VaultDestination.ALBUMS)
        val callbacks = actions().copy(onCreateAlbum = { imports++ })
        chrome.update(state, callbacks, Color.Blue, false)
        chrome.setPrivateModalActive(true)
        assertTrue(chrome.privateModalActive)
        chrome.performPrimary()
        assertEquals(0, imports)
        // An ordinary shared update cannot release the independent modal fence.
        chrome.update(state, callbacks, Color.Blue, false)
        chrome.performPrimary()
        assertEquals(0, imports)
        chrome.setPrivateModalActive(false)
        chrome.update(state, callbacks, Color.Blue, false)
        chrome.performPrimary()
        assertEquals(1, imports)
        chrome.clear()
    }

    @Test
    fun lock_revokes_modal_before_dispatching_the_shared_lock_command() {
        val chrome = IosVaultChrome()
        prepare(chrome)
        var revoked = false
        var locked = false
        chrome.onClearPrivateModals = { revoked = true; chrome.setPrivateModalActive(false) }
        chrome.update(VaultUiState(), actions().copy(onLock = { assertTrue(revoked); locked = true }), Color.Blue, false)
        chrome.setPrivateModalActive(true)
        chrome.lockNow()
        assertTrue(locked)
        assertFalse(chrome.privateModalActive)
        assertTrue(chrome.controller.view.hidden)
    }

    @Test
    fun selection_popover_source_item_stays_mounted_during_covered_chrome_updates() {
        val chrome = IosVaultChrome()
        prepare(chrome)
        val state = VaultUiState(destination = VaultDestination.ALBUMS, selectedCount = 2)
        chrome.update(state, actions(), Color.Blue, false)
        val source = assertNotNull(chrome.privateModalAnchor(PrivateModalAnchor.Export))
        chrome.setPrivateModalActive(true)
        chrome.update(state, actions(), Color.Blue, true, covered = true)
        assertEquals(source.objcPtr(), assertNotNull(chrome.privateModalAnchor(PrivateModalAnchor.Export)).objcPtr())
        assertTrue(chrome.navigationControllers[VaultDestination.ALBUMS.ordinal].topViewController!!.toolbarItems!!.contains(source))
        assertFalse(chrome.toolbar.hidden)
        assertFalse(source.enabled)
        chrome.clear()
    }

    @Test
    fun viewer_info_and_export_sources_stay_mounted_and_retained_info_action_is_blocked() {
        val chrome = IosVaultChrome()
        prepare(chrome)
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS), IosVaultScreen.Viewer(4L)),
            VaultDestination.ALBUMS, animated = false)
        var infos = 0
        val callbacks = IosViewerPanelActions(onBack = {}, onTouch = {}, onFavorite = {}, onInfo = { infos++ },
            onExport = {}, onTags = {}, onDelete = {}, onRestore = {})
        val state = IosViewerPanelState(1L, 4L, "first", "Photo", false, false, false, true)
        chrome.updateViewer(state, callbacks, Color.Blue)
        val info = assertNotNull(chrome.privateModalAnchor(PrivateModalAnchor.Info))
        val export = assertNotNull(chrome.privateModalAnchor(PrivateModalAnchor.Export))
        val retainedInfoAction = assertNotNull(info.primaryAction)
        chrome.setPrivateModalActive(true)
        chrome.updateViewer(state.copy(infoVisible = true), callbacks, Color.Blue)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions(), Color.Blue, true, covered = true)
        assertEquals(info.objcPtr(), assertNotNull(chrome.privateModalAnchor(PrivateModalAnchor.Info)).objcPtr())
        assertEquals(export.objcPtr(), assertNotNull(chrome.privateModalAnchor(PrivateModalAnchor.Export)).objcPtr())
        assertFalse(info.enabled)
        assertFalse(export.enabled)
        UIButton.buttonWithType(UIButtonTypeSystem).sendAction(retainedInfoAction)
        assertEquals(0, infos)
        chrome.clear()
    }

    @Test
    fun native_tab_roots_and_viewer_keep_distinct_owned_bar_items_and_lock_views() {
        val chrome = IosVaultChrome()
        prepare(chrome)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions(), Color.Blue, false)
        val albums = chrome.navigationControllers[VaultDestination.ALBUMS.ordinal].topViewController!!
        fun items(controller: UIViewController) = controller.navigationItem.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>()
        fun lock(controller: UIViewController) = items(controller).single { it.customView != null }.customView!!
        val albumPrimary = items(albums).single { it.title == "New album" }
        val albumLock = lock(albums)
        val projection = photo()
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY, animated = false)
        chrome.update(VaultUiState(tiles = listOf(LibraryTile(projection, null))), actions(), Color.Blue, false)
        val library = chrome.navigationControllers[VaultDestination.LIBRARY.ordinal].topViewController!!
        val libraryPrimary = items(library).single { it.title == "Import" }
        val libraryLock = lock(library)
        assertTrue(albumPrimary.objcPtr() != libraryPrimary.objcPtr())
        assertTrue(albumLock.objcPtr() != libraryLock.objcPtr())
        assertEquals("New album", albumPrimary.title)
        assertEquals(albumPrimary.objcPtr(), items(albums).single { it.title == "New album" }.objcPtr())
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS), IosVaultScreen.Viewer(7L)),
            VaultDestination.ALBUMS, animated = false)
        chrome.updateViewer(IosViewerPanelState(1L, 7L, projection.id, "Photo", false, false, false, true),
            IosViewerPanelActions({}, {}, {}, {}, {}, {}, {}, {}), Color.Blue)
        val viewer = chrome.navigationControllers[VaultDestination.ALBUMS.ordinal].topViewController!!
        val viewerLock = lock(viewer)
        assertEquals(3, setOf(albumLock.objcPtr(), libraryLock.objcPtr(), viewerLock.objcPtr()).size)
        // Parent bars retain their own privacy escape during native Back interpolation.
        assertEquals(albumLock.objcPtr(), lock(albums).objcPtr())
        assertEquals(libraryLock.objcPtr(), lock(library).objcPtr())
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS)), VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions(), Color.Blue, false)
        assertEquals(albumPrimary.objcPtr(), items(albums).single { it.title == "New album" }.objcPtr())
        assertEquals("Import", libraryPrimary.title)
        chrome.clear()
    }

    @Test
    fun retained_inactive_and_retired_control_actions_cannot_dispatch_into_current_frame_or_visit() {
        val chrome = IosVaultChrome()
        prepare(chrome)
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY, animated = false)
        var imports = 0
        var creates = 0
        var backs = 0
        var clears = 0
        var locks = 0
        var panics = 0
        val callbacks = actions().copy(onImport = { imports++ }, onCreateAlbum = { creates++ },
            onShowAllMedia = { backs++ }, onClearSelection = { clears++ }, onLock = { locks++ }, onPanic = { panics++ })
        val recent = VaultUiState(tiles = listOf(LibraryTile(photo(), null)), libraryScopeTitle = "Recently added", recentlyAdded = true)
        chrome.update(recent, callbacks, Color.Blue, false)
        val item = chrome.navigationBar.topItem!!
        val primary = item.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>().single { it.title == "Import" }
        val oldLock = item.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>().single { it.customView != null }.customView as UIButton
        val oldBack = item.leftBarButtonItem!!.customView as UIButton
        fun touchActions(control: UIControl): List<UIAction> {
            val actions = mutableListOf<UIAction>()
            control.enumerateEventHandlers { action, _, _, events, _ ->
                if (action != null && events and UIControlEventTouchUpInside != 0uL) actions += action
            }
            return actions
        }
        val retainedPrimary = assertNotNull(primary.primaryAction)
        val retainedBack = touchActions(oldBack).single()
        val retainedLock = touchActions(oldLock).single()
        val oldEscape = oldLock.accessibilityCustomActions!!.filterIsInstance<UIAccessibilityCustomAction>().single()
        val sender = UIButton.buttonWithType(UIButtonTypeSystem)
        fun oldPrimary() = sender.sendAction(retainedPrimary)
        fun oldBackAction() = sender.sendAction(retainedBack)
        fun oldLockAction() = sender.sendAction(retainedLock)
        // Prove these exact native action paths work while their frame owns the controls.
        oldPrimary()
        oldBackAction()
        assertEquals(1, imports)
        assertEquals(1, backs)
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS)), VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), callbacks, Color.Blue, false)
        oldPrimary()
        oldLockAction()
        assertFalse(oldEscape.actionHandler!!.invoke(oldEscape))
        assertEquals(0, creates)
        assertEquals(0, locks)
        assertEquals(0, panics)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS, selectedCount = 1), callbacks, Color.Blue, false)
        oldBackAction()
        assertEquals(0, clears)
        chrome.goBack()
        assertEquals(1, clears)
        chrome.clear()
        chrome.beginPresentation(2L)
        chrome.configureScreens(factory = { UIViewController() }, onPop = {}, onRetire = {})
        chrome.reconcile(2L, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)), VaultDestination.LIBRARY, animated = false)
        chrome.update(recent, callbacks, Color.Blue, false)
        oldPrimary()
        oldBackAction()
        oldLockAction()
        oldBack.sendActionsForControlEvents(UIControlEventTouchUpInside)
        oldLock.sendActionsForControlEvents(UIControlEventTouchUpInside)
        assertFalse(oldEscape.actionHandler!!.invoke(oldEscape))
        assertNull(primary.primaryAction)
        assertNull(primary.target)
        assertNull(primary.action)
        assertTrue(touchActions(oldBack).isEmpty())
        assertTrue(touchActions(oldLock).isEmpty())
        assertEquals(1, imports)
        assertEquals(1, backs)
        assertEquals(0, locks)
        assertEquals(0, panics)
        val fresh = chrome.navigationBar.topItem!!
        sender.sendAction(fresh.rightBarButtonItems!!.filterIsInstance<UIBarButtonItem>()
            .single { it.title == "Import" }.primaryAction!!)
        sender.sendAction(touchActions(fresh.leftBarButtonItem!!.customView as UIButton).single())
        assertEquals(2, imports)
        assertEquals(2, backs)
        chrome.clear()
    }

    private fun photo() = ObjectProjection(
        objectId = ByteArray(16) { 1 }, primaryStreamId = ByteArray(16), mediaKind = MEDIA_CLASS_IMAGE,
        captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false,
        plaintextSize = 1, width = 1, height = 1, durationMs = 0,
        favorite = false, state = 1, integritySummary = 4, thumbnailReady = true,
    )

    private fun prepare(chrome: IosVaultChrome) {
        chrome.configureScreens(factory = { UIViewController() }, onPop = {}, onRetire = {})
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.ALBUMS)), VaultDestination.ALBUMS, animated = false)
    }
    private fun actions() = VaultActions(onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {},
        onSearch = {}, onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
        onAddRecoverySlot = {}, onChangePassword = {})
}
