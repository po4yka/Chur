package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.MediaKindFilter
import dev.po4yka.chur.app.vault.VaultDestination
import dev.po4yka.chur.app.vault.browseQuery
import dev.po4yka.chur.app.vault.sortChoices
import dev.po4yka.chur.app.vault.toggleKindFilter
import dev.po4yka.chur.app.vault.sortLabel
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.TagSummary
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.QuerySort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaBrowseControlsTest {
    @Test
    fun kind_chips_form_a_catalog_bitmask_and_all_resets_it() {
        val photos = toggleKindFilter(0, MediaKindFilter.PHOTOS)
        val both = toggleKindFilter(photos, MediaKindFilter.VIDEOS)
        assertEquals(1, photos)
        assertEquals(3, both)
        assertEquals(2, toggleKindFilter(both, MediaKindFilter.PHOTOS))
        assertEquals(0, toggleKindFilter(photos, MediaKindFilter.PHOTOS))
        assertEquals(0, toggleKindFilter(7, MediaKindFilter.FILES))
    }

    @Test
    fun query_keeps_sort_and_kind_filter_inside_each_scope() {
        val album = AlbumSummary(ByteArray(16) { 7 }, 0, "Album")
        val albumQuery = browseQuery(VaultDestination.ALBUMS, album, null, false, false,
            "", QuerySort.IMPORT_DESC, QuerySort.ALBUM_MANUAL, 3)!!
        assertEquals(QueryScope.ALBUM, albumQuery.scope)
        assertEquals(QuerySort.ALBUM_MANUAL, albumQuery.sort)
        assertEquals(3, albumQuery.kinds)
        assertTrue(albumQuery.scopeId!!.contentEquals(album.albumId))

        val search = browseQuery(VaultDestination.SEARCH, null, null, false, false,
            "  kittens  ", QuerySort.CAPTURE_ASC, QuerySort.ALBUM_MANUAL, 4)!!
        assertEquals(QueryScope.SEARCH, search.scope)
        assertEquals(QuerySort.CAPTURE_ASC, search.sort)
        assertEquals(4, search.kinds)
        assertEquals("kittens", search.terms)
        assertNull(browseQuery(VaultDestination.SEARCH, null, null, false, false,
            "   ", QuerySort.CAPTURE_DESC, QuerySort.ALBUM_MANUAL, 0))
        assertTrue(QuerySort.ALBUM_MANUAL !in sortChoices(album = false))

        val tag = TagSummary(ByteArray(16) { 8 }, "Trip")
        val tagged = browseQuery(VaultDestination.LIBRARY, null, tag, true, false,
            "", QuerySort.IMPORT_DESC, QuerySort.ALBUM_MANUAL, 2)!!
        assertEquals(QueryScope.TAG, tagged.scope)
        assertTrue(tagged.scopeId!!.contentEquals(tag.tagId))
        assertEquals(QuerySort.IMPORT_DESC, tagged.sort)
        assertEquals(2, tagged.kinds)
        assertEquals(QueryScope.TRASH, browseQuery(VaultDestination.LIBRARY, null, null,
            false, true, "", QuerySort.CAPTURE_ASC, QuerySort.ALBUM_MANUAL, 0)!!.scope)
        assertEquals("Oldest deleted", sortLabel(QuerySort.CAPTURE_ASC, trash = true))
    }
}
