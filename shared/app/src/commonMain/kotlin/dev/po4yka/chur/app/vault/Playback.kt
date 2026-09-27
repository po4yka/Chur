package dev.po4yka.chur.app.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.PlayGlyph
import dev.po4yka.chur.app.theme.ViewerColors
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.vault.VaultRepository

/**
 * What a player needs to open one object, `MEDIA_PIPELINE.md` §9.
 *
 * §9 fixes the exchange: the player asks for plaintext ranges and Rust
 * validates the session and the reader, resolves the encrypted chunks,
 * authenticates and decrypts whole chunks, copies the requested range, and
 * reports the verified range or end of stream. Nothing in this value is a
 * container, a key, or a path; the repository holds the one reader lease and
 * the platform player holds nothing else.
 *
 * [contentType] and [plaintextSize] come from
 * `chur_object_reader_content_info`, which `FFI_CONTRACT.md` §6.1 sources from
 * authenticated canonical metadata rather than from the provider hint §3 of the
 * pipeline classifies as untrusted.
 */
class PlaybackSource(
    internal val vault: VaultRepository,
    internal val objectId: ByteArray,
    /** The lowercase IANA media type the object was imported as. */
    val contentType: String,
    /** The authenticated plaintext size. */
    val plaintextSize: Long,
    /** The stream to play. Video and audio both play their original. */
    internal val kind: StreamKind = StreamKind.ORIGINAL,
) {
    /** True when the type names something a player can open. */
    val playable: Boolean
        get() = contentType.startsWith("video/") || contentType.startsWith("audio/")
}

/**
 * Renders the platform's player over a vault-backed source.
 *
 * The two implementations differ in everything except the contract above:
 * Android drives Media3 through a `DataSource`, and iOS drives `AVPlayer`
 * through an `AVAssetResourceLoaderDelegate`. Both call the same repository
 * lease, and neither can reach a byte the reader did not authenticate.
 *
 * `FFI_CONTRACT.md` §6.1 forbids attaching a reader on an incomplete object to
 * a player, because a player given a length treats a later failure as a
 * transport error and retries forever. Each implementation checks
 * `ContentInfo.complete` before it publishes a length.
 *
 * [poster] is the still the viewer already shows for the object. It covers
 * the player until the player has a frame of its own, so a video opens on its
 * poster frame and not on a black surface, `DESIGN.md` §6.2.
 *
 * The rest is for a player that draws its own controls, `DESIGN.md` §13.2:
 * they show while [controlsVisible], which is the viewer's chrome, and they
 * stay inside [controlsPadding], clear of the chrome rows and the display
 * cutout, §25.5. While the poster covers the player, its controls are off, so
 * nothing under the poster can be tapped, focused or read, and a tap there
 * reaches the viewer and shows or hides the chrome, §13.1. A tap that shows
 * or hides them, and their hide after a time of playback with no touch,
 * report through [onControlsVisibleChange], so the chrome goes with them: the
 * chrome auto-hide of §13.4. [touches] counts the touches on the whole
 * viewer, and each new one starts that time again, so a tap on a chrome
 * control such as Favourite is activity too. They do not hide by themselves
 * while [controlsPinned], which is the route's pinned chrome (the Info sheet,
 * a dialog, an operation), while a screen reader explores the screen, or
 * while the keyboard moves the focus, §13.4 and §23.3. Until the player first
 * plays or fails, they also do not show by themselves while the chrome is
 * hidden, so a swipe onto a paused player does not bring back a chrome the
 * user hid: a swipe is not a tap, §13.1. After that, an end, a pause or a
 * failure shows them and reports it, so the chrome comes back with them. A
 * failure under the poster shows them when the press on the poster's Play
 * removes it.
 */
@Composable
expect fun VaultPlayer(
    source: PlaybackSource,
    modifier: Modifier,
    poster: ImageBitmap?,
    controlsVisible: Boolean,
    controlsPinned: Boolean,
    onControlsVisibleChange: (Boolean) -> Unit,
    controlsPadding: PaddingValues,
    touches: Int,
)

/**
 * A video's poster frame over its player, [VaultPlayer], fitted to the
 * black canvas as the viewer fits a photo, `DESIGN.md` §13.1.
 *
 * With [onPlay] it carries a play control, because the player's own are off
 * under it, and playback starts on that press. A player that starts by
 * itself passes none.
 */
@Composable
internal fun PlayerPoster(poster: ImageBitmap, onPlay: (() -> Unit)?) {
    Box(
        modifier = Modifier.fillMaxSize().background(ViewerColors.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = poster,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (onPlay != null) {
            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(64.dp).background(ViewerColors.chromeScrim, CircleShape),
            ) {
                Icon(PlayGlyph, contentDescription = "Play", tint = ViewerColors.content, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * The playback source for one object, or `null` when it has none.
 *
 * One rule for both hosts. The media class decides, not the file extension and
 * not the provider's hint: `CANONICAL_ENCODING_V1.md` §15.4 allocates `0x02`
 * for video and `0x03` for audio, and those are the two an object container can
 * be played from. The content type comes from the detail record, which
 * `MEDIA_PIPELINE.md` §4 makes the canonical metadata Rust validated at import.
 */
fun playbackFor(
    vault: VaultRepository,
    objectId: ByteArray,
    mediaKind: Int,
    detail: ObjectDetail?,
): PlaybackSource? {
    if (mediaKind != MEDIA_CLASS_VIDEO && mediaKind != MEDIA_CLASS_AUDIO) return null
    val record = detail ?: return null
    val source = PlaybackSource(vault, objectId, record.contentType, record.plaintextSize)
    return if (source.playable) source else null
}

/** `media_class` of an image, `CANONICAL_ENCODING_V1.md` §15.4. */
const val MEDIA_CLASS_IMAGE = 1

/** `media_class` of a video, `CANONICAL_ENCODING_V1.md` §15.4. */
const val MEDIA_CLASS_VIDEO = 2

/** `media_class` of audio, §15.4. */
const val MEDIA_CLASS_AUDIO = 3
