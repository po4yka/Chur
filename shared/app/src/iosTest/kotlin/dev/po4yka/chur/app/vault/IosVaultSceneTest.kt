@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.ui.graphics.Color
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.GateResult
import dev.po4yka.chur.app.NoPrivacyCover
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.runUntilDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIViewController
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosVaultSceneTest {
    @Test
    fun expanded_presentation_activates_both_leases_and_clear_retires_both_bodies() {
        val controller = inertController()
        val chrome = IosVaultChrome()
        val scenes = IosVaultScenes(controller, GateResult.Compatible(0uL), chrome)
        val root = IosVaultScreen.Root(VaultDestination.LIBRARY)
        val viewer = IosVaultScreen.Viewer(1L)
        scenes.begin(1L)
        val libraryLease = scenes.publish(root) {}
        val viewerLease = scenes.publish(viewer) {}
        scenes.activateAll(setOf(root, viewer))
        assertTrue(libraryLease.active)
        assertTrue(viewerLease.active)
        scenes.activateAll(setOf(viewer))
        assertFalse(libraryLease.active)
        assertTrue(viewerLease.active)
        scenes.activateAll(setOf(root, viewer))
        scenes.clear()
        listOf(libraryLease, viewerLease).forEach {
            assertTrue(it.retired)
            assertFalse(it.active)
            assertNull(it.content)
        }
        scenes.activateAll(setOf(root, viewer))
        assertFalse(libraryLease.active)
        assertFalse(viewerLease.active)
        chrome.clear()
    }

    @Test
    fun retired_scene_clears_content_and_cannot_be_reactivated() {
        val controller = inertController()
        val chrome = IosVaultChrome()
        val scene = IosVaultScene(1L, controller, chrome)
        scene.active = true
        scene.content = {}
        assertNotNull(scene.content)
        scene.retire()
        scene.retire()
        assertTrue(scene.retired)
        assertFalse(scene.active)
        assertNull(scene.content)
        scene.active = true
        assertFalse(scene.canAct())
        assertFalse(scene.canObserve())
        assertFalse(scene.canEscape())
        chrome.clear()
    }

    @Test
    fun retiring_slot_releases_private_body_even_while_native_controller_is_retained() {
        val controller = inertController()
        val chrome = IosVaultChrome()
        val scenes = IosVaultScenes(controller, GateResult.Compatible(0uL), chrome)
        val album = IosVaultScreen.Album("private-album")
        scenes.begin(1L)
        val body = publishPrivateBody(scenes, album)
        val outgoing = scenes.createController(album)
        scenes.activate(album)
        scenes.retire(album)
        // Publishing the same key in the same session creates a new lease;
        // the retained outgoing controller must not retain the old body.
        scenes.publish(album) {}
        val replacement = scenes.createController(album)
        scenes.activate(album)
        GC.collect()
        GC.collect()
        assertNull(body.get())
        assertTrue(outgoing !== replacement)
        scenes.clear()
        chrome.clear()
    }

    @Test
    fun clear_releases_all_bodies_and_resets_viewer_status_before_another_visit() {
        val controller = inertController()
        val chrome = IosVaultChrome()
        val scenes = IosVaultScenes(controller, GateResult.Compatible(0uL), chrome)
        val root = IosVaultScreen.Root(VaultDestination.LIBRARY)
        val viewer = IosVaultScreen.Viewer(1L)
        val status = mutableListOf<Boolean?>()
        scenes.viewerChrome = { status += it }
        scenes.begin(1L)
        val rootBody = publishPrivateBody(scenes, root)
        val viewerBody = publishPrivateBody(scenes, viewer)
        val retained = listOf(scenes.createController(root), scenes.createController(viewer))
        scenes.activate(viewer)
        scenes.clear()
        assertEquals(listOf<Boolean?>(null, null), status)
        scenes.begin(1L)
        scenes.publish(root) {}
        scenes.activate(root)
        GC.collect()
        GC.collect()
        assertNull(rootBody.get())
        assertNull(viewerBody.get())
        assertEquals(2, retained.size)
        scenes.clear()
        chrome.clear()
    }

    @Test
    fun guarded_actions_do_not_retain_private_callbacks_after_raw_snapshot_is_released() {
        val controller = inertController()
        val chrome = IosVaultChrome()
        val scene = IosVaultScene(1L, controller, chrome)
        val retained = privateActions(scene)
        scene.retire()
        GC.collect()
        GC.collect()
        assertNull(retained.payload.get())
        retained.actions.onImport()
        retained.actions.onVisibleMediaChanged(setOf("retired"))
        retained.actions.onLock()
        chrome.clear()
    }

    @Test
    fun live_native_controller_guards_transition_retirement_route_and_generation() {
        val rootPath = NSTemporaryDirectory() + "chur-native-scenes-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(rootPath, true, null, null))
        val controller = ChurController(rootPath, NoPrivacyCover, NoExports, clock = { 1_700_000_000_000L })
        val chrome = IosVaultChrome()
        try {
            // Start the actual repository directly: this test needs no app's
            // lifetime idle timer and creates no fake unlocked controller flow.
            runBlocking { controller.vault.start() }
            controller.create("Native-scene-public-fixture", offerRecovery = false)
            await { controller.route.value == AppRoute.Vault && controller.vaultState.value is VaultState.Unlocked }
            val generation = (controller.vaultState.value as VaultState.Unlocked).generation
            chrome.configureScreens(factory = { UIViewController() }, onPop = {}, onRetire = {})
            chrome.reconcile(generation, listOf(IosVaultScreen.Root(VaultDestination.LIBRARY),
                IosVaultScreen.Viewer(1L)), VaultDestination.LIBRARY, animated = false)
            val scene = IosVaultScene(generation, controller, chrome).also { it.active = true }
            var imports = 0
            var observations = 0
            var pagination = 0
            var escapes = 0
            val raw = actions().copy(onImport = { imports++ }, onLoadMore = { pagination++ },
                onVisibleMediaChanged = { observations++ }, onLock = { escapes++ })
            val guarded = raw.forScene(scene)
            chrome.update(VaultUiState(), raw, Color.Blue, false)
            guarded.onImport()
            assertEquals(1, imports)
            val nav = chrome.navigationControllers.first()
            val albumTarget = nav.viewControllers!!.first() as UIViewController
            chrome.navigationController(nav, willShowViewController = albumTarget, animated = true)
            assertTrue(chrome.transitionInFlight)
            guarded.onImport()
            guarded.onVisibleMediaChanged(emptySet())
            guarded.onLoadMore()
            guarded.onLock()
            assertEquals(1, imports)
            assertEquals(1, observations)
            assertEquals(1, pagination)
            assertEquals(1, escapes)
            chrome.navigationController(nav, didShowViewController = nav.topViewController!!, animated = true)
            assertFalse(chrome.transitionInFlight)
            scene.retire()
            val next = IosVaultScene(generation, controller, chrome).also { it.active = true }
            guarded.onImport()
            guarded.onLoadMore()
            raw.forScene(next).onImport()
            assertEquals(2, imports)
            assertEquals(1, pagination)
            assertFalse(scene.canEscape())
            assertTrue(next.canAct())
            next.active = false
            assertFalse(next.canAct())
            assertFalse(next.canObserve())
            assertTrue(next.canEscape())
            next.active = true
            // Creation publishes Vault before its initial page load finishes.
            // Use the actual lock/unlock lifecycle, rather than racing the
            // create request's still-active credential-entry guard.
            controller.lock()
            assertEquals(AppRoute.PublicShell, controller.route.value)
            assertFalse(next.canAct())
            assertFalse(next.canObserve())
            assertFalse(next.canEscape())
            await { controller.vaultState.value is VaultState.Locked }
            controller.openVaultEntry()
            assertEquals(AppRoute.Unlock, controller.route.value)
            controller.unlock("Native-scene-public-fixture")
            await { controller.route.value == AppRoute.Vault &&
                (controller.vaultState.value as? VaultState.Unlocked)?.generation?.let { it != generation } == true }
            assertFalse(next.canAct())
            assertFalse(next.canObserve())
            assertFalse(next.canEscape())
            val freshGeneration = (controller.vaultState.value as VaultState.Unlocked).generation
            val fresh = IosVaultScene(freshGeneration, controller, chrome).also { it.active = true }
            assertTrue(fresh.canAct())
            controller.lock()
            assertEquals(AppRoute.PublicShell, controller.route.value)
            assertFalse(fresh.canAct())
            assertFalse(fresh.canObserve())
            assertFalse(fresh.canEscape())
            await { controller.vaultState.value !is VaultState.Unlocked }
        } finally {
            chrome.clear()
            if (controller.route.value == AppRoute.Vault || controller.vaultState.value is VaultState.Unlocked) {
                controller.lock()
                await { controller.vaultState.value !is VaultState.Unlocked }
            }
            runBlocking { controller.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(rootPath, null)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = NSDate().timeIntervalSince1970 + 60.0
        while (!condition() && NSDate().timeIntervalSince1970 < deadline) {
            NSRunLoop.currentRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.02))
        }
        assertTrue(condition(), "Native scene fixture did not reach its requested controller state")
    }

    private class PrivatePayload(val text: String)

    private fun publishPrivateBody(scenes: IosVaultScenes, screen: IosVaultScreen): WeakReference<PrivatePayload> = autoreleasepool {
        val payload = PrivatePayload("Private render snapshot")
        scenes.publish(screen) { check(payload.text.isNotEmpty()) }
        WeakReference(payload)
    }

    private data class RetainedActions(val actions: VaultActions, val payload: WeakReference<PrivatePayload>)

    private fun privateActions(scene: IosVaultScene): RetainedActions = autoreleasepool {
        val payload = PrivatePayload("Private action snapshot")
        val raw = actions().copy(onImport = { check(payload.text.isNotEmpty()) })
        RetainedActions(raw.forScene(scene), WeakReference(payload))
    }

    private fun inertController() = ChurController("/nonexistent", NoPrivacyCover, NoExports, clock = { 0L })

    private object NoExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = null
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? = null
    }

    private fun actions() = VaultActions(
        onDestination = {}, onOpen = {}, onToggleSelection = {}, onImport = {}, onSearch = {},
        onOpenAlbum = {}, onCloseAlbum = {}, onCreateAlbum = {}, onLock = {}, onVerifyAll = {},
        onAddRecoverySlot = {}, onChangePassword = {},
    )
}
