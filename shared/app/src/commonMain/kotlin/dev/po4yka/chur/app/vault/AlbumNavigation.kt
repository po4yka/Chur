package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.toHex

/** Resolve the complete root-to-current path from the current catalog rows.
 *
 * An empty path means the Albums root. A removed album or an invalid hierarchy
 * also returns the root; never expose a partial path with a missing ancestor.
 */
fun albumPath(albums: List<AlbumSummary>, currentId: String?): List<AlbumSummary> {
    if (currentId == null) return emptyList()
    val byId = albums.associateBy { it.id }
    if (byId.size != albums.size) return emptyList()
    var current = byId[currentId] ?: return emptyList()
    val reversed = ArrayList<AlbumSummary>()
    val visited = HashSet<String>()
    // Each catalog ID can be visited once. This also bounds a corrupt cycle
    // without recursion, including hierarchies at the catalog depth limit.
    while (visited.add(current.id)) {
        reversed += current
        val parentId = current.parentId ?: return reversed.asReversed()
        current = byId[parentId.toHex()] ?: return emptyList()
    }
    return emptyList()
}

/** The parent in a valid current catalog path; null means the Albums root. */
fun albumParent(albums: List<AlbumSummary>, currentId: String?): AlbumSummary? {
    val path = albumPath(albums, currentId)
    return path.getOrNull(path.size - 2)
}

/** Immediate children in the same sibling order as the album organizer. */
fun albumChildren(
    albums: List<AlbumSummary>,
    parentId: String?,
    order: AlbumOrder = AlbumOrder.MANUAL,
): List<AlbumSummary> {
    if (parentId != null && albumPath(albums, parentId).isEmpty()) return emptyList()
    val children = albums.filter { it.parentId?.toHex() == parentId }
    return albumRows(children, order).map { it.first }
}
