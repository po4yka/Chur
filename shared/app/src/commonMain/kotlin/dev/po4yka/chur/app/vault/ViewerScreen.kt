package dev.po4yka.chur.app.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.po4yka.chur.app.ActiveOperation
import dev.po4yka.chur.app.Notice
import dev.po4yka.chur.app.theme.BackGlyph
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.DeleteGlyph
import dev.po4yka.chur.app.theme.DiagnosticTextStyle
import dev.po4yka.chur.app.theme.ExportGlyph
import dev.po4yka.chur.app.theme.FavoriteGlyph
import dev.po4yka.chur.app.theme.FavoriteFilledGlyph
import dev.po4yka.chur.app.theme.LocalChurMotion
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.math.min

/**
 * The media viewer, `DESIGN.md` §13.
 *
 * The viewer has its own ladder, §6.2: a black canvas, a scrim for the chrome,
 * and white content. It does not use the application surfaces, because media is
 * the subject and a light surface behind a photograph changes how the
 * photograph reads.
 *
 * The detail sheet is where the private text lives, and only there: §16.1 of
 * `CATALOG_SCHEMA_V1.md` keeps a filename out of the grid so a page of two
 * hundred rows never carries two hundred filenames, and this screen fetches one
 * object's record.
 *
 * [onLock] and [onPanic] have no defaults. The viewer is a private screen
 * that draws its own chrome over the shell's, so `DISCREET_MODE.md` "The
 * panic gesture" holds here only if this screen carries the lock control,
 * and a host that does not bind both does not compile.
 *
 * [chromeVisible] belongs to the route, which keeps it in a [ViewerChrome]:
 * the Android route hides the system bars with the chrome, and the iOS route
 * hides the status bar with it.
 *
 * [projection] is the item on screen, one of [pages], and everything else
 * here is its own: [preview], [player] and [detail] are the route's for that
 * item alone. A swipe moves to the adjacent page, and [onSettled] tells the
 * route which item it landed on, [ViewerPager].
 *
 * [player] is given the padding that keeps a player's own controls between
 * the chrome rows, [VaultPlayer]. [onTouch] reports each touch on the viewer,
 * a chrome control's too, which a player counts as activity. With
 * [nativeControls], the host supplies both action rows and the always-available
 * Lock/Panic escape. Media and detail controls use its actual safe areas.
 */
