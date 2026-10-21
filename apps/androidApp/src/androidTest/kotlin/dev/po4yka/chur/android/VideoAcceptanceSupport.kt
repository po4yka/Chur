package dev.po4yka.chur.android

import android.content.Intent
import android.app.KeyguardManager
import android.os.PowerManager
import android.view.WindowManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*

/** Test-owned vault and actual app player; no plaintext playback fallback. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoAcceptanceSession private constructor(
    val directory: File,
    val vault: VaultRepository,
    val activity: MainActivity,
    private val screen: VideoTestWindow,
) {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    private var composition: ComposeView? = null
    lateinit var imported: MediaImporter.Outcome.Imported
        private set
    lateinit var originalHash: ByteArray
        private set
    var originalSize = 0L
        private set
    private var contentType = ""

    suspend fun importFixture(name: String, assetDirectory: String = "video-acceptance") {
        val file = copyVideoFixture(directory, name, assetDirectory)
        originalHash = fileDigest(file)
        originalSize = file.length()
        val codec = AndroidMediaCodec(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val outcome = MediaImporter(codec).import(vault, requireNotNull(codec.open(uri)))
        assertTrue("$name must preserve the original: $outcome", outcome is MediaImporter.Outcome.Imported)
        imported = outcome as MediaImporter.Outcome.Imported
        assertFalse("$name must be recognized as video", imported.storedAsFile)
        val reader = vault.leaseReader(imported.objectId)
        try { contentType = vault.readerContentInfo(reader).contentType }
        finally { vault.releaseReader(reader) }
        assertTrue("Remove the import source before encrypted playback", file.delete())
    }

    fun mount(onExport: (() -> Unit)? = null): ExoPlayer {
        onVideoMain {
            composition?.disposeComposition()
            composition = ComposeView(activity).apply {
                setContent { ChurTheme(dark = true) {
                    VaultPlayer(PlaybackSource(vault, imported.objectId, contentType, originalSize, onExport = onExport),
                        Modifier.fillMaxSize(), null, true, true, {}, PaddingValues(), 0)
                } }
            }
            activity.setContentView(FrameLayout(activity).apply { addView(composition) })
        }
        assertTrue("The app must create its native player", awaitVideo {
            onVideoMain { composition?.let(::findVideoPlayer) != null }
        })
        return onVideoMain { requireNotNull(findVideoPlayer(requireNotNull(composition))) }
    }

    fun unmount() = onVideoMain { composition?.disposeComposition(); composition = null }

    suspend fun assertExport() {
        val outputFile = File(directory, "export.mp4")
        ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
            val operation = vault.beginExport(imported.objectId, output.fd)
            try {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var progress = vault.poll(operation)
                while (!progress.terminal && SystemClock.elapsedRealtime() < deadline) {
                    delay(20); progress = vault.poll(operation)
                }
                assertTrue("Native export must finish", progress.terminal)
                assertEquals("Native export must succeed", 0, progress.status)
            } finally { vault.closeOperation(operation) }
        }
        assertEquals(originalSize, outputFile.length())
        assertArrayEquals("Export must preserve the camera/encoded bytes", originalHash, fileDigest(outputFile))
        assertTrue(outputFile.delete())
    }

    suspend fun close() {
        unmount()
        screen.close()
        try { vault.lock(LockReason.USER) } finally {
            vault.shutdown()
            directory.deleteRecursively()
        }
    }

    companion object {
        suspend fun create(): VideoAcceptanceSession {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val directory = File(context.cacheDir, "export-scratch/video-acceptance-${System.nanoTime()}")
            assertTrue(directory.mkdirs())
            val vault = VaultRepository(File(directory, "vault").path, System::currentTimeMillis)
            val screen = VideoTestWindow.open()
            val activity = screen.activity
            try {
                vault.start()
                vault.create("Video acceptance test vault password".encodeToByteArray(), false)
                return VideoAcceptanceSession(directory, vault, activity, screen)
            } catch (failure: Throwable) {
                screen.close()
                runCatching { vault.shutdown() }
                directory.deleteRecursively()
                throw failure
            }
        }
    }
}

internal fun copyVideoFixture(directory: File, name: String, assetDirectory: String = "video-acceptance"): File = File(directory, name).also { output ->
    InstrumentationRegistry.getInstrumentation().context.assets.open("$assetDirectory/$name").use { input ->
        output.outputStream().use { input.copyTo(it) }
    }
}

internal fun fileDigest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            update(buffer, 0, count)
        }
        buffer.fill(0)
    }
    digest()
}

internal fun videoFixtureManifest(): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
    .open("video-acceptance/manifest.json").bufferedReader().use { JSONObject(it.readText()) }

internal fun videoReport(name: String, report: JSONObject) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("video-acceptance"))
    File(directory, "$name.json").writeText(report.toString(2) + "\n")
    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "VIDEO_ACCEPTANCE $name $report\n") })
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun findVideoPlayer(view: View): ExoPlayer? {
    if (view is PlayerView) return view.player as? ExoPlayer
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        findVideoPlayer(view.getChildAt(index))?.let { return it }
    }
    return null
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun videoReady(player: Player) = player.playbackState == Player.STATE_READY || player.playerError != null ||
    (player.currentTracks.containsType(C.TRACK_TYPE_VIDEO) && !player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true))

internal fun awaitVideo(timeoutMs: Long = 30_000, condition: () -> Boolean): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return true
        SystemClock.sleep(10)
    }
    return condition()
}

internal fun <T> onVideoMain(block: () -> T): T {
    var result: T? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
    @Suppress("UNCHECKED_CAST") return result as T
}

/** Keep only a test-owned foreground window awake; never dismiss system authentication. */
internal class VideoTestWindow private constructor(val activity: MainActivity, private val previouslyKeptAwake: Boolean) {
    fun close() = onVideoMain {
        if (!previouslyKeptAwake) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.finish()
    }
    companion object {
        fun open(blank: Boolean = false): VideoTestWindow {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assertFalse("Unlock the test device before video acceptance. Tests do not dismiss a secured keyguard.",
                requireNotNull(context.getSystemService(KeyguardManager::class.java)).isKeyguardLocked)
            assertTrue("Wake the unlocked test device before video acceptance.",
                requireNotNull(context.getSystemService(PowerManager::class.java)).isInteractive)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            return onVideoMain {
                val wasKeptAwake = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (blank) activity.setContentView(FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.BLACK) })
                VideoTestWindow(activity, wasKeptAwake)
            }
        }
    }
}
