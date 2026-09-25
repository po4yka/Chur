package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.canSaveToPhotos
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportOptionsTest {
    @Test
    fun photos_is_only_offered_for_images_and_videos() {
        assertTrue(canSaveToPhotos(1))
        assertTrue(canSaveToPhotos(2))
        assertFalse(canSaveToPhotos(3))
        assertFalse(canSaveToPhotos(0))
    }
}