@Composable
fun ViewerScreen(
    projection: ObjectProjection,
    pages: List<ObjectProjection>,
    thumbnails: Map<String, ImageBitmap>,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onSettled: (ObjectProjection) -> Unit,
    detail: ObjectDetail?,
    preview: ImageBitmap?,
    showDetail: Boolean,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onTouch: () -> Unit = {},
    onBack: () -> Unit,
    onLock: () -> Unit,
    onPanic: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEditTags: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    trashOpen: Boolean = false,
    onRestore: () -> Unit = {},
    onToggleDetail: () -> Unit,
    player: (@Composable (Modifier, PaddingValues) -> Unit)? = null,
    document: (@Composable (Modifier, PaddingValues) -> Unit)? = null,
    waveform: ByteArray? = null,
    operation: ActiveOperation? = null,
    onCancelOperation: () -> Unit = {},
    notice: Notice? = null,
    onNoticeShown: (Long) -> Unit = {},
    stillState: ViewerStillState = if (preview != null) ViewerStillState.Ready else ViewerStillState.Loading,
    onVerifyStill: (() -> Unit)? = null,
    onZoomChanged: (Boolean) -> Unit = {},
    animation: (@Composable (Modifier) -> Unit)? = null,
    nativeControls: Boolean = false,
) {
    // §25.5: the canvas and the scrims run edge to edge, and what is read or
    // pressed keeps clear of the system bars, the cutout, and the gesture
    // area. A Back drawn under the status bar is visible but cannot be
    // pressed, because the bar's window takes the touch first.
    val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    // §13.1: a tap toggles the chrome. The detectors are on the root, so a
    // tap or a drag anywhere on the viewer stays in the viewer: a root without
    // one lets the event through to the screen drawn under it. A chrome
    // control takes its own tap first. The pager takes a horizontal drag and
    // the drag detector a vertical one before the tap detector sees either,
    // so a drag is not a tap. The detector is vertical only: one that took a
    // drag in any direction would pass its slop first on a swipe that is not
    // quite level and take it from the pager.
    val toggleChrome by rememberUpdatedState(onToggleChrome)
    val touch by rememberUpdatedState(onTouch)
    // §13.1: a photo zooms and pans, [ViewerZoom]. The zoom is the item's on
    // screen, so a swipe lands on the next one at 1x. A video or a recording
    // does not zoom: its player has gestures of its own. While the photo is
    // zoomed the pager does not swipe, and one finger pans the photo instead.
    val zoom = remember(projection.id) { mutableStateOf(ViewerZoom()) }
    val zoomed by remember(zoom) { derivedStateOf { zoom.value.zoomed } }
    LaunchedEffect(zoomed) { onZoomChanged(zoomed) }
    val photo = projection.mediaKind == MEDIA_CLASS_IMAGE
    // A preserved Live Photo can have a playable video even when its still
    // format has no decoder and the catalog stores it as a file.
    val documentMode = projection.mediaKind == MEDIA_CLASS_FILE && player == null
    val visibleChrome = chromeVisible || documentMode
    val chromeFadeMillis = LocalChurMotion.current.stateDurationMillis
    val mediaDurationMillis = LocalChurMotion.current.mediaDurationMillis
    // One incoming opacity for this viewer. Page settlement and sharper previews
    // never restart it, and no outgoing bitmap or player is kept for a transition.
    val mediaOpacity = remember { Animatable(0f) }
    LaunchedEffect(mediaDurationMillis) {
        mediaOpacity.animateTo(1f, tween(mediaDurationMillis))
    }
    val still by rememberUpdatedState(preview ?: thumbnails[projection.id])
    // §13.2 and §25.5: a player's controls sit between the chrome rows and
    // clear of the cutout, so its time and its settings are not under the
    // actions. The rows keep their last height while hidden, and so do the
    // controls, which hide with them.
    var chromeTop by remember { mutableIntStateOf(0) }
    var chromeBottom by remember { mutableIntStateOf(0) }
    // UIKit supplies its actual navigation/toolbar safe areas. Only the common
    // controls need their measured rows added to the platform's system insets.
    val controlsPadding = if (nativeControls) WindowInsets.safeDrawing.asPaddingValues() else
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            .add(WindowInsets(top = chromeTop, bottom = chromeBottom))
            .asPaddingValues()
    val detailTop = controlsPadding.calculateTopPadding()
    val detailBottom = controlsPadding.calculateBottomPadding()
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(ViewerColors.canvas)
            .pointerInput(Unit) {
                // §13.4: the chrome hides after inactivity, and a touch
                // anywhere on the viewer is activity, a chrome control's too.
                // The root sees each touch first and consumes nothing.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touch()
                }
            }
            .then(if (documentMode) Modifier else Modifier.pointerInput(zoom, photo) {
                // The pager's pages fill this box, so a tap here is where it
                // lands on the photo. On a photo a tap waits out the double-tap
                // timeout before it toggles the chrome, as a platform viewer's
                // does.
                val zoomAt: (Offset) -> Unit = { tap ->
                    still?.let { zoom.value = zoom.value.doubleTapped(tap, it.size(), size.toSize()) }
                }
                detectTapGestures(onTap = { toggleChrome() }, onDoubleTap = zoomAt.takeIf { photo })
            }
            .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> change.consume() } }),
    ) {
        // The same toggle for TalkBack and VoiceOver, which activate the
        // focused element rather than tap where a finger is.
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = mediaOpacity.value }
                .then(if (documentMode) Modifier else Modifier.semantics {
                contentDescription = tileLabel(projection)
                onClick(label = if (chromeVisible) "Hide controls" else "Show controls") {
                    toggleChrome()
                    true
                }
            }),
        ) {
            ViewerPager(
                pages = pages,
                viewing = projection,
                canLoadMore = canLoadMore,
                onLoadMore = onLoadMore,
                onSettled = onSettled,
                userScrollEnabled = !zoomed && !documentMode,
                modifier = Modifier.fillMaxSize(),
            ) { page, settled ->
                // The page a swipe brings in shows the grid's thumbnail until
                // it lands; only then does the route decrypt its full still or
                // open its player. Without a thumbnail it shows the canvas, as
                // nothing is being decrypted for it.
                if (settled && documentMode) {
                    if (document != null) document(Modifier.fillMaxSize(), controlsPadding)
                    else DocumentFallback(page, detail, "This file has no internal preview.", Modifier.fillMaxSize(), controlsPadding)
                } else if (settled) {
                    ViewerMedia(preview ?: thumbnails[page.id], player, controlsPadding, waveform, zoom.takeIf { photo },
                        audio = page.mediaKind == MEDIA_CLASS_AUDIO, stillState = stillState, onVerifyStill = onVerifyStill,
                        onInteraction = { touch() }, animation = animation)
                } else {
                    thumbnails[page.id]?.let { ViewerMedia(it, player = null, controlsPadding = controlsPadding, waveform = null,
                        audio = page.mediaKind == MEDIA_CLASS_AUDIO) }
                }
            }
        }

        // §13: the chrome sits over a scrim so controls stay legible against
        // both bright and dark media, which §6.4 requires them to be tested on.
        // §22.2 gives a simple state change 120-180 ms, and a fade is also
        // what §22.3 keeps under reduced motion.
        if (!nativeControls) AnimatedVisibility(
            visible = visibleChrome,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(tween(chromeFadeMillis)),
            exit = fadeOut(tween(chromeFadeMillis)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { chromeTop = it.height }
                    .background(ViewerColors.chromeScrim)
                    .windowInsetsPadding(topInsets)
                    .padding(ChurSpacing.two),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(BackGlyph, contentDescription = "Back", tint = ViewerColors.content)
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = onToggleDetail, modifier = Modifier.padding(end = 48.dp)) {
                    Text(
                        if (showDetail) "Hide info" else "Info",
                        color = ViewerColors.content,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        // Privacy controls do not participate in chrome auto-hide or its fade.
        // A single tap must work even while video/photo controls are hidden.
        if (!nativeControls) Box(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(topInsets)
                .padding(ChurSpacing.two).background(ViewerColors.chromeScrim),
        ) {
            LockControl(onLock = onLock, onPanic = onPanic, tint = ViewerColors.content)
        }
        if (!nativeControls) AnimatedVisibility(
            visible = visibleChrome,
            modifier = Modifier.align(Alignment.BottomStart),
            enter = fadeIn(tween(chromeFadeMillis)),
            exit = fadeOut(tween(chromeFadeMillis)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { chromeBottom = it.height }
                    .background(ViewerColors.chromeScrim)
                    .windowInsetsPadding(bottomInsets)
                    .padding(ChurSpacing.two),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!trashOpen) IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (projection.favorite) FavoriteFilledGlyph else FavoriteGlyph,
                        contentDescription = if (projection.favorite) "Remove favourite" else "Favourite",
                        tint = ViewerColors.content,
                    )
                }
                if (!trashOpen) TextButton(onClick = onEditTags) {
                    Text("Tags", color = ViewerColors.content,
                        style = MaterialTheme.typography.labelLarge)
                }
                if (!trashOpen) IconButton(onClick = onExport, enabled = operation == null) {
                    Icon(ExportGlyph, contentDescription = "Export", tint = ViewerColors.content)
                }
                if (trashOpen) TextButton(onClick = onRestore) {
                    Text("Restore", color = ViewerColors.content)
                }
                IconButton(onClick = onDelete) {
                    Icon(DeleteGlyph, contentDescription = if (trashOpen) "Delete permanently" else "Move to Trash",
                        tint = ViewerColors.content)
                }
            }
        }

        if (showDetail && detail != null) {
            DetailSheet(
                detail = detail,
                projection = projection,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(top = detailTop, bottom = detailBottom)
                    .heightIn(max = (maxHeight - detailTop - detailBottom).coerceAtLeast(0.dp)),
            )
        }
        // `DESIGN.md` §26: an outcome is the shell's snackbar, drawn above
        // the progress of a running operation rather than over it.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottomInsets)
                .padding(horizontal = ChurSpacing.gutter)
                .padding(bottom = if (nativeControls) ChurSpacing.two else 88.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NoticeHost(notice, onNoticeShown)
            if (operation != null) {
                OperationProgressCard(operation = operation, onCancel = onCancelOperation)
            }
        }
    }
}

