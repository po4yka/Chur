package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.mediaDetails
import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals("0 B", mediaDetails(item.copy(plaintextSize = 0, width = 0, height = 0,
            durationMs = 0, favorite = false)))
    }
}
