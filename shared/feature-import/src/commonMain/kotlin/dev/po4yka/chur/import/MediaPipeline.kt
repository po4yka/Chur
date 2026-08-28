package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind

/**
 * The platform half of `docs/interop/MEDIA_PIPELINE.md`.
 *
 * §1 splits the work and this module is the platform side of that split:
 * provider interaction, codec probing and decoding, and image resizing. It
 * produces bounded facts and bytes; identity, encryption, persistence, and
 * integrity stay in Rust and are never touched here.
 *
 * §3 is the reason every field below is called a hint. A provider's length and
 * type are untrusted, and Rust re-derives the authenticated size from the final
 * commit record; what this module reports is what the picker said, bounded and
 * validated for shape, and nothing more.
 */

/** What a picker handed over, `MEDIA_PIPELINE.md` §3. */
data class PickedMedia(
    /**
     * An open readable descriptor.
     *
     * §13 of `docs/interop/FFI_CONTRACT.md` has Rust duplicate it, so the
     * caller closes its own on its own schedule; [close] is that.
     */
    val descriptor: Int,
    /** Whether the source can be re-read from an offset. */
    val seekable: Boolean,
    /** The length the provider reported, when it reported one. */
    val knownLength: Long?,
    /** The provider's media-type hint, untrusted. */
    val contentTypeHint: String,
    /** The provider's filename, if any. */
    val originalFilename: String?,
    /** The capture time the provider reported, if any. */
    val captureTimeMs: Long?,
    /**
     * The platform's own reference to the source, which only its codec reads.
     *
     * It is opaque here on purpose. Android's is a content `Uri` and iOS's is
     * an `NSURL`, and a common type that named either would put a source path
     * where `docs/security/PLAINTEXT_LIFECYCLE.md` §4 does not want one: in a
     * value the interface layer holds. Nothing outside a [MediaCodec] reads it.
     */
    val platformHandle: Any?,
    /** Releases the platform resources behind the descriptor. */
    val close: () -> Unit,
)

/** The canonical media facts a probe produced, §4. */
data class ProbedMedia(
    /** The canonical media class of `CANONICAL_ENCODING_V1.md` §15.4. */
    val mediaClass: Int,
    /** Pixel width after orientation normalization, zero when there is none. */
    val width: Int,
    /** Pixel height after orientation normalization, zero when there is none. */
    val height: Int,
    /** Duration in milliseconds, zero when the class has none. */
    val durationMs: Long,
    /** The validated lowercase IANA media type. */
    val contentType: String,
)

/** One derivative the platform produced, §6. */
data class Derivative(
    /** The kind. */
    val kind: StreamKind,
    /**
     * The encoded bytes.
     *
     * For an image kind they are baseline JPEG at the §12 quality for that
     * kind. For [StreamKind.AUDIO_WAVEFORM] they are the §6.1 record, and
     * [width] and [height] are both zero because it carries no pixels.
     */
    val bytes: ByteArray,
    /** The width of the encoded image. */
    val width: Int,
    /** The height of the encoded image. */
    val height: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is Derivative && kind == other.kind && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = kind.hashCode() * 31 + bytes.contentHashCode()
}

/**
 * The bounds of `MEDIA_PIPELINE.md` §12, checked before any decode.
 *
 * §12 says a source above either still-image bound is rejected *before*
 * decode, which is the point: a decoder handed a 60000 by 60000 image is the
 * denial of service, not the pixel count afterwards.
 */
object MediaBounds {
    /** Largest accepted still-image edge. */
    const val IMAGE_EDGE_MAX = 16_384

    /** Largest accepted still-image area. */
    const val IMAGE_AREA_MAX = 67_108_864L

    /** Largest accepted video width. */
    const val VIDEO_WIDTH_MAX = 7_680

    /** Largest accepted video height. */
    const val VIDEO_HEIGHT_MAX = 4_320

    /** Largest accepted duration, four hours. */
    const val DURATION_MS_MAX = 14_400_000L

    /** The long-edge target of one derivative kind, §12. */
    fun longEdge(kind: StreamKind): Int? = when (kind) {
        StreamKind.THUMBNAIL -> 320
        StreamKind.GRID_PREVIEW -> 640
        StreamKind.SCREEN_PREVIEW -> 2_048
        StreamKind.VIDEO_POSTER -> 2_048
        // A waveform is a data record rather than a picture: §6 lists it beside
        // the OCR and embedding records, and §12 gives it no long edge.
        StreamKind.AUDIO_WAVEFORM -> null
        StreamKind.ORIGINAL -> null
    }

