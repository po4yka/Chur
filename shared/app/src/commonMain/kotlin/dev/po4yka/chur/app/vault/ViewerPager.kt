package dev.po4yka.chur.app.vault

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import dev.po4yka.chur.ffi.ObjectProjection

/**
 * The items a swipe in the viewer moves through, `DESIGN.md` §13.1: the page
 * of the scope the viewer was opened from, in the grid's order.
 *
 * [viewing] is the item on screen. A reload can take it out of the page, as
 * removing it from Favorites does, and a new query empties the page until its
 * first rows arrive. The viewer then keeps it as its only page rather than
 * show whichever item took its place.
 */
fun viewerPages(objects: List<ObjectProjection>, viewing: ObjectProjection): List<ObjectProjection> =
    if (objects.any { it.id == viewing.id }) objects else listOf(viewing)

/**
 * Whether the viewer asks for the next page of the scope: it can have one, and
 * the item on screen is one of the last [LOAD_AHEAD] loaded.
 */
internal fun wantsNextPage(settledPage: Int, pageCount: Int, canLoadMore: Boolean): Boolean =
    canLoadMore && settledPage >= pageCount - LOAD_AHEAD

private const val LOAD_AHEAD = 3

/**
 * The viewer's media as a horizontal pager over [pages], `DESIGN.md` §13.1.
 *
 * Only the item on screen, [viewing], is `settled`: the route binds its
 * detail, its dialogs, its full still and its player to that one, and every
 * other page shows the thumbnail the grid already decoded.
 * `PLAINTEXT_LIFECYCLE.md` §4 decrypts only what is requested, so no page
 * beyond the one on screen is composed ahead of a swipe. A swipe from a video
 * therefore holds one reader lease, for the one player a lock has to stop,
 * `ANDROID.md` §17.3.
 *
 * Nothing here reaches saved state: `ANDROID.md` §6.3 restores no object ID
 * and no viewer or player position that can identify private media. The
 * pager's place is remembered, not saved. A lazy layout keeps the saved state
 * of each page under the page's key, the object ID, so the pager and its pages
 * get no saveable state registry; otherwise the player's view state would
 * carry the ID of the item on screen into the activity's saved state. Pages
 * are keyed by item, so a reload or a next page that moves the item on screen
 * moves the pager with it. [onSettled] answers when a swipe lands on another item, and
 * the next page of the scope is asked for through [onLoadMore] as the swipes
 * near the end of what is loaded. [userScrollEnabled] is false while the photo
 * on screen is zoomed, so one finger pans the photo rather than swipe,
 * [ViewerZoom].
 */
@Composable
internal fun ViewerPager(
    pages: List<ObjectProjection>,
    viewing: ObjectProjection,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onSettled: (ObjectProjection) -> Unit,
    userScrollEnabled: Boolean,
    modifier: Modifier = Modifier,
    page: @Composable (projection: ObjectProjection, settled: Boolean) -> Unit,
) {
    // Two rows of one item compare equal, so a state with the default policy
    // would keep the old rows after a reload that changed only a favourite.
    val latestPages by remember { mutableStateOf(pages, referentialEqualityPolicy()) }.apply { value = pages }
    val viewingId by rememberUpdatedState(viewing.id)
    val settle by rememberUpdatedState(onSettled)
    val moreToLoad by rememberUpdatedState(canLoadMore)
    val loadMore by rememberUpdatedState(onLoadMore)
    val state = remember {
        PagerState(currentPage = pages.indexOfFirst { it.id == viewing.id }.coerceAtLeast(0)) { latestPages.size }
    }
    LaunchedEffect(state) {
        // The index alone is watched. A reload changes the pages before the
        // pager moves to the item's new index, and the item at the old index
        // in between is not the one on screen.
        snapshotFlow { state.settledPage }.collect { index ->
            latestPages.getOrNull(index)?.takeIf { it.id != viewingId }?.let(settle)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { wantsNextPage(state.settledPage, latestPages.size, moreToLoad) }
            .collect { wanted -> if (wanted) loadMore() }
    }
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        HorizontalPager(
            state = state,
            modifier = modifier,
            beyondViewportPageCount = 0,
            userScrollEnabled = userScrollEnabled,
            key = { pages[it].id },
        ) { index ->
            val projection = pages[index]
            page(projection, projection.id == viewing.id)
        }
    }
}
