package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.ObjectProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `DESIGN.md` §13.1: what a swipe in the viewer moves through. */
class ViewerPagerTest {
    private fun item(n: Int) = ObjectProjection(
        objectId = ByteArray(16) { n.toByte() }, primaryStreamId = ByteArray(16), mediaKind = MEDIA_CLASS_IMAGE,
        captureTimeMs = 0, importTimeMs = 0, captureTimeSubstituted = false,
        plaintextSize = 1, width = 1, height = 1, durationMs = 0,
        favorite = false, state = 1, integritySummary = 4, thumbnailReady = true,
    )

    private val scope = (0 until 5).map(::item)

    @Test
    fun a_swipe_moves_through_the_scope_the_viewer_was_opened_from() {
        assertSame(scope, viewerPages(scope, scope[2]))
        // A reload gives new rows for the same items.
        assertSame(scope, viewerPages(scope, item(2)))
    }

    @Test
    fun an_item_the_page_no_longer_holds_stays_on_screen_alone() {
        // Removed from Favorites, or a new query that has not answered yet.
        assertEquals(listOf(item(9)), viewerPages(scope, item(9)))
        assertEquals(listOf(scope[1]), viewerPages(emptyList(), scope[1]))
    }

    @Test
    fun the_next_page_is_asked_for_near_the_end_of_what_is_loaded() {
        assertFalse(wantsNextPage(settledPage = 1, pageCount = 5, canLoadMore = true))
        assertTrue(wantsNextPage(settledPage = 2, pageCount = 5, canLoadMore = true))
        assertTrue(wantsNextPage(settledPage = 4, pageCount = 5, canLoadMore = true))
        assertFalse(wantsNextPage(settledPage = 4, pageCount = 5, canLoadMore = false), "the scope has no more")
    }
}
