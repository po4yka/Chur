package dev.po4yka.chur.app.vault

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import dev.po4yka.chur.app.R
import dev.po4yka.chur.app.theme.ViewerColors
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
    val audio = source.contentType.startsWith("audio/")
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
    val player = remember(source.objectId, source.plaintextSize, source.offset, source.length) {
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
    var problem by remember(player) { mutableStateOf(playbackProblem(player.playerError, player.currentTracks)) }
    val failed = problem != null
    var buffering by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var requested by remember(player) { mutableStateOf(player.playWhenReady) }
    val waiting = buffering && requested && !failed
    var covered by remember(player) { mutableStateOf(true) }
    val posterShown = !audio && covered && poster != null && !failed
    // Whether this page's player was asked to play: by the poster's Play, the
    // controls, or a screen reader.
    var started by remember(player) { mutableStateOf(false) }
    // Whether the player has failed, for example on a stream the device cannot
    // decode or on an integrity failure.
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

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                requested = playWhenReady
                if (playWhenReady) {
                    covered = false
                    started = true
                }
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                problem = playbackProblem(error, player.currentTracks)
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Media3 can play supported audio while silently omitting an
                // unsupported video track. Report that partial selection too.
                problem = playbackProblem(player.playerError, tracks)
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
    var options by remember(player) { mutableStateOf<PlaybackOptions?>(null) }
    val openOptions by rememberUpdatedState<(PlaybackOptions) -> Unit> { menu ->
        options = menu
        onControlsVisibleChange(true)
    }
    LaunchedEffect(problem, waiting) {
        if (problem != null) player.pause()
        if (problem != null || waiting) reportControls(true)
    }
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
        if (audio) AudioArtwork(poster, Modifier.fillMaxSize(), controlsPadding)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                (if (audio) LayoutInflater.from(it).inflate(R.layout.chur_audio_player, null) as PlayerView
                else PlayerView(it)).apply {
                    if (audio) {
                        // The bounded, authenticated derivative stays behind
                        // Media3's controls throughout audio playback. Do not
                        // let Media3 decode artwork from the original again.
                        artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    // The viewer plays one object, so there is no previous or
                    // next item for these buttons to reach.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowSubtitleButton(true)
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
                    // Native settings use focusable PopupWindows that cover the viewer's
                    // lock. Keep the real controller, replacing only popup entry points.
                    listOf(
                        androidx.media3.ui.R.id.exo_settings to PlaybackOptions.SETTINGS,
                        androidx.media3.ui.R.id.exo_playback_speed to PlaybackOptions.SPEED,
                        androidx.media3.ui.R.id.exo_audio_track to PlaybackOptions.AUDIO,
                        androidx.media3.ui.R.id.exo_subtitle to PlaybackOptions.TEXT,
                    ).forEach { (id, menu) ->
                        findViewById<View>(id)?.setOnClickListener { openOptions(menu) }
                    }
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
                view.useController = !posterShown && !failed
                view.importantForAccessibility = if (posterShown || failed) {
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
                val timeout = if (waiting || options != null) 0 else showTimeoutMs
                val changed = view.controllerAutoShow != autoShow || view.controllerShowTimeoutMs != timeout
                val touched = seenTouches[0] != touches
                seenTouches[0] = touches
                view.controllerAutoShow = autoShow
                if (view.controllerShowTimeoutMs != timeout) view.controllerShowTimeoutMs = timeout
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
        options?.let { menu ->
            Box(Modifier.align(Alignment.BottomEnd).padding(controlsPadding)) {
                PlaybackOptionsMenu(player, menu, onMenu = { options = it }, onDismiss = { options = null })
            }
        }
        if (posterShown && poster != null) PlayerPoster(poster, onPlay = { player.play() })
        if (waiting) {
            Text("Loading playback…", color = ViewerColors.content,
                modifier = Modifier.align(Alignment.TopCenter).padding(controlsPadding).padding(16.dp)
                    .background(ViewerColors.chromeScrim).semantics { liveRegion = LiveRegionMode.Polite })
        }
        problem?.let {
            PlaybackProblemPanel(it, source.onVerify,
                Modifier.align(Alignment.Center).padding(controlsPadding))
        }
    }
}

private enum class PlaybackOptions { SETTINGS, SPEED, AUDIO, TEXT }

/** Same Media3 choices and track parameters, inside menus with an independent privacy exit. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlaybackOptionsMenu(player: Player, menu: PlaybackOptions, onMenu: (PlaybackOptions) -> Unit, onDismiss: () -> Unit) {
    var revision by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { revision++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val resources = LocalContext.current.resources
    val names = remember(resources) { DefaultTrackNameProvider(resources) }
    val tracks = remember(player, revision) { player.currentTracks }
    val parameters = remember(player, revision) { player.trackSelectionParameters }
    fun choices(type: Int) = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { index -> group.isTrackSupported(index) &&
            group.getTrackFormat(index).selectionFlags and C.SELECTION_FLAG_FORCED == 0 }
            .map { index -> Triple(group, index, names.getTrackName(group.getTrackFormat(index))) }
    }
    androidx.compose.runtime.key(menu) {
        PrivateDropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            when (menu) {
                PlaybackOptions.SETTINGS -> {
                    DropdownMenuItem(text = { Text("Playback speed") }, onClick = { onMenu(PlaybackOptions.SPEED) })
                    DropdownMenuItem(text = { Text("Audio tracks") }, onClick = { onMenu(PlaybackOptions.AUDIO) })
                    DropdownMenuItem(text = { Text("Subtitles") }, onClick = { onMenu(PlaybackOptions.TEXT) })
                }
                PlaybackOptions.SPEED -> {
                    val labels = resources.getStringArray(androidx.media3.ui.R.array.exo_controls_playback_speeds)
                    val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    speeds.forEachIndexed { index, speed ->
                        DropdownMenuItem(text = { Text(labels[index]) },
                            modifier = Modifier.testTag("playback-speed-${(speed * 100).toInt()}")
                                .semantics { selected = player.playbackParameters.speed == speed },
                            onClick = { player.setPlaybackSpeed(speed); onDismiss() })
                    }
                }
                PlaybackOptions.AUDIO, PlaybackOptions.TEXT -> {
                    val type = if (menu == PlaybackOptions.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                    val choices = choices(type)
                    val automatic = resources.getString(if (type == C.TRACK_TYPE_AUDIO)
                        androidx.media3.ui.R.string.exo_track_selection_auto else androidx.media3.ui.R.string.exo_track_selection_none)
                    DropdownMenuItem(text = { Text(automatic) },
                        modifier = Modifier.testTag(if (type == C.TRACK_TYPE_AUDIO) "playback-audio-auto" else "playback-text-none")
                            .semantics { selected = if (type == C.TRACK_TYPE_AUDIO)
                                choices.none { parameters.overrides.containsKey(it.first.mediaTrackGroup) }
                                else choices.none { it.first.isTrackSelected(it.second) } },
                        onClick = {
                            val builder = parameters.buildUpon().clearOverridesOfType(type)
                            if (type == C.TRACK_TYPE_AUDIO) builder.setTrackTypeDisabled(type, false)
                            else builder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                                .setPreferredTextLanguage(null).setPreferredTextRoleFlags(0)
                            player.trackSelectionParameters = builder.build()
                            onDismiss()
                        })
                    choices.forEachIndexed { index, (group, track, label) ->
                        DropdownMenuItem(text = { Text(label) },
                            modifier = Modifier.testTag("playback-${if (type == C.TRACK_TYPE_AUDIO) "audio" else "text"}-track-$index")
                                .semantics { selected = parameters.overrides.containsKey(group.mediaTrackGroup) && group.isTrackSelected(track) },
                            onClick = {
                                player.trackSelectionParameters = parameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                                    .setTrackTypeDisabled(type, false).build()
                                onDismiss()
                            })
                    }
                }
            }
            if (menu != PlaybackOptions.SETTINGS) {
                DropdownMenuItem(text = { Text("Back to playback settings") }, onClick = { onMenu(PlaybackOptions.SETTINGS) })
            }
        }
    }
}

/** Preserve authenticated-reader failures through Media3's exception wrappers. */
internal fun playbackProblem(error: PlaybackException): PlaybackProblem {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is ChurFailure) return playbackProblem(cause.status)
        cause = cause.cause
    }
    return if (error.errorCode in 2000..2999) PlaybackProblem.UNAVAILABLE else PlaybackProblem.UNSUPPORTED
}

/** Decoder rejection can leave other tracks playing without a player error. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackProblem(error: PlaybackException?, tracks: Tracks): PlaybackProblem? {
    // An integrity or availability error must not be replaced by a codec warning.
    if (error != null) return playbackProblem(error)
    return if (listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO).any { type ->
        tracks.containsType(type) && !tracks.isTypeSupported(type, /* allowExceedsCapabilities = */ true)
    }) PlaybackProblem.UNSUPPORTED else null
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
            if (source.offset < 0 || source.length < 0 || source.offset > info.plaintextSize ||
                source.length > info.plaintextSize - source.offset) {
                throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            }
            position = dataSpec.position
            val available = source.length - position
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
            throw DataSourceException(java.io.IOException(failure), failure.status.value)
        } catch (failure: Exception) {
            release()
            throw failure
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return androidx.media3.common.C.RESULT_END_OF_INPUT
        val take = minOf(length.toLong(), remaining).toInt()
        val bytes = try {
            vault.readLeased(reader, source.offset + position, take)
        } catch (failure: ChurFailure) {
            throw DataSourceException(java.io.IOException(failure), failure.status.value)
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
