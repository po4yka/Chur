@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import dev.po4yka.chur.app.ActiveOperation
import kotlinx.cinterop.objcPtr
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.separatorColor
import platform.UIKit.UINavigationBarAppearance
import platform.UIKit.UITabBarAppearance
import platform.UIKit.UIViewController
import platform.UIKit.accessibilityLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosVaultScrollTest {
    @Test
    fun real_native_appearances_follow_each_edge_without_replacing_lock_or_bar_geometry() {
        val chrome = prepare()
        val lock = lock(chrome)
        val source = source(chrome)
        source.update(VaultScrollEdges(atTop = true, atBottom = false))
        assertEdges(chrome, atTop = true, atBottom = false)
        source.update(VaultScrollEdges(atTop = false, atBottom = false))
        assertEdges(chrome, atTop = false, atBottom = false)
        source.update(VaultScrollEdges(atTop = false, atBottom = true))
        assertEdges(chrome, atTop = false, atBottom = true)
        source.update(VaultScrollEdges(atTop = true, atBottom = true))
        assertEdges(chrome, atTop = true, atBottom = true)
        assertEquals(lock.objcPtr(), lock(chrome).objcPtr())
        assertTrue(lock.enabled)
        assertFalse(chrome.navigationControllers.first().navigationBarHidden)
        assertFalse(chrome.tabBar.hidden)
        chrome.clear()
    }

    @Test
    fun replacing_grid_list_or_query_source_revokes_retained_callbacks_and_old_disposal() {
        val chrome = prepare()
        val oldObserver = chrome.scrollObserver(1, IosVaultScreen.Root(VaultDestination.LIBRARY)) { true }
        val old = oldObserver.attach()
        old.update(VaultScrollEdges(true, true))
        val current = source(chrome)
        current.update(VaultScrollEdges(false, false))
        old.update(VaultScrollEdges(true, true))
        old.dispose()
        oldObserver.attach().update(VaultScrollEdges(true, true))
        assertEdges(chrome, false, false)
        current.update(VaultScrollEdges(true, true))
        assertEdges(chrome, true, true)
        current.dispose()
        assertDefaultNativeAppearance(chrome)
        current.update(VaultScrollEdges(true, true))
        assertDefaultNativeAppearance(chrome)
        chrome.clear()
    }

    @Test
    fun route_guard_rejects_metrics_before_compose_publishes_modal_query_or_operation() {
        val chrome = prepare()
        var allowed = true
        val observation = source(chrome) { allowed }
        observation.update(VaultScrollEdges(true, false))
        allowed = false
        observation.update(VaultScrollEdges(false, true))
        // A blocked callback cannot cache metrics to replay after the route opens.
        allowed = true
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
        assertEdges(chrome, true, false)
        chrome.clear()
    }

    @Test
    fun selection_operation_busy_and_modal_reset_to_native_defaults_and_do_not_cache_covered_scroll() {
        val chrome = prepare()
        var observation = source(chrome)
        observation.update(VaultScrollEdges(true, true))
        for (state in listOf(
            VaultUiState(selectedCount = 1, selectionIds = setOf("item")),
            VaultUiState(operation = ActiveOperation(1, "import")),
        )) {
            chrome.update(state, actions(), Color.Blue, false)
            assertDefaultNativeAppearance(chrome)
            observation.update(VaultScrollEdges(false, false))
            chrome.update(VaultUiState(), actions(), Color.Blue, false)
            assertTrue(chrome.scrollInteractionEnabled)
            assertDefaultNativeAppearance(chrome)
            // Scene reattachment on false -> true reads the current viewport,
            // even when snapshotFlow already emitted its edges while selected.
            observation = source(chrome)
            observation.update(VaultScrollEdges(false, true))
            assertEdges(chrome, false, true)
            observation.update(VaultScrollEdges(true, true))
            assertTrue(lock(chrome).enabled)
        }
        chrome.suspendNavigation()
        assertDefaultNativeAppearance(chrome)
        observation.update(VaultScrollEdges(false, false))
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
        assertDefaultNativeAppearance(chrome)
        observation = source(chrome)
        observation.update(VaultScrollEdges(true, true))
        chrome.setPrivateModalActive(true)
        assertDefaultNativeAppearance(chrome)
        observation.update(VaultScrollEdges(false, false))
        chrome.setPrivateModalActive(false)
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
        assertDefaultNativeAppearance(chrome)
        observation = source(chrome)
        observation.update(VaultScrollEdges(false, true))
        assertEdges(chrome, false, true)
        chrome.clear()
    }

    @Test
    fun native_search_and_transition_freeze_native_defaults_until_the_real_delegate_finishes() {
        val chrome = prepare()
        var observation = source(chrome)
        observation.update(VaultScrollEdges(true, true))
        val search = chrome.navigationBar.topItem!!.searchController!!
        assertTrue(search.searchBar.delegate!!.searchBarShouldBeginEditing(search.searchBar))
        assertDefaultNativeAppearance(chrome)
        observation.update(VaultScrollEdges(false, false))
        search.delegate!!.didDismissSearchController(search)
        assertTrue(chrome.scrollInteractionEnabled)
        assertDefaultNativeAppearance(chrome)
        observation = source(chrome)
        observation.update(VaultScrollEdges(true, true))
        val nav = chrome.navigationControllers.first()
        val content = nav.topViewController!!
        chrome.navigationController(nav, willShowViewController = content, animated = true)
        assertDefaultNativeAppearance(chrome)
        observation.update(VaultScrollEdges(false, false))
        chrome.navigationController(nav, didShowViewController = content, animated = true)
        assertTrue(chrome.scrollInteractionEnabled)
        assertDefaultNativeAppearance(chrome)
        source(chrome).update(VaultScrollEdges(false, true))
        assertEdges(chrome, false, true)
        chrome.clear()
    }

    @Test
    fun inactive_root_and_retired_generation_cannot_change_the_current_native_bars() {
        val chrome = prepare()
        val old = source(chrome)
        old.update(VaultScrollEdges(true, true))
        val screen = IosVaultScreen.Root(VaultDestination.ALBUMS)
        chrome.reconcile(1, listOf(screen), VaultDestination.ALBUMS, animated = false)
        chrome.update(VaultUiState(destination = VaultDestination.ALBUMS), actions(), Color.Blue, false)
        val albums = chrome.scrollObserver(1, screen) { true }.attach()
        albums.update(VaultScrollEdges(false, true))
        old.update(VaultScrollEdges(true, false))
        assertEdges(chrome, false, true)
        chrome.clear()
        mount(chrome, generation = 2)
        val fresh = source(chrome, generation = 2)
        fresh.update(VaultScrollEdges(false, false))
        old.update(VaultScrollEdges(true, true))
        albums.dispose()
        assertEdges(chrome, false, false)
        fresh.update(VaultScrollEdges(true, true))
        assertEdges(chrome, true, true)
        chrome.clear()
    }

    @Test
    fun lock_conceals_first_and_retained_observation_cannot_reopen_a_fresh_session() {
        val chrome = prepare()
        var locked = false
        chrome.update(VaultUiState(), actions().copy(onLock = {
            assertTrue(chrome.controller.view.hidden)
            assertTrue(chrome.navigationBar.hidden)
            locked = true
        }), Color.Blue, false)
        val observation = source(chrome)
        observation.update(VaultScrollEdges(false, false))
        chrome.lockNow()
        assertTrue(locked)
        observation.update(VaultScrollEdges(true, true))
        mount(chrome, generation = 2)
        assertDefaultNativeAppearance(chrome)
        observation.update(VaultScrollEdges(true, true))
        observation.dispose()
        assertDefaultNativeAppearance(chrome)
        assertTrue(lock(chrome).enabled)
        chrome.clear()
    }

    @Test
    fun viewport_attached_during_native_factory_is_adopted_without_reusing_a_retired_visit() {
        val chrome = IosVaultChrome()
        val screen = IosVaultScreen.Root(VaultDestination.LIBRARY)
        var early: VaultScrollObservation? = null
        chrome.configureScreens(factory = { current ->
            if (current == screen) early = chrome.scrollObserver(1, current) { true }.attach()
            UIViewController()
        }, onPop = {}, onRetire = {})
        chrome.reconcile(1, listOf(screen), VaultDestination.LIBRARY, animated = false)
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
        assertNotNull(early).update(VaultScrollEdges(true, true))
        assertEdges(chrome, true, true)
        chrome.clear()
        assertNotNull(early).update(VaultScrollEdges(false, false))
        mount(chrome, generation = 2)
        assertDefaultNativeAppearance(chrome)
        chrome.clear()
    }

    private fun assertEdges(chrome: IosVaultChrome, atTop: Boolean, atBottom: Boolean) {
        val transparent = UINavigationBarAppearance().apply { configureWithTransparentBackground() }
        val bar = chrome.navigationBar
        listOf(bar.standardAppearance, bar.compactAppearance!!,
            bar.scrollEdgeAppearance!!, bar.compactScrollEdgeAppearance!!).forEach {
            if (atTop) assertTrue(it.isEqual(transparent)) else {
                assertTrue(assertNotNull(it.shadowColor).isEqual(UIColor.separatorColor))
                assertFalse(it.isEqual(transparent), "Scrolled navigation must retain a distinct native appearance")
            }
        }
        val tabs = UITabBarAppearance().apply {
            if (atBottom) configureWithTransparentBackground() else configureWithDefaultBackground()
        }
        listOf(chrome.tabBar.standardAppearance, chrome.tabBar.scrollEdgeAppearance!!).forEach {
            assertTrue(it.isEqual(tabs))
        }
    }

    private fun assertDefaultNativeAppearance(chrome: IosVaultChrome) {
        // UIKit's native defaults are not equivalent to a non-null blur. On
        // iOS 26 navigation defaults and transparent both have no backgroundEffect.
        val navigation = UINavigationBarAppearance().apply { configureWithDefaultBackground() }
        val tabs = UITabBarAppearance().apply { configureWithDefaultBackground() }
        val bar = chrome.navigationBar
        listOf(bar.standardAppearance, bar.compactAppearance!!,
            bar.scrollEdgeAppearance!!, bar.compactScrollEdgeAppearance!!).forEach {
            assertTrue(it.isEqual(navigation), "Blocked or unobserved navigation must use UIKit defaults")
        }
        listOf(chrome.tabBar.standardAppearance, chrome.tabBar.scrollEdgeAppearance!!).forEach {
            assertTrue(it.isEqual(tabs), "Blocked or unobserved tabs must use UIKit defaults")
        }
    }

    private fun source(chrome: IosVaultChrome, generation: Long = 1, guard: () -> Boolean = { true }) =
        chrome.scrollObserver(generation, IosVaultScreen.Root(VaultDestination.LIBRARY), guard).attach()

    private fun lock(chrome: IosVaultChrome) = chrome.navigationBar.topItem!!.rightBarButtonItems!!
        .filterIsInstance<UIBarButtonItem>().mapNotNull { it.customView as? UIButton }
        .single { it.accessibilityLabel == "Lock now" }

    private fun prepare() = IosVaultChrome().also { mount(it) }
    private fun mount(chrome: IosVaultChrome, generation: Long = 1) {
        chrome.configureScreens(factory = { UIViewController() }, onPop = {}, onRetire = {})
        chrome.reconcile(generation, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY)),
            VaultDestination.LIBRARY, animated = false)
        chrome.update(VaultUiState(), actions(), Color.Blue, false)
    }
    private fun actions() = VaultActions(onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
        onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
        onAddRecoverySlot = {}, onChangePassword = {})
}
