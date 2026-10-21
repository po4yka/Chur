package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodecList
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.PlaybackProblem
import dev.po4yka.chur.app.vault.PlaybackSource
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Main10 MP4 bytes, native frame extraction, and the vault-backed player. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class HevcMain10AndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val names = listOf("sdr10-hvc1", "sdr10-hev1", "hdr10-hvc1", "hdr10-hev1", "hlg-hvc1", "hlg-hev1")

    @Test
    fun allSixProfilesProbeAndProduceSrgbPostersOrAnExplicitPreviewFailure() = runBlocking {
        withFiles { directory ->
            val codec = AndroidMediaCodec(context.contentResolver)
            for (name in names) {
                val source = fixture(directory, name)
                val picked = requireNotNull(codec.open(uri(source)))
                try {
                    val probe = requireNotNull(codec.probe(picked)) { name }
                    assertEquals(name, MediaBounds.CLASS_VIDEO, probe.mediaClass)
                    assertEquals(name, "video/mp4", probe.contentType)
                    assertEquals(name, 192, probe.width)
                    assertEquals(name, 96, probe.height)
                    assertTrue(name, probe.durationMs >= 1_000)
                    val decoder = decoderFor(source)
                    val previewSupported = decoder != null && (Build.VERSION.SDK_INT >= 30 || name.startsWith("sdr10"))
                    for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER)) {
                        assertNull("$name cancelled extraction must return no preview", codec.derive(picked, probe, kind) { true })
                        val derivative = codec.derive(picked, probe, kind)
                        if (!previewSupported) {
                            assertNull("$name: no native SDR preview path", derivative)
                        } else {
                            assertNotNull("$name: advertised Main10 decoder $decoder must extract $kind", derivative)
                            val bytes = requireNotNull(derivative).bytes
                            try {
                                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                                try {
                                    assertEquals(name, 192, bitmap.width)
                                    assertEquals(name, 96, bitmap.height)
                                    assertTrue("$name JPEG must decode as sRGB", bitmap.colorSpace?.isSrgb == true)
                                    val grays = (0..5).map { bitmap.getPixel(16 + 32 * it, 16) }
                                    grays.forEach { pixel ->
                                        assertTrue("$name gray bars must stay neutral",
                                            maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                                                minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 18)
                                    }
                                    val levels = grays.map(Color::red)
                                    assertTrue("$name must retain black/white separation: $levels", levels.last() - levels.first() > 100)
                                    assertTrue("$name gray ramp must keep its order: $levels",
                                        levels.zipWithNext().all { (a, b) -> b + 4 >= a })
                                } finally { bitmap.recycle() }
                            } finally { bytes.fill(0) }
                        }
                    }
                    report("Main10 poster $name nativeContainerDecoder=$decoder previewSupported=$previewSupported")
                } finally { picked.close() }
            }
        }
    }

    @Test
    fun allSixOriginalsSurviveEncryptedPlaybackSeekExportAndLock() = runBlocking {
        withFiles { directory ->
            val vault = VaultRepository(File(directory, "vault").path, System::currentTimeMillis)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var composition: ComposeView? = null
            var capturedUnsupported = false
            try {
                vault.start()
                vault.create("Main10 native acceptance test password".encodeToByteArray(), false)
                for (name in names) {
                    val source = fixture(directory, name)
                    val codec = AndroidMediaCodec(context)
                    val imported = MediaImporter(codec).import(vault, requireNotNull(codec.open(uri(source))))
                    assertTrue("$name original must remain storable: $imported", imported is MediaImporter.Outcome.Imported)
                    val result = imported as MediaImporter.Outcome.Imported
                    if (decoderFor(source) == null || (Build.VERSION.SDK_INT < 30 && !name.startsWith("sdr10"))) {
                        assertEquals(name, 0, result.derivatives)
                        assertNotNull("$name unavailable preview must be reported", result.previewWarning)
                    } else {
                        assertEquals(name, 2, result.derivatives)
                        assertNull(name, result.previewWarning)
                    }
                    val exported = File(directory, "$name-export.mp4")
                    ParcelFileDescriptor.open(exported, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                        val operation = vault.beginExport(result.objectId, output.fd)
                        try {
                            var progress = vault.poll(operation)
                            while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                            assertEquals(name, 0, progress.status)
                        } finally { vault.closeOperation(operation) }
                    }
                    assertArrayEquals(name, source.readBytes(), exported.readBytes())
                    onMain {
                        composition?.disposeComposition()
                        composition = ComposeView(activity).apply {
                            setContent { ChurTheme(dark = true) {
                                VaultPlayer(PlaybackSource(vault, result.objectId, "video/mp4", source.length()),
                                    Modifier.fillMaxSize(), poster = null, controlsVisible = true, controlsPinned = true,
                                    onControlsVisibleChange = {}, controlsPadding = PaddingValues(), touches = 0)
                            } }
                        }
                        activity.setContentView(FrameLayout(activity).apply { addView(composition) })
                    }
                    assertTrue("$name player must finish preparation", await {
                        onMain { player(requireNotNull(composition))?.let {
                            it.playbackState == Player.STATE_READY || it.playerError != null || unsupported(it.currentTracks)
                        } == true }
                    })
                    val native = onMain { requireNotNull(player(requireNotNull(composition))) }
                    val (strictVideoSupported, decoderDescription) = onMain {
                        val tracks = native.currentTracks
                        val formats = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
                            (0 until group.length).map(group::getTrackFormat)
                        }
                        assertTrue("$name must expose a real video format", formats.isNotEmpty())
                        formats.forEach { format ->
                            assertEquals(name, "video/hevc", format.sampleMimeType)
                            val profile = requireNotNull(CodecSpecificDataUtil.getMediaCodecProfileAndLevel(format))
                            assertTrue("$name must describe a MediaCodec HEVC profile", profile.isSupportableByMediaCodec)
                            assertTrue("$name must remain Main10, not 8-bit HEVC", profile.profile in setOf(
                                CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
                                CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                            ))
                            val color = requireNotNull(format.colorInfo)
                            assertEquals(name, 10, color.lumaBitdepth)
                            assertEquals(name, 10, color.chromaBitdepth)
                        }
                        val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos("video/hevc", false, false)
                            .joinToString { info -> "${info.name}[hardware=${info.hardwareAccelerated}," +
                                "strictFormat=${info.isFormatSupported(context, formats.first())}]" }
                        tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities = */ false) to decoders
                    }
                    report("Main10 capability $name strictVideoSupported=$strictVideoSupported decoders=$decoderDescription")
                    if (onMain { native.playerError == null && !unsupported(native.currentTracks) }) {
                        onMain { native.play() }
                        assertTrue("$name must render both tracks or report a specific decoder rejection", await {
                            onMain { native.playerError != null || unsupported(native.currentTracks) ||
                                ((native.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                                    (native.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0) }
                        })
                    }
                    val playbackRejected = onMain { native.playerError != null || unsupported(native.currentTracks) }
                    if (playbackRejected) {
                        val error = onMain { native.playerError }
                        if (error != null) {
                            // A MIME-only platform decoder query does not prove
                            // Main10 support. Media3 can try an exceeds-capability
                            // track, which must either really decode or fail here.
                            assertFalse("$name advertised Main10 support must not fail: $error", strictVideoSupported)
                            val releasedSurface = isReleasedCodecSurfaceFailure(error)
                            report("Main10 rejection $name errorCode=${error.errorCode} releasedVideoSurface=$releasedSurface")
                            assertTrue("$name failure must be specific to decoder initialization/decoding: $error",
                                releasedSurface || error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            if (!releasedSurface) {
                                val rendererError = error as? ExoPlaybackException
                                assertNotNull("$name must identify the rejected video renderer", rendererError)
                                assertEquals(name, "video/hevc", rendererError?.rendererFormat?.sampleMimeType)
                                assertTrue("$name renderer must report an unsupported or exceeds-capability format",
                                    rendererError?.rendererFormatSupport in setOf(C.FORMAT_UNSUPPORTED_TYPE,
                                        C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_EXCEEDS_CAPABILITIES))
                            }
                        }
                        assertEquals(name, PlaybackProblem.UNSUPPORTED, onMain { mappedProblem(native) })
                        assertTrue("$name unsupported warning must be visible", await {
                            onMain { hasText(requireNotNull(composition), "This device cannot decode the HEVC") }
                        })
                        assertFalse(name, onMain { native.playWhenReady })
                        if (!capturedUnsupported) {
                            captureUnsupportedWarning()
                            capturedUnsupported = true
                        }
                    } else {
                        assertNull("$name: ${onMain { native.playerError }}", onMain { native.playerError })
                        val beforeSeek = onMain { native.videoDecoderCounters!!.renderedOutputBufferCount }
                        onMain { native.pause(); native.seekTo(500); native.play() }
                        assertTrue("$name seek must render a new video frame", await {
                            onMain { native.currentPosition >= 450 &&
                                (native.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > beforeSeek }
                        })
                        assertNull("$name seek must not leave a decoder or reader failure", onMain { native.playerError })
                    }
                    val lease = vault.leaseReader(result.objectId)
                    try {
                        vault.lock(LockReason.USER)
                        val locked = vault.state.value
                        assertTrue("$name lock must complete", locked is VaultState.Locked)
                        assertNull("$name lock must not retain a failure", (locked as VaultState.Locked).lastFailure)
                        try { vault.readLeased(lease, 0, 32); fail("$name locked reader returned plaintext") }
                        catch (failure: ChurFailure) { assertEquals(name, ChurStatus.SESSION_EXPIRED, failure.status) }
                    } finally { vault.releaseReader(lease) }
                    onMain { composition?.disposeComposition(); composition = null }
                    vault.unlock("Main10 native acceptance test password".encodeToByteArray())
                    report("Main10 encrypted import/export/lock $name strictVideoSupported=$strictVideoSupported " +
                        "decoders=$decoderDescription " +
                        "playback=${if (playbackRejected) "explicit unsupported" else "rendered and sought"}")
                }
            } finally {
                onMain { composition?.disposeComposition(); activity.finish() }
                vault.shutdown()
            }
        }
    }

    @Test
    fun realUnsupportedVideoWithPlayableAudioMapsToTheProductionWarning() = runBlocking {
        withFiles { directory ->
            val source = fixture(directory, "sdr10-hvc1")
            // Fault injection only in this player: the real MP4 extractor and
            // AAC decoder run, while video reports an unavailable decoder.
            val player = onMain {
                val renderers = DefaultRenderersFactory(context).setMediaCodecSelector { mime, secure, tunneling ->
                    if (mime.startsWith("video/")) emptyList()
                    else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                }
                ExoPlayer.Builder(context, renderers).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(source)))
                    prepare()
                }
            }
            try {
                assertTrue("real extractor must expose rejected Main10 and selected AAC tracks", await {
                    onMain { unsupported(player.currentTracks) && player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) }
                })
                assertNull("partial track rejection must exercise the path without playerError", onMain { player.playerError })
                assertEquals(PlaybackProblem.UNSUPPORTED, onMain { mappedProblem(player) })
            } finally { onMain { player.release() } }
        }
    }

    private fun decoderFor(source: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME) == "video/hevc" }
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        } finally { extractor.release() }
    }

    private fun unsupported(tracks: Tracks) = listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO).any {
        tracks.containsType(it) && !tracks.isTypeSupported(it, true)
    }

    /** API 35 can race a failed unsupported decoder with video surface setup. */
    private fun isReleasedCodecSurfaceFailure(error: PlaybackException): Boolean {
        if (Build.VERSION.SDK_INT != 35 || error.errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK ||
            error !is ExoPlaybackException || error.type != ExoPlaybackException.TYPE_UNEXPECTED) return false
        val cause = error.cause as? IllegalStateException ?: return false
        if (cause.message?.trim() != "setSurface() is valid only at Executing states; currently at Released state") return false
        val expected = listOf(
            "android.media.MediaCodec" to "native_setSurface",
            "android.media.MediaCodec" to "setOutputSurface",
            "androidx.media3.exoplayer.mediacodec.AsynchronousMediaCodecAdapter" to "setOutputSurface",
            "androidx.media3.exoplayer.video.MediaCodecVideoRenderer" to "setOutputSurfaceV23",
        )
        return cause.stackTrace.take(expected.size).map { it.className to it.methodName } == expected
    }

    // The host module does not expose shared module internals as public API.
    private fun mappedProblem(player: Player): PlaybackProblem? =
        Class.forName("dev.po4yka.chur.app.vault.Playback_androidKt")
            .getDeclaredMethod("playbackProblem", PlaybackException::class.java, Tracks::class.java)
            .invoke(null, player.playerError, player.currentTracks) as PlaybackProblem?

    private fun player(view: View): ExoPlayer? = view.views().filterIsInstance<PlayerView>().firstOrNull()?.player as? ExoPlayer
    private fun View.views(): List<View> = listOf(this) + if (this is ViewGroup)
        (0 until childCount).flatMap { getChildAt(it).views() } else emptyList()
    private fun hasText(view: View, expected: String): Boolean = view.views().filter { it.isShown }
        .filterIsInstance<ViewRootForTest>()
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .any { node -> !node.boundsInWindow.isEmpty &&
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(expected) } }

    private fun captureUnsupportedWarning() {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(requireNotNull(context.getExternalFilesDir("hevc-main10")), "unsupported-main10-warning.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun report(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
    private fun uri(file: File) = leasedExportUri(context, file)
    private fun fixture(directory: File, name: String) = File(directory, "$name.mp4").also { file ->
        instrumentation.context.assets.open("hevc-main10/$name.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    private suspend fun withFiles(block: suspend (File) -> Unit) {
        val directory = File(context.cacheDir, "export-scratch/hevc-main10-${System.nanoTime()}").apply { mkdirs() }
        try { block(directory) } finally { directory.deleteRecursively() }
    }
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
            SystemClock.sleep(50)
        }
        return condition()
    }
}
