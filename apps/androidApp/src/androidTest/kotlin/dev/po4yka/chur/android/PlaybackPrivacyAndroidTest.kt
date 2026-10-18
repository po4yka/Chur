package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.LocalPrivatePanic
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real encrypted Media3 playback with two audio languages and a non-forced embedded subtitle. */
@RunWith(AndroidJUnit4::class)
class PlaybackPrivacyAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun actual_native_settings_and_subtitle_buttons_open_menus_with_one_action_hide() = present { f ->
        for (control in listOf("exo_settings", "exo_subtitle")) {
            f.main { f.hidden.value = false }
            f.ready()
            f.nativeClick(control)
            assertTrue(f.await { f.has("Lock and hide") })
            f.capture("playback-$control-private-menu.png")
            f.click("Lock and hide")
            assertTrue(f.await { f.has("Public screen") && !f.has("Lock and hide") && f.nativePlayerView() == null })
        }
        assertEquals(2, f.hides)
    }

    @Test
    fun replacement_menus_apply_real_speed_audio_and_subtitle_parameters() = present { f ->
        f.ready()
        f.nativeClick("exo_settings")
        f.click("Playback speed")
        f.clickTag("playback-speed-150")
        assertEquals(1.5f, f.main {
            val parameters = f.player().javaClass.getMethod("getPlaybackParameters").invoke(f.player())
            parameters.javaClass.getField("speed").getFloat(parameters)
        }, 0.001f)

        f.nativeClick("exo_settings")
        f.click("Audio tracks")
        f.clickTag("playback-audio-track-1")
        val frenchSelected = f.await {
            f.overrideLanguages() == listOf("fra") && f.selectedLanguages("TRACK_TYPE_AUDIO") == listOf("fra")
        }
        assertTrue("manual audio choice must select French in the actual player: ${f.selectionDiagnostic()}", frenchSelected)
        f.nativeClick("exo_settings")
        f.click("Audio tracks")
        f.clickTag("playback-audio-auto")
        assertTrue(f.await { f.overrideLanguages().isEmpty() })

        f.nativeClick("exo_subtitle")
        f.clickTag("playback-text-track-0")
        val englishSelected = f.await {
            f.overrideLanguages() == listOf("eng") && f.selectedLanguages("TRACK_TYPE_TEXT") == listOf("eng")
        }
        assertTrue("subtitle choice must select English in the actual player: ${f.selectionDiagnostic()}", englishSelected)
        f.nativeClick("exo_subtitle")
        f.clickTag("playback-text-none")
        assertTrue(f.await { f.overrideLanguages().isEmpty() && f.selectedLanguages("TRACK_TYPE_TEXT").isEmpty() })
        assertTrue("None must preserve Media3's forced-subtitle policy", f.main {
            val parameters = f.player().javaClass.getMethod("getTrackSelectionParameters").invoke(f.player())
            parameters.javaClass.getField("ignoredTextSelectionFlags").getInt(parameters) != 0
        })
        assertFalse(f.has("Lock and hide"))
    }

    private fun present(test: (Fixture) -> Unit) = runBlocking {
        val root = File(context.cacheDir, "playback-privacy-${System.nanoTime()}").apply { mkdirs() }
        val source = File(context.cacheDir, "export-scratch/playback-privacy-${System.nanoTime()}.mkv")
        source.parentFile?.mkdirs()
        instrumentation.context.assets.open("media-formats/playback-options.mkv").use { input -> source.outputStream().use { input.copyTo(it) } }
        val vault = VaultRepository(root.path, System::currentTimeMillis)
        var activity: MainActivity? = null
        var view: ComposeView? = null
        try {
            vault.start()
            vault.create("Synthetic playback privacy password".encodeToByteArray(), false)
            val codec = AndroidMediaCodec(context)
            val picked = requireNotNull(codec.open(FileProvider.getUriForFile(context, "${context.packageName}.exports", source)))
            val imported = MediaImporter(codec).import(vault, picked) as MediaImporter.Outcome.Imported
            val playback = PlaybackSource(vault, imported.objectId, "video/x-matroska", source.length())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val f = Fixture(requireNotNull(activity))
            f.main {
                val compose = ComposeView(f.activity).apply {
                    setContent {
                        ChurTheme(dark = true) {
                            if (f.hidden.value) Text("Public screen") else {
                                CompositionLocalProvider(LocalPrivatePanic provides { f.hides++; f.hidden.value = true }) {
                                    VaultPlayer(playback, Modifier.fillMaxSize(), poster = null, controlsVisible = true,
                                        controlsPinned = true, onControlsVisibleChange = {}, controlsPadding = PaddingValues(), touches = 0)
                                }
                            }
                        }
                    }
                }
                f.view = compose
                view = compose
                f.activity.setContentView(FrameLayout(f.activity).apply { addView(compose) })
            }
            test(f)
        } finally {
            instrumentation.runOnMainSync { view?.disposeComposition(); activity?.finish() }
            vault.shutdown()
            source.delete()
            root.deleteRecursively()
        }
    }

    private inner class Fixture(val activity: MainActivity) {
        lateinit var view: ComposeView
        val hidden = mutableStateOf(false)
        var hides = 0
        fun <T> main(action: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return action()
            var result: T? = null
            instrumentation.runOnMainSync { result = action() }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
        fun nativePlayerView(): View? = main { view.views().firstOrNull { it.javaClass.name == "androidx.media3.ui.PlayerView" } }
        fun player(): Any {
            val native = requireNotNull(nativePlayerView())
            return requireNotNull(native.javaClass.getMethod("getPlayer").invoke(native))
        }
        fun ready() = assertTrue("actual encrypted media must expose its audio/subtitle tracks", await {
            main {
                nativePlayerView()?.let {
                    val tracks = player().javaClass.getMethod("getCurrentTracks").invoke(player())
                    val groups = tracks.javaClass.getMethod("getGroups").invoke(tracks) as List<*>
                    groups.size >= 4
                } == true
            }
        })
        fun nativeClick(name: String) {
            fun control() = view.views().firstOrNull { it.id != View.NO_ID &&
                runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() == name }
            assertTrue("native control must be available: $name", await {
                main { control()?.let { it.isShown && it.isEnabled && it.isClickable } == true }
            })
            main { assertTrue(requireNotNull(control()).performClick()) }
        }
        private fun overrides(): Map<*, *> {
            val parameters = player().javaClass.getMethod("getTrackSelectionParameters").invoke(player())
            return parameters.javaClass.getField("overrides").get(parameters) as Map<*, *>
        }
        private fun rawOverrideLanguages(): List<Pair<Int, String?>> = overrides().values.flatMap { value ->
            val selection = requireNotNull(value)
            val group = selection.javaClass.getField("mediaTrackGroup").get(selection)
            val indices = selection.javaClass.getField("trackIndices").get(selection) as List<*>
            indices.map { value ->
                val index = (value as Number).toInt()
                val format = group.javaClass.getMethod("getFormat", Int::class.javaPrimitiveType).invoke(group, index)
                index to (format.javaClass.getField("language").get(format) as String?)
            }
        }
        // Media3 Format normalizes ISO639-2/T (fra/eng) to ISO639-1 (fr/en).
        // Compare the language identity, while retaining the exact overridden track indices.
        private fun canonicalLanguage(value: String?): String? = value?.let { Locale.forLanguageTag(it).getISO3Language() }
        fun overrideLanguages(): List<String?> = main { rawOverrideLanguages().map { canonicalLanguage(it.second) } }
        fun selectedLanguages(typeName: String): List<String?> = main {
            val type = Class.forName("androidx.media3.common.C").getField(typeName).getInt(null)
            val tracks = player().javaClass.getMethod("getCurrentTracks").invoke(player())
            val groups = tracks.javaClass.getMethod("getGroups").invoke(tracks) as List<*>
            groups.flatMap { value ->
                val group = requireNotNull(value)
                if (group.javaClass.getMethod("getType").invoke(group) != type) emptyList()
                else (0 until group.javaClass.getField("length").getInt(group)).filter { index ->
                    group.javaClass.getMethod("isTrackSelected", Int::class.javaPrimitiveType).invoke(group, index) == true
                }.map { index ->
                    val format = group.javaClass.getMethod("getTrackFormat", Int::class.javaPrimitiveType).invoke(group, index)
                    canonicalLanguage(format.javaClass.getField("language").get(format) as String?)
                }
            }
        }
        fun selectionDiagnostic(): String = main {
            "raw overridden (index, language)=${rawOverrideLanguages()}, " +
                "selected audio=${selectedLanguages("TRACK_TYPE_AUDIO")}, selected text=${selectedLanguages("TRACK_TYPE_TEXT")}"
        }
        fun nodes(): List<SemanticsNode> = main { WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) } }
        fun has(label: String) = nodes().any { label in it.labels }
        fun click(label: String) = activate { label in it.labels }
        fun clickTag(tag: String) = activate { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        private fun activate(match: (SemanticsNode) -> Boolean) {
            assertTrue(await { nodes().any { match(it) && SemanticsActions.OnClick in it.config } })
            main {
                val button = nodes().single { match(it) && SemanticsActions.OnClick in it.config }
                assertFalse(SemanticsProperties.Disabled in button.config)
                assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
        }
        fun await(check: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                if (check()) return true
                SystemClock.sleep(40)
            }
            return check()
        }
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(requireNotNull(activity.getExternalFilesDir("discreet-navigation")), name).outputStream().use {
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
    private fun View.views(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).views() } else emptyList()
}
