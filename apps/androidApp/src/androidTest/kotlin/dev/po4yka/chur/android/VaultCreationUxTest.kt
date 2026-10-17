package dev.po4yka.chur.android

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.theme.ChurMotion
import dev.po4yka.chur.app.theme.LocalChurMotion
import dev.po4yka.chur.app.vault.CreateVaultScreen
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The production creation form with public credentials, at 320 dp and double font. */
@RunWith(AndroidJUnit4::class)
class VaultCreationUxTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun confirmation_waits_until_blur_then_explains_mismatch_and_matching_enables_creation() = present { f ->
        assertFalse(f.enabled("Create vault"))
        f.enter("Password", PUBLIC_PASSWORD)
        f.enter("Repeat password", "pub")
        assertFalse(f.has("Passwords do not match."))
        assertFalse(f.error("Repeat password"))
        f.focus("Password")
        assertTrue(f.has("Passwords do not match."))
        assertTrue(f.error("Repeat password"))
        assertFalse(f.enabled("Create vault"))
        f.enter("Repeat password", PUBLIC_PASSWORD)
        assertFalse(f.error("Repeat password"))
        assertTrue(f.has("Passwords match. Ready to create vault."))
        assertTrue(f.enabled("Create vault"))
        f.main { assertFalse(f.registry.performSave().toString().contains(PUBLIC_PASSWORD)) }
        f.click("Create vault")
        assertEquals(listOf(PUBLIC_PASSWORD to true), f.creations)
    }

    @Test
    fun changing_factor_resets_confirmation_and_keeps_the_full_pin_policy() = present { f ->
        f.enter("Password", PUBLIC_PASSWORD)
        f.enter("Repeat password", "different")
        f.focus("Password")
        assertTrue(f.error("Repeat password"))
        f.click("Use a PIN instead")
        assertFalse(f.error("Repeat PIN"))
        assertFalse(f.has("Passwords do not match."))
        assertEquals("", f.value("PIN"))
        assertEquals("", f.value("Repeat PIN"))
        f.enter("PIN", "123")
        f.enter("Repeat PIN", "123")
        f.focus("PIN")
        assertFalse(f.error("Repeat PIN"))
        assertFalse(f.enabled("Create vault"))
        assertTrue(f.has("Enter the same 12–20 digit PIN in both fields to enable Create vault."))
        f.enter("PIN", "123456789012")
        f.enter("Repeat PIN", "123456789012")
        assertTrue(f.enabled("Create vault"))
        f.click("Use a password instead")
        assertEquals("", f.value("Password"))
        assertEquals("", f.value("Repeat password"))
        assertFalse(f.enabled("Create vault"))
        assertFalse(f.error("Repeat password"))
    }

    @Test
    fun recovery_is_one_labelled_checkbox_with_a_full_touch_target_and_busy_guard() = present { f ->
        f.main {
            val nodes = f.nodes().filter { "Create a recovery phrase" in it.labels }
            assertEquals(1, nodes.size)
            val checkbox = nodes.single()
            assertEquals(Role.Checkbox, checkbox.config[SemanticsProperties.Role])
            assertEquals(ToggleableState.On, checkbox.config[SemanticsProperties.ToggleableState])
            assertTrue(checkbox.size.height >= 48 * f.activity.resources.displayMetrics.density)
        }
        f.capture()
        f.reveal("Create a recovery phrase")
        f.capture("vault-creation-recovery-public-320-font2.png")
        f.click("Create a recovery phrase")
        f.main {
            assertEquals(ToggleableState.Off, f.node("Create a recovery phrase").config[SemanticsProperties.ToggleableState])
        }
        assertTrue(f.has("Without a recovery phrase, a forgotten password cannot be recovered. " +
            "You can set one up later in Settings. Chur asks for your screen lock first."))
        f.main { f.busy.value = true }
        f.frames()
        assertFalse(f.enabled("Create a recovery phrase"))
    }

    @Test
    fun system_reduce_motion_updates_the_composed_theme_live_and_restores_normal_motion() {
        val animator = Settings.Global.ANIMATOR_DURATION_SCALE
        val transition = Settings.Global.TRANSITION_ANIMATION_SCALE
        val resolver = instrumentation.targetContext.contentResolver
        val previousAnimator = Settings.Global.getString(resolver, animator)
        val previousTransition = Settings.Global.getString(resolver, transition)
        try {
            setMotionScale(animator, "1")
            setMotionScale(transition, "1")
            present { f ->
                f.assertMotion(reduced = false)
                setMotionScale(animator, "0")
                setMotionScale(transition, "0")
                f.assertMotion(reduced = true)
                setMotionScale(animator, null)
                setMotionScale(transition, null)
                f.assertMotion(reduced = false)
                setMotionScale(animator, "0")
                f.assertMotion(reduced = true)
                f.capture("vault-creation-reduced-motion-public-320-font2.png")
                setMotionScale(animator, "1")
                f.assertMotion(reduced = false)
                setMotionScale(transition, "0")
                f.assertMotion(reduced = true)
                setMotionScale(transition, "1")
                f.assertMotion(reduced = false)
            }
        } finally {
            try { setMotionScale(animator, previousAnimator) }
            finally { setMotionScale(transition, previousTransition) }
        }
        assertEquals(previousAnimator, Settings.Global.getString(resolver, animator))
        assertEquals(previousTransition, Settings.Global.getString(resolver, transition))
    }

    private fun present(test: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val f = Fixture(activity)
        try {
            f.main {
                val density = activity.resources.displayMetrics.density
                val config = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 320; screenHeightDp = 800; fontScale = 2f
                }
                val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(config) }
                f.view = ComposeView(context).apply {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalConfiguration provides config,
                            LocalSaveableStateRegistry provides f.registry) {
                            ChurTheme(dark = false) {
                                val motion = LocalChurMotion.current
                                SideEffect { f.observedMotion = motion }
                                CreateVaultScreen(busy = f.busy.value, error = null,
                                    onCreate = { password, recovery -> f.creations += password to recovery }, onCancel = {})
                            }
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(f.view, FrameLayout.LayoutParams((320 * density).toInt(), (800 * density).toInt()))
                })
            }
            f.frames()
            test(f)
        } finally { f.main { f.view.disposeComposition(); activity.finish() } }
    }

    private inner class Fixture(val activity: MainActivity) {
        lateinit var view: ComposeView
        val busy = mutableStateOf(false)
        val registry = SaveableStateRegistry(emptyMap()) { true }
        val creations = mutableListOf<Pair<String, Boolean>>()
        var observedMotion: ChurMotion? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun nodes() = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        fun node(label: String) = nodes().first { label in it.labels }
        fun has(label: String): Boolean { var found = false; main { found = nodes().any { label in it.labels } }; return found }
        fun enabled(label: String): Boolean { var result = false; main { result = SemanticsProperties.Disabled !in node(label).config }; return result }
        fun error(label: String): Boolean { var result = false; main { result = SemanticsProperties.Error in node(label).config }; return result }
        fun value(label: String): String { var result = ""; main { result = node(label).config[SemanticsProperties.EditableText].text }; return result }
        fun focus(label: String) {
            main { assertTrue(node(label).config[SemanticsActions.RequestFocus].action?.invoke() == true) }
            frames()
        }
        fun enter(label: String, value: String) {
            focus(label)
            main { assertTrue(node(label).config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
            frames()
        }
        fun click(label: String) {
            main {
                val node = node(label)
                assertFalse(SemanticsProperties.Disabled in node.config)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            frames()
        }
        fun reveal(label: String) {
            main {
                val node = node(label)
                val scroll = generateSequence(node.parent) { it.parent }
                    .first { SemanticsActions.ScrollBy in it.config }
                val viewport = scroll.boundsInWindow
                val top = node.positionInWindow.y
                val bottom = top + node.size.height
                val delta = when {
                    top < viewport.top -> top - viewport.top
                    bottom > viewport.bottom -> bottom - viewport.bottom
                    else -> 0f
                }
                if (delta != 0f) assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta) == true)
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            var visible = false
            do {
                instrumentation.waitForIdleSync()
                main { node(label).let { visible = it.boundsInWindow.height + 2f >= it.size.height } }
                if (visible) break
                SystemClock.sleep(30)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue("The full recovery control must be visible", visible)
            frames()
        }
        fun frames() {
            val ready = CountDownLatch(1)
            main { view.postOnAnimation { view.postOnAnimation { ready.countDown() } } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        fun assertMotion(reduced: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var observed: ChurMotion? = null
            do {
                instrumentation.waitForIdleSync()
                main { observed = observedMotion }
                if (observed?.reducedMotion == reduced) break
                SystemClock.sleep(30)
            } while (SystemClock.uptimeMillis() < deadline)
            val resolver = instrumentation.targetContext.contentResolver
            val settings = "animator=${Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)}, " +
                "transition=${Settings.Global.getString(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE)}"
            assertEquals("The composed theme must observe system motion changes ($settings)", reduced, observed?.reducedMotion)
            assertEquals(if (reduced) 120 else 240, observed?.navigationDurationMillis)
            assertEquals(if (reduced) 0 else 90, observed?.pressDurationMillis)
        }
        fun capture(name: String = "vault-creation-public-320-font2.png") = main {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                File(requireNotNull(activity.getExternalFilesDir(null)), name).outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }
    private val SemanticsNode.labels get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
    private fun setMotionScale(setting: String, value: String?) {
        require(value == null || (value.none { it.isWhitespace() } && value.toFloatOrNull()?.isFinite() == true)) {
            "An animation scale must be a numeric argument or absent"
        }
        // UiAutomation executes argument tokens directly; shell quotes would become part of the stored value.
        val command = if (value == null) "settings delete global $setting"
            else "settings put global $setting $value"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        assertEquals("The animation setting must contain the exact requested value", value,
            Settings.Global.getString(instrumentation.targetContext.contentResolver, setting))
    }
    private companion object { const val PUBLIC_PASSWORD = "public creation fixture" }
}
