package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerAnimationTest {
    private val photo = ObjectProjection(
        objectId = ByteArray(16), primaryStreamId = ByteArray(16), mediaKind = 1,
        captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false,
        plaintextSize = 1_024, width = 320, height = 240, durationMs = 0,
        favorite = false, state = 1, integritySummary = 4, thumbnailReady = true,
    )

    @Test fun only_animation_capable_content_types_read_the_original() {
        for (type in listOf("image/gif", "image/webp", "image/png", "image/apng", "IMAGE/GIF; charset=binary")) assertTrue(animationContentType(type))
        for (type in listOf(null, "image/jpeg", "image/heic", "image/avif", "image/tiff", "video/mp4")) assertFalse(animationContentType(type))
    }

    @Test fun only_bounded_measured_photos_can_open_an_animation_source() {
        assertTrue(animationCandidate(photo))
        assertTrue(animationCandidate(photo.copy(plaintextSize = ANIMATION_BYTES_MAX, width = 2_048, height = 2_048)))
        for (item in listOf(
            photo.copy(plaintextSize = 0), photo.copy(plaintextSize = ANIMATION_BYTES_MAX + 1),
            photo.copy(width = 0), photo.copy(height = -1), photo.copy(width = 2_049),
            photo.copy(mediaKind = 2), photo.copy(mediaKind = 3), photo.copy(integritySummary = 6),
        )) assertFalse(animationCandidate(item))
    }
}
