package dev.po4yka.chur.android

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.compatibleVideoSource
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.StreamKind
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual private dialogs, native viewing-copy playback and the preserved host export callback. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoPlaybackToolsAndroidTest {
    @Test
    fun explicitConfirmCancelAndSourceSwitchesKeepExportOnTheOriginal() = runBlocking {
        val session = VideoAcceptanceSession.create()
        try {
            session.importFixture("main10-long-gop-vfr.mp4")
            val exports = AtomicInteger()
            val exportOriginal: () -> Unit = { exports.incrementAndGet() }
            session.mount(exportOriginal)
            assertTrue(awaitVideo { onVideoMain { hasText(session, "Create viewing copy") } })
            assertTrue(onVideoMain { click(session, "Create viewing copy") })
            assertTrue(awaitVideo { onVideoMain { hasText(session, "Create viewing copy?") } })
            assertNoCopy(session)
            assertTrue(onVideoMain { click(session, "Cancel") })
            assertTrue(awaitVideo { onVideoMain { !hasText(session, "Create viewing copy?") } })
            assertNoCopy(session)

            assertTrue(onVideoMain { click(session, "Create viewing copy") })
            assertTrue(awaitVideo { onVideoMain { click(session, "Create copy") } })
            assertTrue(awaitVideo { onVideoMain { hasText(session, "Creating viewing copy") } })
            assertTrue(onVideoMain { click(session, "Cancel") })
            assertTrue(awaitVideo { onVideoMain { !hasText(session, "Creating viewing copy") && hasText(session, "Create viewing copy") } })
            assertNoCopy(session)

            assertTrue(onVideoMain { click(session, "Create viewing copy") })
            assertTrue(awaitVideo { onVideoMain { click(session, "Create copy") } })
            assertTrue("A real encoded copy must complete", awaitVideo(180_000) {
                onVideoMain { hasText(session, "Viewing copy") && !hasText(session, "Creating viewing copy") }
            })
            assertTrue("Encrypted viewing copy must be stored", readCompatibleVideoForTest(session.vault, session.imported.objectId).let { bytes -> try { bytes.isNotEmpty() } finally { bytes.fill(0) } })
            assertTrue("The actual app must decode and render AVC", awaitVideo {
                onVideoMain {
                    val player = player(session)
                    if (player == null) false else {
                        player.play()
                        player.videoFormat?.sampleMimeType == MimeTypes.VIDEO_H264 && player.videoDecoderCounters?.let {
                            it.ensureUpdated(); it.renderedOutputBufferCount > 0
                        } == true && player.playerError == null
                    }
                }
            })
            assertTrue(onVideoMain { click(session, "Original") })
            assertTrue("Original choice must reopen HEVC", awaitVideo {
                onVideoMain { player(session)?.videoFormat?.sampleMimeType == MimeTypes.VIDEO_H265 }
            })
            val originalPlayer = onVideoMain { requireNotNull(player(session)) }
            assertTrue(onVideoMain { click(session, "Viewing copy") })
            assertTrue("Reselected viewing copy must render a fresh AVC frame", awaitVideo {
                onVideoMain {
                    val current = player(session)
                    current != null && current !== originalPlayer && current.videoFormat?.sampleMimeType == MimeTypes.VIDEO_H264 &&
                        current.playerError == null && current.videoDecoderCounters?.let {
                            it.ensureUpdated(); it.renderedOutputBufferCount > 0
                        } == true
                }
            })
            awaitVideoWindowDraw(session.activity)
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                val directory = requireNotNull(session.context.getExternalFilesDir("video-acceptance"))
                File(directory, "viewing-copy-source-selection.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            val original = PlaybackSource(session.vault, session.imported.objectId, "video/mp4", session.originalSize,
                onExport = exportOriginal)
            val saved = compatibleVideoSource(original)
            assertSame("The compatible source must keep the host's original export action", exportOriginal, saved.onExport)
            requireNotNull(saved.onExport).invoke()
            assertEquals(1, exports.get())
            session.unmount()
            session.assertExport()
        } finally { session.close() }
    }

    private suspend fun assertNoCopy(session: VideoAcceptanceSession) {
        try { session.vault.readDerived(session.imported.objectId, StreamKind.COMPATIBLE_VIDEO); fail("An unconfirmed or cancelled copy was saved") }
        catch (failure: ChurFailure) { assertEquals(ChurStatus.NOT_FOUND, failure.status) }
    }
    private fun player(session: VideoAcceptanceSession): ExoPlayer? = findVideoPlayer(session.activity.window.decorView)
    // WindowInspector is limited to this instrumented process. This test
    // owns its only activity and the private dialogs that activity creates.
    private fun nodes(session: VideoAcceptanceSession) = (listOf(session.activity.window.decorView) +
        WindowInspector.getGlobalWindowViews()).distinct().flatMap(::views).distinct()
        .filterIsInstance<ViewRootForTest>()
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }.filter { !it.boundsInWindow.isEmpty }
    private fun hasText(session: VideoAcceptanceSession, text: String) = nodes(session).any {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { label -> label.text == text }
    }
    private fun click(session: VideoAcceptanceSession, text: String): Boolean = nodes(session).firstOrNull {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { label -> label.text == text } &&
            it.config.getOrNull(SemanticsProperties.Disabled) == null && it.config.getOrNull(SemanticsActions.OnClick) != null
    }?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
