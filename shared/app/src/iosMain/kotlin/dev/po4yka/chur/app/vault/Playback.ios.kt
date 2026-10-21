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
import androidx.compose.material3.DropdownMenuItem
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
import platform.AVFoundation.playable
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVURLAssetReferenceRestrictionsKey
import platform.AVFoundation.AVAssetReferenceRestrictionForbidAll
import platform.AVFoundation.asset
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.currentMediaSelection
import platform.AVFoundation.selectMediaOption
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
import platform.Foundation.NSNumber
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification
import platform.UniformTypeIdentifiers.UTType
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t

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
    val loader = remember(source.objectId, source.kind, source.offset, source.length) { ChurResourceLoader(source) }
    val player = remember(loader) { privatePlayer(privatePlayerItem(loader)) }
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
    var audioGroup by remember(player) { mutableStateOf<AVMediaSelectionGroup?>(null) }
    var subtitleGroup by remember(player) { mutableStateOf<AVMediaSelectionGroup?>(null) }
    var selectedAudio by remember(player) { mutableStateOf<AVMediaSelectionOption?>(null) }
    var selectedSubtitle by remember(player) { mutableStateOf<AVMediaSelectionOption?>(null) }
    var groupsLoaded by remember(player) { mutableStateOf(false) }
    var scrub by remember(player) { mutableStateOf(IosPlaybackScrub()) }
    val controlsHeld = iosPlaybackControlsHeld(
        controlsPinned, voiceOver, keyboard, controlsFocused, dragging || scrub.active,
    )
    val displayedPosition = scrub.displayedPosition(position)
    LaunchedEffect(player) {
        while (true) {
            val item = player.currentItem
            if (item?.status == AVPlayerItemStatusReadyToPlay) {
                // Native playback can accept AAC while silently dropping an
                // unsupported MP4 video track. A ready item alone is not proof
                // that this video asset is playable.
                if (!audio && nativeProblem == null) nativeProblem = iosVideoPlaybackSupportProblem(item)
                if (!groupsLoaded) {
                    audioGroup = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)
                    subtitleGroup = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)
                    groupsLoaded = true
                }
                selectedAudio = audioGroup?.let { item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(it) }
                selectedSubtitle = subtitleGroup?.let { item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(it) }
            }
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
            if (phase == IosPlaybackPhase.PLAYING) source.vault.noteActivity()
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
                player.currentItem?.let { item ->
                    audioGroup?.takeIf { it.options.size > 1 }?.let { group ->
                        MediaTrackMenu(item, group, "Audio", selectedAudio, allowOff = false)
                    }
                    subtitleGroup?.takeIf { it.options.isNotEmpty() }?.let { group ->
                        MediaTrackMenu(item, group, "Subtitles", selectedSubtitle, allowOff = group.allowsEmptySelection)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTrackMenu(
    item: AVPlayerItem,
    group: AVMediaSelectionGroup,
    label: String,
    selected: AVMediaSelectionOption?,
    allowOff: Boolean,
) {
    var expanded by remember(group) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("$label: ${selected?.displayName ?: if (allowOff) "Off" else "Automatic"}", color = Color.White)
        }
        PrivateDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowOff) DropdownMenuItem(
                text = { Text("Off") },
                onClick = { selectMediaTrack(item, group, null); expanded = false },
            )
            group.options.filterIsInstance<AVMediaSelectionOption>().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    enabled = option.playable,
                    onClick = { selectMediaTrack(item, group, option); expanded = false },
                )
            }
        }
    }
}

/** Only select tracks embedded in the current native group; null means subtitles off. */
internal fun selectMediaTrack(item: AVPlayerItem, group: AVMediaSelectionGroup, option: AVMediaSelectionOption?): Boolean {
    if (option == null && !group.allowsEmptySelection) return false
    if (option != null && (option !in group.options || !option.playable)) return false
    item.selectMediaOption(option, inMediaSelectionGroup = group)
    return true
}

/** Forbid remote and local references outside the encrypted container. */
internal fun privatePlaybackAsset(url: NSURL): AVURLAsset = AVURLAsset(
    url,
    options = mapOf(AVURLAssetReferenceRestrictionsKey to NSNumber(unsignedLongLong = AVAssetReferenceRestrictionForbidAll)),
)

