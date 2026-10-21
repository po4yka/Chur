package dev.po4yka.chur.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.vault.AndroidVideoTimelineGenerator
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.createVideoTimeline
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native TimeBar touch events, private preview and encrypted resume checkpoints. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoPlaybackTimelineAndroidTest {
    @Test
    fun resumeStaysPausedAndHeldScrubShowsRealAtlasUntilReleaseOrLock() = runBlocking {
        val session = VideoAcceptanceSession.create()
        try {
            session.importFixture("main10-long-gop-vfr.mp4")
            val atlas = createVideoTimeline(PlaybackSource(session.vault, session.imported.objectId,
                "video/mp4", session.originalSize), AndroidVideoTimelineGenerator(session.context))
            atlas.fill(0)
            val generation = (session.vault.state.value as VaultState.Unlocked).generation
            val first = session.mount()
            assertTrue(awaitVideo { onVideoMain { first.playbackState == Player.STATE_READY } })
            onVideoMain { first.play() }
            assertTrue("Actual original frames must render before checkpointing", awaitVideo {
                onVideoMain { first.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount > 0 } == true }
            })
            onVideoMain { first.seekTo(10_000) }
            assertTrue(awaitVideo { onVideoMain { first.currentPosition in 10_000L..10_800L } })
            onVideoMain { first.pause() }
            assertTrue("Pause must persist a real position", awaitVideo {
                runBlocking { session.vault.playbackPosition(session.imported.objectId, generation) in 9_500L..11_000L }
            })
            val saved = session.vault.playbackPosition(session.imported.objectId, generation)
            session.unmount()
            val resumed = session.mount()
            assertTrue("Reopening must restore the native position", awaitVideo {
                onVideoMain { resumed.playbackState == Player.STATE_READY && abs(resumed.currentPosition - saved) <= 500 }
            })
            assertFalse("A resume checkpoint must not start playback", onVideoMain { resumed.playWhenReady })
            val timeBar = onVideoMain {
                val native = requireNotNull(views(session.activity.window.decorView).filterIsInstance<PlayerView>().firstOrNull())
                native.showController()
                requireNotNull(native.findViewById<View>(androidx.media3.ui.R.id.exo_progress))
            }
            val downTime = SystemClock.uptimeMillis()
            onVideoMain {
                touch(timeBar, downTime, MotionEvent.ACTION_DOWN, 0.45f)
                touch(timeBar, downTime, MotionEvent.ACTION_MOVE, 0.60f)
            }
            assertTrue("A held real TimeBar scrub must show the Compose preview", awaitVideo {
                onVideoMain { previewNodes(session).isNotEmpty() && taggedNodes(session, "video-seek-preview-image").isNotEmpty() }
            })
            assertTrue("Preview must show a scrub timestamp", onVideoMain { previewText(session).any { it.matches(Regex("[0-9]+:[0-9]{2}")) } })
            awaitVideoWindowDraw(session.activity)
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                val directory = requireNotNull(session.context.getExternalFilesDir("video-acceptance"))
                File(directory, "timeline-held-preview.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { screenshot.recycle() }
            onVideoMain { touch(timeBar, downTime, MotionEvent.ACTION_UP, 0.60f) }
            assertTrue("Release must hide the preview", awaitVideo { onVideoMain { previewNodes(session).isEmpty() } })
            val secondDown = SystemClock.uptimeMillis()
            onVideoMain {
                touch(timeBar, secondDown, MotionEvent.ACTION_DOWN, 0.35f)
                touch(timeBar, secondDown, MotionEvent.ACTION_MOVE, 0.40f)
            }
            assertTrue(awaitVideo { onVideoMain { previewNodes(session).isNotEmpty() } })
            session.vault.lock(LockReason.USER)
            assertNull((session.vault.state.value as VaultState.Locked).lastFailure)
            assertTrue("Lock must remove the private preview and native video surface", awaitVideo {
                onVideoMain { previewNodes(session).isEmpty() && findVideoPlayer(session.activity.window.decorView) == null }
            })
        } finally { session.close() }
    }

    private fun touch(view: View, downTime: Long, action: Int, fraction: Float) {
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            view.width * fraction, view.height / 2f, 0).let { event ->
            try { assertTrue("Native TimeBar must handle the actual touch", view.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
    }
    private fun previewNodes(session: VideoAcceptanceSession) = taggedNodes(session, "video-seek-preview")
    private fun taggedNodes(session: VideoAcceptanceSession, tag: String) = views(session.activity.window.decorView)
        .filterIsInstance<ViewRootForTest>().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
        .filter { !it.boundsInWindow.isEmpty && it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun previewText(session: VideoAcceptanceSession) = views(session.activity.window.decorView)
        .filterIsInstance<ViewRootForTest>().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
        .filter { !it.boundsInWindow.isEmpty }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