/**
 * One page's media: its player, its waveform over the player, or its still,
 * which a pinch zooms when the page is given a [zoom].
 */
@Composable
private fun ViewerMedia(
    preview: ImageBitmap?,
    player: (@Composable (Modifier, PaddingValues) -> Unit)?,
    controlsPadding: PaddingValues,
    waveform: ByteArray?,
    zoom: MutableState<ViewerZoom>? = null,
    audio: Boolean = false,
    stillState: ViewerStillState = ViewerStillState.Ready,
    onVerifyStill: (() -> Unit)? = null,
    onInteraction: () -> Unit = {},
    animation: (@Composable (Modifier) -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (player != null) {
            // A video or a recording is played rather than shown. The player is
            // a slot rather than a call, because it is the one part of this
            // screen that reads a repository, and this file's rule is that a
            // screen is a pure function of a state value.
            player(Modifier.fillMaxSize(), controlsPadding)
            // The platform keeps audio artwork beneath its transport controls.
            // A waveform supplements the cover in the upper safe area, away
            // from the player's central Play/Pause and lower seek controls.
            if (audio && waveform != null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(controlsPadding).padding(top = ChurSpacing.two),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    WaveformStrip(record = waveform, color = ViewerColors.content,
                        modifier = Modifier.background(ViewerColors.chromeScrim))
                }
            }
        } else if (audio) {
            AudioArtwork(preview, Modifier.fillMaxSize(), controlsPadding)
        } else if (preview != null && zoom != null) {
            ZoomableStill(preview, zoom, onInteraction, animation)
        } else if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (stillState == ViewerStillState.Loading) "Decrypting" else "Preview unavailable",
                        color = ViewerColors.content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (stillState == ViewerStillState.Unavailable && onVerifyStill != null) {
                        TextButton(onClick = onVerifyStill) {
                            Text("Verify files", color = ViewerColors.content)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A photo that a pinch zooms and, while it is zoomed, one finger pans,
 * [ViewerZoom]. The detector is on the page, inside the pager, so it sees a
 * two-finger gesture before the pager does. At 1x one finger is not a pan,
 * and the pager keeps it as a swipe.
 */
@Composable
private fun ZoomableStill(
    still: ImageBitmap,
    zoom: MutableState<ViewerZoom>,
    onInteraction: () -> Unit,
    animation: (@Composable (Modifier) -> Unit)? = null,
) {
    var viewer by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(still) {
        zoom.value = zoom.value.normalized(still.size(), viewer)
    }
    fun update(next: ViewerZoom): Boolean {
        if (next == zoom.value) return false
        zoom.value = next
        onInteraction()
        return true
    }
    fun scaleTo(scale: Float): Boolean = update(zoom.value.transformed(
        viewer.center, scale.coerceIn(1f, MAX_ZOOM) / zoom.value.scale, Offset.Zero, still.size(), viewer))
    fun pan(direction: Offset): Boolean = update(zoom.value.transformed(
        viewer.center, 1f, Offset(direction.x * viewer.width / 4, direction.y * viewer.height / 4), still.size(), viewer))
    val transform = rememberTransformableState { centroid, zoomChange, pan, _ ->
        zoom.value = zoom.value.transformed(centroid, zoomChange, pan, still.size(), viewer)
    }
    val imageModifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                viewer = it.toSize()
                zoom.value = zoom.value.normalized(still.size(), viewer)
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Equals, Key.Plus -> scaleTo(zoom.value.scale + 0.5f)
                    Key.Minus -> scaleTo(zoom.value.scale - 0.5f)
                    Key.Zero, Key.MoveHome -> update(ViewerZoom())
                    Key.DirectionLeft -> pan(Offset(1f, 0f))
                    Key.DirectionRight -> pan(Offset(-1f, 0f))
                    Key.DirectionUp -> pan(Offset(0f, 1f))
                    Key.DirectionDown -> pan(Offset(0f, -1f))
                    else -> false
                }
            }
            .focusable()
            .semantics {
                contentDescription = "Photo"
                stateDescription = "Zoom ${(zoom.value.scale * 100).toInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(zoom.value.scale, 1f..MAX_ZOOM)
                setProgress { scaleTo(it) }
                customActions = buildList {
                    if (zoom.value.scale < MAX_ZOOM) add(CustomAccessibilityAction("Zoom in") { scaleTo(zoom.value.scale + 0.5f) })
                    if (zoom.value.zoomed) {
                        add(CustomAccessibilityAction("Zoom out") { scaleTo(zoom.value.scale - 0.5f) })
                        add(CustomAccessibilityAction("Reset zoom") { update(ViewerZoom()) })
                        add(CustomAccessibilityAction("Pan left") { pan(Offset(1f, 0f)) })
                        add(CustomAccessibilityAction("Pan right") { pan(Offset(-1f, 0f)) })
                        add(CustomAccessibilityAction("Pan up") { pan(Offset(0f, 1f)) })
                        add(CustomAccessibilityAction("Pan down") { pan(Offset(0f, -1f)) })
                    }
                }
            }
            .transformable(transform, canPan = { zoom.value.zoomed })
            // Read in the layer, so a pinch redraws the photo and composes
            // nothing.
            .graphicsLayer {
                val now = zoom.value
                scaleX = now.scale
                scaleY = now.scale
                translationX = now.offset.x
                translationY = now.offset.y
            }
    if (animation != null) animation(imageModifier)
    else Image(bitmap = still, contentDescription = null, contentScale = ContentScale.Fit, modifier = imageModifier)
}

private fun ImageBitmap.size() = Size(width.toFloat(), height.toFloat())

/**
 * How far the photo on screen is zoomed, `DESIGN.md` §13.1: zoom and pan
 * follow platform expectations. A pinch zooms about the fingers, from 1x to
 * [MAX_ZOOM]. A double tap zooms to [DOUBLE_TAP_ZOOM] with the tapped point
 * kept under the finger, and a double tap on a zoomed photo returns it to 1x.
 * While the photo is [zoomed], one finger pans it and the pager does not
 * swipe.
 *
 * [offset] moves the photo from the centre of the viewer after [scale]. The
 * viewer fits the photo and centres it, so a pan stops where an edge of the
 * photo meets the edge of the viewer, and along a side that the photo does
 * not fill, the photo stays centred. `still` is the photo's size in pixels
 * and `viewer` is the viewer's.
 */
@Immutable
internal data class ViewerZoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    val zoomed: Boolean get() = scale > 1f

    fun transformed(centroid: Offset, zoomChange: Float, pan: Offset, still: Size, viewer: Size): ViewerZoom {
        val next = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        // The point of the photo under the fingers stays under them.
        val focus = centroid - viewer.center
        val moved = (offset - focus) * (next / scale) + focus + pan
        return ViewerZoom(next, moved).normalized(still, viewer)
    }

    /** Clamp again when the viewport or the decoded preview changes. */
    fun normalized(still: Size, viewer: Size): ViewerZoom {
        if (still.width <= 0f || still.height <= 0f || viewer.width <= 0f || viewer.height <= 0f ||
            !scale.isFinite() || !offset.x.isFinite() || !offset.y.isFinite()
        ) return ViewerZoom()
        val next = scale.coerceIn(1f, MAX_ZOOM)
        if (next == 1f) return ViewerZoom()
        val fit = min(viewer.width / still.width, viewer.height / still.height)
        val slackX = ((still.width * fit * next - viewer.width) / 2).coerceAtLeast(0f)
        val slackY = ((still.height * fit * next - viewer.height) / 2).coerceAtLeast(0f)
        return ViewerZoom(next, Offset(offset.x.coerceIn(-slackX, slackX), offset.y.coerceIn(-slackY, slackY)))
    }

    fun doubleTapped(tap: Offset, still: Size, viewer: Size): ViewerZoom =
        if (zoomed) ViewerZoom() else transformed(tap, DOUBLE_TAP_ZOOM, Offset.Zero, still, viewer)
}

