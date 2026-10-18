package dev.po4yka.chur.android

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.AlbumPickerDialog
import dev.po4yka.chur.app.vault.CreateVaultScreen
import dev.po4yka.chur.app.notes.NoteEditorScreen
import dev.po4yka.chur.app.notes.OpenNote
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.app.vault.LocalPrivatePanic
import dev.po4yka.chur.app.vault.NewAlbumDialog
import dev.po4yka.chur.app.vault.PrivateDropdownMenu
import dev.po4yka.chur.app.vault.RecoveryScreen
import dev.po4yka.chur.app.vault.UnlockScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production forms and modal windows: a pending operation must not disable the exit. */
@RunWith(AndroidJUnit4::class)
class DiscreetExitPresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun busy_creation_keeps_an_immediate_hide_action() {
        val hidden = mutableStateOf(false)
        present({
            if (hidden.value) Text("Public screen") else CreateVaultScreen(
                busy = true, error = null, onCreate = { _, _ -> }, onCancel = {},
                onHide = { hidden.value = true },
            )
        }) { f ->
            f.click("Lock and hide")
            assertTrue(f.await { f.has("Public screen") && !f.has("Creating") })
        }
    }

    @Test
    fun public_note_editor_can_open_settings_without_returning_to_the_list() {
        var settings = 0
        val note = Note("public", "Public note", "Public text", 1)
        present({ NoteEditorScreen(OpenNote(note, false),
            {}, {}, {}, onOpenSettings = { settings++ }, savedNote = note) }) { f ->
            f.click("Settings")
            assertEquals(1, settings)
        }
    }

    @Test
    fun public_settings_keeps_a_failed_draft_until_saved_content_is_acknowledged() {
        val note = Note("public", "Public note", "Public text", 1)
        val saved = mutableStateOf(note)
        val failed = mutableStateOf(true)
        var submitted: Note? = null
        var settings = 0
        present({ NoteEditorScreen(OpenNote(note, false), { submitted = it }, {}, {},
            onOpenSettings = { settings++ }, savedNote = saved.value, writeFailed = failed.value) }) { f ->
            f.main {
                val title = f.nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == note.title }
                assertTrue(title.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Changed public title")) == true)
            }
            assertTrue(f.await { f.nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Changed public title"
            } })
            f.click("Settings")
            assertTrue(f.await { f.has("Save failed") })
            assertEquals(0, settings)
            f.click("Keep editing")
            assertTrue(f.await { f.buttons("Settings").any { SemanticsProperties.Disabled !in it.config } })
            assertTrue(f.await { f.nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Changed public title"
            } })
            f.click("Settings")
            f.main {
                assertEquals("Changed public title", submitted?.title)
                failed.value = false
                saved.value = requireNotNull(submitted)
            }
            assertTrue(f.await { settings == 1 })
        }
    }

    @Test
    fun busy_vault_unlock_can_return_to_the_public_screen() {
        val hidden = mutableStateOf(false)
        present({
            if (hidden.value) Text("Public screen") else UnlockScreen(
                busy = true, error = null, onUnlock = {}, onUseRecovery = {},
                onReturnToPublic = { hidden.value = true },
            )
        }) { f ->
            f.click("Return to public screen")
            assertTrue(f.await { f.has("Public screen") && !f.has("Opening") })
        }
    }

    @Test
    fun busy_vault_recovery_can_return_to_the_public_screen() {
        val hidden = mutableStateOf(false)
        present({
            if (hidden.value) Text("Public screen") else RecoveryScreen(
                busy = true, error = null, onRecover = {}, onBack = {},
                onReturnToPublic = { hidden.value = true },
            )
        }) { f ->
            f.click("Return to public screen")
            assertTrue(f.await { f.has("Public screen") && !f.has("Recovery phrase") })
        }
    }

    @Test
    fun whole_app_gate_cannot_offer_a_public_bypass_even_if_a_callback_is_supplied() {
        var bypasses = 0
        present({ UnlockScreen(busy = true, error = null, onUnlock = {}, onUseRecovery = {},
            appGate = true, onReturnToPublic = { bypasses++ }) }) { f ->
            assertTrue(f.await { f.has("Enter your password or PIN to open Chur.") })
            assertFalse(f.has("Return to public screen"))
            assertEquals(0, bypasses)
        }
    }

    @Test
    fun whole_app_recovery_can_cancel_busy_authentication_without_a_public_exit() {
        var cancellations = 0
        present({ RecoveryScreen(busy = true, error = null, onRecover = {},
            onBack = { cancellations++ }) }) { f ->
            assertTrue(f.await { f.has("Recovery phrase") })
            assertFalse(f.has("Return to public screen"))
            f.click("Back")
            assertEquals(1, cancellations)
        }
    }

    @Test
    fun private_modal_hides_the_entire_private_surface_with_one_action() {
        val hidden = mutableStateOf(false)
        var dismisses = 0
        present({
            if (hidden.value) Text("Public screen") else {
                Text("Private surface")
                CompositionLocalProvider(LocalPrivatePanic provides { hidden.value = true }) {
                    NewAlbumDialog(onCreate = {}, onDismiss = { dismisses++ })
                }
            }
        }) { f ->
            f.click("Lock and hide")
            assertTrue(f.await { f.has("Public screen") && !f.has("New album") && !f.has("Private surface") })
            assertEquals("hiding must not require dismissing the modal first", 0, dismisses)
        }
    }

    @Test
    fun private_destination_popup_has_its_own_hide_action_above_the_modal() {
        val hidden = mutableStateOf(false)
        present({
            if (hidden.value) Text("Public screen") else {
                CompositionLocalProvider(LocalPrivatePanic provides { hidden.value = true }) {
                    AlbumPickerDialog(emptyList(), null, false, {}, { _, _ -> }, {})
                }
            }
        }) { f ->
            f.click("Inside: Top level")
            // Both windows expose their own exit; activate the popup's node, not the covered modal.
            assertTrue(f.await { f.popupButtons("Lock and hide").size == 1 })
            f.main {
                assertTrue(f.popupButtons("Lock and hide").single().config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertTrue(f.await { f.has("Public screen") && !f.has("Add to album") })
        }
    }

    @Test
    fun scrolling_a_long_private_menu_keeps_the_one_action_exit_visible() {
        val hidden = mutableStateOf(false)
        present({
            if (hidden.value) Text("Public screen") else {
                CompositionLocalProvider(LocalPrivatePanic provides { hidden.value = true }) {
                    PrivateDropdownMenu(expanded = true, onDismissRequest = {}) {
                        repeat(80) { index -> DropdownMenuItem(text = { Text("Destination $index") }, onClick = {}) }
                    }
                }
            }
        }) { f ->
            assertTrue(f.await { f.popupButtons("Lock and hide").size == 1 })
            val scroll = f.nodes().single { SemanticsActions.ScrollBy in it.config &&
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.let { max -> max > 0f } == true }
            f.main { assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 100_000f) == true) }
            assertTrue(f.await { scroll.config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f })
            f.main {
                val exit = f.popupButtons("Lock and hide").single()
                assertTrue("the pinned exit must remain in the viewport", exit.boundsInWindow.top >= 0f &&
                    exit.boundsInWindow.height > 0f && exit.boundsInWindow.bottom <=
                    instrumentation.targetContext.resources.displayMetrics.heightPixels)
                assertTrue(exit.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertTrue(f.await { f.has("Public screen") && !f.has("Destination 79") })
        }
    }

    @Test
    fun public_use_of_a_shared_dialog_does_not_advertise_a_private_hide_action() {
        present({ NewAlbumDialog(onCreate = {}, onDismiss = {}) }) { f ->
            assertTrue(f.await { f.has("New album") })
            assertFalse(f.has("Lock and hide"))
        }
    }

    private fun present(content: @Composable () -> Unit, test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val f = Fixture()
            f.main {
                val view = ComposeView(activity).apply { setContent { ChurTheme(dark = false, content = content) } }
                activity.setContentView(FrameLayout(activity).apply { addView(view) })
            }
            test(f)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private inner class Fixture {
        fun main(action: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
            else instrumentation.runOnMainSync(action)
        }
        fun nodes(): List<SemanticsNode> {
            var nodes = emptyList<SemanticsNode>()
            main { nodes = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) } }
            return nodes
        }
        fun popupButtons(label: String): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            main {
                result = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
                    .map { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
                    .filter { nodes -> nodes.any { SemanticsProperties.IsPopup in it.config } }
                    .flatten().filter { label in it.labels && SemanticsActions.OnClick in it.config }
            }
            return result
        }
        fun has(label: String) = nodes().any { label in it.labels }
        fun buttons(label: String) = nodes().filter { label in it.labels && SemanticsActions.OnClick in it.config }
        fun click(label: String) {
            assertTrue("missing action: $label", await { buttons(label).isNotEmpty() })
            main {
                val node = buttons(label).last()
                assertFalse("exit must remain enabled", SemanticsProperties.Disabled in node.config)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
        }
        fun await(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                if (condition()) return true
                SystemClock.sleep(30)
            }
            return condition()
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
}
