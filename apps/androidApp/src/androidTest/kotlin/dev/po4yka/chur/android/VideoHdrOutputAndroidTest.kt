package dev.po4yka.chur.android

import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Decoder output color metadata and the actual app surface, separate from display luminance. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoHdrOutputAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun sdrPqAndHlgReachTheNativeSurfaceWithTheirDecoderOutputTransfer() = runBlocking {
        val hold = InstrumentationRegistry.getArguments().getString("chur.video.hdrHoldMs")
        val holdMs = if (hold == null) 0L else requireNotNull(hold.toLongOrNull()) { "HDR hold must be an integer" }
        require(holdMs in 0L..10_000L) { "HDR hold must be between 0 and 10000 ms" }
        val directory = File(context.cacheDir, "export-scratch/hdr-output-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(directory, "vault").path, System::currentTimeMillis)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val displays = requireNotNull(context.getSystemService(DisplayManager::class.java))
        val activeDisplay = AtomicInteger(-1)
        val displayChanges = AtomicInteger(0)
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == activeDisplay.get()) displayChanges.incrementAndGet()
            }
        }
        var composition: ComposeView? = null
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        try {
            vault.start()
            vault.create("HDR native surface acceptance test password".encodeToByteArray(), false)
            val cases = listOf(
                OutputCase("sdr10", C.COLOR_TRANSFER_SDR, MediaFormat.COLOR_STANDARD_BT709, null),
                OutputCase("hdr10", C.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_STANDARD_BT2020, Display.HdrCapabilities.HDR_TYPE_HDR10),
                OutputCase("hlg", C.COLOR_TRANSFER_HLG, MediaFormat.COLOR_STANDARD_BT2020, Display.HdrCapabilities.HDR_TYPE_HLG),
            )
            // Start each MP4 representation with an SDR control. Each case
            // re-reads the display attached to its actual SurfaceView.
            for (sampleEntry in listOf("hvc1", "hev1")) for (case in cases) {
                val name = "${case.prefix}-$sampleEntry"
                val source = File(directory, "$name.mp4")
                instrumentation.context.assets.open("hevc-main10/$name.mp4").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                val codec = AndroidMediaCodec(context)
                val imported = MediaImporter(codec).import(vault, requireNotNull(codec.open(
                    leasedExportUri(context, source),
                )))
                assertTrue("$name must import through the real media pipeline: $imported", imported is MediaImporter.Outcome.Imported)
                val result = imported as MediaImporter.Outcome.Imported
                val contentType = vault.detail(result.objectId).contentType
                onMain {
                    composition?.disposeComposition()
                    composition = ComposeView(activity).apply {
                        setContent { ChurTheme(dark = true) {
                            VaultPlayer(PlaybackSource(vault, result.objectId, contentType, source.length()),
                                Modifier.fillMaxSize(), poster = null, controlsVisible = true, controlsPinned = true,
                                onControlsVisibleChange = {}, controlsPadding = PaddingValues(), touches = 0)
                        } }
                    }
                    activity.setContentView(FrameLayout(activity).apply { addView(composition) })
                }
                assertTrue("$name must attach the actual app PlayerView", await {
                    onMain { playerView(requireNotNull(composition))?.player != null }
                })
                val view = onMain { requireNotNull(playerView(requireNotNull(composition))) }
                val player = onMain { view.player as ExoPlayer }
                val surface = onMain {
                    assertTrue("$name must use SurfaceView for native HDR output", view.videoSurfaceView is SurfaceView)
                    (view.videoSurfaceView as SurfaceView).also {
                        activeDisplay.set(requireNotNull(it.display).displayId)
                    }
                }
                val output = AtomicReference<FrameOutput?>(null)
                val frames = VideoFrameMetadataListener { _, _, format, nativeFormat ->
                    if (nativeFormat != null) output.set(FrameOutput(
                        format, nativeFormat.integerOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                        nativeFormat.integerOrNull(MediaFormat.KEY_COLOR_STANDARD),
                        nativeFormat.integerOrNull(MediaFormat.KEY_COLOR_RANGE),
                        nativeFormat.integerOrNull(MediaFormat.KEY_COLOR_FORMAT),
                    ))
                }
                onMain {
                    player.setVideoFrameMetadataListener(frames)
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.play()
                }
                assertTrue("$name must render a native decoder output frame", await {
                    onMain { player.playerError != null || (output.get() != null &&
                        (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0) }
                })
                assertNull("$name must decode on the test device: ${onMain { player.playerError }}", onMain { player.playerError })
                onMain { player.pause() }
                val frame = requireNotNull(output.get())
                assertEquals("$name coded Main10 luma", 10, frame.coded.colorInfo?.lumaBitdepth)
                assertEquals("$name coded Main10 chroma", 10, frame.coded.colorInfo?.chromaBitdepth)
                assertEquals("$name coded transfer", case.transfer, frame.coded.colorInfo?.colorTransfer)
                // Media3 passes MediaCodecVideoRenderer.getCodecOutputMediaFormat()
                // to this callback. These assertions are output metadata, not
                // just the input Format.colorInfo or a thumbnail's color space.
                assertEquals("$name native decoder output transfer", case.transfer, frame.transfer)
                assertEquals("$name native decoder output color standard", case.standard, frame.standard)
                assertEquals("$name native decoder output range", MediaFormat.COLOR_RANGE_LIMITED, frame.range)
                val before = onMain {
                    assertTrue("$name must own a valid displayed surface", surface.isShown && surface.holder.surface.isValid)
                    displaySnapshot(requireNotNull(surface.display))
                }
                activeDisplay.set(before.id)
                case.displayHdrType?.let { type ->
                    assertTrue("$name active display must support HDR type $type: ${before.types}", type in before.types)
                }
                report("HDR_OUTPUT_READY name=$name codedTransfer=${frame.coded.colorInfo?.colorTransfer} " +
                    "decoderOutputTransfer=${frame.transfer} decoderOutputStandard=${frame.standard} " +
                    "decoderOutputRange=${frame.range} decoderOutputColorFormat=${frame.colorFormat} " +
                    "surface=SurfaceView display=${before.id} displayHdrTypes=${before.types} " +
                    "displayAvailableHdrSdrRatio=${before.availableRatio} measuredLuminance=unverified")
                if (case.displayHdrType != null && holdMs > 0) SystemClock.sleep(holdMs)
                val after = onMain { displaySnapshot(requireNotNull(surface.display)) }
                case.displayHdrType?.let { type ->
                    assertTrue("$name current display must still support the decoded HDR type: ${after.types}", type in after.types)
                }
                report("HDR_OUTPUT_DISPLAY name=$name currentDisplay=${after.id} currentHdrTypes=${after.types} " +
                    "displayChangeEvents=${displayChanges.get()} displayAvailableHdrSdrRatio=${after.availableRatio} " +
                    "surfaceBufferDataspace=external-observation-required measuredLuminance=unverified")
                onMain {
                    player.clearVideoFrameMetadataListener(frames)
                    composition?.disposeComposition()
                    composition = null
                }
            }
        } finally {
            displays.unregisterDisplayListener(displayListener)
            onMain { composition?.disposeComposition(); activity.finish() }
            vault.shutdown()
            directory.deleteRecursively()
        }
    }

    private data class OutputCase(val prefix: String, val transfer: Int, val standard: Int, val displayHdrType: Int?)
    private data class FrameOutput(val coded: Format, val transfer: Int?, val standard: Int?, val range: Int?, val colorFormat: Int?)
    private data class DisplaySnapshot(val id: Int, val types: List<Int>, val availableRatio: Float?)
    private fun displaySnapshot(display: Display) = DisplaySnapshot(display.displayId,
        display.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty(),
        if (Build.VERSION.SDK_INT >= 34 && display.isHdrSdrRatioAvailable) display.hdrSdrRatio else null)
    private fun MediaFormat.integerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun playerView(root: View) = root.views().filterIsInstance<PlayerView>().firstOrNull()
    private fun View.views(): List<View> = listOf(this) + if (this is ViewGroup)
        (0 until childCount).flatMap { getChildAt(it).views() } else emptyList()
    private fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(40)
        }
        return condition()
    }
}