internal const val MAX_ZOOM = 5f
internal const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Whether the viewer shows its chrome, `DESIGN.md` §13.1: a tap on the media
 * hides it, and the next tap brings it back.
 *
 * §13.4 names when chrome must not hide by itself. Nothing here hides it on a
 * timer; a player's controls hide after a time of playback with no touch, and
 * the chrome follows them, [follow]. While the route says it is pinned,
 * because the Info sheet, a dialog, a confirmation or an operation is open, it
 * stays shown and a tap leaves it as it is, and the player holds its controls
 * with it. Both hosts keep one, so they follow the same rule.
 */
@Stable
class ViewerChrome {
    private var hidden by mutableStateOf(false)

    fun visible(pinned: Boolean): Boolean = pinned || !hidden

    fun toggle(pinned: Boolean) {
        if (!pinned) hidden = !hidden
    }

    /**
     * A player's own controls showed or hid, as a tap on a video does, or
     * hid after a time of playback with no touch, [VaultPlayer]. The chrome
     * goes with them, so one tap shows or hides both, and a pinned chrome
     * stays.
     */
    fun follow(visible: Boolean, pinned: Boolean) {
        if (!pinned) hidden = !visible
    }

    /**
     * How many touches the viewer has had, [touched]. A player's controls,
     * and the chrome with them, hide after a time of playback with no touch,
     * and a touch on a chrome control that leaves the chrome as it is, as
     * Favourite does, starts that time again, [VaultPlayer].
     */
    var touches by mutableIntStateOf(0)
        private set

