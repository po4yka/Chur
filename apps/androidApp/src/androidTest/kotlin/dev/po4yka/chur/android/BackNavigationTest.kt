package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back on the routes of `ChurRoutes` and inside the vault.
 *
 * Without a handler Back is the platform's back-to-home: the activity pauses,
 * and the background lock of `MainActivity.onPause` closes the vault and drops
 * what the screen held. Each case presses Back once and checks that the
 * application stayed in front and landed where the screen's own control goes.
 * The order of the vault's ladder is a pure function, pinned by
 * `VaultBackTest`; the vault cases here pin that the host delivers it, and the
 * viewer's own handlers.
 *
 * The vault cases open a vault of their own: they create it on an application
 * without one and unlock it on later runs, and they keep it from growing. A
 * device whose vault refuses this test's password skips them rather than
 * touching that vault. The screens are driven through the accessibility tree,
 * which needs no test dependency.
 */
@RunWith(AndroidJUnit4::class)
class BackNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val controller: ChurController get() = ChurHost.of(activity).controller

    @Before
    fun start() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        assertTrue("the vault must start", await { controller.vaultState.value !is VaultState.Starting })
    }

    @After
    fun finish() {
        activity.finish()
    }

    // -----------------------------------------------------------------------
    // Routes
    // -----------------------------------------------------------------------

    @Test
    fun theNotesRootLetsBackThrough() {
        lockIfOpen()
        instrumentation.runOnMainSync { controller.goTo(AppRoute.PublicShell) }
        assertFalse(backIsHandled())
    }

    @Test
    fun publicSettingsReturnsToNotes() {
        lockIfOpen()
        assertEquals(AppRoute.PublicShell, backFrom(AppRoute.PublicSettings))
    }

    @Test
    fun unlockReturnsToTheSettingsEntry() {
        lockIfOpen()
        assertEquals(AppRoute.PublicSettings, backFrom(AppRoute.Unlock))
    }

    @Test
    fun recoveryReturnsToItsUnlockScreen() {
        lockIfOpen()
        assertEquals(AppRoute.Unlock, backFrom(AppRoute.Recover))
        assertEquals(AppRoute.AppUnlock, backFrom(AppRoute.AppRecover))
    }

    @Test
    fun creationAndRestoreWithoutASessionReturnToNotes() {
        lockIfOpen()
        assertEquals(AppRoute.PublicShell, backFrom(AppRoute.CreateVault))
        assertEquals(AppRoute.PublicShell, backFrom(AppRoute.RestoreBackup))
    }

    // -----------------------------------------------------------------------
    // The vault
    // -----------------------------------------------------------------------

    @Test
    fun theLibraryRootLetsBackThrough() = inTestVault {
        assertFalse(backIsHandled())
    }

    @Test
    fun backLeavesAnotherTabForTheLibrary() = inTestVault {
        tap(label("Albums"))
        pressBack()
        assertFalse("back at the Library root", backIsHandled())
    }

    @Test
    fun backClosesAnAlbumAndThenItsTab() = inTestVault {
        tap(label("Albums"))
        // The tab loads the albums; the album is created on the first run only.
        if (!await(3_000) { find(label(ALBUM)) != null }) {
            instrumentation.runOnMainSync { controller.createAlbum(ALBUM) }
        }
        tap(label(ALBUM))
        assertTrue("the album opens", await { find(label("Back")) != null })

        pressBack()

        assertTrue("the album closes", await { find(label("Back")) == null })
        assertTrue("still on the Albums tab", backIsHandled())
        pressBack()
        assertFalse("back at the Library root", backIsHandled())
    }

    @Test
    fun backClosesTheInfoOverlayAndThenTheViewer() = inTestVault {
        // The photo is imported on the first run only.
        if (!await(3_000) { controller.page.value.objects.isNotEmpty() }) importPhoto()
        var tile: AccessibilityNodeInfo? = null
        assertTrue("a media tile", await { findTile()?.also { tile = it } != null })
        tap { it == tile }
        assertTrue("the viewer opens", await { find(label("Info")) != null })
        tap(label("Info"))
        // The overlay is drawn, and Back closes it, once the detail has loaded.
        assertTrue(
            "the overlay opens",
            await { find(label("Captured")) != null || find(label("No capture date recorded")) != null },
        )

        pressBack()

        assertTrue("the overlay closes and the viewer stays", await { find(label("Info")) != null })
        pressBack()
        assertTrue("the viewer closes", await { find(label("Info")) == null })
        assertFalse("back at the Library root", backIsHandled())
        assertTrue(controller.vaultState.value is VaultState.Unlocked)
    }

    @Test
    fun backDoesNotSpendTheOneShowingOfTheRecoveryPhrase() = inTestVault {
        val before = runBlocking { controller.vault.slots() }.map { it.id }.toSet()
        instrumentation.runOnMainSync { controller.addRecoverySlot() }
        try {
            assertTrue("the vault shows a new phrase", await(60_000) { controller.recoveryPhrase.value != null })

            pressBack()

            assertNotNull("Back must not leave the phrase", controller.recoveryPhrase.value)
        } finally {
            // A descriptor holds at most 16 slots, so each run removes the one
            // it added while the session is still open.
            instrumentation.runOnMainSync { controller.acknowledgeRecoveryPhrase() }
            runBlocking {
                controller.vault.slots().filter { it.id !in before }.forEach { controller.vault.removeSlot(it.slotId) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Shows [route], presses Back once, and returns where the press landed. */
    private fun backFrom(route: AppRoute): AppRoute {
        instrumentation.runOnMainSync { controller.goTo(route) }
        pressBack()
        return controller.route.value
    }

    private fun pressBack() {
        awaitFrames()
        instrumentation.runOnMainSync {
            // Without a handler the press below would go home. This fails at
            // once rather than through the pause that follows.
            assertTrue("the screen must register a Back handler", activity.onBackPressedDispatcher.hasEnabledCallbacks())
            activity.onBackPressedDispatcher.onBackPressed()
        }
        awaitFrames()
        assertEquals(
            "Back must stay in the application",
            Lifecycle.State.RESUMED,
            activity.lifecycle.currentState,
        )
    }

    private fun backIsHandled(): Boolean {
        awaitFrames()
        var handled = false
        instrumentation.runOnMainSync { handled = activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return handled
    }

    /** One frame recomposes the new state, the next runs the effects that register its handlers. */
    private fun awaitFrames() {
        repeat(2) {
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync {
                Choreographer.getInstance().postFrameCallback { frame.countDown() }
            }
            assertTrue(frame.await(5, TimeUnit.SECONDS))
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun lockIfOpen() {
        lockQuietly()
        assertTrue("the vault must lock", controller.vaultState.value !is VaultState.Unlocked)
    }

    /** Locks without asserting, so a cleanup cannot hide the failure it follows. */
    private fun lockQuietly() {
        if (controller.vaultState.value !is VaultState.Unlocked && controller.recoveryPhrase.value == null) return
        instrumentation.runOnMainSync {
            controller.acknowledgeRecoveryPhrase()
            controller.lock()
        }
        await { controller.vaultState.value !is VaultState.Unlocked }
    }

    private fun isOpen(): Boolean =
        controller.route.value == AppRoute.Vault && controller.vaultState.value is VaultState.Unlocked

    /** Runs [body] on the Library root of this test's vault, and locks it after. */
    private fun inTestVault(body: () -> Unit) {
        val opening = controller.vaultState.value
        instrumentation.runOnMainSync {
            controller.report(null)
            when (opening) {
                is VaultState.NoVault -> controller.create(PASSWORD, offerRecovery = false)
                is VaultState.Unlocked -> controller.goTo(AppRoute.Vault)
                else -> {
                    controller.goTo(AppRoute.Unlock)
                    controller.unlock(PASSWORD)
                }
            }
        }
        await(60_000) { isOpen() || controller.message.value != null }
        if (!isOpen()) {
            // Only a vault this test did not create refuses its password. Any
            // other way of not opening is a failure, not a reason to skip.
            assumeTrue("a vault this test did not create", opening is VaultState.Locked && controller.message.value != null)
            fail("the test vault did not open: ${controller.message.value}")
        }
        try {
            awaitFrames()
            body()
        } finally {
            lockQuietly()
        }
    }

    /** Imports one generated photograph through the production importer. */
    private fun importPhoto() {
        val context = instrumentation.targetContext
        // The application's own provider serves this directory, so the photo
        // arrives as a content URI with a name, a size and a type, as a picked
        // one does.
        val file = File(context.cacheDir, "export-scratch/back-test.jpg")
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val codec = AndroidMediaCodec(context.contentResolver)
        val outcome = runBlocking { controller.importMedia(MediaImporter(codec)) { codec.open(uri) } }
        assertTrue("the photo must import: $outcome", outcome is MediaImporter.Outcome.Imported)
        instrumentation.runOnMainSync { controller.reportImport(null) }
        assertTrue("the page shows it", await { controller.page.value.objects.isNotEmpty() })
    }

    private fun label(text: String): (AccessibilityNodeInfo) -> Boolean =
        { it.text?.toString() == text || it.contentDescription?.toString() == text }

    /**
     * A photo tile: a clickable node of the media grid's collection with no
     * label of its own and no text beneath it. Compose puts a control's label
     * on a child, so every other clickable node here - tabs, buttons, the
     * load-more row - has text somewhere beneath it; a video tile does too,
     * its duration.
     */
    private fun findTile(): AccessibilityNodeInfo? =
        findAll { it.collectionInfo != null }.firstNotNullOfOrNull { grid ->
            find(grid) {
                it.isClickable && it.text.isNullOrEmpty() && it.contentDescription.isNullOrEmpty() && !it.hasTextBelow()
            }
        }

    private fun AccessibilityNodeInfo.hasTextBelow(): Boolean =
        (0 until childCount).any { index ->
            val child = getChild(index) ?: return@any false
            !child.text.isNullOrEmpty() || child.hasTextBelow()
        }

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        find(instrumentation.uiAutomation.rootInActiveWindow, match)

    private fun find(from: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (from == null) return null
        if (match(from)) return from
        for (index in 0 until from.childCount) find(from.getChild(index), match)?.let { return it }
        return null
    }

    private fun findAll(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (match(node)) found += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }

    /** Clicks the first node [match] finds, through its nearest clickable ancestor. */
    private fun tap(match: (AccessibilityNodeInfo) -> Boolean) {
        var node: AccessibilityNodeInfo? = null
        assertTrue("nothing to tap", await { find(match)?.also { node = it } != null })
        var target = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("the tap must land", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        awaitFrames()
    }

    private companion object {
        const val PASSWORD = "BackNavigationTest-password"
        const val ALBUM = "Back test album"
    }
}
