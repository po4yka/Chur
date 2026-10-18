package dev.po4yka.chur.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.LauncherAction
import dev.po4yka.chur.app.NoPrivacyCover
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherActionsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun native_metadata_has_four_stable_generic_explicit_task_preserving_actions() {
        val context = instrumentation.targetContext
        val shortcuts = launcherShortcutInfos(context)
        assertEquals(listOf("import", "search", "lock-and-hide", "new-private-note"), shortcuts.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), shortcuts.map { it.rank })
        shortcuts.forEach { shortcut ->
            assertEquals(ComponentName(context, MainActivity::class.java), shortcut.activity)
            val intent = requireNotNull(shortcut.intent)
            assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
            assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.flags)
            assertEquals(shortcut.id, LauncherAction.fromExternalAction(intent.action)?.shortcutId)
            assertNull(intent.extras)
            assertNull(intent.data)
            assertNull(intent.clipData)
        }
        val ru = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("ru")))
        })
        val russianShortcuts = launcherShortcutInfos(ru)
        assertEquals(listOf("Импорт", "Поиск", "Заблокировать", "Приватная заметка"),
            russianShortcuts.map { it.shortLabel.toString() })
        assertEquals(listOf("Импорт", "Поиск", "Блокировка и маскировка", "Новая приватная заметка"),
            russianShortcuts.map { it.longLabel.toString() })
        assertNull(LauncherAction.fromExternalAction("dev.po4yka.chur.action.search?query=private"))
        val sanitized = sanitizedLauncherIntent(context)
        assertEquals(Intent.ACTION_MAIN, sanitized.action)
        assertNull(LauncherAction.fromExternalAction(sanitized.action))
        assertNull(sanitized.extras)
    }

    @Test
    fun external_activity_intents_are_sanitized_on_cold_and_warm_delivery() {
        val context = instrumentation.targetContext
        val hostile = Intent(context, MainActivity::class.java)
            .setAction("dev.po4yka.chur.action.search?query=private")
            .putExtra("query", "untrusted private text")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(hostile) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertEquals(Intent.ACTION_MAIN, activity.intent.action)
                assertNull(activity.intent.extras)
                instrumentation.callActivityOnNewIntent(activity, Intent(context, MainActivity::class.java)
                    .setAction("dev.po4yka.chur.action.unknown")
                    .putExtra("query", "another untrusted query"))
                assertEquals(Intent.ACTION_MAIN, activity.intent.action)
                assertNull(activity.intent.extras)
                assertNull(activity.intent.data)
                assertNull(activity.intent.clipData)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun foreground_publication_reaches_the_real_shortcut_manager() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val completed = CountDownLatch(1)
            LauncherShortcuts.refresh(activity) { completed.countDown() }
            assertTrue(completed.await(15, TimeUnit.SECONDS))
            val manager = activity.getSystemService(ShortcutManager::class.java)
            val expected = LauncherAction.entries.take(manager.maxShortcutCountPerActivity).map { it.shortcutId }.toSet()
            assertEquals(expected, manager.dynamicShortcuts.map { it.id }.toSet())
            manager.dynamicShortcuts.forEach { assertEquals(ComponentName(activity, MainActivity::class.java), it.intent?.component) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun real_vault_routes_import_empty_focused_search_and_unsaved_note_without_interrupting_drafts() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val root = File(activity.noBackupFilesDir, "launcher-actions-${System.nanoTime()}").apply { mkdirs() }
        val controller = ChurController(root.absolutePath, NoPrivacyCover, UnusedExports, clock = { System.currentTimeMillis() })
        var view: ComposeView? = null
        try {
            runBlocking {
                controller.start()
                controller.create("Launcher public test password", offerRecovery = false)
                withTimeout(10_000) { controller.route.first { it == AppRoute.Vault } }
            }
            instrumentation.runOnMainSync {
                val compose = ComposeView(activity).apply {
                    setContent {
                        val route by controller.route.collectAsState()
                        val state by controller.vaultState.collectAsState()
                        ChurTheme { ChurRoutes(controller, route, state) }
                    }
                }
                view = compose
                activity.setContentView(FrameLayout(activity).apply { addView(compose) })
            }
            assertTrue(await { has("Library") })
            request(controller, LauncherAction.IMPORT)
            assertTrue(await { has("Photos and videos") && controller.launcherRequest.value == null })
            instrumentation.runOnMainSync { assertFalse(controller.requestLauncherAction(LauncherAction.SEARCH)) }
            assertTrue(has("Photos and videos"))
            // The sheet owns a Dialog window: deliver system Back to that
            // window, rather than invoking the underlying Activity's Back.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue(await { !has("Photos and videos") })
            request(controller, LauncherAction.SEARCH)
            assertTrue(await {
                var focused = false
                instrumentation.runOnMainSync {
                    focused = nodes().any { SemanticsActions.SetText in it.config &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true &&
                        it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" }
                }
                focused
            })
            request(controller, LauncherAction.NEW_PRIVATE_NOTE)
            assertTrue(await { tagged("private-note-body") != null })
            val generation = (controller.vaultState.value as VaultState.Unlocked).generation
            assertEquals(0L, runBlocking { controller.vault.privateNotes(generation).notes.size.toLong() })
            instrumentation.runOnMainSync {
                val body = requireNotNull(taggedOnMain("private-note-body"))
                assertTrue(body.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Public unsaved draft")) == true)
                assertFalse(controller.requestLauncherAction(LauncherAction.IMPORT))
            }
            assertTrue(await { tagged("private-note-body")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "Public unsaved draft" })
            instrumentation.runOnMainSync {
                val save = nodes().first { "Save" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Save" } }
                assertTrue(save.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertTrue(await { runBlocking { controller.vault.privateNotes(generation).notes.size == 1 } })
            val saved = runBlocking { controller.vault.privateNotes(generation).notes.single() }
            assertEquals("Public unsaved draft", runBlocking { controller.vault.privateNote(generation, saved.noteId).body })
            assertTrue(await { !has("Saving or loading note…") })
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            assertTrue(await { tagged("private-note-search") != null && tagged("private-note-body") == null })
            request(controller, LauncherAction.NEW_PRIVATE_NOTE)
            assertTrue(await { tagged("private-note-body")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "" })
            assertEquals(1, runBlocking { controller.vault.privateNotes(generation).notes.size })
            instrumentation.runOnMainSync {
                val body = requireNotNull(taggedOnMain("private-note-body"))
                assertTrue(body.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Public second unsaved draft")) == true)
            }
            assertTrue(await { tagged("private-note-body")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "Public second unsaved draft" })
            request(controller, LauncherAction.LOCK_AND_HIDE)
            assertTrue(await { controller.route.value == AppRoute.PublicShell && controller.vaultState.value !is VaultState.Unlocked })
            assertFalse(has("Public unsaved draft"))
            instrumentation.runOnMainSync {
                controller.openVaultEntry()
                controller.unlock("Launcher public test password")
            }
            assertTrue(await { controller.route.value == AppRoute.Vault && !controller.unlocking.value })
            val reopened = (controller.vaultState.value as VaultState.Unlocked).generation
            assertEquals(1, runBlocking { controller.vault.privateNotes(reopened).notes.size })
            assertEquals("Public unsaved draft", runBlocking { controller.vault.privateNote(reopened, saved.noteId).body })
        } finally {
            instrumentation.runOnMainSync { view?.disposeComposition(); activity.finish() }
            runBlocking { controller.shutdown() }
            root.deleteRecursively()
        }
    }

    private fun request(controller: ChurController, action: LauncherAction) = instrumentation.runOnMainSync {
        assertTrue("command must be accepted: $action", controller.requestLauncherAction(action))
    }
    private fun await(check: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (check()) return true
            SystemClock.sleep(40)
        }
        return check()
    }
    private fun nodes(): List<SemanticsNode> = WindowInspector.getGlobalWindowViews()
        .flatMap { it.roots() }.flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
    private fun taggedOnMain(tag: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun tagged(tag: String): SemanticsNode? {
        var result: SemanticsNode? = null
        instrumentation.runOnMainSync { result = taggedOnMain(tag) }
        return result
    }
    private fun has(text: String): Boolean {
        var result = false
        instrumentation.runOnMainSync {
            result = nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } ||
                text in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        }
        return result
    }
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
    private object UnusedExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = error("No export in this test")
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? =
            error("No export in this test")
    }
}
