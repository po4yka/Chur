@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.viewinterop.UIKitView
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.rate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.AVFoundation.resourceLoader
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

/**
 * The AVFoundation half of `MEDIA_PIPELINE.md` §9.
 *
 * `AVPlayer` owns the codec and the surface and never sees a container, a key,
 * or a path. It asks a resource loader for byte ranges, and the loader asks the
 * vault, which is §1's split applied to playback exactly as the Android side
 * applies it.
 *
 * AVFoundation consults a resource-loader delegate only for a URL whose scheme
 * it does not itself handle, which is why the asset carries the `chur` scheme.
 * The URL names no object: `DISCREET_MODE.md`'s "Deep links" section forbids a
 * private identifier in one, and the delegate already knows which object it is
 * for.
 */
@Composable
actual fun VaultPlayer(
    source: PlaybackSource,
    modifier: Modifier,
    poster: ImageBitmap?,
    controlsVisible: Boolean,
    controlsPinned: Boolean,
    onControlsVisibleChange: (Boolean) -> Unit,
    controlsPadding: PaddingValues,
    touches: Int,
) {
    val audio = source.contentType.startsWith("audio/")
    var voiceOver by remember { mutableStateOf(UIAccessibilityIsVoiceOverRunning()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = UIAccessibilityVoiceOverStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { voiceOver = UIAccessibilityIsVoiceOverRunning() }
        voiceOver = UIAccessibilityIsVoiceOverRunning()
        onDispose { center.removeObserver(observer) }
    }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scrubInteractions = remember { MutableInteractionSource() }
    val dragging by scrubInteractions.collectIsDraggedAsState()
    var controlsFocused by remember { mutableStateOf(false) }
    val controlsHeld = iosPlaybackControlsHeld(controlsPinned, voiceOver, keyboard, controlsFocused, dragging)
    val loader = remember(source.objectId, source.plaintextSize) { ChurResourceLoader(source) }
    val player = remember(loader) {
        val asset = AVURLAsset(NSURL(string = "chur://vault/object"), options = null)
        // A serial queue of its own, never the main queue. The delegate answers
        // synchronously and its first answer takes the repository's mutex and
        // then a native read; on the main queue that is the user interface
        // waiting on a catalog query, which is the shape of an unresponsive
        // application rather than a slow one. Serial is what AVFoundation
        // expects and is also what the reader needs: `FFI_CONTRACT.md` §8
        // serializes calls per reader handle in v1.
        asset.resourceLoader.setDelegate(loader, loaderQueue)
        privatePlayer(AVPlayerItem(asset as AVAsset))
    }
    val layer = remember(player, audio) { if (audio) null else AVPlayerLayer.playerLayerWithPlayer(player) }
    var started by remember(player) { mutableStateOf(false) }
    var covered by remember(layer) { mutableStateOf(!audio) }
    var phase by remember(player) { mutableStateOf(IosPlaybackPhase.INITIAL) }
    val playing = phase == IosPlaybackPhase.PLAYING
    val buffering = phase == IosPlaybackPhase.BUFFERING
    var position by remember(player) { mutableStateOf(0f) }
    var duration by remember(player) { mutableStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            position = CMTimeGetSeconds(player.currentTime()).takeIf { it.isFinite() }?.toFloat() ?: 0f
            duration = player.currentItem?.let { CMTimeGetSeconds(it.duration) }
                ?.takeIf { it.isFinite() && it > 0 }?.toFloat() ?: 0f
            phase = iosPlaybackPhase(
                started = started,
                advancing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying,
                waiting = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
                position = position,
                duration = duration,
            )
            covered = !audio && (!started || layer?.readyForDisplay != true)
            if (playing) source.vault.noteActivity()
            delay(250)
        }
    }
    LaunchedEffect(playing, controlsHeld, touches) {
        if (!playing || controlsHeld) onControlsVisibleChange(true)
        else {
            delay(3_000)
            onControlsVisibleChange(false)
        }
    }
    val play = {
        started = true
        if (duration > 0 && position >= duration) player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
        player.play()
        onControlsVisibleChange(true)
    }
    DisposableEffect(player) {
        onDispose {
            // `PLAINTEXT_LIFECYCLE.md` §8 step 1: private playback stops before
            // anything else, and leaving the surface is that event here.
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            loader.release()
        }
    }
    Box(modifier = modifier) {
        if (audio) AudioArtwork(poster, Modifier.fillMaxSize(), controlsPadding)
        else UIKitView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val view = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
                layer?.let { view.layer.addSublayer(it) }
                view
            },
            update = { view ->
                // The layer does not follow its host's bounds on its own, and a
                // Compose surface resizes on rotation and on a split view.
                (view.layer.sublayers?.firstOrNull() as? AVPlayerLayer)?.setFrame(view.bounds)
            },
        )
        if (!audio && covered && poster != null) PlayerPoster(poster, onPlay = if (!started) play else null)
        if (controlsVisible || controlsHeld || !started || !playing) {
            Column(
                Modifier.align(Alignment.BottomCenter).onFocusChanged { controlsFocused = it.hasFocus }.padding(controlsPadding)
                    .fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp),
            ) {
                if (buffering) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(Modifier.padding(8.dp), color = Color.White)
                        Text("Loading playback…", color = Color.White)
                    }
                }
                Slider(
                    value = position.coerceIn(0f, duration.coerceAtLeast(0f)),
                    onValueChange = {
                        position = it
                        player.seekToTime(CMTimeMakeWithSeconds(it.toDouble(), 600))
                    },
                    valueRange = 0f..duration.coerceAtLeast(1f),
                    enabled = started && duration > 0,
                    interactionSource = scrubInteractions,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (playing) player.pause() else play() }) {
                        Text(if (playing) "Pause" else "Play", color = Color.White)
                    }
                    TextButton(onClick = {
                        player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
                        play()
                    }, enabled = started) { Text("Replay", color = Color.White) }
                    Text("${position.toInt()} / ${duration.toInt()} seconds", color = Color.White)
                }
            }
        }
    }
}

