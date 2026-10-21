package dev.po4yka.chur.android

import android.net.Uri
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.vault.PlaybackAudioFormat
import dev.po4yka.chur.app.vault.PlaybackDiagnostic
import dev.po4yka.chur.app.vault.PlaybackProblem
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real MP4 parser and decoder selection; no replacement codec selector. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackDiagnosticsAndroidTest {
    @Test
    fun nativeDtsAudioRejectionExplainsTheTrackAndExportsTheOriginal() = runBlocking {
        val session = VideoAcceptanceSession.create()
        try {
            session.importFixture("hevc-dts.mp4", "video-diagnostics")
            val exports = AtomicInteger()
            val player = session.mount { exports.incrementAndGet() }
            assertTrue(awaitVideo { onVideoMain { videoReady(player) ||
                player.currentTracks.containsType(C.TRACK_TYPE_AUDIO) && !player.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO, true) } })
            val diagnostic = onVideoMain { diagnostic(player.playerError, player.currentTracks) }
            if (diagnostic?.problem == PlaybackProblem.UNSUPPORTED_AUDIO) {
                assertEquals(PlaybackAudioFormat.DTS, diagnostic.audio)
                val explained = awaitVideo {
                    onVideoMain { text(session.activity.window.decorView).any { it.contains("DTS track") } }
                }
                if (!explained) {
                    val screenshot = requireNotNull(session.instrumentation.uiAutomation.takeScreenshot())
                    try { File(requireNotNull(session.context.getExternalFilesDir("video-diagnostics")), "dts-panel-failure.png")
                        .outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { screenshot.recycle() }
                    android.util.Log.e("ChurVideoDiagnostics", onVideoMain {
                        "visible=${text(session.activity.window.decorView)} native=${diagnostic(player.playerError, player.currentTracks)} state=${player.playbackState}"
                    })
                }
                assertTrue("The actual app must identify DTS audio", explained)
                assertTrue("Export original action must be visible and actionable", onVideoMain {
                    click(session.activity.window.decorView, "Export original")
                })
                assertEquals(1, exports.get())
                assertFalse(onVideoMain { player.playWhenReady })
            } else {
                assertFalse("This device lane requires an actual DTS rejection",
                    InstrumentationRegistry.getArguments().getString("chur.video.requireUnsupportedDts") == "true")
                assertNull("A supported DTS track must not invent a rejection", diagnostic)
                onVideoMain { player.play() }
                assertTrue("Supported audio must really decode", awaitVideo {
                    onVideoMain { player.audioDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount > 0 } == true }
                })
            }
            session.unmount()
            session.assertExport()
        } finally { session.close() }
    }

    @Test
    fun aRealTruncatedContainerHasAParserReasonAndNeverAnIntegrityClaim() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "export-scratch/invalid-video-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val source = copyVideoFixture(directory, "truncated-container.mp4", "video-diagnostics")
        val player = onVideoMain { ExoPlayer.Builder(instrumentation.targetContext).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(source))); prepare()
        } }
        try {
            assertTrue("The native extractor must reject the actual truncated bytes", awaitVideo { onVideoMain { player.playerError != null } })
            val error = onVideoMain { requireNotNull(player.playerError) }
            val mapped = requireNotNull(onVideoMain { diagnostic(error, player.currentTracks) })
            assertTrue("A native parser failure must describe the container, not authentication", mapped.problem in
                setOf(PlaybackProblem.INVALID_MEDIA, PlaybackProblem.UNSUPPORTED_CONTAINER))
            assertFalse(mapped.canRetry)
            assertFalse(mapped.canViewCopy)
            assertTrue(mapped.canExportOriginal)
        } finally { onVideoMain { player.release() }; directory.deleteRecursively() }
    }

    private fun diagnostic(error: PlaybackException?, tracks: Tracks): PlaybackDiagnostic? =
        Class.forName("dev.po4yka.chur.app.vault.PlaybackDiagnostics_androidKt")
            .getDeclaredMethod("androidPlaybackDiagnostic", PlaybackException::class.java, Tracks::class.java)
            .invoke(null, error, tracks) as PlaybackDiagnostic?

    private fun nodes(view: View) = views(view).filterIsInstance<ViewRootForTest>()
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .filter { !it.boundsInWindow.isEmpty }
    private fun text(view: View) = nodes(view).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun click(view: View, label: String): Boolean = nodes(view).firstOrNull {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
