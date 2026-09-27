package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.captureLine
import dev.po4yka.chur.app.vault.detailFacts
import dev.po4yka.chur.app.vault.mediaDetails
import dev.po4yka.chur.app.vault.rowLabel
import dev.po4yka.chur.app.vault.tileLabel
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MediaDetailsTest {
    @Test
    fun list_metadata_handles_short_media_and_favorites() {
        val item = ObjectProjection(
            objectId = ByteArray(16), primaryStreamId = ByteArray(16), mediaKind = 2,
            captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false,
            plaintextSize = 1_572_864, width = 1920, height = 1080, durationMs = 65_000,
            favorite = true, state = 1, integritySummary = 4, thumbnailReady = false,
        )
        assertEquals("1:05 · 1920×1080 · 1 MB · Favorite", mediaDetails(item))
        // A row speaks every fact its details line shows, in words.
        assertEquals("Video, 1 minute 5 seconds, Favorite, 1920 by 1080, 1 MB", rowLabel(item))
        assertEquals("Audio, 0 B", rowLabel(item.copy(mediaKind = 3, plaintextSize = 0, width = 0, height = 0,
            durationMs = 0, favorite = false)))
        assertEquals("0 B", mediaDetails(item.copy(plaintextSize = 0, width = 0, height = 0,
            durationMs = 0, favorite = false)))
    }

    /** `DESIGN.md` §23.2: a tile is spoken as its kind, its length in words, and its state. */
    @Test
    fun a_tile_speaks_its_kind_length_favorite_and_state_and_nothing_private() {
        val photo = ObjectProjection(
            objectId = ByteArray(16) { (0xa0 + it).toByte() }, primaryStreamId = ByteArray(16), mediaKind = 1,
            captureTimeMs = 1_786_000_000_000, importTimeMs = 0, captureTimeSubstituted = false,
            plaintextSize = 319_488, width = 1200, height = 1600, durationMs = 0,
            favorite = false, state = 1, integritySummary = 4, thumbnailReady = true,
        )
        val labels = listOf(
            "Photo" to photo,
            "Video, 2 minutes 18 seconds, Favorite" to photo.copy(mediaKind = 2, durationMs = 138_000, favorite = true),
            "Audio, 1 minute" to photo.copy(mediaKind = 3, durationMs = 60_400),
            "Audio, 1 second" to photo.copy(mediaKind = 3, durationMs = 1_000),
            "Audio, less than 1 second" to photo.copy(mediaKind = 3, durationMs = 400),
            "Photo, Verification recommended" to photo.copy(integritySummary = 1),
            "Photo, Corrupt" to photo.copy(state = 4),
        )
        labels.forEach { (expected, item) ->
            val label = tileLabel(item)
            assertEquals(expected, label)
            // §23.2 do-not-announce: no object ID, not even a part of it.
            assertFalse(Regex("[0-9a-f]{6}").containsMatchIn(label), label)
        }
    }

    /**
     * The viewer's Info names the capture date it has, §8.1 of the catalog,
     * and never one that was substituted, and it gives a video its length.
     */
    @Test
    fun info_shows_the_capture_date_and_the_length() {
        val video = ObjectDetail(
            captureTimeMs = 1_556_712_000_000, importTimeMs = 1_786_000_000_000, captureTimeSubstituted = false,
            width = 1920, height = 1080, durationMs = 65_000, plaintextSize = 12_345_678,
            contentType = "video/mp4", filename = "", caption = "", tags = emptyList(),
        )
        val format = { ms: Long -> "at $ms" }
        assertEquals("Captured at 1556712000000", captureLine(video, format))
        assertEquals("No capture date recorded", captureLine(video.copy(captureTimeSubstituted = true), format))
        assertEquals("video/mp4  ·  1920 × 1080  ·  1:05  ·  12.3 MB", detailFacts(video))
        assertEquals("image/jpeg  ·  319.4 kB",
            detailFacts(video.copy(contentType = "image/jpeg", width = 0, height = 0, durationMs = 0,
                plaintextSize = 319_488)))
    }
}
