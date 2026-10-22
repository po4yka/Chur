package dev.po4yka.chur.android

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.publicshell.PublicUtilityStore
import dev.po4yka.chur.app.publicshell.PublicUtilityTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real CameraX capture through the production UI, into an isolated public directory. */
@RunWith(AndroidJUnit4::class)
class PublicCameraCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun photoAndVideoAreAutomaticallySavedAndReadableAfterReopen() {
        val base = instrumentation.targetContext
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(base.packageName, permission)
        }
        val activity = instrumentation.startActivitySync(Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val root = File(base.cacheDir, "camera-capture-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(activity) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        var exported: android.net.Uri? = null
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        CompositionLocalProvider(LocalContext provides context) {
                            PublicUtilityTheme(PublicUtilityStore()) {
                                AndroidPublicCameraScreen(onOpenSettings = {}, showFirstWriteDisclosure = false,
                                    onAcknowledgeDisclosure = {}, onBeginCapture = { {} })
                            }
                        }
                    }
                })
            }
            click("Take photo")
            await("photo persistence") { PublicMediaAlbum(context).list().any { !it.video } }
            val photo = PublicMediaAlbum(context).list().single { !it.video }
            val thumbnail = PublicMediaAlbum(context).thumbnail(photo.id)
            assertNotNull(thumbnail)
            thumbnail?.recycle()
            click("Video")
            click("Record video")
            await("recording start") { hasAction("Stop recording") }
            SystemClock.sleep(3000) // Capture enough real audio/video samples to inspect both tracks.
            click("Stop recording")
            await("video persistence") { PublicMediaAlbum(context).list().any { it.video } }
            val reopened = PublicMediaAlbum(context)
            assertEquals(2, reopened.list().size)
            val file = reopened.file(reopened.list().single { it.video }.id)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
                assertTrue(formats.filter { it.containsKey(MediaFormat.KEY_DURATION) }.all { it.getLong(MediaFormat.KEY_DURATION) > 0 })
            } finally { extractor.release() }
            click("Gallery")
            click(reopened.list().first().label)
            await("video playback advances") {
                var playing = false
                instrumentation.runOnMainSync {
                    fun inspect(view: View) {
                        if (view is android.widget.VideoView && view.isPlaying && view.currentPosition > 0) playing = true
                        if (view is ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                    }
                    WindowInspector.getGlobalWindowViews().forEach(::inspect)
                }
                playing
            }
            click("Export")
            click("Save to Photos")
            await("explicit system gallery export") {
                base.contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID),
                    "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(file.name), null)?.use { cursor ->
                    if (cursor.moveToFirst()) exported = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
                exported != null
            }
            base.contentResolver.openAssetFileDescriptor(requireNotNull(exported), "r")!!.use { descriptor ->
                assertTrue(descriptor.length > 0)
            }
            click("Camera")
            click("Photo")
            click("Flip")
            click("Take photo")
            await("front camera photo") { reopened.list().count { !it.video } == 2 }
            click("Flip")
            click("Controls")
            var lensLabels = emptyList<String>()
            await("Pixel exposes physical lenses") {
                instrumentation.runOnMainSync { lensLabels = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }.filter { it.endsWith(" mm") }.distinct() }
                lensLabels.isNotEmpty()
            }
            for (lens in lensLabels) {
                click(lens)
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                click("Take photo")
                val expected = 3 + lensLabels.indexOf(lens)
                await("physical lens $lens persists photo") { reopened.list().count { !it.video } == expected }
                click("Controls")
            }
            click("Auto")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.runOnMainSync { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("landscape configuration") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            click("Take photo")
            await("landscape photo") { reopened.list().count { !it.video } == 3 + lensLabels.size }
            instrumentation.runOnMainSync { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("portrait configuration") { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            click("Video")
            click("Controls")
            click("Sound")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            click("Record video")
            await("silent recording start") { hasAction("Stop recording") }
            SystemClock.sleep(2000)
            click("Stop recording")
            await("silent recording saved") { reopened.list().count { it.video } == 2 }
            MediaExtractor().let { silent ->
                try {
                    silent.setDataSource(reopened.file(reopened.list().first { it.video }.id).path)
                    assertFalse((0 until silent.trackCount).any { silent.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
                } finally { silent.release() }
            }
            click("Record video")
            await("second recording start") { hasAction("Stop recording") }
            SystemClock.sleep(2000)
            instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
            await("background recording finalization") { reopened.list().count { it.video } == 3 }
            assertTrue(reopened.list().all { reopened.file(it.id).length() > 0 })
        } finally {
            exported?.let { base.contentResolver.delete(it, null, null) }
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            root.deleteRecursively()
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < until) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(label, condition())
    }
    private fun hasAction(label: String): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = nodes().any {
            label in (it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } + it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) &&
                SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config
        } }
        return found
    }
    private fun click(label: String) {
        await(label) { hasAction(label) }
        instrumentation.runOnMainSync {
            val node = nodes().first {
                label in (it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } + it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) &&
                    SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config
            }
            assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun nodes() = WindowInspector.getGlobalWindowViews().flatMap { it.roots() }
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
    private fun View.roots(): List<ViewRootForTest> = buildList {
        if (this@roots is ViewRootForTest) add(this@roots)
        if (this@roots is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).roots())
    }
}
