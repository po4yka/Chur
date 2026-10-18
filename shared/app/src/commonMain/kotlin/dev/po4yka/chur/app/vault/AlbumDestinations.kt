package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.toHex

internal data class AlbumDestination(val album: AlbumSummary, val path: String)

/** Build each valid destination once. A moved album and its descendants cannot be targets. */
internal fun albumDestinations(
    albums: List<AlbumSummary>,
    excludedSubtreeId: String? = null,
): List<AlbumDestination> {
    if (albums.map { it.id }.toSet().size != albums.size) return emptyList()
    val children = albums.groupBy { it.parentId?.toHex() }
        .mapValues { (_, rows) -> rows.sortedWith(compareBy<AlbumSummary> { it.position }.thenBy { it.id }) }
    val pending = ArrayDeque<AlbumDestination>()
    children[null].orEmpty().asReversed().forEach { pending.addLast(AlbumDestination(it, it.name)) }
    val result = ArrayList<AlbumDestination>(albums.size)
    val visited = HashSet<String>()
    while (pending.isNotEmpty()) {
        val destination = pending.removeLast()
        if (destination.album.id == excludedSubtreeId || !visited.add(destination.album.id)) continue
        result += destination
        children[destination.album.id].orEmpty().asReversed().forEach { child ->
            pending.addLast(AlbumDestination(child, "${destination.path} / ${child.name}"))
        }
    }
    val repeatedPaths = result.groupingBy { it.path }.eachCount()
    return result.map { destination ->
        // Identical names at every level still need distinct accessible choices.
        if (repeatedPaths.getValue(destination.path) > 1)
            destination.copy(path = "${destination.path} (${destination.album.id})")
        else destination
    }
}

/** Selection applies to fetched rows only, even when the query has more results. */
fun loadedObjectSelection(page: ObjectPage): Set<String> = page.objects.map { it.id }.toSet()
