package dev.po4yka.chur.app.vault

import android.net.Uri
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.runBlocking

/**
 * The Media3 half of `MEDIA_PIPELINE.md` §9.
 *
 * ExoPlayer owns the codec, the buffering, and the surface; it never sees a
 * container, a key, or a path. What it sees is [ChurDataSource], which asks the
 * vault for authenticated plaintext ranges and hands them over. That is §1's
 * split applied to playback, and it is why a codec bug cannot reach ciphertext:
 * the codec is downstream of every cryptographic check.
 */
@androidx.annotation.OptIn(UnstableApi::class)
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
    val context = LocalContext.current
    // `DESIGN.md` §13.4 and §23.3: the controls, and the chrome with them,
    // stay while a screen reader explores the screen.
    val accessibility = remember(context) { checkNotNull(context.getSystemService(AccessibilityManager::class.java)) }
    var exploring by remember(accessibility) { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    // §13.4 and §23.3: nor while the keyboard moves the focus. Media3 holds its
    // controls only for keys that reach the player view, and a hide would take
    // the chrome control that has the focus away from under it.
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    // Media3's time of playback with no touch before the controls hide, made
    // longer if the user asked for more time to act in the accessibility
    // settings. None while held: a pinned chrome stays, §13.4, and without the
    // hold each timeout would hide the controls and the chrome would show them
    // again.
    val showTimeoutMs = if (controlsPinned || exploring || keyboard) {
        0
    } else {
        accessibility.getRecommendedTimeoutMillis(
            PlayerControlView.DEFAULT_SHOW_TIMEOUT_MS,
            AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_ICONS,
        )
    }
    val player = remember(source.objectId, source.plaintextSize) {
        val factory = ChurDataSource.Factory(source)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(factory))
            // Media3 takes audio focus and reacts to a headset disconnect only
            // when asked. With the focus, the player pauses when another app
            // starts to play or a call rings, and a lock has a focus to give
            // up, step 5 of `ANDROID.md` §17.3: `release()` below abandons it.
            // "Becoming noisy" pauses it when headphones are unplugged or a
            // Bluetooth headset drops, so a private recording does not go on
            // through the loudspeaker. Neither publishes a media session or a
            // notification, which §17.3 and §20.3 keep off.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(
                        if (source.contentType.startsWith("video/")) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC,
                    )
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        // §6.1: the type is the authenticated one, used
                        // unchanged as the MIME type.
                        .setMimeType(mimeTypeOf(source.contentType))
                        .setUri(ChurDataSource.URI)
                        .build(),
                )
                prepare()
            }
    }
    // The poster covers the view until the player draws a frame. Media3
    // draws the first one while paused, so on most devices the poster goes
    // before a press. It also goes on the press that starts playback: a
    // stream the device cannot decode then shows the player's own controls
    // and the chrome, not a poster that still looks playable.
    var covered by remember(player) { mutableStateOf(true) }
    val posterShown = covered && poster != null
    // Whether this page's player was asked to play: by the poster's Play, the
    // controls, or a screen reader.
    var started by remember(player) { mutableStateOf(false) }
    // Whether the player has failed, for example on a stream the device cannot
    // decode or on an integrity failure.
    var failed by remember(player) { mutableStateOf(false) }
    // Media3 shows the controls by itself on a paused, ended or failed player,
    // first when the view gets the player. Before the first play it may do so
    // only while the chrome shows: a swipe onto this page is not a tap, and
    // must not bring back a chrome the user hid, §13.1. After the first play,
    // or after a failure, which is not a swipe, it always may. The controls
    // and the chrome can hide during playback, and an end, a pause that the
    // user did not make (another app's audio, a headset that drops) or a
    // failure then brings back the controls, and the chrome with them, §13.2
    // and §13.4. Without that, the user sees a still frame or a black canvas
    // with no Back and no sign that playback stopped.
    val autoShow = controlsVisible || started || failed
    // What the controls show, as the controller last reported it.
    var controllerShown by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val frames = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                covered = false
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    covered = false
                    started = true
                }
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                failed = error != null
            }
        }
        player.addListener(frames)
        onDispose {
            player.removeListener(frames)
            // §8 of `PLAINTEXT_LIFECYCLE.md`: stopping private playback is the
            // first step of a lock, and leaving the surface is the same event
            // for this player's purposes.
            player.release()
        }
    }
    val reportControls by rememberUpdatedState(onControlsVisibleChange)
    // Read here, not in `update`: a report changes this and the chrome in the
    // same frame, and `update` has to see both. Read there, it would run on
    // this change alone, with the chrome of the frame before.
    val shown = controllerShown
    // The last count of [touches] that `update` saw, so each new touch starts
    // the timeout again once.
    val seenTouches = remember(player) { intArrayOf(touches) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    // The viewer plays one object, so there is no previous or
                    // next item for these buttons to reach.
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    controllerAutoShow = autoShow
                    // Media3 does not restart its timeout for controls that
                    // are still sliding in, so a rule that changes then, as a
                    // pin or a tap on a paused player does, would keep the old
                    // timeout and hide them. Without the slide they show and
                    // hide at once, and with the chrome.
                    setControllerAnimationEnabled(false)
                    // A tap on the video reaches this view, not the viewer,
                    // and so does the inactivity timeout. The view reports
                    // what they did, and the chrome follows it. The hide that
                    // turns the controls off under the poster is not the
                    // user's, so the chrome stays.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controllerShown = visibility == View.VISIBLE
                            if (useController) reportControls(controllerShown)
                        },
                    )
                    this.player = player
                }
            },
            update = { view ->
                // §13.2 and §25.5: the time bar and the settings sit above the
                // viewer's actions and clear of the cutout. The padding moves
                // the controls only, so the video keeps the whole canvas.
                val controls = (0 until view.childCount).map(view::getChildAt).filterIsInstance<PlayerControlView>()
                with(density) {
                    controls.firstOrNull()?.setPadding(
                        controlsPadding.calculateLeftPadding(direction).roundToPx(),
                        controlsPadding.calculateTopPadding().roundToPx(),
                        controlsPadding.calculateRightPadding(direction).roundToPx(),
                        controlsPadding.calculateBottomPadding().roundToPx(),
                    )
                }
                // The poster only covers the view, so the controls under it
                // are off: a tap there would seek or open the settings
                // unseen, the keyboard and a screen reader would reach them,
                // and a tap goes to the viewer's chrome instead, §13.1.
                // Whether Media3 could show the controls by itself before
                // this frame: it looks only when the player's state changes,
                // and neither setter below makes it look again.
                val couldShow = view.useController && view.controllerAutoShow
                view.useController = !posterShown
                view.importantForAccessibility = if (posterShown) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
                // The chrome drives the controls. When it is pinned it does
                // not follow a tap, and the controls come back to it here.
                // A show applies the rules of this frame: no timeout on a
                // paused or held player, and a new one when that ends. The
                // timeout is set only when it changes, as a set starts it
                // again. A touch on the viewer outside this view, as on a
                // chrome control, also starts it again: Media3 counts only
                // the touches that reach this view.
                val changed = view.controllerAutoShow != autoShow || view.controllerShowTimeoutMs != showTimeoutMs
                val touched = seenTouches[0] != touches
                seenTouches[0] = touches
                view.controllerAutoShow = autoShow
                if (view.controllerShowTimeoutMs != showTimeoutMs) view.controllerShowTimeoutMs = showTimeoutMs
                val stopped = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
                if (controlsVisible && (changed || !shown || touched)) {
                    view.showController()
                } else if (!controlsVisible && shown) {
                    view.hideController()
                } else if (!controlsVisible && !couldShow && view.useController && autoShow && stopped) {
                    // The player ended or failed while Media3 could not show
                    // the controls, as under the poster, where a stream that
                    // cannot be decoded fails, and the press on the poster's
                    // Play then brings no new state. Media3 would show them
                    // now, and the chrome comes back with them, §13.4. A
                    // paused player does not show here: a swipe onto it is
                    // not a tap, §13.1.
                    view.showController()
                }
            },
            onRelease = {
                // A view that loses its player hides its controls, and this
                // page has left by then. The listener goes first, so its
                // report does not hide the chrome of the page a swipe landed
                // on: a swipe is not a tap, §13.1.
                it.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
                it.player = null
            },
        )
        if (covered && poster != null) PlayerPoster(poster, onPlay = { player.play() })
    }
}