/** Private playback starts paused and cannot use an external player. */
internal fun privatePlayer(item: AVPlayerItem): AVPlayer = AVPlayer().apply {
    allowsExternalPlayback = false
    replaceCurrentItemWithPlayerItem(item)
}

/**
 * Serves one vault object's ranges to AVFoundation.
 *
 * One reader lease is held for the loader's life and every request goes through
 * it, which `FFI_CONTRACT.md` §8 permits from the loader's own queue.
 *
 * The delegate answers `shouldWaitForLoadingOfRequestedResource` synchronously
 * and returns `true`: the range is served and the request finished before the
 * method returns, which is the simplest correct shape and is what a local
 * source can do. A network source would return `true` and finish later.
 */
private class ChurResourceLoader(private val source: PlaybackSource) :
    NSObject(), AVAssetResourceLoaderDelegateProtocol {

    private val vault: VaultRepository get() = source.vault
    private var reader = 0L
    private var size = 0L
    private var complete = false

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        if (!ensureReader()) {
            request.finishLoadingWithError(null)
            return false
        }

        // §6.1: content information before the first range request. A player
        // given a length treats a later failure as transport trouble and
        // retries without end, so an incomplete object publishes none.
        request.contentInformationRequest?.let { information ->
            if (!complete) {
                request.finishLoadingWithError(null)
                return false
            }
            information.setContentLength(size)
            information.setByteRangeAccessSupported(true)
            information.setContentType(utTypeOf(source.contentType))
        }

        val data = request.dataRequest
        if (data == null) {
            request.finishLoading()
            return true
        }
        val offset = data.requestedOffset
        if (offset >= size) {
            request.finishLoading()
            return true
        }
        val wanted = minOf(data.requestedLength.toLong(), size - offset)
        val bytes = try {
            vault.readLeased(reader, offset, wanted.toInt())
        } catch (_: ChurFailure) {
            request.finishLoadingWithError(null)
            return false
        }
        if (bytes.isNotEmpty()) {
            data.respondWithData(bytes.toNSData())
        }
        request.finishLoading()
        return true
    }

    /** Releases the lease, which the composable does when it leaves. */
    fun release() {
        if (reader != 0L) {
            vault.releaseReader(reader)
            reader = 0L
        }
    }

    private fun ensureReader(): Boolean {
        if (reader != 0L) return true
        return try {
            reader = runBlocking { vault.leaseReader(source.objectId, source.kind) }
            val info = vault.readerContentInfo(reader)
            size = info.plaintextSize
            complete = info.complete && info.byteRangeSupported
            true
        } catch (_: ChurFailure) {
            false
        }
    }
}

/**
 * The uniform type identifier AVFoundation is told, from the IANA type the
 * catalog authenticated.
 *
 * `FFI_CONTRACT.md` §6.1 says iOS converts the value with `UTType(mimeType:)`.
 * That initializer is unavailable from Kotlin/Native, so the two abstract types
 * the player needs are named directly: `public.movie` and `public.audio` are
 * the identifiers every concrete video and audio type conforms to, which is
 * enough for AVFoundation to choose a track reader, and anything else is left
 * to the codec's own sniffing rather than replaced with a guess.
 */
private fun utTypeOf(contentType: String): String? = when {
    contentType.startsWith("video/") -> "public.movie"
    contentType.startsWith("audio/") -> "public.audio"
    else -> null
}

/**
 * The queue the resource loader answers on.
 *
 * One serial queue for the process. A second player replaces the first on this
 * surface, so two loaders are never live at once, and sharing the queue keeps
 * the ordering AVFoundation and `FFI_CONTRACT.md` §8 both want.
 */
private val loaderQueue = dispatch_queue_create("dev.po4yka.chur.resource-loader", null)

/** Copies a Kotlin array into an `NSData` AVFoundation can hold. */
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
