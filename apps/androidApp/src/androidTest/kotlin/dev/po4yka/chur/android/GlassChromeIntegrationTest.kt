package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real application, native vault and platform chrome; run on a synthetic test device. */
@RunWith(AndroidJUnit4::class)
class GlassChromeIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private val controller: ChurController get() = ChurHost.of(activity).controller

    @Before
    fun openVault() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        assertTrue(await { controller.vaultState.value !is VaultState.Starting })
        main {
            when (controller.vaultState.value) {
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
        assertTrue("the real test vault opens: ${controller.formError.value}", await(60_000) {
            controller.vaultState.value is VaultState.Unlocked && controller.route.value == AppRoute.Vault
        })
        assertTrue(await { has("Browse") && has("Lock now") })
        assertSecure()
    }

    @After
    fun finish() {
        if (!::activity.isInitialized) return
        main { controller.panic() }
        await { controller.vaultState.value !is VaultState.Unlocked }
        main { activity.finish() }
    }

    @Test
    fun tabs_browse_and_selection_preserve_commands_then_lock_removes_private_chrome() {
        listOf("Albums", "Search", "Settings", "Library").forEach { label ->
            click(label)
            assertTrue("tab selection reaches shared state: $label", await {
                var selected = false
                main { selected = nodes().any { label in it.labels && it.config.getOrNull(SemanticsProperties.Selected) == true } }
                selected
            })
        }
        click("Browse")
        click("Recently added")
        assertTrue(await { has("Order: Recently added") })
        click("Back")
        importPhoto()
        assertTrue("the imported photo has an accessible selection action", await {
            var selected = false
            main {
                nodes().firstOrNull { SemanticsActions.OnLongClick in it.config && "Lock now" !in it.labels }
                    ?.let { selected = it.config[SemanticsActions.OnLongClick].action?.invoke() == true }
            }
            selected
        })
        assertTrue(await { has("1 selected") && has("More") && has("Lock now") })
        click("Lock now")
        assertPrivateChromeGone()
        main {
            assertTrue(controller.page.value.objects.isEmpty())
            assertTrue(controller.albums.value.isEmpty())
        }
    }

    @Test
    fun panic_is_accessible_and_drops_glass_chrome_before_returning_to_public_shell() {
        click("Albums")
        main {
            val lock = nodes().first { "Lock now" in it.labels && SemanticsActions.CustomActions in it.config }
            assertTrue(lock.config[SemanticsActions.CustomActions].single { it.label == "Lock immediately" }.action())
            assertEquals("panic changes the route synchronously", AppRoute.PublicShell, controller.route.value)
        }
        assertPrivateChromeGone()
    }

    @Test
    fun background_and_resume_keep_secure_window_and_do_not_restore_glass_or_private_navigation() {
        click("Search")
        main { assertTrue(activity.moveTaskToBack(true)) }
        assertTrue(await { controller.vaultState.value !is VaultState.Unlocked && controller.route.value != AppRoute.Vault })
        assertSecure()
        // singleTask resumes this instance through onNewIntent; there is no new
        // Activity creation for startActivitySync's launch monitor to observe.
        main {
            activity.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        assertTrue("the existing task resumes", await {
            var resumed = false
            main { resumed = activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            resumed
        })
        assertPrivateChromeGone()
        assertEquals(AppRoute.PublicShell, controller.route.value)
    }

    private fun assertPrivateChromeGone() {
        assertTrue("vault closes and its source/effect subtree leaves composition", await {
            controller.vaultState.value !is VaultState.Unlocked && controller.route.value != AppRoute.Vault &&
                !has("Lock now") && !has("Browse") && !has("1 selected")
        })
        assertFalse(has("More"))
    }

    private fun assertSecure() = main {
        assertTrue("glass must preserve FLAG_SECURE",
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    private fun importPhoto() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "export-scratch/glass-chrome-synthetic.png")
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            val codec = AndroidMediaCodec(context.contentResolver)
            val outcome = runBlocking { controller.importMedia(MediaImporter(codec)) { codec.open(uri) } }
            assertTrue("generated pixels import through the real native vault: $outcome", outcome is MediaImporter.Outcome.Imported)
            main { controller.reportImport(null) }
            assertTrue(await { controller.page.value.objects.isNotEmpty() })
        } finally { file.delete() }
    }

    private fun click(label: String) {
        assertTrue("control exists: $label", await { has(label) })
        main {
            val node = nodes().first { label in it.labels && SemanticsActions.OnClick in it.config }
            assertFalse(SemanticsProperties.Disabled in node.config)
            assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        instrumentation.waitForIdleSync()
    }

    private fun has(label: String): Boolean {
        var found = false
        main { found = nodes().any { label in it.labels } }
        return found
    }

    private fun nodes() = WindowInspector.getGlobalWindowViews().flatMap { roots(it) }
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }

    private fun roots(view: View): List<ViewRootForTest> = when {
        view is ViewRootForTest -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
        else -> emptyList()
    }

    private val SemanticsNode.labels: List<String>
        get() = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(timeout: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(40)
        }
        return condition()
    }

    private companion object {
        const val PASSWORD = "BackNavigationTest-password"
    }
}
