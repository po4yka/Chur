package dev.po4yka.chur.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.ExifInterface
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.AutoLock
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.RepositorySyncBoundary
import dev.po4yka.chur.app.vault.MEDIA_CLASS_AUDIO
import dev.po4yka.chur.app.vault.MEDIA_CLASS_IMAGE
import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.viewerStill
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.core.platformkeys.DeviceSlotPolicy
import dev.po4yka.chur.core.platformkeys.ownerFactorEnrolled
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.ffi.SyncProcessReport
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.sync.FileSyncStateStore
import dev.po4yka.chur.sync.SyncState
import dev.po4yka.chur.sync.SyncVaultBoundary
import dev.po4yka.chur.vault.VaultState
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back on the routes of `ChurRoutes`, in the note editor and inside the
 * vault.
 *
 * Without a handler Back is the platform's back-to-home: the activity pauses,
 * and the background lock of `MainActivity.onPause` closes the vault and drops
 * what the screen held. Each case presses Back once and checks that the
 * application stayed in front and landed where the screen's own control goes.
 * The note editor's cases also pin what leaving it writes, since that write,
 * not the press, is what keeps the draft, and that Delete asks before it
 * removes a note; they remove the notes they make.
 * The order of the vault's ladder is a pure function, pinned by
 * `VaultBackTest`; the vault cases here pin that the host delivers it, and the
 * viewer's own handlers.
 *
 * The vault cases open a vault of their own: they create it on an application
 * without one and unlock it on later runs, and they keep it from growing. A
 * device whose vault refuses this test's password skips them rather than
 * touching that vault. The screens are driven through the accessibility tree,
 * which needs no test dependency.
 *
 * The gate's forms are checked here too, since they share the harness: each
 * clears the status bar and the keyboard, `DESIGN.md` §25.5, and the keyboard's
 * own action does what the form's button does. A screen reader hears every
 * refused unlock, not only the first, §23.2. The vault's lock settings are
 * switches that show the state in force, and a tap flips it. Stopping sync
 * asks first, §26, and only the confirm forgets the server. Private playback
 * holds the audio focus, so another app's playback pauses it, and the lock
 * gives the focus up, `ANDROID.md` §17.3, with no media session published.
 * The player's own controls sit clear of the viewer's actions, `DESIGN.md`
 * §13.2, and show and hide with the viewer's chrome. A swipe away from the
 * player keeps the chrome. After the first play, a pause or the end brings
 * the controls back, and the chrome with them. A video's poster keeps the
 * controls under it off.
 * The viewer carries the lock control of `DISCREET_MODE.md` "The panic
 * gesture": a press locks and releases the player, and the panic is a screen
 * reader's custom action on the same control. A long press on a media tile
 * that does not move starts a selection, `DESIGN.md` §11.4, in the Library, in
 * an album and in Trash, and a screen reader starts one through the tile's
 * long-click action; the selection bar then reaches the bulk actions. A long
 * press that moves still drops the item into an album. A tile is named by its
 * kind and its length in words, §23.2. The viewer is drawn over the grid:
 * closing it, or a delete from it, leaves the grid where it was, a screen
 * reader and the keyboard focus cannot reach the shell under it, and Back
 * closes it before the scope under it. A photo too small for a screen preview
 * is shown from its original, at full size and upright. The activity's saved
 * state holds no object ID while the viewer shows a player, `ANDROID.md` §6.3.
 * A recovery phrase from Settings waits for the device authentication of
 * `DESIGN.md` §17.1 step 2; the cases that pass it need the screen-lock PIN as
 * the `devicePin` runner argument and are skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class BackNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val controller: ChurController get() = ChurHost.of(activity).controller

    @Before
    fun start() {
        // Each case starts as a finger leaves the screen. A key press, from an
        // earlier case or run, leaves touch mode for the whole display, and
        // the player holds its controls while the keyboard moves the focus.
        instrumentation.setInTouchMode(true)
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
    // The gate's forms
    // -----------------------------------------------------------------------

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun theCreationFormClearsTheBarsAndNextMovesToTheRepeat() = onGateRoute(AppRoute.CreateVault) {
        val title = boundsOf(label("Create a vault"))
        val safeTop = onWindow { insets, _ ->
            insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
        }
        assertTrue("the title must clear the status bar", title.top >= safeTop)

        val keyboardTop = showKeyboardFor(findAll { it.isEditable }.first())
        assertTrue(findAll { it.isEditable }.first().pressImeAction())
        assertTrue("Next moves to the repeat field", await { findAll { it.isEditable }.getOrNull(1)?.isFocused == true })
        assertFormEndsAbove(keyboardTop, "Create vault")
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun theGoKeyUnlocksAsTheButtonDoes() = inTestVault {
        lockQuietly()
        instrumentation.runOnMainSync { controller.goTo(AppRoute.Unlock) }
        assertTrue("the unlock form", await { find { it.isEditable } != null })
        assertFormEndsAbove(showKeyboardFor(find { it.isEditable }!!), "Unlock")

        // An empty field disables the button, and Go must not try either.
        assertTrue(find { it.isEditable }!!.pressImeAction())
        assertFalse(
            "an empty field must not be tried",
            await(1_000) { find(label("Unable to unlock.")) != null || controller.vaultState.value !is VaultState.Locked },
        )
        setText(find { it.isEditable }, PASSWORD)
        assertTrue(find { it.isEditable }!!.pressImeAction())
        assertTrue("Go opens the vault", await(60_000) { isOpen() })
    }

    @Test
    fun theRecoveryFormScrollsAboveTheKeyboard() = onGateRoute(AppRoute.Recover) {
        assertFormEndsAbove(showKeyboardFor(findAll { it.isEditable }.first()), "Recover")
    }

    @Test
    fun everyRefusedUnlockIsSpokenAgain() = inTestVault {
        lockQuietly()
        instrumentation.runOnMainSync { controller.goTo(AppRoute.Unlock) }
        assertTrue("the unlock form", await { find { it.isEditable } != null })
        val refusal = "Unable to unlock."
        repeat(2) { attempt ->
            instrumentation.runOnMainSync { controller.unlock("not the password") }
            // The refusal of the last attempt leaves the same locked state, so
            // the error region goes blank while this one runs.
            assertTrue(
                "attempt $attempt is busy",
                await { find(label("Opening")) != null && find(label(refusal)) == null },
            )
            assertTrue("attempt $attempt is refused", await(60_000) { find(label(refusal)) != null })
            // `DESIGN.md` §23.2: a polite live region speaks the refusal, and
            // the field carries it as its error rather than a generic one.
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, find(label(refusal))?.liveRegion)
            assertEquals(refusal, find { it.isEditable }?.error?.toString())
        }
    }

    // -----------------------------------------------------------------------
    // The note editor
    // -----------------------------------------------------------------------

    @Test
    fun backClosesTheNoteEditorAndKeepsTheDraft() = inNewNote {
        assertTrue("the editor holds Back", backIsHandled())

        // The text lands and Back is pressed in one main-thread message, so no
        // frame composes the text before the editor leaves. Its autosave never
        // starts, and only the save on leaving can write the draft, however
        // slow the device.
        pressBack { replaceBodyText(NOTE_TEXT) }

        assertEquals(AppRoute.PublicShell, controller.route.value)
        assertTrue("the draft is written as the editor leaves", await { madeNotes().any { it.body == NOTE_TEXT } })
        assertFalse("back at the Notes root", backIsHandled())
    }

    @Test
    fun deleteAfterAnAutosaveLeavesNoNote() = inNewNote {
        typeNote(NOTE_TEXT)
        assertTrue("the autosave writes the draft", await { madeNotes().any { it.body == NOTE_TEXT } })

        tap(label("Delete"))
        tap(label("Delete note"))

        assertTrue("the note is removed", await { madeNotes().isEmpty() })
        assertFalse("the save on leaving must not write it back", await(1_000) { madeNotes().isNotEmpty() })
    }

    @Test
    fun cancellingDeleteKeepsTheNoteAndTheEditor() = inNewNote {
        typeNote(NOTE_TEXT)
        assertTrue("the autosave writes the draft", await { madeNotes().any { it.body == NOTE_TEXT } })

        tap(label("Delete"))
        assertTrue("Delete asks first", await { find(label("Delete note")) != null })
        assertTrue("the note is kept while it asks", madeNotes().any { it.body == NOTE_TEXT })
        tap(label("Cancel"))

        assertTrue("the editor stays open", await { find(label("Delete")) != null })
        assertFalse("Cancel must not remove the note", await(1_000) { madeNotes().none { it.body == NOTE_TEXT } })
    }

    @Test
    fun aNewNoteErasedAgainLeavesNoEmptyRow() = inNewNote {
        typeNote(NOTE_TEXT)
        assertTrue("the autosave writes the draft", await { madeNotes().any { it.body == NOTE_TEXT } })
        typeNote("")

        pressBack()

        assertTrue("no empty row is left", await { madeNotes().isEmpty() })
    }

    @Test
    fun openingANoteTheUserEmptiedKeepsIt() = inNotes {
        // A note the user emptied: stored with text, then written blank. It is
        // pinned and the newest, so its row heads the list.
        val emptied = Note(id = "back-test-${System.nanoTime()}", title = "", body = NOTE_TEXT, updatedMs = 0L, pinned = true)
        instrumentation.runOnMainSync { controller.putNote(emptied) }
        assertTrue("the note is stored", await { madeNotes().any { it.body == NOTE_TEXT } })
        instrumentation.runOnMainSync { controller.putNote(emptied.copy(body = "")) }
        assertTrue("the note is stored blank", await { madeNotes().singleOrNull()?.body == "" })

        tap(label("Untitled"))
        assertTrue("the editor opens", await { find(label("Delete")) != null })
        assertFalse("the autosave must not remove it", await(1_000) { madeNotes().isEmpty() })
        pressBack()

        assertFalse("leaving must not remove it", await(1_000) { madeNotes().isEmpty() })
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
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_IMAGE } }) importPhoto()
        var tile: AccessibilityNodeInfo? = null
        assertTrue("a media tile", await { findTile()?.also { tile = it } != null })
        tap { it == tile }
        assertTrue("the viewer opens", await { find(label("Info")) != null })
        tap(label("Info"))
        // The overlay is drawn, and Back closes it, once the detail has loaded.
        assertTrue(
            "the overlay opens",
            await { find(::isCaptureLine) != null },
        )

        pressBack()

        assertTrue("the overlay closes and the viewer stays", await { find(label("Info")) != null })
        pressBack()
        assertTrue("the viewer closes", await { find(label("Info")) == null })
        assertFalse("back at the Library root", backIsHandled())
        assertTrue(controller.vaultState.value is VaultState.Unlocked)
    }

    @Test
    fun theViewerLeavesTheGridWhereItWas() = inTestVault {
        // A grid long enough to scroll; the photos go again at the end, so
        // the tiles other cases look for stay on the first screen.
        val imported = mutableListOf<ByteArray>()
        try {
            repeat(SCROLLING_LIBRARY) { index ->
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(0xFF000000.toInt() or index * 0x050A0F) }
                imported += importFile("grid-test.jpg") { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
            onGrids { it.single().config[SemanticsActions.ScrollToIndex].action?.invoke(SCROLLING_LIBRARY - 10) }
            awaitFrames()
            val place = onGrids { it.single().scroll }
            assertTrue("the grid scrolls", place > 0f)

            val tile = photoTile()
            tap { it == tile }
            assertTrue("the viewer opens", await { find(label("Info")) != null })
            // `PLAINTEXT_LIFECYCLE.md` §4: the grid under the viewer is out of
            // a screen reader's reach, and its semantics are cleared, not only
            // left out as covered.
            // The viewer's pager is a collection too, of one row: its pages.
            assertTrue("no grid behind the viewer", await { findAll { it.collectionInfo.let { info -> info != null && info.rowCount != 1 } }.isEmpty() })
            assertTrue("no grid semantics behind the viewer", onGrids { it.isEmpty() })
            pressBack()
            assertTrue("the viewer closes", await { find(label("Info")) == null })
            assertEquals("the grid shows the same rows", place, onGrids { it.single().scroll }, 0f)

            // The delete reloads the page once, and the grid never empties on
            // the way, which would also drop its place.
            val emptied = AtomicBoolean(false)
            val watch = CoroutineScope(Dispatchers.Main).launch {
                controller.page.collect { if (it.objects.isEmpty()) emptied.set(true) }
            }
            try {
                val shown = photoTile()
                tap { it == shown }
                tap(label("Move to Trash"))
                tap(label("Move to Trash"))
                assertTrue("the move is confirmed", await { find(label("Moved 1 item to Trash.")) != null })
            } finally {
                watch.cancel()
            }
            assertFalse("the grid never empties", emptied.get())
            assertTrue("the grid stays down", onGrids { it.single().scroll } > 0f)

            // The shell's Back is live under the viewer; the viewer's answers
            // first.
            tap(label("Browse"))
            tap(label("Trash"))
            val trashed = photoTile()
            tap { it == trashed }
            assertTrue("the viewer opens in Trash", await { find(label("Restore")) != null })
            pressBack()
            assertTrue("the viewer closes", await { find(label("Restore")) == null })
            assertTrue("Trash stays open", find(label("Trash")) != null && backIsHandled())
        } finally {
            settle { done -> controller.restoreTrash(done) }
            if (imported.isNotEmpty()) {
                settle { done -> controller.deleteAll(imported, done) }
                settle { done -> controller.permanentlyDeleteAll(imported, done) }
            }
        }
    }

    @Test
    fun aSwipeInTheViewerMovesToTheAdjacentItem() = inTestVault {
        // `DESIGN.md` §13.1. The newest photos lead the library, so these
        // three are the first items of the scope.
        val names = (1..3).map { "swipe-test-$it.jpg" }
        val imported = names.mapIndexed { index, name ->
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(0xFF000000.toInt() or (index + 1) * 0x304050) }
            importFile(name) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        try {
            val tile = photoTile()
            tap { it == tile }
            assertTrue("the viewer opens", await { find(label("Info")) != null })
            val first = nameInInfo(names)
            val media = boundsOf { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Hide controls" }
            }
            val (left, right, y) = Triple(media.left + media.width() / 6, media.right - media.width() / 6, media.centerY())

            shell("input swipe $right $y $left $y 250")
            assertFalse("a swipe is not a tap and keeps the chrome", await(1_000) { onScreen { nodes -> nodes.none { "Back" in it.labels } } })
            val second = nameInInfo(names, previous = first)

            shell("input swipe $left $y $right $y 250")
            assertEquals("a swipe back returns to the first item", first, nameInInfo(names, previous = second))
            pressBack()
            assertTrue("the viewer closes", await { find(label("Info")) == null })
        } finally {
            settle { done -> controller.deleteAll(imported, done) }
            settle { done -> controller.permanentlyDeleteAll(imported, done) }
        }
    }

    @Test
    fun aZoomedPhotoPansUnderOneFingerAndDoesNotSwipe() = inTestVault {
        // `DESIGN.md` §13.1: zoom and pan follow platform expectations.
        val names = (1..3).map { "zoom-test-$it.jpg" }
        val imported = names.mapIndexed { index, name ->
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(0xFF000000.toInt() or (index + 1) * 0x304050) }
            importFile(name) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        try {
            val tile = photoTile()
            tap { it == tile }
            assertTrue("the viewer opens", await { find(label("Info")) != null })
            val first = nameInInfo(names)
            val media = boundsOf { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Hide controls" }
            }
            val (left, right, y) = Triple(media.left + media.width() / 6, media.right - media.width() / 6, media.centerY())
            // A swipe the pager took has landed by then.
            val landed = { Thread.sleep(1_000) }

            doubleTap(media.centerX(), media.centerY())
            assertFalse("a double tap is not a tap and keeps the chrome", await(1_000) { find(label("Back")) == null })
            shell("input swipe $right $y $left $y 250")
            landed()
            assertEquals("one finger pans the zoomed photo and stays on it", first, nameInInfo(names))

            doubleTap(media.centerX(), media.centerY())
            awaitFrames()
            shell("input swipe $right $y $left $y 250")
            val second = nameInInfo(names, previous = first)

            pinch(media.centerX(), media.centerY(), from = media.height() / 12, to = media.height() / 3)
            awaitFrames()
            shell("input swipe $left $y $right $y 250")
            landed()
            assertEquals("a pinch zooms, and one finger pans", second, nameInInfo(names))
            pressBack()
            assertTrue("the viewer closes", await { find(label("Info")) == null })
        } finally {
            settle { done -> controller.deleteAll(imported, done) }
            settle { done -> controller.permanentlyDeleteAll(imported, done) }
        }
    }

    @Test
    fun theViewerTakesTheFocusFromTheShell() = inTestVault {
        withMedia()
        tap(label("Search"))
        var field: AccessibilityNodeInfo? = null
        assertTrue("the search field", await { find { it.isEditable }?.also { field = it } != null })
        showKeyboardFor(checkNotNull(field))
        setText(field, "jpg")
        val result = photoTile()
        tap { it == result }
        assertTrue("the viewer opens", await { find(label("Info")) != null })
        // `DESIGN.md` §23.3: the field under the viewer lets the focus go, so
        // the keyboard closes, the viewer stays edge to edge, and typing does
        // not reach the field. A hardware keyboard moves the focus to the
        // viewer, not back under it, where it would be out of sight.
        assertTrue(
            "the keyboard closes",
            await { !onWindow { insets, _ -> insets.isVisible(WindowInsetsCompat.Type.ime()) } },
        )
        shell("input text zz")
        shell("input keyevent KEYCODE_TAB")
        assertTrue("the focus moves to the viewer", await { focusedNodes() == 1 })
        shell("input text zz")
        pressBack()
        assertTrue("the viewer closes", await { find(label("Info")) == null })
        val after = find { it.isEditable }
        assertEquals("the terms stay", "jpg", after?.text?.toString())
        assertFalse("the field stays unfocused", checkNotNull(after).isFocused)
    }

    @Test
    fun backDoesNotSpendTheOneShowingOfTheRecoveryPhrase() = inTestVault {
        val pin = devicePin()
        val before = runBlocking { controller.vault.slots() }.map { it.id }.toSet()
        instrumentation.runOnMainSync { controller.addRecoverySlot() }
        passOwnerCheck(pin)
        assertTrue("the vault shows a new phrase", await(60_000) { controller.recoveryPhrase.value != null })

        pressBack()

        assertNotNull("Back must not leave the phrase", controller.recoveryPhrase.value)
        // Nor the step that asks for words of it back.
        tap(label("Show words"))
        tap(label("I have written them down"))
        pressBack()
        assertNotNull("Back must not leave the check", controller.recoveryPhrase.value)
        // The slot commits only when the phrase is confirmed, and a lock
        // discards it. A descriptor holds at most 16 slots, so the test vault
        // must not keep one per run; a failure above leaves through the same
        // lock in `inTestVault`.
        lockQuietly()
        assertTrue("the lock leaves the vault", await { controller.route.value != AppRoute.Vault })
        instrumentation.runOnMainSync {
            controller.goTo(AppRoute.Unlock)
            controller.unlock(PASSWORD)
        }
        assertTrue("the vault opens again", await(60_000) { isOpen() })
        assertEquals(before, runBlocking { controller.vault.slots() }.map { it.id }.toSet())
    }

    /**
     * `RECOVERY.md` §2.3 in the steps of `DESIGN.md` §17.1: the words appear
     * only on request, under the version marker and in order, and the slot
     * commits only once three of them are typed back.
     */
    @Test
    fun theRecoveryPhraseShowsOnRequestAndCommitsOnceThreeWordsAreTypedBack() = inTestVault {
        val pin = devicePin()
        val before = runBlocking { controller.vault.slots() }.map { it.id }.toSet()
        instrumentation.runOnMainSync { controller.addRecoverySlot() }
        passOwnerCheck(pin)
        assertTrue("the vault shows a new phrase", await(60_000) { controller.recoveryPhrase.value != null })
        val words = checkNotNull(controller.recoveryPhrase.value).split(" ")
        val cell = Regex("""^ ?\d{1,2}\. \S+$""")
        fun cells() = onScreen { nodes -> nodes.flatMap { it.labels }.filter { cell.matches(it) } }

        assertTrue("the reveal", await { find(label("Show words")) != null })
        assertEquals("no word before the reveal", emptyList<String>(), cells())
        tap(label("Show words"))
        assertTrue("the marker", await { find(label("chur-recovery-v1")) != null })
        assertEquals(words.mapIndexed { index, word -> "${index + 1}".padStart(2) + ". $word" }, cells())

        tap(label("I have written them down"))
        val asked = onScreen { nodes ->
            nodes.filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                .map { field -> field.labels.first { it.startsWith("Word ") }.removePrefix("Word ").toInt() - 1 }
        }
        assertEquals(3, asked.size)
        fun type(position: Int, text: String) = onScreen { nodes ->
            nodes.first { "Word ${position + 1}" in it.labels && it.config.getOrNull(SemanticsProperties.EditableText) != null }
                .config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
        }
        fun canContinue() = onScreen { nodes ->
            nodes.first { "Continue" in it.labels }.config.getOrNull(SemanticsProperties.Disabled) == null
        }

        // A wrong word names its position and never the word.
        type(asked[0], "zzzz")
        type(asked[1], words[asked[1]])
        type(asked[2], words[asked[2]])
        awaitFrames()
        assertTrue("the mismatch", onScreen { nodes -> nodes.any { "Word ${asked[0] + 1} does not match." in it.labels } })
        assertFalse("Continue waits for all three", canContinue())
        assertEquals("nothing commits yet", before, runBlocking { controller.vault.slots() }.map { it.id }.toSet())

        // §2.2: the first four letters decide, whatever the case.
        type(asked[0], words[asked[0]].uppercase() + "x")
        awaitFrames()
        assertTrue("three matching words allow Continue", canContinue())
        tap(label("Continue"))

        assertTrue("the user is told", await(60_000) { controller.notice.value?.text == "Recovery phrase saved." })
        val added = runBlocking { controller.vault.slots() }.filter { it.id !in before }
        assertEquals(listOf("Recovery"), added.map { it.familyName })
        // A descriptor holds at most 16 slots, so the test vault keeps none of these.
        runBlocking { added.forEach { controller.vault.removeSlot(it.slotId) } }
    }

    /**
     * `RECOVERY.md` §8 from Settings: once a phrase exists the row offers to
     * replace it and asks first, Cancel stages nothing, and the confirmed
     * phrase is the only one left: the old phrase no longer opens the vault.
     * Each phrase waits for the device authentication of `DESIGN.md` §17.1
     * step 2, and a cancelled prompt shows and stages nothing.
     */
    @Test
    fun replacingTheRecoveryPhraseAsksFirstAndRetiresTheOldOne() = inTestVault {
        val pin = devicePin()
        fun recovery() = runBlocking { controller.vault.slots() }.filter { it.familyName == "Recovery" }
        fun confirmShownPhrase(): String {
            assertTrue("the vault shows a new phrase", await(60_000) { controller.recoveryPhrase.value != null })
            val phrase = checkNotNull(controller.recoveryPhrase.value)
            instrumentation.runOnMainSync { controller.acknowledgeRecoveryPhrase() }
            assertTrue("back in the vault", await(60_000) { isOpen() })
            awaitFrames()
            return phrase
        }
        assertEquals("the test vault keeps no phrase", emptyList<Any>(), recovery())
        try {
            tap(label("Settings"))
            tap(label("Set up a recovery phrase"))
            passOwnerCheck(pin)
            val old = confirmShownPhrase()
            val first = recovery().single()

            tap(label("Settings"))
            tap(label("Replace recovery phrase"))
            assertTrue("it asks first", await { find(label("Replace recovery phrase?")) != null })
            tap(label("Cancel"))
            assertTrue("Cancel closes the question", await { find(label("Replace recovery phrase?")) == null })
            assertNull("Cancel stages no phrase", controller.recoveryPhrase.value)
            assertEquals(listOf(first), recovery())

            tap(label("Replace recovery phrase"))
            tap(label("Replace"))
            assertTrue("the device asks who it is", await { ownerPromptShown() })
            shell("input keyevent KEYCODE_BACK")
            assertTrue("Back cancels the prompt", await { !ownerPromptShown() })
            awaitFrames()
            assertNull("a cancelled prompt shows no phrase", controller.recoveryPhrase.value)
            assertNull("and says nothing", controller.notice.value)
            assertEquals(listOf(first), recovery())

            tap(label("Replace recovery phrase"))
            tap(label("Replace"))
            passOwnerCheck(pin)
            val new = confirmShownPhrase()
            val replaced = recovery()
            assertEquals(1, replaced.size)
            assertFalse("the old slot is gone", first in replaced)
            tap(label("Settings"))
            assertTrue("the row still offers a replace", await { find(label("Replace recovery phrase")) != null })
            assertEquals("the Access list shows one Recovery row", 1, findAll(label("Recovery")).size)

            lockIfOpen()
            instrumentation.runOnMainSync {
                controller.goTo(AppRoute.Recover)
                controller.recover(old)
            }
            assertTrue("the old phrase is refused", await(60_000) { controller.formError.value != null && !controller.unlocking.value })
            assertFalse(isOpen())
            instrumentation.runOnMainSync { controller.recover(new) }
            assertTrue("the new phrase opens the vault", await(60_000) { isOpen() })
        } finally {
            // A descriptor holds at most 16 slots, so the test vault keeps no
            // phrase of this run. The password slot stays, so the removal is
            // allowed.
            if (isOpen()) runCatching { runBlocking { recovery().forEach { controller.vault.removeSlot(it.slotId) } } }
        }
    }

    /**
     * `DESIGN.md` §17.1 step 2 under `Biometrics only`: the screen lock does
     * not pass, so a device with no strong biometric enrolled is told to set
     * one up and shows no phrase. The switch is restored after.
     */
    @Test
    fun biometricsOnlyKeepsTheScreenLockFromSettingUpARecoveryPhrase() = inTestVault {
        assumeFalse("a device with a strong biometric enrolled", ownerFactorEnrolled(activity, DeviceSlotPolicy.STRICT))
        // With a device slot the switch would re-enroll it behind a biometric.
        assumeTrue("a test vault with a device slot", runBlocking { controller.vault.slots() }.none { it.slotType == 2 })
        val before = controller.deviceSlotStrict.value
        try {
            tap(label("Settings"))
            if (!before) {
                tap(label("Biometrics only"))
                assertTrue("the switch turns on", await { controller.deviceSlotStrict.value })
            }
            tap { label("Set up a recovery phrase")(it) || label("Replace recovery phrase")(it) }
            find(label("Replace"))?.let { tap(label("Replace")) }
            assertTrue(
                "the device is told what to set up",
                await { controller.notice.value?.text?.startsWith("Set up a fingerprint or face unlock first.") == true },
            )
            assertFalse("and asks nothing", ownerPromptShown())
            assertNull("no phrase is shown", controller.recoveryPhrase.value)
        } finally {
            if (controller.deviceSlotStrict.value != before) {
                instrumentation.runOnMainSync { controller.toggleDeviceSlotPolicy() }
                await { controller.deviceSlotStrict.value == before }
            }
        }
    }

    @Test
    fun anImportOutcomeIsAnnouncedOnceAndStaysOnItsScreen() = inTestVault {
        val outcome = "Imported into vault."
        instrumentation.runOnMainSync { controller.reportImport(outcome) }

        // `DESIGN.md` §23.2: a screen reader hears the outcome. Material's
        // snackbar host makes its container a polite live region.
        assertTrue(
            "the outcome is in a polite live region",
            await { find { it.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE && find(it, label(outcome)) != null } != null },
        )
        // §26: a routine confirmation times out, and a tab change does not
        // bring it back.
        assertTrue("the snackbar times out", await(20_000) { find(label(outcome)) == null })
        tap(label("Albums"))
        pressBack()
        assertFalse("the outcome does not come back", await(2_000) { find(label(outcome)) != null })
        assertEquals(null, controller.notice.value)

        // "Set up a second vault" opens a clean creation form. It used to
        // show the vault's last outcome there, in error red.
        instrumentation.runOnMainSync {
            controller.report(outcome)
            controller.createSecondIdentity()
        }
        assertTrue("the creation form opens", await { find(label("Create a vault")) != null })
        assertTrue("with nothing left from the vault", find(label(outcome)) == null)
        tap(label("Not now"))
        assertTrue("back in the vault", await { isOpen() })
    }

    @Test
    fun theLockSettingsAreSwitchesThatShowTheirState() = inTestVault {
        tap(label("Settings"))
        // `KEY_SLOTS.md` §1: the row shows the device-slot policy in force.
        assertEquals(controller.deviceSlotStrict.value, switchRow("Biometrics only").isChecked)

        val before = controller.appLockEnabled.value
        try {
            assertEquals(before, switchRow("Lock whole app").isChecked)
            tap(label("Lock whole app"))
            assertTrue("the setting flips", await { controller.appLockEnabled.value != before })
            assertTrue("the switch shows it", await { switchRow("Lock whole app").isChecked != before })
            tap(label("Lock whole app"))
            assertTrue(
                "a second tap restores it",
                await { controller.appLockEnabled.value == before && switchRow("Lock whole app").isChecked == before },
            )
        } finally {
            // Whole-app lock left on would gate the Notes cases that follow.
            if (controller.appLockEnabled.value != before) {
                instrumentation.runOnMainSync { controller.toggleAppLock() }
                await { controller.appLockEnabled.value == before }
            }
        }
    }

    @Test
    fun theAutoLockRowShowsItsChoiceAndTheChoiceLocksAnIdleVault() = inTestVault {
        val before = controller.autoLock.value
        val chosen = AutoLock.AFTER_30_SECONDS
        try {
            tap(label("Settings"))
            // `DESIGN.md` §14.4: the row states the choice in force.
            assertNotNull("the row shows ${before.label}", find(autoLockRow(), label(before.label)))
            tap(label("Auto-lock"))
            // A reader hears each choice as a radio button and its state.
            AutoLock.entries.forEach { choice ->
                assertEquals("${choice.label} is checked", choice == before, radio(choice.label).isChecked)
            }
            tap(label(chosen.label))
            assertTrue("the choice applies", await { controller.autoLock.value == chosen })
            val appliedAt = SystemClock.elapsedRealtime()
            assertTrue("the row shows it", await { find(autoLockRow(), label(chosen.label)) != null })

            // Nothing on Settings reads the vault, so it is idle from here.
            assertTrue(
                "the vault locks after about 30 idle seconds",
                await(45_000) { controller.vaultState.value !is VaultState.Unlocked },
            )
            val waited = SystemClock.elapsedRealtime() - appliedAt
            assertTrue("not before the limit: $waited ms", waited >= 29_000)
        } finally {
            // Other cases need the default back, and it changes only in an
            // open vault.
            if (controller.autoLock.value != before) {
                if (controller.vaultState.value !is VaultState.Unlocked) {
                    instrumentation.runOnMainSync {
                        controller.goTo(AppRoute.Unlock)
                        controller.unlock(PASSWORD)
                    }
                    await(60_000) { isOpen() }
                }
                instrumentation.runOnMainSync { controller.setAutoLock(before) }
                await { controller.autoLock.value == before }
            }
        }
    }

    @Test
    fun stoppingSyncAsksFirstAndOnlyTheConfirmForgetsTheServer() = inTestVault {
        val sync = ChurHost.of(activity).sync
        // The state file `ChurHost` gives the engine. Disconnecting never
        // talks to the server, so a saved state that names no reachable server
        // stands in for a bootstrap, and the case needs no server of its own.
        val store = FileSyncStateStore(File(activity.noBackupFilesDir, "chur-sync.json").path)
        assumeTrue("a sync server this test did not configure", runBlocking { store.load() } == null)
        val objects = controller.page.value.objects.size
        try {
            runBlocking {
                store.save(savedServer("https://sync.invalid"))
                sync.refresh()
            }
            tap(label("Settings"))
            assertTrue(
                "the server's row must be on screen",
                await {
                    find(label("Stop using the server")) != null ||
                        find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
                },
            )

            tap(label("Stop using the server"))
            assertTrue("it asks first", await { find(label("Stop syncing with this server?")) != null })
            assertTrue("the server is kept while it asks", sync.status.value.configured)
            tap(label("Cancel"))
            assertTrue("Cancel closes the question", await { find(label("Stop syncing with this server?")) == null })
            assertFalse("Cancel must keep the server", await(1_000) { !sync.status.value.configured })

            tap(label("Stop using the server"))
            tap(label("Stop syncing"))
            assertTrue("the confirm forgets the server", await { !sync.status.value.configured })
            assertTrue(
                "the setup form returns and says what sync does not copy",
                await { find { it.text?.contains("Sync is not a backup.") == true } != null },
            )
            assertEquals("nothing in the vault changes", objects, controller.page.value.objects.size)
        } finally {
            // The case began with no state, so it leaves none.
            runBlocking { sync.disconnect() }
        }
    }

    @Test
    fun aForkVerdictIsABannerUntilTheServerIsForgotten() = inTestVault {
        val sync = ChurHost.of(activity).sync
        val store = FileSyncStateStore(File(activity.noBackupFilesDir, "chur-sync.json").path)
        assumeTrue("a sync server this test did not configure", runBlocking { store.load() } == null)
        // The vault as the engine sees it, except that its inbox pass reports
        // what the native engine reports for two different signed records at
        // one device sequence: one rejection, and the fork as its status.
        val real = RepositorySyncBoundary(controller.vault)
        sync.bind(
            object : SyncVaultBoundary by real {
                override suspend fun process(): SyncProcessReport =
                    SyncProcessReport(0, 0, 0, 1, ChurStatus.SYNC_CHAIN_FORK.value)
            },
        )
        try {
            runBlocking {
                store.save(savedServer("https://sync.invalid"))
                sync.refresh()
                // What an unlock does with the records staged while locked.
                sync.applyStaged()
            }
            tap(label("Settings"))
            assertTrue("the verdict is a banner in the Sync section", scrollUntil(label("Sync stopped for one device")))
            assertTrue("the banner's action must be on screen", scrollUntil(label("Stop using the server")))

            tap(label("Stop using the server"))
            assertTrue("it asks first", await { find(label("Stop syncing with this server?")) != null })
            tap(label("Stop syncing"))
            assertTrue("forgetting the server clears the verdict", await { sync.status.value.integrityStop == null })
            assertTrue("and the banner with it", await { find(label("Sync stopped for one device")) == null })
        } finally {
            sync.bind(real)
            // The case began with no state, so it leaves none.
            runBlocking { sync.disconnect() }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun aLocalServerAsksForLocalNetworkAccessWithTheVaultOpen() = inTestVault {
        val sync = ChurHost.of(activity).sync
        assumeFalse("a sync server this test did not configure", sync.status.value.configured)
        assumeFalse("local network access this test did not grant", localNetworkGranted())
        try {
            tap(label("Settings"))
            assertTrue(
                "the setup form must be on screen",
                await {
                    find(label("Connect")) != null ||
                        find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
                },
            )
            val fields = findAll { it.isEditable }
            assertEquals("the address and the secret", 2, fields.size)
            // The emulator's host is a private address, and a loopback
            // forward cannot stand in for it: loopback needs no access.
            setText(fields[0], "https://10.0.2.2:9")
            setText(fields[1], "ab".repeat(32))
            tap(label("Connect"))

            assertTrue("the system asks for local network access", await { ownerPromptShown() })
            assertTrue("the vault stays open under the request", controller.vaultState.value is VaultState.Unlocked)
            tap { it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true }
            assertTrue("a refusal says how to allow it", await { find(label(LOCAL_NETWORK_OFF)) != null })
            assertTrue("the vault is still open", controller.vaultState.value is VaultState.Unlocked)
            assertFalse("nothing is configured", sync.status.value.configured)
        } finally {
            // A second refusal would stop the system from asking at all, so
            // the case leaves the permission as it found it.
            shell("pm clear-permission-flags ${activity.packageName} ${Manifest.permission.ACCESS_LOCAL_NETWORK} user-set user-fixed")
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun settingsNamesMissingLocalNetworkAccessWithoutAsking() = inTestVault {
        val sync = ChurHost.of(activity).sync
        val store = FileSyncStateStore(File(activity.noBackupFilesDir, "chur-sync.json").path)
        assumeTrue("a sync server this test did not configure", runBlocking { store.load() } == null)
        assumeFalse("local network access this test did not grant", localNetworkGranted())
        try {
            // What a revoked grant leaves: a configured local server, which
            // the worker and every run only time out on.
            runBlocking {
                store.save(savedServer("https://10.0.2.2:9"))
                sync.refresh()
            }
            tap(label("Settings"))
            assertTrue(
                "Settings names the cause",
                await {
                    find(label(LOCAL_NETWORK_OFF)) != null ||
                        find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
                },
            )
            assertFalse("nothing asks until the user syncs", ownerPromptShown())
        } finally {
            // The case began with no state, so it leaves none.
            runBlocking { sync.disconnect() }
        }
    }

    /**
     * A saved server that belongs to the open vault. The engine shows a saved
     * server only to the vault that configured it, so a state for any other
     * identity would read as no server at all.
     */
    private suspend fun savedServer(serverUrl: String): SyncState {
        val identity = checkNotNull(controller.vault.syncIdentity()) { "the test vault is open" }
        return SyncState(serverUrl, identity.vaultId, identity.deviceId, ByteArray(32), emptyList())
    }

    private fun localNetworkGranted(): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    @Test
    fun privatePlaybackYieldsTheAudioFocusAndTheLockGivesItUp() = inTestVault {
        // The recording is imported on the first run only.
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_AUDIO } }) importRecording()
        tap(::isRecording)
        tap(label("Play"))
        assertTrue("playback takes the media focus", await { focusEntries().any { "usage=USAGE_MEDIA" in it } })
        assertFalse(
            "playback publishes no media session",
            "package=${activity.packageName}" in shell("dumpsys media_session"),
        )

        // Another app that starts to play asks for the whole focus.
        val audio = activity.getSystemService(AudioManager::class.java)
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener {}.build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(other))
        try {
            assertTrue("the vault's playback pauses", await { find(label("Play")) != null })
        } finally {
            audio.abandonAudioFocusRequest(other)
        }

        tap(label("Play"))
        assertTrue("playing again takes the focus again", await { focusEntries().isNotEmpty() })
        lockQuietly()
        assertTrue("the lock gives the focus up", await { focusEntries().isEmpty() })
    }

    @Test
    fun theViewerOffersThePanicToAScreenReader() = inTestVault {
        // The photo is imported on the first run only.
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_IMAGE } }) importPhoto()
        var tile: AccessibilityNodeInfo? = null
        assertTrue("a media tile", await { findTile()?.also { tile = it } != null })
        tap { it == tile }
        assertTrue("the viewer opens", await { find(label("Info")) != null })

        // `DISCREET_MODE.md` "The panic gesture": the control is in the
        // viewer's own chrome, where a press can reach it, §25.5.
        val statusBar = onWindow { insets, _ -> insets.getInsets(WindowInsetsCompat.Type.statusBars()).top }
        assertTrue("the lock sits below the status bar", boundsOf(label("Lock now")).top >= statusBar)
        val control = generateSequence(find(label("Lock now"))) { it.parent }.first { it.isClickable }
        val panic = control.actionList.firstOrNull {
            it.label?.toString() == "Lock immediately" && it.id != AccessibilityNodeInfo.ACTION_LONG_CLICK
        }
        assertNotNull("the long press is a custom action too", panic)

        assertTrue(control.performAction(checkNotNull(panic).id))

        assertTrue("the panic locks", await { controller.vaultState.value is VaultState.Locked })
        assertTrue("and leaves the vault", await { controller.route.value != AppRoute.Vault })
    }

    @Test
    fun aTapOnThePhotoHidesTheChromeAndTheSystemBars() = inTestVault {
        // The photo is imported on the first run only.
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_IMAGE } }) importPhoto()
        val tile = photoTile()
        tap { it == tile }
        assertTrue("the viewer opens", await { find(label("Info")) != null })
        val showsControls = { action: String -> { node: AccessibilityNodeInfo ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == action }
        } }
        val media = boundsOf(showsControls("Hide controls"))
        // The status bar stands for both: `isVisible` of `systemBars()` also
        // asks for a caption bar, which a phone window never has.
        val barsShown = { onWindow { insets, _ -> insets.isVisible(WindowInsetsCompat.Type.statusBars()) } }

        // A drag is not a tap.
        shell("input swipe ${media.centerX()} ${media.centerY()} ${media.centerX()} ${media.centerY() + media.height() / 4} 300")
        assertFalse("a drag keeps the chrome", await(1_000) { find(label("Back")) == null })

        // `DESIGN.md` §13.1: a finger on the photo, as a user taps.
        shell("input tap ${media.centerX()} ${media.centerY()}")

        assertTrue("the chrome goes", await { find(label("Back")) == null && find(label("Info")) == null })
        assertTrue("the system bars go with it", await { !barsShown() })

        // A screen reader brings it back through the media's own action.
        val hidden = find(showsControls("Show controls"))
        assertNotNull("the media offers to show the controls", hidden)
        assertTrue(checkNotNull(hidden).performAction(AccessibilityNodeInfo.ACTION_CLICK))

        assertTrue("the chrome comes back", await { find(label("Back")) != null })
        assertTrue("with the system bars", await { barsShown() })

        // §13.4: the chrome stays while the sheet it opened is up.
        tap(label("Info"))
        assertTrue(
            "the overlay opens",
            await { find(::isCaptureLine) != null },
        )
        shell("input tap ${media.centerX()} ${media.top + media.height() / 4}")
        assertFalse("a tap keeps the chrome under Info", await(1_000) { find(label("Back")) == null })

        pressBack()
        assertTrue("the overlay closes", await { find(label("Info")) != null })
        pressBack()
        assertTrue("the viewer closes", await { find(label("Info")) == null })
        assertTrue("the system bars stay", barsShown())
    }

    /**
     * A provider that publishes no taken time, as a document provider does,
     * leaves the importer to read the photo's EXIF, and the time it records
     * is kept rather than substituted, `CATALOG_SCHEMA_V1.md` §8.1.
     */
    @Test
    fun anImportKeepsTheCaptureTimeThePhotoRecords() = inTestVault {
        val source = File(instrumentation.targetContext.cacheDir, "exif-source.jpg")
        source.outputStream().use {
            Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF669933.toInt()) }
                .compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:05:01 12:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00")
            saveAttributes()
        }
        val id = importFile("exif-capture.jpg") { it.write(source.readBytes()) }
        val detail = checkNotNull(runBlocking { controller.detailOf(id) })
        assertFalse("the capture time is the photo's", detail.captureTimeSubstituted)
        assertEquals(1_556_704_800_000, detail.captureTimeMs)
    }

    @Test
    fun thePlayerControlsSitClearOfTheActionsAndGoWithTheChrome() = inTestVault {
        // The recording is imported on the first run only. It plays in the
        // same player view as a video, with the same controls.
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_AUDIO } }) importRecording()
        tap(::isRecording)
        // The controls move until the chrome rows have their size, so they
        // are measured and tapped once two looks agree.
        var progress = Rect()
        assertTrue("the controls settle", await {
            val now = boundsOf(playerControl("exo_progress"))
            (now == progress).also { progress = now }
        })

        // `DESIGN.md` §13.2: the time and the settings are not under the
        // viewer's actions, and one item has no previous or next.
        val settings = boundsOf(playerControl("exo_settings"))
        for (action in listOf("Favourite", "Export", "Move to Trash")) {
            val bounds = boundsOf(label(action))
            assertFalse("the time bar clears $action", Rect.intersects(progress, bounds))
            assertFalse("the settings clear $action", Rect.intersects(settings, bounds))
        }
        assertNull("no previous item", find(playerControl("exo_prev")))
        assertNull("no next item", find(playerControl("exo_next")))

        // §13.1: a tap on the player reaches the player, and the chrome goes
        // with its controls.
        val media = boundsOf { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Hide controls" }
        }
        shell("input tap ${media.centerX()} ${media.top + media.height() / 4}")
        assertTrue("the controls go", await { find(playerControl("exo_progress")) == null })
        assertTrue("and the chrome with them", await { find(label("Back")) == null })

        // The chrome brings the controls back when a screen reader shows it.
        val hidden = find { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Show controls" }
        }
        assertTrue(checkNotNull(hidden).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue("the chrome comes back", await { find(label("Back")) != null })
        assertTrue("with the controls", await { find(playerControl("exo_progress")) != null })
        pressBack()
    }

    @Test
    fun theChromeHidesWithThePlayerControlsAfterPlaybackWithNoTouch() = inTestVault {
        // `DESIGN.md` §13.4: the chrome hides after a time of playback with no
        // touch, and not while it is pinned. The recording plays in the same
        // player view as a video, with the same controls.
        val imported = listOf(importRecording("inactivity-test.wav", seconds = 120))
        try {
            tap(label("Audio, 2 minutes"))
            val media = boundsOf { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Hide controls" }
            }
            tap(playerControl("exo_play_pause"))
            assertTrue("playback hides the controls", await { find(playerControl("exo_progress")) == null })
            assertTrue("and the chrome with them", await { find(label("Back")) == null })

            // A touch on a chrome control that leaves the chrome as it is,
            // as Favourite does, is activity, and the time starts again. The
            // touch comes 3 s into the 5 s, and the controls stay 3 s more.
            shell("input tap ${media.centerX()} ${media.top + media.height() / 4}")
            assertTrue("a tap brings the chrome back", await { find(label("Favourite")) != null })
            Thread.sleep(3_000)
            val favourite = boundsOf(label("Favourite"))
            shell("input tap ${favourite.centerX()} ${favourite.centerY()}")
            assertFalse("a touch on Favourite holds them", await(3_000) { find(playerControl("exo_progress")) == null })
            assertTrue("then the controls go", await { find(playerControl("exo_progress")) == null })
            assertTrue("and the chrome with them", await { find(label("Back")) == null })

            // The Info sheet pins the chrome, and the player holds its
            // controls with it. Without the hold, each timeout would hide
            // them and the pinned chrome would show them again.
            shell("input tap ${media.centerX()} ${media.top + media.height() / 4}")
            assertTrue("a tap brings the chrome back", await { find(label("Info")) != null })
            assertTrue("the controls time out", (controlsTimeoutMs() ?: 0) > 0)
            tap(label("Info"))
            assertEquals("the pinned chrome holds the controls", 0, controlsTimeoutMs())
            assertFalse("the controls stay", await(7_000) { find(playerControl("exo_progress")) == null || find(label("Back")) == null })

            // The accessibility snapshot can keep the old label for seconds,
            // so the sheet closes through the semantics, as in [nameInInfo].
            onScreen { nodes -> nodes.first { "Hide info" in it.labels }.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
            awaitFrames()
            assertTrue("without the sheet the controls go", await { find(playerControl("exo_progress")) == null })
            assertTrue("and the chrome with them", await { find(label("Back")) == null })
            pressBack()
            assertTrue("the viewer closes", await { find(label("Info")) == null })
        } finally {
            settle { done -> controller.deleteAll(imported, done) }
            settle { done -> controller.permanentlyDeleteAll(imported, done) }
        }
    }

    @Test
    fun aScreenReaderOrAKeyboardHoldsThePlayerControls() = inTestVault {
        // `DESIGN.md` §13.4 and §23.3: the controls, and the chrome with them,
        // do not hide by themselves while a screen reader explores the screen
        // or the keyboard moves the focus. The test's own accessibility
        // service asks for touch exploration, as TalkBack does.
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_AUDIO } }) importRecording()
        tap(::isRecording)
        assertTrue("the controls time out", await { (controlsTimeoutMs() ?: 0) > 0 })
        val automation = instrumentation.uiAutomation
        val explore = { on: Boolean ->
            automation.serviceInfo = automation.serviceInfo.apply {
                val flag = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                flags = if (on) flags or flag else flags and flag.inv()
            }
        }
        try {
            explore(true)
            assertTrue("a screen reader holds them", await { controlsTimeoutMs() == 0 })
        } finally {
            explore(false)
        }
        assertTrue("without it they time out again", await { (controlsTimeoutMs() ?: 0) > 0 })

        // A key that moves the focus leaves touch mode, and a touch ends it.
        shell("input keyevent KEYCODE_TAB")
        assertTrue("the keyboard holds them", await { controlsTimeoutMs() == 0 })
        val media = boundsOf { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Hide controls" }
        }
        shell("input tap ${media.centerX()} ${media.top + media.height() / 4}")
        assertTrue("a touch lets them time out", await { (controlsTimeoutMs() ?: 0) > 0 })
        pressBack()
    }

    @Test
    fun thePosterKeepsThePlayerControlsOffUntilPlay() = inTestVault {
        // `DESIGN.md` §6.2 and §13.2: a video opens on its poster, and the
        // player's controls under it are off, so a tap there cannot seek or
        // open the settings unseen, and a screen reader hears one Play. The
        // poster also goes when the player draws a frame, and many decoders
        // draw one while paused. No player can read this video, so the
        // poster stays until the press on every device.
        val imported = listOf(importUnplayableVideo("poster-test.mp4"))
        try {
            tap { it.contentDescription?.startsWith("Video, ") == true }
            val poster = { node: AccessibilityNodeInfo -> label("Play")(node) && node.viewIdResourceName == null }
            assertTrue("the poster shows", await { find(poster) != null })
            assertNotNull("with the chrome", find(label("Back")))
            for (id in listOf("exo_play_pause", "exo_rew_with_amount", "exo_ffwd_with_amount", "exo_progress", "exo_settings")) {
                assertNull("no $id under the poster", find(playerControl(id)))
            }
            assertNotNull("the poster still covers the player", find(poster))

            // §13.1: a tap on the poster reaches the viewer, and the chrome
            // goes.
            val media = boundsOf { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Hide controls" }
            }
            shell("input tap ${media.centerX()} ${media.top + media.height() / 4}")
            assertTrue("a tap on the poster hides the chrome", await { find(label("Back")) == null })

            tap(poster)
            // The player failed on the stream, and the press brings no new
            // state. It then shows its own state, and the chrome with it, not
            // a black canvas or a poster that looks playable, §13.4.
            assertTrue("the press brings the controls", await { find(playerControl("exo_play_pause")) != null })
            assertTrue("and the chrome", await { find(label("Back")) != null })
            assertTrue("in place of the poster", await { find(poster) == null })
            assertFalse(
                "the failure holds them",
                await(7_000) { find(playerControl("exo_play_pause")) == null || find(label("Back")) == null },
            )
            pressBack()
            assertTrue("the viewer closes", await { find(label("Info")) == null })
        } finally {
            settle { done -> controller.deleteAll(imported, done) }
            settle { done -> controller.permanentlyDeleteAll(imported, done) }
        }
    }

    @Test
    fun aPauseOrTheEndBringsBackThePlayerControlsAndTheChrome() = inTestVault {
        // `DESIGN.md` §13.2 and §13.4: the controls and the chrome hide after a
        // time of playback with no touch. A pause that the user did not make,
        // or the end of playback, then shows the controls again, and the
        // chrome with them, so Back and the state of playback are in view.
        val imported = listOf(importRecording("resume-test.wav", seconds = 20))
        try {
            tap(label("Audio, 20 seconds"))
            tap(playerControl("exo_play_pause"))
            assertTrue("playback hides the controls", await { find(playerControl("exo_progress")) == null })
            assertTrue("and the chrome with them", await { find(label("Back")) == null })

            // Another app that starts to play takes the whole focus, and the
            // vault's playback pauses.
            val audio = activity.getSystemService(AudioManager::class.java)
            val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener {}.build()
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(other))
            try {
                assertTrue("the pause brings the controls back", await { find(playerControl("exo_progress")) != null })
                assertTrue("and the chrome with them", await { find(label("Back")) != null })
            } finally {
                audio.abandonAudioFocusRequest(other)
            }

            tap(playerControl("exo_play_pause"))
            assertTrue("playback hides them again", await { find(playerControl("exo_progress")) == null })
            assertTrue("the end brings the controls back", await(30_000) { find(playerControl("exo_progress")) != null })
            assertTrue("and the chrome with them", await { find(label("Back")) != null })
            pressBack()
            assertTrue("the viewer closes", await { find(label("Info")) == null })
        } finally {
            settle { done -> controller.deleteAll(imported, done) }
            settle { done -> controller.permanentlyDeleteAll(imported, done) }
        }
    }

    @Test
    fun aSwipeOffOrOntoThePlayerLeavesTheChromeAsItIs() = inTestVault {
        // `DESIGN.md` §13.1: a swipe is not a tap. The player's view hides
        // its controls when its page leaves, and that must not hide the
        // chrome of the page the swipe lands on. A paused player shows its
        // controls by itself, and that must not show a chrome the user hid.
        // The newest items lead the scope, so the recording is the first
        // item and the photo the second.
        val names = listOf("swipe-from-player.jpg", "swipe-from-player.wav")
        val photo = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF304050.toInt()) }
        val imported = listOf(
            importFile(names[0]) { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) },
            importRecording(names[1], seconds = 120),
        )
        try {
            tap(label("Audio, 2 minutes"))
            assertTrue("the controls show", await { find(playerControl("exo_progress")) != null })
            assertEquals(names[1], nameInInfo(names))
            val media = boundsOf { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Hide controls" }
            }
            val (left, right, y) = Triple(media.left + media.width() / 6, media.right - media.width() / 6, media.centerY())

            shell("input swipe $right $y $left $y 250")
            // The released view hides its controls well inside this wait.
            assertFalse("the chrome stays", await(3_000) { onScreen { nodes -> nodes.none { "Back" in it.labels } } })
            assertEquals("the swipe lands on the photo", names[0], nameInInfo(names, previous = names[1]))

            shell("input tap ${media.centerX()} ${media.centerY()}")
            assertTrue("a tap on the photo hides the chrome", await { find(label("Back")) == null })
            shell("input swipe $left $y $right $y 250")
            assertFalse("the chrome stays hidden", await(3_000) {
                onScreen { nodes -> nodes.any { "Back" in it.labels } } || find(playerControl("exo_progress")) != null
            })
            val hidden = find { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Show controls" }
            }
            assertTrue(checkNotNull(hidden).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals("the swipe lands on the recording", names[1], nameInInfo(names, previous = names[0]))
            pressBack()
            assertTrue("the viewer closes", await { find(label("Info")) == null })
        } finally {
            settle { done -> controller.deleteAll(imported, done) }
            settle { done -> controller.permanentlyDeleteAll(imported, done) }
        }
    }

    @Test
    fun aPhotoWithNoScreenPreviewIsShownFromItsOriginalUpright() = inTestVault {
        // `MEDIA_PIPELINE.md` §8: 1600 by 1200 as stored is inside the 2048 px
        // edge, so the photo has no screen preview and the viewer decodes the
        // original. Its EXIF orientation 6 turns it to 1200 by 1600, which §11
        // leaves to that decoder.
        val jpeg = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val objectId = importFile("still-test.jpg") { out ->
            out.write(jpeg, 0, 2)
            out.write(EXIF_ORIENTATION_6)
            out.write(jpeg, 2, jpeg.size - 2)
        }
        try {
            assertTrue("the page shows it", await { controller.page.value.objects.any { it.objectId.contentEquals(objectId) } })
            val photo = controller.page.value.objects.first { it.objectId.contentEquals(objectId) }
            val generation = (controller.vaultState.value as VaultState.Unlocked).generation
            val still = runBlocking { viewerStill(controller.vault, ThumbnailCache(), generation, photo) }
            assertEquals("the original, upright", 1_200 to 1_600, still?.let { it.width to it.height })
        } finally {
            settle { done -> controller.deleteAll(listOf(objectId), done) }
            settle { done -> controller.permanentlyDeleteAll(listOf(objectId), done) }
        }
    }

    @Test
    fun theLockInTheViewerStopsPlayback() = inTestVault {
        // The recording is imported on the first run only.
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_AUDIO } }) importRecording()
        tap(::isRecording)
        tap(label("Play"))
        assertTrue("playback takes the media focus", await { focusEntries().isNotEmpty() })

        tap(label("Lock now"))

        assertTrue("the press locks", await { controller.vaultState.value is VaultState.Locked })
        assertTrue("and leaves the vault", await { controller.route.value != AppRoute.Vault })
        assertTrue("the lock releases the player", await { focusEntries().isEmpty() })
    }

    @Test
    fun theViewerPutsNoObjectIdInTheSavedState() = inTestVault {
        // The recording is imported on the first run only.
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_AUDIO } }) importRecording()
        tap(::isRecording)
        // The player's view saves its own state, as any view does.
        assertTrue("the viewer shows the player", await { find(label("Play")) != null })

        // What the activity writes when it stops, for example when Export
        // opens the system's file picker over the viewer. A Bundle that was
        // never parcelled prints every value it holds.
        val saved = Bundle()
        instrumentation.runOnMainSync { instrumentation.callActivityOnSaveInstanceState(activity, saved) }
        val written = saved.toString()

        assertEquals(emptyList<String>(), controller.page.value.objects.map { it.id }.filter { it in written })
    }

    @Test
    fun aLongPressThatStaysPutSelectsAndATapAddsToTheSelection() = inTestVault {
        withMedia()
        // A finger that does not move: the release reaches the tile's click
        // alone, and that click must select rather than open.
        longPress(photoTile())
        assertTrue("the long press starts a selection", await { find(label("1 selected")) != null })
        assertNull("and does not open the viewer", find(label("Info")))
        tap(::isRecording)
        assertTrue("a tap adds to the selection", await { find(label("2 selected")) != null })

        pressBack()

        assertTrue("Back ends the selection", await { find(label("Clear selection")) == null })
        // A finger that drifts under the touch slop: the drag takes the move
        // and cancels the click, and the end of the drag selects.
        longPress(photoTile(), drift = 6)
        assertTrue("a small drift still selects", await { find(label("1 selected")) != null })
        assertNull("and does not open the viewer", find(label("Info")))
    }

    @Test
    fun aScreenReaderSelectsWithTheLongClickActionOfATile() = inTestVault {
        withMedia()
        val tile = photoTile()
        // Compose reports `selected` outside a tab as a checked state, which
        // a screen reader speaks as selected or not selected, §23.5.
        assertFalse("nothing is selected yet", tile.isChecked)
        assertEquals("Select", tile.longClickLabel())
        // §23.2: a tile is named by its kind and its length in words, and
        // its length is not read a second time as a time of day.
        assertEquals("Audio, 1 minute", find(::isRecording)?.contentDescription?.toString())
        assertNull("the length is read once", find { it.text?.matches(Regex("\\d+:\\d{2}")) == true })

        assertTrue(tile.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))

        assertTrue("the action starts a selection", await { find(label("1 selected")) != null })
        assertNull("and does not open the viewer", find(label("Info")))
        assertTrue("the tile says it is selected", await { tile.refresh() && tile.isChecked })
        assertNull("the checkmark is not a second control", find(label("✓")))
        assertEquals("Deselect", tile.longClickLabel())
        assertTrue(tile.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        assertTrue("the same action ends it", await { find(label("1 selected")) == null })
    }

    @Test
    fun aSelectionLeavesAnAlbumInOneAction() = inTestVault {
        withMedia()
        val members = listOf(MEDIA_CLASS_IMAGE, MEDIA_CLASS_AUDIO).map { kind ->
            controller.page.value.objects.first { it.mediaKind == kind }.objectId
        }
        tap(label("Albums"))
        // The tab loads the albums; the album is created on the first run only.
        if (!await(3_000) { find(label(ALBUM)) != null }) {
            instrumentation.runOnMainSync { controller.createAlbum(ALBUM) }
        }
        assertTrue("the album", await { controller.albums.value.any { it.name == ALBUM } })
        val placed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val album = controller.albums.value.first { it.name == ALBUM }
            controller.placeObjectsInAlbum(album.albumId, members) { placed.countDown() }
        }
        assertTrue("the media land in the album", placed.await(30, TimeUnit.SECONDS))
        val count = albumMembers()
        tap(label(ALBUM))
        // The album keeps a manual order, where a long press that moves
        // reorders and one that stays put selects.
        longPress(photoTile())
        assertTrue("the long press starts a selection", await { find(label("1 selected")) != null })
        tap(::isRecording)
        assertTrue("a tap adds to the selection", await { find(label("2 selected")) != null })

        tap(label("More"))
        tap(label("Remove from album"))

        assertTrue("both leave the album", await { albumMembers() == count - 2 })
    }

    @Test
    fun aLongPressThatMovesStillDropsIntoAnAlbum() = inTestVault {
        withMedia()
        val photos = controller.page.value.objects.filter { it.mediaKind == MEDIA_CLASS_IMAGE }.map { it.objectId }
        tap(label("Albums"))
        if (!await(3_000) { find(label(ALBUM)) != null }) {
            instrumentation.runOnMainSync { controller.createAlbum(ALBUM) }
        }
        assertTrue("the album", await { controller.albums.value.any { it.name == ALBUM } })
        // The photos start outside the album, so the drop is what adds one.
        val cleared = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val album = controller.albums.value.first { it.name == ALBUM }
            controller.removeAllFromAlbum(album.albumId, photos) { cleared.countDown() }
        }
        assertTrue("the photos leave the album", cleared.await(30, TimeUnit.SECONDS))
        val count = albumMembers()
        // Back leaves the Albums tab for the Library.
        pressBack()
        val tile = Rect().also { photoTile().getBoundsInScreen(it) }
        val tray: (AccessibilityNodeInfo) -> Boolean = { it.text?.endsWith("Choose album…") == true }
        var x = tile.centerX()
        var y = tile.centerY()
        shell("input motionevent DOWN $x $y")
        var down = true
        try {
            Thread.sleep(800)
            assertNull("a long press that has not moved shows no album tray", find(tray))
            // Small steps, as a finger moves: the first stays under the touch
            // slop, so the drag takes it before the grid can scroll.
            repeat(4) {
                y += 10
                shell("input motionevent MOVE $x $y")
            }
            assertTrue("the move shows the tray", await { find(tray) != null })
            val target = boundsOf(label(ALBUM))
            x = target.centerX()
            y = target.centerY()
            shell("input motionevent MOVE $x $y")
            awaitFrames()
            shell("input motionevent UP $x $y")
            down = false
            assertTrue("the drop adds the photo", await { albumMembers() == count + 1 })
            // §26: the outcome is a count, and never the album's private name.
            assertTrue("the drop is confirmed", await { find(label("Added 1 item to the album.")) != null })
        } finally {
            if (down) shell("input motionevent UP $x $y")
        }
    }

    @Test
    fun aLongPressSelectsInTrashAndTheSelectionRestores() = inTestVault {
        withMedia()
        val before = controller.page.value.objects.map { it.id }.toSet()
        longPress(photoTile())
        assertTrue("the long press starts a selection", await { find(label("1 selected")) != null })
        tap(label("More"))
        tap(label("Move to Trash"))
        // The confirmation names the same action.
        tap(label("Move to Trash"))
        var trashed = emptySet<String>()
        assertTrue(
            "one item moves to Trash",
            await { (before - controller.page.value.objects.map { it.id }.toSet()).also { trashed = it }.size == 1 },
        )
        try {
            tap(label("Browse"))
            tap(label("Trash"))
            assertTrue("Trash shows it", await { controller.page.value.objects.any { it.id in trashed } })
            // Trash has no album tray and no order, and a long press selects
            // there too.
            longPress(photoTile())
            assertTrue("a long press selects in Trash", await { find(label("1 selected")) != null })

            tap(label("More"))
            tap(label("Restore"))

            assertTrue("the item leaves Trash", await { controller.page.value.objects.none { it.id in trashed } })
        } finally {
            // A failure above must not leave the test's media in Trash.
            val restored = CountDownLatch(1)
            instrumentation.runOnMainSync { controller.restoreTrash { restored.countDown() } }
            restored.await(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun theSnackbarUndoesAMoveToTrashFromTheViewer() = inTestVault {
        withMedia()
        val before = controller.page.value.objects.map { it.id }.toSet()
        val tile = photoTile()
        tap { it == tile }
        tap(label("Move to Trash"))
        // §27: the single item is still confirmed first.
        assertTrue("the confirmation", await { find(label("Chur keeps this item for 30 days so you can restore it.")) != null })
        tap(label("Move to Trash"))
        try {
            // §26: the outcome is counted, and a mis-tap is taken back here
            // rather than from Trash.
            assertTrue("the move is confirmed", await { find(label("Moved 1 item to Trash.")) != null })
            assertTrue("the item left All media", controller.page.value.objects.size == before.size - 1)
            tap(label("Undo"))
            assertTrue("the item is back in All media", await { controller.page.value.objects.map { it.id }.toSet() == before })
            assertTrue("the undo is confirmed", await { find(label("Restored 1 item.")) != null })
        } finally {
            val restored = CountDownLatch(1)
            instrumentation.runOnMainSync { controller.restoreTrash { restored.countDown() } }
            restored.await(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun emptyingTrashSaysTheDeletionIsPermanent() = inTestVault {
        withMedia()
        val tile = photoTile()
        tap { it == tile }
        tap(label("Move to Trash"))
        tap(label("Move to Trash"))
        assertTrue("the move is confirmed", await { find(label("Moved 1 item to Trash.")) != null })
        tap(label("Browse"))
        tap(label("Trash"))
        assertTrue("Trash shows it", await { controller.page.value.objects.isNotEmpty() })
        tap(label("Browse"))
        tap(label("Empty trash"))
        // §27: what goes and what stays.
        assertTrue(
            "the confirmation",
            await { find(label("This removes Chur's local keys and encrypted objects for everything in Trash. " +
                "Copies exported elsewhere are not affected.")) != null },
        )
        tap(label("Empty trash"))

        assertTrue("the outcome", await { find(label("Deleted permanently.")) != null })
        assertTrue("Trash is empty", await { controller.page.value.objects.isEmpty() })
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Imports a photo and a recording on the first run only. */
    private fun withMedia() {
        if (!await(3_000) { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_IMAGE } }) importPhoto()
        if (controller.page.value.objects.none { it.mediaKind == MEDIA_CLASS_AUDIO }) importRecording()
    }

    private fun photoTile(): AccessibilityNodeInfo {
        var tile: AccessibilityNodeInfo? = null
        assertTrue("a photo tile", await { findTile()?.also { tile = it } != null })
        return checkNotNull(tile)
    }

    /** The label of the recording's tile: its kind and its length, `DESIGN.md` §23.2. */
    private fun isRecording(node: AccessibilityNodeInfo): Boolean = node.contentDescription?.startsWith("Audio, ") == true

    /**
     * The filename, one of [names], that the viewer's Info overlay shows once
     * it names an item other than [previous]. The overlay is the item's own,
     * so a swipe that lands closes it, and it is opened again until it names
     * the item the swipe landed on. It is closed again before this returns.
     *
     * The semantics are read where they are current: the accessibility
     * snapshot can keep a label a swipe changed for seconds.
     */
    private fun nameInInfo(names: List<String>, previous: String? = null): String {
        val click = { nodes: List<SemanticsNode>, label: String ->
            nodes.firstOrNull { label in it.labels }?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        }
        var name: String? = null
        assertTrue(
            "the Info overlay names ${if (previous == null) "the item" else "another item than $previous"}",
            await {
                name = onScreen { nodes ->
                    nodes.flatMap { it.labels }.firstOrNull { it in names }
                        .also { shown -> if (shown == null) click(nodes, "Info") }
                }
                name != null && name != previous
            },
        )
        onScreen { click(it, "Hide info") }
        awaitFrames()
        return checkNotNull(name)
    }

    /** The merged semantics nodes on screen, read on the main thread. */
    private fun <T> onScreen(read: (List<SemanticsNode>) -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync {
            value = runCatching {
                read(activity.window.decorView.composeRoots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) })
            }
        }
        return checkNotNull(value).getOrThrow()
    }

    private val SemanticsNode.labels: List<String>
        get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun AccessibilityNodeInfo.longClickLabel(): String? =
        actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }?.label?.toString()

    private fun albumMembers(): Long = controller.albums.value.first { it.name == ALBUM }.memberCount

    /**
     * Holds a finger on [node] past the long-press timeout and lifts it, as
     * `adb shell input swipe x y x y 800` does. The finger moves [drift]
     * pixels on the way, which stays under the touch slop.
     */
    private fun longPress(node: AccessibilityNodeInfo, drift: Int = 0) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val x = bounds.centerX()
        val y = bounds.centerY()
        shell("input swipe $x $y ${x + drift} $y 800")
        awaitFrames()
    }

    /** Two taps at [x], [y], as close together as a user's double tap. */
    private fun doubleTap(x: Int, y: Int) {
        repeat(2) {
            val down = SystemClock.uptimeMillis()
            inject(down, MotionEvent.ACTION_DOWN, listOf(x to y))
            Thread.sleep(40)
            inject(down, MotionEvent.ACTION_UP, listOf(x to y))
            Thread.sleep(80)
        }
        awaitFrames()
    }

    /** Two fingers above and below [x], [y] that spread from [from] to [to] pixels off it. */
    private fun pinch(x: Int, y: Int, from: Int, to: Int) {
        val fingers = { gap: Int -> listOf(x to y - gap, x to y + gap) }
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, fingers(from).take(1))
        inject(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fingers(from))
        for (step in 1..10) {
            Thread.sleep(16)
            inject(down, MotionEvent.ACTION_MOVE, fingers(from + (to - from) * step / 10))
        }
        inject(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fingers(to))
        inject(down, MotionEvent.ACTION_UP, fingers(to).take(1))
        awaitFrames()
    }

    private fun inject(downTime: Long, action: Int, points: List<Pair<Int, Int>>) {
        val properties = points.indices.map { id ->
            MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = points.map { (px, py) ->
            MotionEvent.PointerCoords().apply { x = px.toFloat(); y = py.toFloat(); pressure = 1f; size = 1f }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, points.size, properties.toTypedArray(), coords.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    /** This application's entries in the audio focus stack of `dumpsys audio`. */
    private fun focusEntries(): List<String> =
        shell("dumpsys audio").lineSequence()
            .dropWhile { !it.startsWith("Audio Focus stack entries") }
            .takeWhile { "focus policy" !in it }
            .filter { "pack: ${activity.packageName}" in it }
            .toList()

    /**
     * The settings row named [title], scrolled into view: a switch, which a
     * screen reader announces with its name and its state, `DESIGN.md` §23.5.
     */
    private fun switchRow(title: String): AccessibilityNodeInfo {
        var row: AccessibilityNodeInfo? = null
        assertTrue(
            "$title must be on screen",
            await {
                row = find(label(title))?.let { node -> generateSequence(node) { it.parent }.firstOrNull { it.isCheckable } }
                row != null ||
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
            },
        )
        // Compose gives a row with children its role on a child of its own,
        // as a role description, and the reader speaks it with the row.
        assertNotNull(
            "$title must be a switch",
            find(row) { AccessibilityNodeInfoCompat.wrap(it).roleDescription?.toString() == "Switch" },
        )
        return checkNotNull(row)
    }

    /** The Auto-lock row of Settings, scrolled into view. */
    private fun autoLockRow(): AccessibilityNodeInfo {
        var row: AccessibilityNodeInfo? = null
        assertTrue(
            "Auto-lock must be on screen",
            await {
                row = find(label("Auto-lock"))?.let { node -> generateSequence(node) { it.parent }.firstOrNull { it.isClickable } }
                row != null ||
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
            },
        )
        return checkNotNull(row)
    }

    /** The dialog choice named [text]: a radio button, which a reader announces with its state. */
    private fun radio(text: String): AccessibilityNodeInfo {
        var row: AccessibilityNodeInfo? = null
        assertTrue(
            "$text must be on screen",
            await { find(label(text))?.let { node -> generateSequence(node) { it.parent }.firstOrNull { it.isCheckable } }?.also { row = it } != null },
        )
        assertNotNull("$text must be a radio button", find(row) { it.className?.toString() == "android.widget.RadioButton" })
        return checkNotNull(row)
    }

    /** Shows [route], presses Back once, and returns where the press landed. */
    private fun backFrom(route: AppRoute): AppRoute {
        instrumentation.runOnMainSync { controller.goTo(route) }
        pressBack()
        return controller.route.value
    }

    /** Presses Back, after [first] in the same main-thread message. */
    private fun pressBack(first: () -> Unit = {}) {
        awaitFrames()
        instrumentation.runOnMainSync {
            first()
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

    /**
     * The screen-lock PIN of the device, which a run passes as the `devicePin`
     * runner argument. A recovery phrase from Settings asks for the device
     * authentication of `DESIGN.md` §17.1 step 2 first, and this test cannot
     * know the PIN, so a run without it skips the case.
     */
    private fun devicePin(): String {
        val pin = InstrumentationRegistry.getArguments().getString("devicePin")
        assumeTrue("pass the screen-lock PIN as the devicePin runner argument", pin != null)
        return checkNotNull(pin)
    }

    /** Whether a system window, the device authentication prompt, is in front of the application. */
    private fun ownerPromptShown(): Boolean =
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.let { it != activity.packageName } == true

    /**
     * Waits for the device authentication prompt and enters [pin] in it.
     *
     * The system draws the PIN entry as a pad of its own, which takes no typed
     * keys, so the digits are pressed through the accessibility tree. A PIN
     * entered while the dialog still animates in is verified and then never
     * answered, so the wait lets the animation end, as a person's would.
     */
    private fun passOwnerCheck(pin: String) {
        assertTrue("the device asks who it is", await { ownerPromptShown() })
        SystemClock.sleep(1_000)
        pin.forEach { digit -> tap(label(digit.toString())) }
        tap(label("Enter"))
        assertTrue("the prompt closes", await { !ownerPromptShown() })
    }

    private fun lockIfOpen() {
        lockQuietly()
        assertTrue("the vault must lock", controller.vaultState.value !is VaultState.Unlocked)
    }

    /**
     * Locks without asserting, so a cleanup cannot hide the failure it follows.
     *
     * The lock also discards a recovery slot or a creation whose phrase is
     * still on screen, so nothing the test did not confirm is committed.
     */
    private fun lockQuietly() {
        if (controller.vaultState.value !is VaultState.Unlocked && controller.recoveryPhrase.value == null) return
        instrumentation.runOnMainSync { controller.lock() }
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
                is VaultState.NoVault -> {
                    controller.goTo(AppRoute.CreateVault)
                    controller.create(PASSWORD, offerRecovery = false)
                }
                is VaultState.Unlocked -> controller.goTo(AppRoute.Vault)
                else -> {
                    controller.goTo(AppRoute.Unlock)
                    controller.unlock(PASSWORD)
                }
            }
        }
        await(60_000) { isOpen() || controller.formError.value != null }
        if (!isOpen()) {
            // Only a vault this test did not create refuses its password. Any
            // other way of not opening is a failure, not a reason to skip.
            assumeTrue("a vault this test did not create", opening is VaultState.Locked && controller.formError.value != null)
            fail("the test vault did not open: ${controller.formError.value}")
        }
        try {
            awaitFrames()
            body()
        } finally {
            lockQuietly()
        }
    }

    /** The notes that were not in the store when the running case began. */
    private var notesBefore: Set<String> = emptySet()

    private fun madeNotes() = controller.notesState.value.filter { it.id !in notesBefore }

    /** Runs [body] in the editor of a new note, and removes every note it made. */
    private fun inNewNote(body: () -> Unit) = inNotes {
        // The empty list offers "Create note"; a list with notes, its button.
        tap { label("Create note")(it) || label("New note")(it) }
        body()
    }

    /** Runs [body] on the Notes root, and removes every note it made. */
    private fun inNotes(body: () -> Unit) {
        lockIfOpen()
        instrumentation.runOnMainSync { controller.goTo(AppRoute.PublicShell) }
        notesBefore = controller.notesState.value.map { it.id }.toSet()
        try {
            body()
        } finally {
            // The editor writes its draft as it leaves, so a case that failed
            // with it open leaves through Delete and its confirmation, which
            // write nothing, before the rest is removed. Nothing here asserts,
            // so a cleanup cannot hide the failure it follows.
            fun click(node: AccessibilityNodeInfo) {
                generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            find(label("Delete"))?.let { delete ->
                click(delete)
                await { find(label("Delete note")) != null }
            }
            find(label("Delete note"))?.let { confirm ->
                click(confirm)
                await { find(label("Delete note")) == null && find(label("Delete")) == null }
            }
            instrumentation.runOnMainSync { madeNotes().forEach { controller.removeNote(it.id) } }
        }
    }

    /** Replaces the text of the editor's body, as typing it does. */
    private fun typeNote(text: String) {
        var field: AccessibilityNodeInfo? = null
        // The title comes first and the body last.
        assertTrue("the note field", await { findAll { it.isEditable }.lastOrNull()?.also { field = it } != null })
        setText(field, text)
    }

    private fun setText(field: AccessibilityNodeInfo?, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        assertTrue("the text must land", field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true)
        awaitFrames()
    }

    /** Runs [body] on the gate form of [route], locked, and returns to Notes after. */
    private fun onGateRoute(route: AppRoute, body: () -> Unit) {
        lockIfOpen()
        instrumentation.runOnMainSync { controller.goTo(route) }
        try {
            assertTrue("the form", await { find { it.isEditable } != null })
            body()
        } finally {
            instrumentation.runOnMainSync { controller.goTo(AppRoute.PublicShell) }
        }
    }

    /** Reads the window's insets and height on the main thread. */
    private fun <T> onWindow(read: (insets: WindowInsetsCompat, height: Int) -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            value = runCatching { read(checkNotNull(ViewCompat.getRootWindowInsets(decor)), decor.height) }
        }
        return checkNotNull(value).getOrThrow()
    }

    /** The keyboard's action key, as the platform delivers it to accessibility services. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun AccessibilityNodeInfo.pressImeAction(): Boolean =
        performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    /** Taps [field], as a user does, and returns the top of the keyboard it shows. */
    private fun showKeyboardFor(field: AccessibilityNodeInfo): Int {
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(
            "the keyboard must show",
            await { onWindow { insets, _ -> insets.isVisible(WindowInsetsCompat.Type.ime()) } },
        )
        return onWindow { insets, height -> height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom }
    }

    /**
     * `DESIGN.md` §25.5 on a gate form: the area it scrolls in ends at the
     * keyboard, and [action], brought on screen, ends above it.
     */
    private fun assertFormEndsAbove(keyboardTop: Int, action: String) {
        // The keyboard slides in, and the form follows it frame by frame.
        assertTrue("the form must end above the keyboard", await { scrollAreaBottom() <= keyboardTop })
        // A control scrolled out of view is not in the tree, so the form is
        // scrolled until it is, as a user would.
        assertTrue(
            "$action must be reachable",
            await {
                find(label(action)) != null ||
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD).let { false }
            },
        )
        find(label(action))?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        assertTrue("$action must be above the keyboard", await { boundsOf(label(action)).bottom <= keyboardTop })
    }

    /** The lowest edge of a vertically scrolling area on screen, in window pixels. */
    private fun scrollAreaBottom(): Float {
        var bottom = Float.MAX_VALUE
        instrumentation.runOnMainSync {
            bottom = activity.window.decorView.composeRoots()
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
                .filter { SemanticsProperties.VerticalScrollAxisRange in it.config }
                .maxOfOrNull { it.boundsInWindow.bottom } ?: Float.MAX_VALUE
        }
        return bottom
    }

    private fun boundsOf(match: (AccessibilityNodeInfo) -> Boolean): Rect {
        var node: AccessibilityNodeInfo? = null
        assertTrue("nothing matches", await { find(match)?.also { node = it } != null })
        return Rect().also { checkNotNull(node).getBoundsInScreen(it) }
    }

    /**
     * Replaces the text of the editor's body through its semantics, on the
     * main thread. Unlike the accessibility action of [typeNote], which runs in
     * a message of its own, this lets a case press Back before a frame runs.
     */
    private fun replaceBodyText(text: String) {
        // The title comes first and the body last.
        val body = activity.window.decorView.composeRoots()
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
            .last { SemanticsActions.SetText in it.config }
        val landed = body.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
        assertTrue("the text must land", landed == true)
    }

    private fun View.composeRoots(): List<ViewRootForTest> =
        listOfNotNull(this as? ViewRootForTest) +
            ((this as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { group.getChildAt(it).composeRoots() } }
                ?: emptyList())

    /**
     * How long the player view keeps its controls with no touch during
     * playback, in milliseconds, 0 while it holds them, or `null` before the
     * view is there. The test does not link Media3, so it finds the view and
     * its getter by name.
     */
    private fun controlsTimeoutMs(): Int? {
        fun View.player(): View? = takeIf { it.javaClass.name == "androidx.media3.ui.PlayerView" }
            ?: (this as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).player() } }
        var timeout: Int? = null
        instrumentation.runOnMainSync {
            timeout = activity.window.decorView.player()?.let { it.javaClass.getMethod("getControllerShowTimeoutMs").invoke(it) as Int }
        }
        return timeout
    }

    /** Imports one generated photograph through the production importer. */
    private fun importPhoto() {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        importFile("back-test.jpg") { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        assertTrue("the page shows it", await { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_IMAGE } })
    }

    /**
     * Imports a generated recording of silence through the production
     * importer: a 16-bit mono WAV, long enough to still play when a check
     * that needs it paused looks. Returns its object ID.
     */
    private fun importRecording(name: String = "focus-test.wav", seconds: Int = 60): ByteArray {
        val samples = 8_000 * seconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            putShort(2).putShort(16)
            put("data".toByteArray()).putInt(samples * 2)
        }
        val id = importFile(name) { it.write(header.array()); it.write(ByteArray(samples * 2)) }
        assertTrue(
            "the page shows it",
            await { controller.page.value.objects.any { it.mediaKind == MEDIA_CLASS_AUDIO } },
        )
        return id
    }

    /**
     * Imports a video that no player can read through the production
     * importer: its bytes are zeros, so no frame ever replaces its poster. The
     * platform cannot probe such a file or take a frame from it, so a test
     * codec gives the importer the size, the length and a poster of one
     * colour. Returns its object ID.
     */
    private fun importUnplayableVideo(name: String): ByteArray {
        val codec = object : MediaCodec {
            override fun probe(media: PickedMedia) = ProbedMedia(MediaBounds.CLASS_VIDEO, 320, 240, 2_000, "video/mp4")

            override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? {
                val (width, height) = MediaBounds.targetSize(kind, probe.width, probe.height) ?: return null
                val out = ByteArrayOutputStream()
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt()) }
                    .compress(Bitmap.CompressFormat.JPEG, 90, out)
                return Derivative(kind, out.toByteArray(), width, height)
            }
        }
        return importFile(name, codec) { it.write(ByteArray(16_384)) }
    }

    /** Runs [call] on the main thread and waits for its success callback. */
    private fun settle(call: (done: () -> Unit) -> Unit) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { call { done.countDown() } }
        done.await(60, TimeUnit.SECONDS)
    }

    /**
     * Reads the semantics nodes of the media grid on the main thread: one while
     * it shows. The merged tree is the one a screen reader is given, and it
     * leaves out what `clearAndSetSemantics` replaced.
     */
    private fun <T> onGrids(read: (List<SemanticsNode>) -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync {
            value = runCatching {
                read(activity.window.decorView.composeRoots()
                    .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
                    .filter { SemanticsProperties.CollectionInfo in it.config && SemanticsProperties.VerticalScrollAxisRange in it.config })
            }
        }
        return checkNotNull(value).getOrThrow()
    }

    private val SemanticsNode.scroll: Float get() = config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** The focused nodes in the merged tree, which leaves out what `clearAndSetSemantics` replaced. */
    private fun focusedNodes(): Int {
        var count = 0
        instrumentation.runOnMainSync {
            count = activity.window.decorView.composeRoots()
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
                .count { SemanticsProperties.Focused in it.config && it.config[SemanticsProperties.Focused] }
        }
        return count
    }

    private fun importFile(name: String, codec: MediaCodec? = null, write: (java.io.OutputStream) -> Unit): ByteArray {
        val context = instrumentation.targetContext
        // The application's own provider serves this directory, so the file
        // arrives as a content URI with a name, a size and a type, as a picked
        // one does.
        val file = File(context.cacheDir, "export-scratch/$name")
        file.parentFile?.mkdirs()
        file.outputStream().use(write)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val platform = AndroidMediaCodec(context.contentResolver)
        val outcome = runBlocking { controller.importMedia(MediaImporter(codec ?: platform)) { platform.open(uri) } }
        assertTrue("$name must import: $outcome", outcome is MediaImporter.Outcome.Imported)
        instrumentation.runOnMainSync { controller.reportImport(null) }
        return (outcome as MediaImporter.Outcome.Imported).objectId
    }

    /** The Info line that says when the item was captured, or that no date was recorded. */
    private fun isCaptureLine(node: AccessibilityNodeInfo): Boolean =
        node.text?.toString()?.let { it.startsWith("Captured ") || it == "No capture date recorded" } == true

    private fun label(text: String): (AccessibilityNodeInfo) -> Boolean =
        { it.text?.toString() == text || it.contentDescription?.toString() == text }

    /** A view of Media3's player controls, by its resource name. */
    private fun playerControl(id: String): (AccessibilityNodeInfo) -> Boolean =
        { it.viewIdResourceName?.endsWith(":id/$id") == true }

    /**
     * A photo tile: the clickable node of the media grid's collection whose
     * label, `DESIGN.md` §23.2, starts with its kind. Compose puts a control's
     * label on a child, so the search climbs from the label to the tile.
     */
    private fun findTile(): AccessibilityNodeInfo? =
        findAll { it.collectionInfo != null }.firstNotNullOfOrNull { grid ->
            var node = find(grid) { it.contentDescription?.startsWith("Photo") == true }
            while (node != null && !node.isClickable) node = node.parent
            node
        }

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        find(instrumentation.uiAutomation.rootInActiveWindow, match)

    /**
     * Scrolls the list on screen forward a page at a time until [match] finds
     * a node.
     *
     * A page scroll animates, and the accessibility tree shows the new page
     * only after the list settles. A search that scrolls again at once can
     * skip the page that holds the node, as the Settings list's Sync section
     * was skipped, so each page gets time to reach the tree first.
     */
    private fun scrollUntil(match: (AccessibilityNodeInfo) -> Boolean): Boolean =
        (0 until 8).any {
            await(1_500) { find(match) != null }.also { found ->
                if (!found) find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }

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
        const val SCROLLING_LIBRARY = 40
        const val NOTE_TEXT = "BackNavigationTest note"

        /** An EXIF segment that holds only orientation 6, a quarter turn clockwise. */
        val EXIF_ORIENTATION_6: ByteArray = "ffe100224578696600004d4d002a00000008000101120003000000010006000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
