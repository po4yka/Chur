@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import dev.po4yka.chur.ffi.AlbumSummary
import kotlinx.cinterop.autoreleasepool
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import platform.UIKit.accessibilityCustomActions
import platform.UIKit.accessibilityElementsHidden
import platform.UIKit.accessibilityLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosVaultChromeTest {
    @Test
    fun disposed_presenter_is_not_retained_by_native_host_menu_or_accessibility_blocks() {
        val retained = retiredNativeControls()
        // Drain both sides of the documented Kotlin/Objective-C collection chain.
        GC.collect()
        GC.collect()
        assertNull(retained.owner.get())
        assertFalse(retained.panic.actionHandler!!.invoke(retained.panic))
        assertTrue(retained.menuAction.title.isEmpty())
        assertEquals(2, retained.host.subviews.size)
    }

    private fun retiredNativeControls(): RetainedNativeControls = autoreleasepool {
        val chrome = IosVaultChrome()
        val host = platform.UIKit.UIView()
        chrome.attach(host)
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
        val items = chrome.navigationBar.topItem!!.rightBarButtonItems!!
            .filterIsInstance<platform.UIKit.UIBarButtonItem>()
        val lock = items.mapNotNull { it.customView as? platform.UIKit.UIButton }.single()
        val panic = lock.accessibilityCustomActions!!
            .filterIsInstance<platform.UIKit.UIAccessibilityCustomAction>().single()
        val menuAction = items.mapNotNull { it.menu }.single().children
            .filterIsInstance<platform.UIKit.UIAction>().first()
        chrome.clear()
        RetainedNativeControls(WeakReference(chrome), host, panic, menuAction)
    }

    private data class RetainedNativeControls(
        val owner: WeakReference<IosVaultChrome>,
        val host: platform.UIKit.UIView,
        val panic: platform.UIKit.UIAccessibilityCustomAction,
        val menuAction: platform.UIKit.UIAction,
    )

    @Test
    fun native_tabs_forward_to_the_existing_navigation_owner_and_modal_blocks_touches() {
        val destinations = mutableListOf<VaultDestination>()
        val chrome = IosVaultChrome()
        val host = platform.UIKit.UIView()
        chrome.attach(host)
        chrome.update(VaultUiState(), actions().copy(onDestination = { destinations += it }), Color.Blue, false)
        assertEquals(chrome.navigationBar, host.subviews.filterIsInstance<platform.UIKit.UINavigationBar>().single())
        assertEquals(chrome.tabBar, host.subviews.filterIsInstance<platform.UIKit.UITabBar>().single())
        val tabs = chrome.tabBar.items!!.filterIsInstance<platform.UIKit.UITabBarItem>()
        assertEquals(VaultDestination.entries.map { it.label }, tabs.map { it.title })
        tabs.forEach { chrome.tabBar(chrome.tabBar, didSelectItem = it) }
        assertEquals(VaultDestination.entries.toList(), destinations)
        chrome.update(VaultUiState(), actions().copy(onDestination = { destinations += it }), Color.Blue, true)
        assertFalse(chrome.tabBar.userInteractionEnabled)
        assertTrue(chrome.tabBar.items!!.filterIsInstance<platform.UIKit.UITabBarItem>().all { !it.enabled })
        val prior = destinations.toList()
        chrome.tabBar(chrome.tabBar, didSelectItem = chrome.tabBar.items!!.filterIsInstance<platform.UIKit.UITabBarItem>().last())
        assertEquals(prior, destinations)
        chrome.clear()
    }

    @Test
    fun lock_clears_native_private_title_menu_and_callbacks_before_controller_transition() {
        var imports = 0
        var locked = false
        val chrome = IosVaultChrome()
        val album = AlbumSummary(ByteArray(16) { 7 }, 1, "Private album title")
        chrome.update(VaultUiState(openAlbum = album), actions().copy(
            onImport = { imports++ },
            onLock = {
                assertTrue(chrome.navigationBar.hidden)
                assertTrue(chrome.tabBar.hidden)
                assertNull(chrome.navigationBar.topItem)
                assertTrue(chrome.tabBar.items.isNullOrEmpty())
                locked = true
            },
        ), Color.Blue, false)
        assertEquals(album.name, chrome.navigationBar.topItem?.title)
        val menu = chrome.navigationBar.topItem!!.rightBarButtonItems!!
            .filterIsInstance<platform.UIKit.UIBarButtonItem>()
            .mapNotNull { it.menu }.single()
        val retiredActions = menu.children.filterIsInstance<platform.UIKit.UIAction>()
        chrome.lockNow()
        assertTrue(retiredActions.all { it.title.isEmpty() })
        assertTrue(locked)
        // The backend lock can suspend while the old shared shell recomposes.
        chrome.update(VaultUiState(openAlbum = album), actions(), Color.Blue, false)
        assertTrue(chrome.navigationBar.hidden)
        assertNull(chrome.navigationBar.topItem)
        chrome.performPrimary()
        assertEquals(0, imports)
        assertTrue(chrome.navigationBar.accessibilityElementsHidden)
        assertTrue(chrome.tabBar.accessibilityElementsHidden)
        chrome.beginSession()
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
        assertFalse(chrome.navigationBar.hidden)
        chrome.clear()
    }

    @Test
    fun viewer_cover_hides_native_chrome_even_when_underlying_shell_updates_then_restores_current_actions() {
        var oldImports = 0
        var currentImports = 0
        val chrome = IosVaultChrome()
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions().copy(onCreateAlbum = { oldImports++ }), Color.Blue, false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS, libraryScopeTitle = "Private scope"),
            actions().copy(onCreateAlbum = { currentImports++ }), Color.Blue, false, covered = true)
        assertTrue(chrome.navigationBar.hidden)
        assertTrue(chrome.tabBar.hidden)
        assertTrue(chrome.navigationBar.accessibilityElementsHidden)
        assertTrue(chrome.tabBar.accessibilityElementsHidden)
        chrome.performPrimary()
        assertEquals(0, currentImports)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions().copy(onCreateAlbum = { currentImports++ }), Color.Blue, false)
        assertFalse(chrome.navigationBar.hidden)
        assertFalse(chrome.tabBar.hidden)
        chrome.performPrimary()
        assertEquals(0, oldImports)
        assertEquals(1, currentImports)
        chrome.clear()
    }

    @Test
    fun empty_library_has_one_shared_import_action_and_selection_keeps_native_export() {
        var exports = 0
        val chrome = IosVaultChrome()
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
        assertFalse(chrome.navigationBar.topItem!!.rightBarButtonItems!!
            .filterIsInstance<platform.UIKit.UIBarButtonItem>().any { it.title == "Import" })
        chrome.update(VaultUiState(selectedCount = 2), actions().copy(onExportSelection = { exports++ }), Color.Blue, false)
        assertEquals("2 selected", chrome.navigationBar.topItem?.title)
        chrome.performPrimary()
        assertEquals(1, exports)
        chrome.clear()
    }

    @Test
    fun native_voiceover_panic_action_clears_chrome_before_lock_callback() {
        var panicked = false
        val chrome = IosVaultChrome()
        chrome.update(VaultUiState(), actions().copy(onPanic = {
            assertTrue(chrome.navigationBar.hidden)
            assertNull(chrome.navigationBar.topItem)
            panicked = true
        }), Color.Blue, false)
        val lock = chrome.navigationBar.topItem!!.rightBarButtonItems!!
            .filterIsInstance<platform.UIKit.UIBarButtonItem>()
            .mapNotNull { it.customView as? platform.UIKit.UIButton }.single { it.accessibilityLabel == "Lock now" }
        val panic = lock.accessibilityCustomActions!!.filterIsInstance<platform.UIKit.UIAccessibilityCustomAction>().single()
        assertEquals("Lock immediately", panic.name)
        assertTrue(panic.actionHandler!!.invoke(panic))
        assertTrue(panicked)
    }

    @Test
    fun modal_blocks_native_accessibility_commands_while_lock_remains_available() {
        var exports = 0
        var backs = 0
        var locks = 0
        val chrome = IosVaultChrome()
        chrome.update(VaultUiState(selectedCount = 2), actions().copy(
            onExportSelection = { exports++ }, onClearSelection = { backs++ }, onLock = { locks++ },
        ), Color.Blue, true)
        val back = chrome.navigationBar.topItem!!.leftBarButtonItem!!.customView as platform.UIKit.UIButton
        assertFalse(back.enabled)
        chrome.performPrimary()
        chrome.goBack()
        assertEquals(0, exports)
        assertEquals(0, backs)
        chrome.lockNow()
        assertEquals(1, locks)
    }

    private fun actions() = VaultActions(
        onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
        onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
        onAddRecoverySlot = {}, onChangePassword = {},
    )
}
