package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPipelineTest {
    @Test
    fun explicit_files_have_no_media_bounds_or_derivatives_even_with_misleading_hints() {
        val file = PickedMedia(1, true, 1_099_511_627_776L, "image/jpeg", "video.mp4", null, null, {}, importAsFile = true)
        val facts = fileImportProbe(file)
        assertEquals(MediaBounds.CLASS_OPAQUE, facts.mediaClass)
        assertEquals(0, facts.width)
        assertEquals(0, facts.height)
        assertEquals(0L, facts.durationMs)
        assertEquals("image/jpeg", facts.contentType)
        assertNull(MediaBounds.check(facts))
        assertTrue(requiredDerivatives(facts).isEmpty())
    }

    @Test
    fun unusable_file_type_hints_fall_back_to_the_canonical_opaque_type() {
        assertEquals("application/pdf", normalizedFileContentType(" Application/PDF; charset=binary "))
        assertEquals("application/vnd.custom+zip", normalizedFileContentType("application/vnd.custom+zip"))
        for (hint in listOf("", "unknown", "image/*", "application/pdf\nheader", "text/pläin", "a/b/c", "text/" + "a".repeat(60))) {
            assertEquals("application/octet-stream", normalizedFileContentType(hint))
        }
    }

    private fun probe(
        mediaClass: Int = MediaBounds.CLASS_IMAGE,
        width: Int = 4_000,
        height: Int = 3_000,
        durationMs: Long = 0,
    ) = ProbedMedia(mediaClass, width, height, durationMs, "image/jpeg")

    @Test
    fun the_long_edges_and_qualities_are_the_table_of_section_12() {
        assertEquals(320, MediaBounds.longEdge(StreamKind.THUMBNAIL))
        assertEquals(640, MediaBounds.longEdge(StreamKind.GRID_PREVIEW))
        assertEquals(2_048, MediaBounds.longEdge(StreamKind.SCREEN_PREVIEW))
        assertEquals(2_048, MediaBounds.longEdge(StreamKind.VIDEO_POSTER))
        assertNull(MediaBounds.longEdge(StreamKind.ORIGINAL))

        assertEquals(80, MediaBounds.quality(StreamKind.THUMBNAIL))
        assertEquals(82, MediaBounds.quality(StreamKind.GRID_PREVIEW))
        assertEquals(85, MediaBounds.quality(StreamKind.SCREEN_PREVIEW))
        assertEquals(85, MediaBounds.quality(StreamKind.VIDEO_POSTER))
    }

    @Test
    fun a_derivative_preserves_orientation_and_scales_the_long_edge() {
        // Landscape.
        assertEquals(320 to 240, MediaBounds.targetSize(StreamKind.THUMBNAIL, 4_000, 3_000))
        // Portrait: the long edge is the height, so the height reaches the
        // target and the width follows.
        assertEquals(240 to 320, MediaBounds.targetSize(StreamKind.THUMBNAIL, 3_000, 4_000))
        // Square.
        assertEquals(320 to 320, MediaBounds.targetSize(StreamKind.THUMBNAIL, 1_000, 1_000))
    }

    @Test
    fun a_source_already_inside_the_target_is_not_enlarged() {
        assertEquals(100 to 80, MediaBounds.targetSize(StreamKind.THUMBNAIL, 100, 80))
    }

    @Test
    fun a_scaled_edge_is_never_zero() {
        // An extreme aspect ratio would round the short edge to zero, and a
        // zero-pixel derivative is not an image.
        val (width, height) = MediaBounds.targetSize(StreamKind.THUMBNAIL, 16_000, 3)!!
        assertEquals(320, width)
        assertTrue(height >= 1)
    }

    @Test
    fun the_bounds_of_section_12_are_checked_before_any_decode() {
        assertNull(MediaBounds.check(probe()))
        assertNotNull(MediaBounds.check(probe(width = 16_385)))
        assertNotNull(MediaBounds.check(probe(width = 16_384, height = 16_384)))
        assertNotNull(
            MediaBounds.check(probe(mediaClass = MediaBounds.CLASS_VIDEO, width = 7_681)),
        )
        assertNotNull(MediaBounds.check(probe(durationMs = 14_400_001)))
        assertNull(MediaBounds.check(probe(durationMs = 14_400_000)))
    }

    @Test
    fun video_limits_allow_both_orientations_and_keep_the_same_pixel_bound() {
        assertNull(MediaBounds.check(probe(mediaClass = MediaBounds.CLASS_VIDEO, width = 7_680, height = 4_320)))
        assertNull(MediaBounds.check(probe(mediaClass = MediaBounds.CLASS_VIDEO, width = 4_320, height = 7_680)))
        assertNotNull(MediaBounds.check(probe(mediaClass = MediaBounds.CLASS_VIDEO, width = 4_321, height = 7_680)))
        assertNotNull(MediaBounds.check(probe(mediaClass = MediaBounds.CLASS_VIDEO, width = 4_320, height = 7_681)))
    }

    @Test
    fun a_still_always_gets_a_thumbnail_and_a_preview_only_when_it_is_larger() {
        assertEquals(listOf(StreamKind.THUMBNAIL), requiredDerivatives(probe(width = 400, height = 300)))
        assertEquals(
            listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW),
            requiredDerivatives(probe(width = 1_000, height = 800)),
        )
        assertEquals(
            listOf(StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.SCREEN_PREVIEW),
            requiredDerivatives(probe(width = 4_000, height = 3_000)),
        )
    }

    @Test
    fun a_video_gets_a_thumbnail_and_a_poster_frame() {
        assertEquals(
            listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER),
            requiredDerivatives(probe(mediaClass = MediaBounds.CLASS_VIDEO, durationMs = 1_000)),
        )
    }

    @Test
    fun an_opaque_object_gets_none() {
        assertTrue(requiredDerivatives(probe(mediaClass = MediaBounds.CLASS_OPAQUE)).isEmpty())
    }

    @Test
    fun audio_keeps_its_waveform_and_attempts_optional_art_without_changing_canonical_dimensions() {
        val audio = ProbedMedia(MediaBounds.CLASS_AUDIO, 0, 0, 1_000, "audio/mpeg")
        assertEquals(
            listOf(StreamKind.AUDIO_WAVEFORM, StreamKind.THUMBNAIL, StreamKind.GRID_PREVIEW, StreamKind.SCREEN_PREVIEW),
            requiredDerivatives(audio),
        )
        assertNull(MediaBounds.check(audio))
        assertEquals(0, audio.width)
        assertEquals(0, audio.height)
    }

    @Test
    fun embedded_art_has_independent_bounds_and_never_enlarges_small_covers() {
        assertEquals(320 to 240, MediaBounds.artworkTargetSize(StreamKind.THUMBNAIL, 4_000, 3_000, 1_024))
        assertEquals(480 to 640, MediaBounds.artworkTargetSize(StreamKind.GRID_PREVIEW, 3_000, 4_000, 1_024))
        assertEquals(2_048 to 1_536, MediaBounds.artworkTargetSize(StreamKind.SCREEN_PREVIEW, 4_000, 3_000, 1_024))
        assertEquals(128 to 96, MediaBounds.artworkTargetSize(StreamKind.SCREEN_PREVIEW, 128, 96,
            MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES.toLong()))
    }

    @Test
    fun unsafe_art_is_refused_before_decode_without_refusing_its_audio_recording() {
        for ((width, height, size) in listOf(Triple(0, 10, 10L), Triple(10, -1, 10L), Triple(16_385, 1, 10L),
            Triple(16_384, 16_384, 10L), Triple(Int.MAX_VALUE, Int.MAX_VALUE, 10L), Triple(100, 100, 0L),
            Triple(100, 100, MediaBounds.AUDIO_ART_ENCODED_MAX_BYTES + 1L))) {
            assertNull(MediaBounds.artworkTargetSize(StreamKind.THUMBNAIL, width, height, size))
        }
        for (kind in listOf(StreamKind.ORIGINAL, StreamKind.AUDIO_WAVEFORM, StreamKind.VIDEO_POSTER)) {
            assertNull(MediaBounds.artworkTargetSize(kind, 320, 320, 1_024))
        }
        assertNull(MediaBounds.check(ProbedMedia(MediaBounds.CLASS_AUDIO, 0, 0, 1_000, "audio/mpeg")))
    }
}