    /** The JPEG quality of one derivative kind, §12. */
    fun quality(kind: StreamKind): Int = when (kind) {
        StreamKind.THUMBNAIL -> 80
        StreamKind.GRID_PREVIEW -> 82
        else -> 85
    }

    /**
     * Whether a probed source is inside the bounds.
     *
     * It returns a reason rather than a boolean, because the caller has to tell
     * the user which bound was exceeded and §13 keeps "resource limit
     * exceeded" distinct from "unsupported codec".
     */
    fun check(probe: ProbedMedia): String? = when {
        probe.mediaClass == CLASS_IMAGE && maxOf(probe.width, probe.height) > IMAGE_EDGE_MAX ->
            "The image is larger than Chur can import."
        probe.mediaClass == CLASS_IMAGE &&
            probe.width.toLong() * probe.height.toLong() > IMAGE_AREA_MAX ->
            "The image has more pixels than Chur can import."
        probe.mediaClass == CLASS_VIDEO &&
            (probe.width > VIDEO_WIDTH_MAX || probe.height > VIDEO_HEIGHT_MAX) ->
            "The video is larger than Chur can import."
        probe.durationMs > DURATION_MS_MAX ->
            "The recording is longer than four hours."
        else -> null
    }

    /** `media_class` of a still image. */
    const val CLASS_IMAGE = 1

    /** `media_class` of a video. */
    const val CLASS_VIDEO = 2

    /** `media_class` of audio. */
    const val CLASS_AUDIO = 3

    /** `media_class` of an opaque object. */
    const val CLASS_OPAQUE = 4

    /**
     * The size a derivative of one source should be.
     *
     * The long edge is scaled to the target and the short edge follows, so
     * orientation is preserved and nothing is cropped: §11 requires the
     * generator to define orientation and this is that definition. A source
     * already inside the target is not enlarged, because enlarging spends bytes
     * and adds nothing.
     */
    fun targetSize(kind: StreamKind, width: Int, height: Int): Pair<Int, Int>? {
        val edge = longEdge(kind) ?: return null
        if (width <= 0 || height <= 0) return null
        val longest = maxOf(width, height)
        if (longest <= edge) return width to height
        val scale = edge.toDouble() / longest
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return scaledWidth to scaledHeight
    }
}

/**
 * The platform's codec work, §1.
 *
 * `docs/interop/MEDIA_PIPELINE.md` §11 says pixel-identical cross-platform
 * thumbnails may be impractical and requires the generator profile to be
 * declared rather than the output matched. That is why this interface returns
 * bytes and a size and makes no promise about which bytes.
 */
interface MediaCodec {
    /**
     * Probes a source without decoding it whole, §2.
     *
     * It returns `null` when the platform cannot identify the source, which
     * §13 distinguishes from a malformed one: the import may still proceed as
     * an opaque object.
     */
    fun probe(media: PickedMedia): ProbedMedia?

    /**
     * Decodes, resizes, and encodes one derivative, §6.
     *
     * It returns `null` when the source has no decodable image, which §13 says
     * must not commit a catalog entry claiming the derivative exists.
     */
    fun derive(media: PickedMedia, probe: ProbedMedia, kind: StreamKind): Derivative?
}

/**
 * The derivatives one probed source needs, §6 and §8.
 *
 * The thumbnail is always generated, because §8 has the timeline read
 * thumbnails and reading originals there would defeat the point. A preview is
 * generated only when the source is larger than the target: a photograph
 * already inside 2048px is its own screen preview, and a second container for a
 * copy of it spends a key and a file for nothing.
 */
fun requiredDerivatives(probe: ProbedMedia): List<StreamKind> = when (probe.mediaClass) {
    MediaBounds.CLASS_IMAGE -> buildList {
        add(StreamKind.THUMBNAIL)
        if (maxOf(probe.width, probe.height) > 640) add(StreamKind.GRID_PREVIEW)
        if (maxOf(probe.width, probe.height) > 2_048) add(StreamKind.SCREEN_PREVIEW)
    }
    // A video needs its poster at every resolution. The poster is the still the
    // viewer shows before playback starts, so a 1080p video that is already
    // inside the 2048 px target has no still at all until one is generated.
    MediaBounds.CLASS_VIDEO -> listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER)
    MediaBounds.CLASS_AUDIO -> listOf(StreamKind.AUDIO_WAVEFORM)
    else -> emptyList()
}