/** Create a private player item that reads authenticated vault ranges. */
internal fun privatePlayerItem(loader: ChurResourceLoader): AVPlayerItem {
    val asset = privatePlaybackAsset(NSURL(string = "chur://vault/object"))
    // Native range reads run on one serial queue, away from the main thread.
    asset.resourceLoader.setDelegate(loader, loaderQueue)
    return AVPlayerItem(
        asset as AVAsset,
        automaticallyLoadedAssetKeys = listOf("playable", "availableMediaCharacteristicsWithMediaSelectionOptions"),
    )
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
internal class ChurResourceLoader(private val source: PlaybackSource) :
    NSObject(), AVAssetResourceLoaderDelegateProtocol {

    private val vault: VaultRepository get() = source.vault
    val problem = MutableStateFlow<PlaybackProblem?>(null)
    // The terminal flag crosses threads; the reader and size stay on loaderQueue.
    private var reader = 0L
    private var size = 0L
    private val retirement = IosPlaybackReaderRetirement(loaderQueue) { releaseReader() }

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        try {
            if (retirement.terminal) return finishRetired(request)
            ensureReader()
            if (retirement.terminal) return finishRetired(request)

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
            if (data.requestedOffset < 0 || data.currentOffset < 0 || data.requestedLength < 0) return fail(request, ChurStatus.INVALID_INPUT)
            val offset = maxOf(data.requestedOffset, data.currentOffset)
            if (offset >= size) {
                request.finishLoading()
                return true
            }
            val alreadyDelivered = offset - data.requestedOffset
            val wanted = minOf((data.requestedLength.toLong() - alreadyDelivered).coerceAtLeast(0L), size - offset)
            var delivered = 0L
            while (delivered < wanted) {
                if (retirement.terminal) return finishRetired(request)
                val take = minOf(65_536L, wanted - delivered).toInt()
                val bytes = vault.readLeased(reader, source.offset + offset + delivered, take)
                try {
                    // Disposal wins even when an authenticated read was already in flight.
                    if (retirement.terminal) return finishRetired(request)
                    if (bytes.isEmpty()) return fail(request, ChurStatus.OBJECT_INCOMPLETE)
                    data.respondWithData(bytes.toNSData())
                    delivered += bytes.size
                } finally { bytes.fill(0) }
            }
            request.finishLoading()
            return true
        } catch (failure: ChurFailure) {
            return if (retirement.terminal) finishRetired(request) else fail(request, failure.status)
        }
    }

    /** Marks disposal now; queued close never makes the UI wait for IO. */
    fun release() = retirement.retire()

    private fun finishRetired(request: AVAssetResourceLoadingRequest): Boolean {
        // Disposal must not replace a known codec/integrity problem with a
        // synthetic session error from a callback that was already queued.
        request.finishLoadingWithError(iosReaderPlaybackError(ChurStatus.SESSION_EXPIRED))
        return true
    }

    private fun releaseReader() {
        if (reader != 0L) {
            vault.releaseReader(reader)
            reader = 0L
        }
    }

    private fun fail(request: AVAssetResourceLoadingRequest, status: ChurStatus): Boolean {
        // A terminal failure prevents AVFoundation from reopening the reader.
        retirement.retire()
        if (problem.value == null) problem.value = playbackProblem(status)
        request.finishLoadingWithError(iosReaderPlaybackError(status))
        return true // The delegate handled this request, including its error.
    }

    private fun ensureReader() {
        if (retirement.terminal) throw ChurFailure(ChurStatus.SESSION_EXPIRED, "private playback retired")
        if (reader != 0L) return
        reader = runBlocking { vault.leaseReader(source.objectId, source.kind) }
        val info = vault.readerContentInfo(reader)
        if (!info.complete) throw ChurFailure(ChurStatus.OBJECT_INCOMPLETE, "private playback")
        if (!info.byteRangeSupported || info.plaintextSize < 0) {
            throw ChurFailure(ChurStatus.INVALID_INPUT, "private playback ranges")
        }
        if (source.offset < 0 || source.length < 0 || source.offset > info.plaintextSize ||
            source.length > info.plaintextSize - source.offset) {
            throw ChurFailure(ChurStatus.INVALID_INPUT, "private playback resource bounds")
        }
        size = source.length
    }

}

/** Retires immediately, then closes after the owning queue's in-flight read. */
internal class IosPlaybackReaderRetirement(
    private val queue: dispatch_queue_t,
    private val closeReader: () -> Unit,
) {
    private val retired = MutableStateFlow(false)
    val terminal: Boolean get() = retired.value

    fun retire() {
        if (retired.compareAndSet(expect = false, update = true)) {
            dispatch_async(queue) { closeReader() }
        }
    }
}

/** Resolve the authenticated MIME type through the native type registry. */
internal fun utTypeOf(contentType: String): String? =
    UTType.typeWithMIMEType(contentType)?.identifier

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
internal fun iosVideoPlaybackSupportProblem(item: AVPlayerItem): PlaybackProblem? =
    if (item.status == AVPlayerItemStatusReadyToPlay && !item.asset.playable) PlaybackProblem.UNSUPPORTED else null

internal fun iosNativePlaybackProblem(error: NSError?): PlaybackProblem =
    if (error != null && error.domain == AVFoundationErrorDomain &&
        (error.code == AVErrorDecoderNotFound || error.code == AVErrorFileFormatNotRecognized)
    ) PlaybackProblem.UNSUPPORTED else PlaybackProblem.UNAVAILABLE

/** AVFoundation receives a real, redacted error, never a successful nil error. */
internal fun iosReaderPlaybackError(status: ChurStatus): NSError = NSError(
    domain = "dev.po4yka.chur.playback",
    code = status.value.toLong(),
    userInfo = mapOf(NSLocalizedDescriptionKey to "Private playback could not read the media."),
)