/**
 * The MIME type Media3 is told, from the type the catalog authenticated.
 *
 * Media3 uses the value to choose an extractor. A type it does not know makes
 * it sniff the stream instead, which still works and only costs a read, so an
 * unknown type is passed through rather than replaced with a guess.
 */
private fun mimeTypeOf(contentType: String): String = when (contentType) {
    "audio/mp4", "audio/m4a" -> MimeTypes.AUDIO_MP4
    "audio/wav", "audio/x-wav" -> MimeTypes.AUDIO_WAV
    "video/quicktime" -> MimeTypes.VIDEO_MP4
    else -> contentType
}

/**
 * A Media3 data source over one vault object, `FFI_CONTRACT.md` §6.3.
 *
 * One reader lease is held for the life of the source and every read goes
 * through it. §8's table permits that from a loader thread the reader did not
 * come from, and it is why a seek costs one chunk authentication rather than a
 * whole reopen: `PERFORMANCE_BUDGETS.md` §12 measures both, and the difference
 * is about two percent, so the lease buys correctness rather than speed — a
 * reader that stayed open across a lock would be a handle the session no longer
 * owns.
 */
@UnstableApi
private class ChurDataSource(private val source: PlaybackSource) : DataSource {

    private val vault: VaultRepository get() = source.vault
    private var reader = 0L
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        // §10 has no callbacks out of Rust and this source produces no transfer
        // events of its own; the player's own listeners cover what it needs.
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        try {
            reader = runBlocking { vault.leaseReader(source.objectId, source.kind) }
            val info = vault.readerContentInfo(reader)
            // §6.1: a reader on an incomplete object may serve ranges for
            // verification but must not be attached to a player. A player given
            // a length treats a later failure as transport trouble and retries
            // without end.
            if (!info.complete || !info.byteRangeSupported) {
                throw DataSourceException(
                    java.io.IOException("the object is not complete"),
                    DataSourceException.POSITION_OUT_OF_RANGE,
                )
            }
            position = dataSpec.position
            val available = info.plaintextSize - position
            if (available < 0) {
                throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            }
            remaining = if (dataSpec.length == androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(dataSpec.length, available)
            }
            opened = true
            return remaining
        } catch (failure: ChurFailure) {
            release()
            throw DataSourceException(java.io.IOException(failure.message), failure.status.value)
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return androidx.media3.common.C.RESULT_END_OF_INPUT
        val take = minOf(length.toLong(), remaining).toInt()
        val bytes = try {
            vault.readLeased(reader, position, take)
        } catch (failure: ChurFailure) {
            throw DataSourceException(java.io.IOException(failure.message), failure.status.value)
        }
        if (bytes.isEmpty()) return androidx.media3.common.C.RESULT_END_OF_INPUT
        bytes.copyInto(target, offset)
        position += bytes.size
        remaining -= bytes.size
        return bytes.size
    }

    override fun getUri(): Uri? = if (opened) URI else null

    override fun close() {
        if (opened || reader != 0L) release()
    }

    private fun release() {
        if (reader != 0L) {
            vault.releaseReader(reader)
            reader = 0L
        }
        opened = false
        position = 0
        remaining = 0
    }

    class Factory(private val source: PlaybackSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChurDataSource(source)
    }

    companion object {
        /**
         * The URI the media item carries.
         *
         * It names no object and no path. `DISCREET_MODE.md`'s "Deep links"
         * section forbids a private identifier in a URI, and the data source
         * already knows which object it is for, so the value is a constant.
         */
        val URI: Uri = Uri.parse("chur://vault/object")
    }
}
