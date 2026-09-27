package dev.po4yka.chur.app.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    player: (@Composable (Modifier) -> Unit)? = null,
    waveform: ByteArray? = null,
    operation: ActiveOperation? = null,
    onCancelOperation: () -> Unit = {},
    notice: Notice? = null,
    onNoticeShown: (Long) -> Unit = {},
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
    // §13.1: a photo zooms and pans, [ViewerZoom]. The zoom is the item's on
    // screen, so a swipe lands on the next one at 1x. A video or a recording
    // does not zoom: its player has gestures of its own. While the photo is
    // zoomed the pager does not swipe, and one finger pans the photo instead.
    val zoom = remember(projection.id) { mutableStateOf(ViewerZoom()) }
    val zoomed by remember(zoom) { derivedStateOf { zoom.value.zoomed } }
    val photo = projection.mediaKind == MEDIA_CLASS_IMAGE
    val still by rememberUpdatedState(preview ?: thumbnails[projection.id])
    Box(
        modifier = Modifier.fillMaxSize().background(ViewerColors.canvas)
            .pointerInput(zoom, photo) {
                // The pager's pages fill this box, so a tap here is where it
                // lands on the photo. On a photo a tap waits out the double-tap
                // timeout before it toggles the chrome, as a platform viewer's
                // does.
                val zoomAt: (Offset) -> Unit = { tap ->
                    still?.let { zoom.value = zoom.value.doubleTapped(tap, it.size(), size.toSize()) }
                }
                detectTapGestures(onTap = { toggleChrome() }, onDoubleTap = zoomAt.takeIf { photo })
            }
            .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> change.consume() } },
    ) {
        // The same toggle for TalkBack and VoiceOver, which activate the
        // focused element rather than tap where a finger is.
        Box(
            modifier = Modifier.fillMaxSize().semantics {
                contentDescription = tileLabel(projection)
                onClick(label = if (chromeVisible) "Hide controls" else "Show controls") {
                    toggleChrome()
                    true
                }
            },
        ) {
            ViewerPager(
                pages = pages,
                viewing = projection,
                canLoadMore = canLoadMore,
                onLoadMore = onLoadMore,
                onSettled = onSettled,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page, settled ->
                // The page a swipe brings in shows the grid's thumbnail until
                // it lands; only then does the route decrypt its full still or
                // open its player. Without a thumbnail it shows the canvas, as
                // nothing is being decrypted for it.
                if (settled) {
                    ViewerMedia(preview ?: thumbnails[page.id], player, waveform, zoom.takeIf { photo })
                } else {
                    thumbnails[page.id]?.let { ViewerMedia(it, player = null, waveform = null) }
                }
            }
        }

        // §13: the chrome sits over a scrim so controls stay legible against
        // both bright and dark media, which §6.4 requires them to be tested on.
        // §22.2 gives a simple state change 120-180 ms, and a fade is also
        // what §22.3 keeps under reduced motion.
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(tween(CHROME_FADE_MS)),
            exit = fadeOut(tween(CHROME_FADE_MS)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ViewerColors.chromeScrim)
                    .windowInsetsPadding(topInsets)
                    .padding(ChurSpacing.two),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(BackGlyph, contentDescription = "Back", tint = ViewerColors.content)
                }
                Box(modifier = Modifier.weight(1f))
                LockControl(onLock = onLock, onPanic = onPanic, tint = ViewerColors.content)
                IconButton(onClick = onToggleDetail) {
                    Text(
                        if (showDetail) "Hide info" else "Info",
                        color = ViewerColors.content,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomStart),
            enter = fadeIn(tween(CHROME_FADE_MS)),
            exit = fadeOut(tween(CHROME_FADE_MS)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                if (!trashOpen) IconButton(onClick = onEditTags) {
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
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottomInsets)
                    .padding(bottom = 72.dp()),
            )
        }
        // `DESIGN.md` §26: an outcome is the shell's snackbar, drawn above
        // the progress of a running operation rather than over it.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottomInsets)
                .padding(horizontal = ChurSpacing.gutter).padding(bottom = 88.dp),
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
    player: (@Composable (Modifier) -> Unit)?,
    waveform: ByteArray?,
    zoom: MutableState<ViewerZoom>? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (player != null) {
            // A video or a recording is played rather than shown. The player is
            // a slot rather than a call, because it is the one part of this
            // screen that reads a repository, and this file's rule is that a
            // screen is a pure function of a state value.
            player(Modifier.fillMaxSize())
            // A recording has nothing to look at, so the waveform is what the
            // screen shows: `MEDIA_PIPELINE.md` §6.1 makes it a peak envelope
            // rather than a picture, which is why it can be drawn in the
            // viewer's own palette rather than baked into a second derivative.
            if (waveform != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    WaveformStrip(record = waveform, color = ViewerColors.content)
                }
            }
        } else if (preview != null && zoom != null) {
            ZoomableStill(preview, zoom)
        } else if (preview != null) {
            Image(
                bitmap = preview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Decrypting",
                    color = ViewerColors.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
private fun ZoomableStill(still: ImageBitmap, zoom: MutableState<ViewerZoom>) {
    var viewer by remember { mutableStateOf(Size.Zero) }
    val transform = rememberTransformableState { centroid, zoomChange, pan, _ ->
        zoom.value = zoom.value.transformed(centroid, zoomChange, pan, still.size(), viewer)
    }
    Image(
        bitmap = still,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewer = it.toSize() }
            .transformable(transform, canPan = { zoom.value.zoomed })
            // Read in the layer, so a pinch redraws the photo and composes
            // nothing.
            .graphicsLayer {
                val now = zoom.value
                scaleX = now.scale
                scaleY = now.scale
                translationX = now.offset.x
                translationY = now.offset.y
            },
    )
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
        val fit = min(viewer.width / still.width, viewer.height / still.height)
        val slackX = ((still.width * fit * next - viewer.width) / 2).coerceAtLeast(0f)
        val slackY = ((still.height * fit * next - viewer.height) / 2).coerceAtLeast(0f)
        return ViewerZoom(next, Offset(moved.x.coerceIn(-slackX, slackX), moved.y.coerceIn(-slackY, slackY)))
    }

    fun doubleTapped(tap: Offset, still: Size, viewer: Size): ViewerZoom =
        if (zoomed) ViewerZoom() else transformed(tap, DOUBLE_TAP_ZOOM, Offset.Zero, still, viewer)
}

