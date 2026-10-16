package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerThumbnailWindowTest {
    private val objects = (0 until 5).map { n ->
        ObjectProjection(
            objectId = ByteArray(16) { n.toByte() }, primaryStreamId = ByteArray(16), mediaKind = MEDIA_CLASS_IMAGE,
            captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false,
            plaintextSize = 1, width = 1, height = 1, durationMs = 0,
            favorite = false, state = 1, integritySummary = 4, thumbnailReady = true,
        )
    }

    @Test
    fun the_current_page_has_only_its_immediate_loaded_neighbours() {
        assertEquals(objects.subList(1, 4), viewerThumbnailWindow(objects, objects[2].id))
        assertEquals(objects.take(2), viewerThumbnailWindow(objects, objects.first().id))
        assertEquals(objects.takeLast(2), viewerThumbnailWindow(objects, objects.last().id))
        assertEquals(listOf(objects[0]), viewerThumbnailWindow(objects.take(1), objects[0].id))
    }

    @Test
    fun a_closed_or_missing_page_requests_no_previews() {
        assertTrue(viewerThumbnailWindow(objects, null).isEmpty())
        assertTrue(viewerThumbnailWindow(objects, "missing").isEmpty())
        assertTrue(viewerThumbnailWindow(emptyList(), objects[0].id).isEmpty())
    }

    @Test
    fun a_reload_uses_the_current_page_identity_and_new_order() {
        val reordered = listOf(objects[4], objects[2], objects[0], objects[3], objects[1])
        assertEquals(reordered.take(3), viewerThumbnailWindow(reordered, objects[2].id))
    }
}
