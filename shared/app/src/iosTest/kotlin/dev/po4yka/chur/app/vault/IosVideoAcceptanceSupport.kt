@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.*
import dev.po4yka.chur.imports.IosMediaCodec
import dev.po4yka.chur.imports.MediaBounds
import dev.po4yka.chur.imports.livePhotoDigest
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.CoreVideo.*
import platform.Foundation.*
import platform.UIKit.UIDevice
import platform.posix.*
import kotlin.test.*

internal data class IosVideoCase(
    val name: String,
    val codedWidth: Int,
    val codedHeight: Int,
    val displayWidth: Int = codedWidth,
    val displayHeight: Int = codedHeight,
    val durationMs: LongRange,
    val seekSeconds: List<Double>,
    val fixtureEnvironment: String = "CHUR_VIDEO_ACCEPTANCE_FIXTURES",
)

internal val ios4k60Video = IosVideoCase("main10-4k60.mp4", 3840, 2160, durationMs = 2_450L..2_600L, seekSeconds = listOf(0.5, 1.5))
internal val iosPortraitVideo = IosVideoCase("main10-portrait-rotation.mp4", 1920, 1080, 1080, 1920, 2_450L..2_600L, listOf(0.5, 1.5))
internal val iosLongGopVfrVideo = IosVideoCase("main10-long-gop-vfr.mp4", 320, 180, durationMs = 63_000L..65_000L, seekSeconds = listOf(0.5, 31.0, 62.0))
internal val iosCameraDolbyVision = IosVideoCase("camera-dolby-vision.mov", 1280, 720, 720, 1280, 50L..150L, emptyList())

internal class IosVideoSession(val case: IosVideoCase, val root: String, val vault: VaultRepository, val objectId: ByteArray) {
    suspend fun player(attributes: Map<Any?, *>? = null): IosTestVideoPlayer {
        val lease = vault.leaseReader(objectId)
        val info = try { vault.readerContentInfo(lease) } finally { vault.releaseReader(lease) }
        assertTrue(info.complete && info.byteRangeSupported)
        val loader = ChurResourceLoader(PlaybackSource(vault, objectId, info.contentType, info.plaintextSize))
        return IosTestVideoPlayer(loader, privatePlayerItem(loader), attributes)
    }

    suspend fun verifyExportAndLock(originalSha256: ByteArray) {
        val outputPath = "$root/export.bin"
        val output = open(outputPath, O_WRONLY or O_CREAT or O_EXCL, 384)
        assertTrue(output >= 0)
        try {
            val operation = vault.beginExport(objectId, output)
            try {
                val result = withTimeout(60_000) {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
                    progress
                }
                assertEquals(0, result.status, "${case.name} native export")
            } finally { vault.closeOperation(operation) }
        } finally { close(output) }
        assertContentEquals(originalSha256, iosVideoSha256(outputPath), "${case.name} exported original SHA-256")
        val reader = vault.leaseReader(objectId)
        vault.lock(LockReason.USER)
        assertNull(assertIs<VaultState.Locked>(vault.state.value).lastFailure)
        assertEquals(ChurStatus.SESSION_EXPIRED, assertFailsWith<ChurFailure> { vault.readLeased(reader, 0, 16) }.status)
        vault.releaseReader(reader)
    }
}

internal suspend fun withIosImportedVideo(case: IosVideoCase, action: suspend (IosVideoSession, ByteArray) -> Unit) {
    val fixtures = assertNotNull(getenv(case.fixtureEnvironment)?.toKString(), "${case.fixtureEnvironment} is required")
    val originalPath = "$fixtures/${case.name}"
    val sha256 = iosVideoSha256(originalPath)
    val root = NSTemporaryDirectory() + "chur-video-acceptance-" + NSUUID().UUIDString
    assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
    val path = "$root/${case.name}"
    assertTrue(NSFileManager.defaultManager.copyItemAtPath(originalPath, path, null))
    val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
    try {
        vault.start(); vault.create("Video-acceptance-native-test".encodeToByteArray(), false)
        val codec = IosMediaCodec()
        val source = assertNotNull(codec.open(NSURL.fileURLWithPath(path), case.name))
        val probe = assertNotNull(codec.probe(source))
        assertEquals(MediaBounds.CLASS_VIDEO, probe.mediaClass)
        assertEquals(case.displayWidth to case.displayHeight, probe.width to probe.height, case.name)
        assertTrue(probe.durationMs in case.durationMs, "${case.name}: ${probe.durationMs} ms")
        val imported = assertIs<MediaImporter.Outcome.Imported>(MediaImporter(codec).import(vault, source))
        assertFalse(imported.storedAsFile); assertFalse(imported.previewsSkipped); assertNull(imported.previewWarning)
        assertTrue(imported.derivatives >= 2)
        for (kind in listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER)) assertTrue(vault.readDerived(imported.objectId, kind).isNotEmpty())
        assertEquals(0, unlink(path), "Playback must read the encrypted object after source removal")
        action(IosVideoSession(case, root, vault, imported.objectId), sha256)
    } finally { vault.shutdown(); NSFileManager.defaultManager.removeItemAtPath(root, null) }
}

