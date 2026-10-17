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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.viewinterop.UIKitView
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.error
import platform.AVFoundation.status
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
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync

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
    val readerProblem by loader.problem.collectAsState()
    var nativeProblem by remember(player) { mutableStateOf<PlaybackProblem?>(null) }
    val problem = readerProblem ?: nativeProblem
    val layer = remember(player, audio) { if (audio) null else AVPlayerLayer.playerLayerWithPlayer(player) }
    var started by remember(player) { mutableStateOf(false) }
    var covered by remember(layer) { mutableStateOf(!audio) }
    var phase by remember(player) { mutableStateOf(IosPlaybackPhase.INITIAL) }
    val playing = phase == IosPlaybackPhase.PLAYING
    val buffering = phase == IosPlaybackPhase.BUFFERING
    var position by remember(player) { mutableStateOf(0f) }
    var duration by remember(player) { mutableStateOf(0f) }
    var scrub by remember(player) { mutableStateOf(IosPlaybackScrub()) }
    val controlsHeld = iosPlaybackControlsHeld(
        controlsPinned, voiceOver, keyboard, controlsFocused, dragging || scrub.active,
    )
    val displayedPosition = scrub.displayedPosition(position)
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
            if (player.currentItem?.status == AVPlayerItemStatusFailed && nativeProblem == null) {
                nativeProblem = iosNativePlaybackProblem(player.currentItem?.error)
            }
            covered = !audio && (!started || layer?.readyForDisplay != true)
            if (playing) source.vault.noteActivity()
            delay(250)
        }
    }
    LaunchedEffect(problem) {
        if (problem != null) {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            loader.release()
            onControlsVisibleChange(true)
        }
    }
    LaunchedEffect(phase, started, controlsHeld) {
        if (iosPlaybackShouldReveal(started, phase, controlsHeld)) onControlsVisibleChange(true)
    }
    LaunchedEffect(playing, controlsHeld, controlsVisible, touches) {
        if (playing && !controlsHeld && controlsVisible) {
            delay(3_000)
            onControlsVisibleChange(false)
        }
    }
    val play = {
        started = true
        if (duration > 0 && position >= duration) player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
        player.play()
        // Acknowledge the accepted action before the next native clock sample.
        phase = IosPlaybackPhase.BUFFERING
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
        val posterShown = !audio && covered && poster != null && problem == null
        val posterOffersPlay = posterShown && !started
        if (posterShown) PlayerPoster(checkNotNull(poster), onPlay = if (posterOffersPlay) play else null)
        // The poster owns the only Play until it is pressed. Hidden transport
        // controls must not remain in the keyboard or VoiceOver focus order.
        if (problem != null) {
            PlaybackProblemPanel(
                problem = problem,
                onVerify = source.onVerify,
                modifier = Modifier.align(Alignment.Center).padding(controlsPadding),
            )
        }
        if ((controlsVisible || controlsHeld) && !posterOffersPlay && problem == null) {
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
                    modifier = Modifier.semantics {
                        contentDescription = "Playback position"
                        stateDescription = iosPlaybackTimeDescription(displayedPosition, duration)
                    },
                    value = displayedPosition.coerceIn(0f, duration.coerceAtLeast(0f)),
                    onValueChange = {
                        scrub = scrub.previewAt(it)
                        onControlsVisibleChange(true)
                    },
                    onValueChangeFinished = {
                        scrub = scrub.commit()
                        scrub.target?.let { target ->
                            val revision = scrub.revision
                            player.seekToTime(CMTimeMakeWithSeconds(target.toDouble(), 600)) { _ ->
                                // Read the confirmed time before releasing the optimistic thumb.
                                position = CMTimeGetSeconds(player.currentTime())
                                    .takeIf { it.isFinite() }?.toFloat() ?: position
                                scrub = scrub.complete(revision)
                            }
                        }
                    },
                    valueRange = 0f..duration.coerceAtLeast(1f),
                    enabled = started && duration > 0,
                    interactionSource = scrubInteractions,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (playing || buffering) {
                            player.pause()
                            phase = IosPlaybackPhase.PAUSED
                            onControlsVisibleChange(true)
                        } else play()
                    }) {
                        Text(if (playing || buffering) "Pause" else "Play", color = Color.White)
                    }
                    TextButton(onClick = {
                        player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
                        play()
                    }, enabled = started) { Text("Replay", color = Color.White) }
                }
                Text(
                    iosPlaybackTimeDescription(displayedPosition, duration),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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
    val problem = MutableStateFlow<PlaybackProblem?>(null)
    // These fields are owned by loaderQueue, including release on disposal.
    private var reader = 0L
    private var size = 0L
    private var released = false

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        try {
            if (released) return fail(request, ChurStatus.SESSION_EXPIRED)
            ensureReader()

            // Publish a length only after the authenticated reader confirms
            // completeness. A partial source must never reach the decoder.
            request.contentInformationRequest?.let { information ->
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
            if (offset < 0) return fail(request, ChurStatus.INVALID_INPUT)
            if (offset >= size) {
                request.finishLoading()
                return true
            }
            val wanted = minOf(data.requestedLength.toLong(), size - offset)
            val bytes = vault.readLeased(reader, offset, wanted.toInt())
            if (bytes.isEmpty() && wanted > 0) return fail(request, ChurStatus.OBJECT_INCOMPLETE)
            if (bytes.isNotEmpty()) data.respondWithData(bytes.toNSData())
            request.finishLoading()
            return true
        } catch (failure: ChurFailure) {
            return fail(request, failure.status)
        }
    }

    /** Stops queued reads and releases the lease on its owning serial queue. */
    fun release() {
        dispatch_sync(loaderQueue) {
            released = true
            releaseReader()
        }
    }

    private fun releaseReader() {
        if (reader != 0L) {
            vault.releaseReader(reader)
            reader = 0L
        }
    }

    private fun fail(request: AVAssetResourceLoadingRequest, status: ChurStatus): Boolean {
        // A terminal failure prevents AVFoundation from reopening the reader.
        released = true
        releaseReader()
        if (problem.value == null) problem.value = playbackProblem(status)
        request.finishLoadingWithError(iosReaderPlaybackError(status))
        return true // The delegate handled this request, including its error.
    }

    private fun ensureReader() {
        if (reader != 0L) return
        reader = runBlocking { vault.leaseReader(source.objectId, source.kind) }
        val info = vault.readerContentInfo(reader)
        if (!info.complete) throw ChurFailure(ChurStatus.OBJECT_INCOMPLETE, "private playback")
        if (!info.byteRangeSupported || info.plaintextSize < 0) {
            throw ChurFailure(ChurStatus.INVALID_INPUT, "private playback ranges")
        }
        size = info.plaintextSize
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

/** Native codec failures are separate from authenticated reader failures. */
internal fun iosNativePlaybackProblem(error: NSError?): PlaybackProblem =
    if (error?.domain == AVFoundationErrorDomain &&
        (error.code == AVErrorDecoderNotFound || error.code == AVErrorFileFormatNotRecognized)
    ) PlaybackProblem.UNSUPPORTED else PlaybackProblem.UNAVAILABLE

/** AVFoundation receives a real, redacted error, never a successful nil error. */
internal fun iosReaderPlaybackError(status: ChurStatus): NSError = NSError(
    domain = "dev.po4yka.chur.playback",
    code = status.value.toLong(),
    userInfo = mapOf(NSLocalizedDescriptionKey to "Private playback could not read the media."),
)