    fun touched() {
        touches++
    }
}

/**
 * The one place a filename, a caption, and a tag reach the screen.
 *
 * §16.1 of the catalog is the reason it is one place: a caller that fetched
 * this record per row would be defeating the rule the projection exists for.
 */
@Composable
private fun DetailSheet(
    detail: ObjectDetail,
    projection: ObjectProjection,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ViewerColors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(ChurSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(ChurSpacing.one),
    ) {
        Text(
            text = detail.filename.ifBlank { "No filename" },
            color = ViewerColors.content,
            style = MaterialTheme.typography.titleMedium,
        )
        if (detail.caption.isNotBlank()) {
            Text(
                detail.caption,
                color = ViewerColors.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = detailFacts(detail),
            color = ViewerColors.content,
            style = DiagnosticTextStyle,
        )
        Text(
            text = captureLine(detail),
            color = ViewerColors.content,
            style = MaterialTheme.typography.bodySmall,
        )
        val state = PresentedState.of(projection)
        if (state != PresentedState.ORDINARY) {
            Text(state.label, color = ViewerColors.content, style = MaterialTheme.typography.bodySmall)
        }
        if (detail.tags.isNotEmpty()) {
            Text(
                detail.tags.joinToString(", ") { it.second },
                color = ViewerColors.content,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The facts line of the detail sheet: the type, the dimensions, the length
 * of a video or a recording in the grid's "1:05" form, and the size.
 */
internal fun detailFacts(detail: ObjectDetail): String = buildList {
    add(detail.contentType)
    if (detail.width > 0 && detail.height > 0) add("${detail.width} × ${detail.height}")
    if (detail.durationMs > 0) add(durationLabel(detail.durationMs))
    add(humanSize(detail.plaintextSize))
}.joinToString("  ·  ")

/**
 * When the item was captured, in the reader's own date and time format.
 *
 * §8.1 of the catalog: a substituted capture time is not a capture time, and
 * the interface declines to present one it does not have. The viewer is a
 * private screen, so the exact time `DESIGN.md` §11.2 keeps off public
 * surfaces is shown here.
 */
internal fun captureLine(detail: ObjectDetail, format: (Long) -> String = ::formatCaptureDate): String =
    if (detail.captureTimeSubstituted) "No capture date recorded" else "Captured ${format(detail.captureTimeMs)}"

/** A date and time in milliseconds since the epoch, in the device's zone and locale. */
internal expect fun formatCaptureDate(epochMs: Long): String

/**
 * A size a person reads.
 *
 * It is deliberately coarse: the exact byte count of a private object is a
 * value `DISCREET_MODE.md` §30 would rather not have on a screenshot, and a
 * reader does not need it.
 */
fun humanSize(bytes: Long): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit += 1
    }
    return if (unit == 0) {
        "${bytes} ${units[0]}"
    } else {
        val rounded = ((value * 10).toLong()) / 10.0
        "$rounded ${units[unit]}"
    }
}
