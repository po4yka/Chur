package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.stillSources
import dev.po4yka.chur.app.vault.originalImageCandidate
import dev.po4yka.chur.ffi.ObjectProjection
import dev.po4yka.chur.ffi.StreamKind.GRID_PREVIEW
import dev.po4yka.chur.ffi.StreamKind.ORIGINAL
import dev.po4yka.chur.ffi.StreamKind.SCREEN_PREVIEW
import dev.po4yka.chur.ffi.StreamKind.THUMBNAIL
import dev.po4yka.chur.ffi.StreamKind.VIDEO_POSTER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The viewer's still, `MEDIA_PIPELINE.md` §8: a photograph with no screen
 * preview shows its original, not the 320 px thumbnail scaled up.
 */
class ViewerStillTest {
    private val photo = ObjectProjection(
        objectId = ByteArray(16), primaryStreamId = ByteArray(16), mediaKind = 1,
        captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false,
        plaintextSize = 2L * 1024 * 1024, width = 1200, height = 1600, durationMs = 0,
        favorite = false, state = 1, integritySummary = 4, thumbnailReady = true,
    )

    @Test
    fun a_synced_image_without_derivatives_can_use_a_bounded_original() {
        assertTrue(originalImageCandidate(photo.copy(width = 2050, height = 1025, thumbnailReady = false)))
        assertFalse(originalImageCandidate(photo.copy(width = 0)))
        assertFalse(originalImageCandidate(photo.copy(height = -1)))
        assertFalse(originalImageCandidate(photo.copy(width = 16_385)))
        assertFalse(originalImageCandidate(photo.copy(width = 16_384, height = 16_384)))
        assertFalse(originalImageCandidate(photo.copy(plaintextSize = 33L * 1024 * 1024)))
        assertFalse(originalImageCandidate(photo.copy(integritySummary = 6)))
        assertFalse(originalImageCandidate(photo.copy(mediaKind = 4)))
    }

    @Test
    fun a_photograph_inside_the_preview_edge_shows_its_original() {
        assertEquals(listOf(SCREEN_PREVIEW, ORIGINAL, GRID_PREVIEW, THUMBNAIL), stillSources(photo))
        // 2048 px is the last edge with no screen preview of its own.
        assertEquals(ORIGINAL, stillSources(photo.copy(width = 2_048, height = 1_536))[1])
    }

    @Test
    fun a_large_or_unmeasured_photograph_keeps_to_its_derivatives() {
        val derivatives = listOf(SCREEN_PREVIEW, GRID_PREVIEW, THUMBNAIL)
        assertEquals(derivatives, stillSources(photo.copy(width = 4_000, height = 3_000)))
        assertEquals(derivatives, stillSources(photo.copy(width = 2_049, height = 1_000)))
        assertEquals(derivatives, stillSources(photo.copy(width = 1_500, height = 1_500, plaintextSize = 40L * 1024 * 1024)))
        assertEquals(derivatives, stillSources(photo.copy(width = 0, height = 0)))
    }

    @Test
    fun zoom_reads_a_bounded_original_before_its_screen_preview() {
        val large = photo.copy(width = 4_000, height = 3_000)
        assertEquals(listOf(ORIGINAL, SCREEN_PREVIEW, GRID_PREVIEW, THUMBNAIL), stillSources(large, highResolution = true))
        val fallback = listOf(SCREEN_PREVIEW, GRID_PREVIEW, THUMBNAIL)
        assertEquals(fallback, stillSources(large.copy(plaintextSize = 33L * 1024 * 1024), highResolution = true))
        assertEquals(fallback, stillSources(large.copy(integritySummary = 6), highResolution = true))
        assertEquals(fallback, stillSources(large.copy(width = 16_385), highResolution = true))
        assertEquals(fallback, stillSources(large.copy(width = 16_384, height = 16_384), highResolution = true))
    }

    @Test
    fun a_quarantined_photograph_never_reads_its_original() {
        // CATALOG_SCHEMA_V1.md §5.1: a quarantined container is not presented or retried.
        assertEquals(listOf(SCREEN_PREVIEW, GRID_PREVIEW, THUMBNAIL), stillSources(photo.copy(integritySummary = 6)))
        // A trashed row (state 6) is not ORDINARY, but the trash viewer still shows the original.
        assertEquals(ORIGINAL, stillSources(photo.copy(state = 6))[1])
    }

    @Test
    fun a_video_starts_from_its_poster_and_audio_uses_only_its_optional_artwork_derivatives() {
        assertEquals(listOf(VIDEO_POSTER, THUMBNAIL), stillSources(photo.copy(mediaKind = 2)))
        assertEquals(listOf(SCREEN_PREVIEW, GRID_PREVIEW, THUMBNAIL), stillSources(photo.copy(mediaKind = 3, width = 0, height = 0)))
        // Even misleading image dimensions must not send an audio original to an image decoder.
        assertEquals(listOf(SCREEN_PREVIEW, GRID_PREVIEW, THUMBNAIL), stillSources(photo.copy(mediaKind = 3)))
    }
}
