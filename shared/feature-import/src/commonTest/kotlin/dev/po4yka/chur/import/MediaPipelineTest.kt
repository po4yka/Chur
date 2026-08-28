package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaPipelineTest {
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

    /**
     * Audio asked for nothing until Phase 2, because §6's waveform had no
     * format to be produced in. `WaveformTest` covers the record; this is the
     * one line that decides audio gets one at all.
     */
    @Test
    fun audio_asks_for_its_waveform() {
        assertEquals(
            listOf(StreamKind.AUDIO_WAVEFORM),
            requiredDerivatives(probe(mediaClass = MediaBounds.CLASS_AUDIO)),
        )
    }
}