internal class IosTestVideoPlayer(val loader: ChurResourceLoader, val item: AVPlayerItem, attributes: Map<Any?, *>?) {
    val output = AVPlayerItemVideoOutput(pixelBufferAttributes = attributes)
    val player = privatePlayer(item)
    init { item.addOutput(output); player.muted = true }

    fun ready() {
        iosVideoWait { item.status == AVPlayerItemStatusReadyToPlay || item.error != null }
        assertNull(item.error); assertEquals(AVPlayerItemStatusReadyToPlay, item.status)
        assertNull(iosVideoPlaybackSupportProblem(item))
    }

    fun frame(case: IosVideoCase, at: Double? = null): CVPixelBufferRef {
        var frame: CVPixelBufferRef? = null
        var frameSeconds = Double.NaN
        iosVideoWait {
            memScoped {
                val actualTime = alloc<CMTime>()
                frame = output.copyPixelBufferForItemTime(at?.let { CMTimeMakeWithSeconds(it, 600) } ?: player.currentTime(), actualTime.ptr)
                if (frame != null) frameSeconds = CMTimeGetSeconds(actualTime.readValue())
                // Seek completion can precede the output queue's flush. Dispose
                // of an old frame and wait for a frame at the requested time.
                if (frame != null && at != null && (!frameSeconds.isFinite() || kotlin.math.abs(frameSeconds - at) > 0.15)) {
                    frame?.let { CVPixelBufferRelease(it) }
                    frame = null
                }
            }
            frame != null || item.error != null
        }
        assertNull(item.error)
        return assertNotNull(frame, "${case.name} must decode a video frame").also {
            assertEquals(case.codedWidth, CVPixelBufferGetWidth(it).toInt())
            assertEquals(case.codedHeight, CVPixelBufferGetHeight(it).toInt())
            if (at != null) assertTrue(kotlin.math.abs(frameSeconds - at) <= 0.15, "${case.name} decoded frame $frameSeconds must match seek $at")
        }
    }

    fun seek(case: IosVideoCase, seconds: Double) {
        player.pause()
        val completed = MutableStateFlow<Boolean?>(null)
        player.seekToTime(CMTimeMakeWithSeconds(seconds, 600), toleranceBefore = kCMTimeZero.readValue(), toleranceAfter = kCMTimeZero.readValue()) { finished -> completed.value = finished }
        iosVideoWait { completed.value != null || item.error != null }
        val diagnostic = "${case.name} target=$seconds callback=${completed.value} current=${CMTimeGetSeconds(player.currentTime())} " +
            "itemStatus=${item.status} errorCode=${item.error?.code} readerProblem=${loader.problem.value}"
        assertEquals(true, completed.value, "Native seek completion: $diagnostic")
        assertNull(item.error)
        assertTrue(kotlin.math.abs(CMTimeGetSeconds(player.currentTime()) - seconds) <= 0.15, "${case.name} seek to $seconds")
        player.play()
        CVPixelBufferRelease(frame(case, seconds))
    }

    fun close() {
        player.pause()
        item.cancelPendingSeeks()
        item.removeOutput(output)
        player.replaceCurrentItemWithPlayerItem(null)
        item.asset.cancelLoading()
        loader.release()
    }
}

internal fun iosVideoWait(timeoutSeconds: Double = 15.0, condition: () -> Boolean) {
    val until = NSDate().timeIntervalSince1970 + timeoutSeconds
    while (!condition() && NSDate().timeIntervalSince1970 < until) NSRunLoop.currentRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.01))
}

internal fun iosVideoSha256(path: String): ByteArray {
    val input = open(path, O_RDONLY); assertTrue(input >= 0, path)
    val digest = livePhotoDigest()
    try {
        val bytes = ByteArray(65_536)
        while (true) {
            val count = bytes.usePinned { read(input, it.addressOf(0), bytes.size.toULong()) }
            assertTrue(count >= 0)
            if (count == 0L) break
            digest.update(bytes, 0, count.toInt())
        }
        return digest.finish()
    } finally { close(input) }
}

internal fun iosVideoReport(name: String, values: Map<String, Any>) {
    val reports = assertNotNull(getenv("CHUR_VIDEO_ACCEPTANCE_REPORTS")?.toKString(), "Video report directory is required")
    assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(reports, true, null, null))
    val report = mapOf("schema_version" to 1, "platform" to "ios", "simulator" to (getenv("SIMULATOR_MODEL_IDENTIFIER") != null),
        "device_model" to UIDevice.currentDevice.model, "os_version" to UIDevice.currentDevice.systemVersion) + values
    val data = assertNotNull(NSJSONSerialization.dataWithJSONObject(report, NSJSONWritingPrettyPrinted, null))
    assertTrue(data.writeToFile("$reports/$name.json", atomically = true))
    println("CHUR_IOS_VIDEO_REPORT=$reports/$name.json")
}
