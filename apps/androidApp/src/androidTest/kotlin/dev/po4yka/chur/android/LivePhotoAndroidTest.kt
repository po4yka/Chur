package dev.po4yka.chur.android

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.app.theme.ChurTheme
import dev.po4yka.chur.app.vault.ViewerScreen
import dev.po4yka.chur.app.vault.VaultPlayer
import dev.po4yka.chur.app.vault.livePhotoPlaybackFor
import dev.po4yka.chur.app.vault.readVaultLivePhoto
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.imports.LivePhotoBundle
import dev.po4yka.chur.imports.LivePhotoBundleException
import dev.po4yka.chur.imports.MediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.ProbedMedia
import dev.po4yka.chur.imports.Derivative
import dev.po4yka.chur.imports.readLivePhotoBundle
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI, framework image decoding, and Media3 decoding of a range in one encrypted original. */
@RunWith(AndroidJUnit4::class)
class LivePhotoAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun completeBundleRoundTripsAndPlaysSelectedMovieWithAudio() = runBlocking {
        withVault { vault, source, root ->
            val codec = AndroidMediaCodec(context)
            val picked = requireNotNull(codec.open(uri(source), asFile = true))
            val originalTable = requireNotNull(picked.livePhoto)
            assertFalse("a recognized package is media even from Files", picked.importAsFile)
            val result = MediaImporter(codec).import(vault, picked)
            assertTrue(result.toString(), result is MediaImporter.Outcome.Imported)
            val objectId = (result as MediaImporter.Outcome.Imported).objectId
            val detail = vault.detail(objectId)
            assertEquals(LivePhotoBundle.CONTENT_TYPE, detail.contentType)
            assertEquals(1, vault.page(ObjectQuery()).objects.single().mediaKind)
            val preview = vault.readDerived(objectId, StreamKind.SCREEN_PREVIEW)
            try {
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(preview, 0, preview.size))
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle()
            } finally { preview.fill(0) }
            val table = requireNotNull(readVaultLivePhoto(vault, objectId))
            assertEquals(originalTable.resources.map { it.originalFilename }, table.resources.map { it.originalFilename })
            val reader = vault.leaseReader(objectId)
            try {
                val sourceBytes = source.readBytes()
                for (resource in table.resources) {
                    val actual = vault.readLeased(reader, resource.offset, resource.byteLength.toInt())
                    try { assertArrayEquals(sourceBytes.copyOfRange(resource.offset.toInt(),
                        (resource.offset + resource.byteLength).toInt()), actual) } finally { actual.fill(0) }
                }
            } finally { vault.releaseReader(reader) }
            val exported = File(root, "exported.churlive")
            ParcelFileDescriptor.open(exported, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                val operation = vault.beginExport(objectId, output.fd)
                try {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                    assertEquals(0, progress.status)
                } finally { vault.closeOperation(operation) }
            }
            assertArrayEquals(source.readBytes(), exported.readBytes())
            ParcelFileDescriptor.open(exported, ParcelFileDescriptor.MODE_READ_ONLY).use {
                assertEquals(table.totalLength, requireNotNull(readLivePhotoBundle(it.fd, null)).totalLength)
            }
            assertPairedVideoAndAudioDecode(vault, objectId)
        }
    }

    @Test
    fun unavailableStillDecoderKeepsEveryResourceAndPlaysMovieInFileViewer() = runBlocking {
        withVault { vault, source, _ ->
            val platform = AndroidMediaCodec(context)
            val noStillDecoder = object : MediaCodec {
                override fun probe(media: PickedMedia): ProbedMedia? = null
                override fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind, cancelRequested: () -> Boolean): Derivative? =
                    error("opaque preservation must not decode a still")
            }
            val result = MediaImporter(noStillDecoder).import(vault, requireNotNull(platform.open(uri(source))))
            assertTrue(result is MediaImporter.Outcome.Imported)
            val imported = result as MediaImporter.Outcome.Imported
            assertTrue(imported.storedAsFile)
            assertEquals(0, imported.derivatives)
            assertEquals(4, vault.page(ObjectQuery()).objects.single().mediaKind)
            assertEquals(LivePhotoBundle.CONTENT_TYPE, vault.detail(imported.objectId).contentType)
            assertArrayEquals(source.readBytes(), vault.readRange(imported.objectId, 0, source.length().toInt()))
            assertPairedVideoAndAudioDecode(vault, imported.objectId)
        }
    }

    @Test
    fun malformedMissingAndCancelledResourcesNeverActivatePartialObjects() = runBlocking {
        withVault { vault, source, _ ->
            val codec = AndroidMediaCodec(context)
            val original = source.readBytes()
            val headerLength = LivePhotoBundle.headerLength(original.copyOf(24))
            val corrupt = File(source.parentFile, "corrupt.churlive").apply {
                writeBytes(original.copyOf().also { it[headerLength] = (it[headerLength].toInt() xor 1).toByte() })
            }
            val picked = requireNotNull(codec.open(uri(corrupt)))
            assertEquals(MediaImporter.Outcome.Refused(ChurStatus.OBJECT_CORRUPT), MediaImporter(codec).import(vault, picked))
            assertTrue(vault.page(ObjectQuery()).objects.isEmpty())
            val short = File(source.parentFile, "short.churlive").apply { writeBytes(original.copyOf(original.size - 1)) }
            ParcelFileDescriptor.open(short, ParcelFileDescriptor.MODE_READ_ONLY).use {
                try { readLivePhotoBundle(it.fd, original.size.toLong()); fail("truncated resource accepted") }
                catch (_: LivePhotoBundleException) { }
            }
            var checks = 0
            val cancelled = MediaImporter(codec).import(vault, requireNotNull(codec.open(uri(source))),
                cancelRequested = { ++checks >= 5 })
            assertEquals(MediaImporter.Outcome.Refused(ChurStatus.CANCELLED), cancelled)
            assertTrue(vault.page(ObjectQuery()).objects.isEmpty())
            checks = 0
            val locked = MediaImporter(codec).import(vault, requireNotNull(codec.open(uri(source))), cancelRequested = {
                if (++checks == 5) runBlocking { vault.lock(LockReason.USER) }
                false
            })
            assertEquals(MediaImporter.Outcome.Refused(ChurStatus.VAULT_LOCKED), locked)
        }
    }

    private suspend fun assertPairedVideoAndAudioDecode(vault: VaultRepository, objectId: ByteArray) {
        val source = requireNotNull(livePhotoPlaybackFor(vault, objectId))
        val projection = vault.page(ObjectQuery()).objects.single { it.objectId.contentEquals(objectId) }
        val detail = vault.detail(objectId)
        assertTrue(source.offset > 0)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var view: ComposeView? = null
        try {
            instrumentation.runOnMainSync {
                view = ComposeView(activity).apply {
                    setContent { ChurTheme(dark = true) {
                        ViewerScreen(projection = projection, pages = listOf(projection), thumbnails = emptyMap(),
                            canLoadMore = false, onLoadMore = {}, onSettled = {}, detail = detail, preview = null,
                            showDetail = false, chromeVisible = true, onToggleChrome = {}, onBack = {},
                            onLock = {}, onPanic = {}, onToggleFavorite = {}, onEditTags = {}, onExport = {},
                            onDelete = {}, onToggleDetail = {}, player = { modifier, padding ->
                                VaultPlayer(source, modifier, null, controlsVisible = true, controlsPinned = true,
                                    onControlsVisibleChange = {}, controlsPadding = padding, touches = 0)
                            })
                    } }
                }
                activity.setContentView(FrameLayout(activity).apply { addView(view) })
            }
            val ready = await {
                onMain { player(requireNotNull(view))?.let { invokePlayer(it, "getPlaybackState") == 3 } == true }
            }
            assertTrue("native player reaches READY: " + onMain {
                player(requireNotNull(view))?.let { native ->
                    "state=${invokePlayer(native, "getPlaybackState")}, error=" +
                        (invokePlayer(native, "getPlayerError") as? Throwable)?.stackTraceToString()
                } ?: "no PlayerView"
            }, ready)
            onMain { invokePlayer(requireNotNull(player(requireNotNull(view))), "play") }
            assertTrue("paired movie renders video and AAC audio through the bounded vault range", await {
                onMain {
                    val player = player(requireNotNull(view)) ?: return@onMain false
                    val video = invokePlayer(player, "getVideoDecoderCounters")
                    val audio = invokePlayer(player, "getAudioDecoderCounters")
                    video != null && audio != null &&
                        video.javaClass.getField("renderedOutputBufferCount").getInt(video) > 0 &&
                        audio.javaClass.getField("renderedOutputBufferCount").getInt(audio) > 0
                }
            })
            vault.lock(LockReason.USER)
            // Generation invalidation must refuse further reads from an already prepared source.
            try { vault.leaseReader(objectId); fail("locked vault granted a resource reader") } catch (_: dev.po4yka.chur.ffi.ChurFailure) { }
        } finally { instrumentation.runOnMainSync { view?.disposeComposition(); activity.finish() } }
    }

    private fun invokePlayer(player: Any, name: String): Any? =
        Class.forName("androidx.media3.exoplayer.ExoPlayer").getMethod(name).invoke(player)
    private fun player(root: View): Any? = root.views().firstOrNull { it.javaClass.name == "androidx.media3.ui.PlayerView" }
        ?.let { it.javaClass.getMethod("getPlayer").invoke(it) }
    private fun View.views(): List<View> = listOf(this) + if (this is ViewGroup)
        (0 until childCount).flatMap { getChildAt(it).views() } else emptyList()
    private fun uri(file: File) = leasedExportUri(context, file)
    private suspend fun withVault(block: suspend (VaultRepository, File, File) -> Unit) {
        val root = File(context.cacheDir, "live-photo-native-${System.nanoTime()}").apply { mkdirs() }
        val source = File(context.cacheDir, "export-scratch/live-photo-${System.nanoTime()}/native.churlive")
        source.parentFile!!.mkdirs()
        instrumentation.context.assets.open("live-photo-native/native.churlive").use { input -> source.outputStream().use { input.copyTo(it) } }
        val vault = VaultRepository(File(root, "vault").path, System::currentTimeMillis)
        try { vault.start(); vault.create("live photo native test password".encodeToByteArray(), false); block(vault, source, root) }
        finally { vault.shutdown(); root.deleteRecursively(); source.parentFile?.deleteRecursively() }
    }
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { instrumentation.waitForIdleSync(); if (condition()) return true; SystemClock.sleep(50) }
        return condition()
    }
}