internal const val MAX_ZOOM = 5f
internal const val DOUBLE_TAP_ZOOM = 2.5f

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

private const val CHROME_FADE_MS = 150

/**
 * Whether the viewer shows its chrome, `DESIGN.md` §13.1: a tap on the media
 * hides it, and the next tap brings it back.
 *
 * §13.4 names when chrome must not hide by itself. Nothing here hides it on a
 * timer, and while the route says it is pinned, because the Info sheet, a
 * dialog, a confirmation or an operation is open, it stays shown and a tap
 * leaves it as it is. Both hosts keep one, so they follow the same rule.
 */
@Stable
class ViewerChrome {
    private var hidden by mutableStateOf(false)

    fun visible(pinned: Boolean): Boolean = pinned || !hidden

    fun toggle(pinned: Boolean) {
        if (!pinned) hidden = !hidden
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
            .background(ViewerColors.chromeScrim)
            .padding(ChurSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(ChurSpacing.one),
    ) {
        Text(
            text = detail.filename.ifBlank { "No filename" },
            color = ViewerColors.content,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.caption.isNotBlank()) {
            Text(
                detail.caption,
                color = ViewerColors.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = buildString {
                append(detail.contentType)
                if (detail.width > 0 && detail.height > 0) {
                    append("  ·  ${detail.width} × ${detail.height}")
                }
                append("  ·  ${humanSize(detail.plaintextSize)}")
            },
            color = ViewerColors.content,
            style = DiagnosticTextStyle,
        )
        // §8.1 of the catalog: a substituted capture time is not a capture
        // time, and the interface declines to present one it does not have.
        Text(
            text = if (detail.captureTimeSubstituted) {
                "No capture date recorded"
            } else {
                "Captured"
            },
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
