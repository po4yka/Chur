@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.AdaptiveRegion
import dev.po4yka.chur.app.vault.IosVaultChrome
import dev.po4yka.chur.app.vault.IosVaultScreen
import dev.po4yka.chur.app.vault.VaultDestination
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIViewController
import platform.UIKit.childViewControllers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class IosAdaptiveRootTest {
    @Test
    fun hiding_viewer_controls_does_not_hide_status_above_the_visible_library() {
        val chrome = IosVaultChrome()
        chrome.configureScreens(factory = { UIViewController() }, onPop = {}, onRetire = {})
        val root = ChurRootViewController(UIViewController(), chrome)
        root.loadViewIfNeeded()
        root.view.setFrame(CGRectMake(0.0, 0.0, 900.0, 700.0))
        root.view.layoutIfNeeded()
        chrome.reconcile(1L, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY), IosVaultScreen.Viewer(1L)),
            VaultDestination.LIBRARY, animated = false)
        root.viewerChrome = false
        assertTrue(chrome.dualPane)
        assertFalse(root.prefersStatusBarHidden())
        root.view.setFrame(CGRectMake(0.0, 0.0, 400.0, 700.0))
        root.view.layoutIfNeeded()
        assertFalse(chrome.dualPane)
        assertTrue(root.prefersStatusBarHidden())
        chrome.clear()
    }

    @Test
    fun authentication_content_stays_in_one_region_and_recovers_full_bounds_when_fold_disappears() {
        val content = UIViewController()
        val chrome = IosVaultChrome()
        val root = ChurRootViewController(content, chrome)
        root.loadViewIfNeeded()
        root.view.setFrame(CGRectMake(0.0, 0.0, 900.0, 700.0))
        churUpdateLayout(root, emptyList(), listOf(AdaptiveRegion(440.0, 0.0, 20.0, 700.0)))
        root.view.layoutIfNeeded()
        content.view.frame.useContents {
            assertEquals(440.0, size.width)
            assertTrue(origin.x + size.width <= 440.0 || origin.x >= 460.0)
        }
        churUpdateLayout(root, emptyList(), emptyList())
        root.view.layoutIfNeeded()
        content.view.frame.useContents {
            assertEquals(0.0, origin.x)
            assertEquals(900.0, size.width)
            assertEquals(700.0, size.height)
        }
        chrome.clear()
    }

    @Test
    fun camera_occlusion_and_resize_update_public_content_without_replacing_controller() {
        val content = UIViewController()
        val chrome = IosVaultChrome()
        val root = ChurRootViewController(content, chrome)
        root.loadViewIfNeeded()
        root.view.setFrame(CGRectMake(0.0, 0.0, 900.0, 700.0))
        churUpdateLayout(root, listOf(AdaptiveRegion(0.0, 0.0, 900.0, 40.0)), emptyList())
        root.view.layoutIfNeeded()
        content.view.frame.useContents {
            assertEquals(40.0, origin.y)
            assertEquals(660.0, size.height)
        }
        root.view.setFrame(CGRectMake(0.0, 0.0, 400.0, 600.0))
        root.view.layoutIfNeeded()
        content.view.frame.useContents {
            assertEquals(400.0, size.width)
            assertEquals(560.0, size.height)
        }
        assertTrue(root.childViewControllers.contains(content))
        chrome.clear()
    }
}
