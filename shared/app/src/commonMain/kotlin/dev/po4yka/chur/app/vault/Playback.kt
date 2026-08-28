package dev.po4yka.chur.app.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 */
@Composable
expect fun VaultPlayer(source: PlaybackSource, modifier: Modifier)

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

/** `media_class` of a video, `CANONICAL_ENCODING_V1.md` §15.4. */
const val MEDIA_CLASS_VIDEO = 2

/** `media_class` of audio, §15.4. */
const val MEDIA_CLASS_AUDIO = 3
