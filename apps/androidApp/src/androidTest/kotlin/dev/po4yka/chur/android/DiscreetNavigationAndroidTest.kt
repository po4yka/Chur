package dev.po4yka.chur.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.AppRoute
import dev.po4yka.chur.app.ChurApp
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.app.GateResult
import dev.po4yka.chur.app.NoPrivacyCover
import dev.po4yka.chur.app.PublicShell
import dev.po4yka.chur.app.notes.Disclosure
import dev.po4yka.chur.vault.VaultState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production Android routes over a temporary JNI vault; no existing host storage is changed. */
@RunWith(AndroidJUnit4::class)
class DiscreetNavigationAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun every_public_shell_reaches_the_vault_gate_and_cancels_unlock_and_recovery_directly_back() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val root = File(activity.noBackupFilesDir, "discreet-navigation-${System.nanoTime()}").apply { mkdirs() }
        val vaultRoot = File(root, "vault").apply { mkdirs() }
        val controller = ChurController(vaultRoot.absolutePath, NoPrivacyCover, UnusedExports,
            clock = { System.currentTimeMillis() })
        var view: ComposeView? = null
        try {
            val verdict = runGate(activity)
            assertTrue("the real JNI ABI gate must pass", verdict is GateResult.Compatible)
            runBlocking {
                withContext(Dispatchers.Main) {
                    controller.start()
                    controller.create("Synthetic navigation test password", offerRecovery = false)
                }
                withTimeout(15_000) { controller.route.first { it == AppRoute.Vault } }
                val generation = (controller.vaultState.value as VaultState.Unlocked).generation
                controller.vault.savePrivateNote(generation, title = PRIVATE_TITLE, body = PRIVATE_BODY)
                withContext(Dispatchers.Main) { controller.lock() }
                withTimeout(15_000) { controller.vaultState.first { it is VaultState.Locked } }
                withTimeout(15_000) { controller.route.first { it == AppRoute.PublicShell } }
            }
            // Camera's public cache/album are also isolated. Only the locked/public routes
            // are ever composed, so VaultRoute cannot bind the process host to this controller.
            val isolatedContext = object : ContextWrapper(activity) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = File(root, "public-files").apply { mkdirs() }
                override fun getCacheDir(): File = File(root, "public-cache").apply { mkdirs() }
            }
            val f = Fixture(activity, controller)
            f.main {
                val compose = ComposeView(activity).apply {
                    setContent {
                        val route by controller.route.collectAsState()
                        val state by controller.vaultState.collectAsState()
                        CompositionLocalProvider(LocalContext provides isolatedContext) {
                            ChurApp(verdict, route) { ChurRoutes(controller, route, state) }
                        }
                    }
                }
                view = compose
                f.view = compose
                activity.setContentView(FrameLayout(activity).apply { addView(compose) })
            }
            for (shell in PublicShell.entries) {
                // Select through the production settings controls, preserving the normal entry path.
                f.click("Settings", AppRoute.PublicSettings)
                f.click(shell.label, AppRoute.PublicSettings)
                assertTrue("public screen preference must finish saving", f.await { controller.publicShell.value == shell })
                assertEquals(shell, controller.publicShell.value)
                f.click("Back", AppRoute.PublicShell)
                f.assertPublic(shell)
                f.capture("${shell.storedValue}-public.png")

                f.click("Settings", AppRoute.PublicSettings)
                f.click(Disclosure.VAULT_ENTRY, AppRoute.Unlock)
                assertTrue(f.await { f.has("Enter your password or PIN to open the vault.") })
                f.assertPrivateContentAbsent()
                f.capture("${shell.storedValue}-unlock.png")
                f.click("Return to public screen", AppRoute.PublicShell)
                f.assertPublic(shell)
                f.capture("${shell.storedValue}-returned.png")

                f.click("Settings", AppRoute.PublicSettings)
                f.click(Disclosure.VAULT_ENTRY, AppRoute.Unlock)
                f.click("Use recovery phrase", AppRoute.Recover)
                assertTrue(f.await { f.has("Recovery phrase") })
                f.click("Return to public screen", AppRoute.PublicShell)
                f.assertPublic(shell)
                assertEquals(shell, controller.publicShell.value)
            }
        } finally {
            instrumentation.runOnMainSync { view?.disposeComposition(); activity.finish() }
            runBlocking { controller.shutdown() }
            root.deleteRecursively()
        }
    }

    private inner class Fixture(val activity: MainActivity, val controller: ChurController) {
        lateinit var view: ComposeView
        fun main(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else instrumentation.runOnMainSync(action)
        }
        fun nodes(): List<SemanticsNode> {
            var result = emptyList<SemanticsNode>()
            main { result = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
                .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) } }
            return result
        }
        fun has(label: String) = nodes().any { label in it.labels }
        fun click(label: String, expected: AppRoute) {
            assertTrue("missing route action: $label", await {
                nodes().any { label in it.labels && SemanticsActions.OnClick in it.config }
            })
            main {
                val button = nodes().single { label in it.labels && SemanticsActions.OnClick in it.config }
                assertFalse("route action must be enabled: $label", SemanticsProperties.Disabled in button.config)
                assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
                // This assertion runs before leaving the click's Main turn, without waiting for JNI.
                assertEquals("$label must change route synchronously", expected, controller.route.value)
            }
        }
        fun assertPublic(shell: PublicShell) {
            assertTrue("selected public shell must render after cancellation", await {
                has(shell.label) && !has("Return to public screen") && !has("Recovery phrase") &&
                    !has("Enter your password or PIN to open the vault.")
            })
            assertEquals(AppRoute.PublicShell, controller.route.value)
            assertPrivateContentAbsent()
            main {
                assertFalse("credential controls must leave public semantics", nodes().any {
                    SemanticsProperties.Password in it.config || "Lock now" in it.labels ||
                        "Use recovery phrase" in it.labels || "Private notes" in it.labels || "Library" in it.labels
                })
            }
        }
        fun assertPrivateContentAbsent() {
            val labels = nodes().flatMap { it.labels }
            assertFalse(labels.any { PRIVATE_TITLE in it || PRIVATE_BODY in it })
        }
        fun await(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                if (condition()) return true
                SystemClock.sleep(40)
            }
            return condition()
        }
        fun capture(name: String) {
            val drawn = CountDownLatch(1)
            main { view.postOnAnimation { view.postOnAnimation { drawn.countDown() } } }
            assertTrue("route must draw before capture", drawn.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val output = File(requireNotNull(activity.getExternalFilesDir("discreet-navigation")), name)
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }

    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
    private object UnusedExports : ExportSink {
        override fun cancelPending() = Unit
        override fun create(displayName: String, contentType: String): ExportSink.Destination? = error("No export in navigation test")
        override fun create(displayName: String, contentType: String, target: ExportTarget, uri: String?): ExportSink.Destination? =
            error("No export in navigation test")
    }
    private companion object {
        const val PRIVATE_TITLE = "Synthetic private navigation title"
        const val PRIVATE_BODY = "Synthetic encrypted content must never enter a public shell"
    }
}
