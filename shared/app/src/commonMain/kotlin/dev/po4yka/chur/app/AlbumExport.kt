package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.ffi.toHex
import dev.po4yka.chur.ffi.QuerySort
import kotlinx.coroutines.CancellationException

/**
 * A transient directory lease. All creation is exclusive, sanitizes names and
 * chooses an unused suffix on a collision. Existing files are never replaced.
 * Closing a child releases that handle; closing the root releases platform access.
 */
interface ExportDirectory {
    fun createDirectory(displayName: String): ExportDirectory?
    fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination?
    fun close()
}

/** One path component, never a path supplied by private metadata. Hosts also validate it. */
internal fun safeExportName(name: String, fallback: String): String = name.take(180)
    .map { if (it.code < 32 || it in "/\\:*?\"<>|" || it == '\u007f') '_' else it }
    .joinToString("").trim().trim('.').trim().ifEmpty { fallback }

internal data class AlbumExportOutcome(
    val total: Int,
    val exported: Int,
    val failed: Int,
    val status: ChurStatus? = null,
    val cleanupFailed: Boolean = false,
) {
    val remaining: Int get() = (total - exported - failed).coerceAtLeast(0)
    fun summary(): String {
        val counts = buildList {
            add("$exported exported")
            if (failed > 0) add("$failed failed")
            if (remaining > 0) add("$remaining ${if (status == ChurStatus.CANCELLED) "cancelled" else "skipped"}")
        }.joinToString(", ")
        val reason = status?.let { " " + userCopy(it) }.orEmpty()
        return "$counts. Exported copies remain unencrypted outside the vault.$reason" +
            if (cleanupFailed) " Export cleanup could not finish." else ""
    }
}

/**
 * Keeps only album metadata and one native page in memory. Every membership is
 * exported in its own album, including an object that belongs to two albums.
 * A catalog change stops the batch rather than producing a silently incomplete tree.
 */
internal suspend fun exportAlbumTree(
    albumId: ByteArray,
    albums: List<AlbumSummary>,
    root: ExportDirectory,
    page: suspend (ObjectQuery) -> ObjectPage,
    cancelled: () -> Boolean,
    onItem: (Int, Int) -> Unit,
    export: suspend (ExportDirectory, ByteArray) -> Unit,
    expectedGeneration: Long? = null,
): AlbumExportOutcome {
    val requested = albums.firstOrNull { it.albumId.contentEquals(albumId) }
        ?: throw ChurFailure(ChurStatus.NOT_FOUND, "the album")
    val children = albums.groupBy { it.parentId?.toHex() }
    val ordered = mutableListOf<AlbumSummary>()
    val pending = ArrayDeque<AlbumSummary>().apply { addLast(requested) }
    val seen = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
        val album = pending.removeLast()
        if (!seen.add(album.id)) throw ChurFailure(ChurStatus.CATALOG_CORRUPT, "the album tree")
        ordered += album
        children[album.id].orEmpty().sortedWith(compareBy<AlbumSummary> { it.position }.thenBy { it.id })
            .asReversed().forEach { pending.addLast(it) }
    }
    var total = 0
    var exported = 0
    var failed = 0
    var failure: ChurStatus? = null
    var cleanupFailed = false
    var catalogGeneration: Long? = expectedGeneration
    val counts = mutableMapOf<String, Long>()
    val opened = mutableListOf<ExportDirectory>()
    val directories = mutableMapOf<String, ExportDirectory>()
    fun check() {
        if (cancelled()) throw ChurFailure(ChurStatus.CANCELLED, "the album export")
    }
    fun checkPage(result: ObjectPage) {
        if (catalogGeneration != null && result.catalogGeneration != catalogGeneration) {
            throw ChurFailure(ChurStatus.CONFLICT, "the changed album catalog")
        }
        catalogGeneration = result.catalogGeneration
    }
    fun query(album: AlbumSummary, cursor: ByteArray? = null) = ObjectQuery(
        scope = QueryScope.ALBUM, sort = QuerySort.IMPORT_DESC,
        kinds = 0, limit = 128, scopeId = album.albumId, cursor = cursor,
    )
    try {
        for (album in ordered) {
            check()
            val result = page(query(album))
            checkPage(result)
            if (result.totalCount < 0 || result.totalCount > Int.MAX_VALUE - total) {
                throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "the album export count")
            }
            counts[album.id] = result.totalCount
            total += result.totalCount.toInt()
        }
        for (album in ordered) {
            check()
            val parent = if (album.id == requested.id) root else directories[album.parentId?.toHex()]
                ?: throw ChurFailure(ChurStatus.CATALOG_CORRUPT, "the album parent")
            val directory = parent.createDirectory(safeExportName(album.name, "Album"))
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the export directory")
            opened += directory
            directories[album.id] = directory
            var cursor: ByteArray? = null
            var visited = 0L
            do {
                check()
                val result = page(query(album, cursor))
                checkPage(result)
                if (result.totalCount != counts[album.id] || (result.objects.isEmpty() && result.nextCursor != null)) {
                    throw ChurFailure(ChurStatus.CONFLICT, "the changed album membership")
                }
                for (item in result.objects) {
                    check()
                    onItem(exported + failed + 1, total)
                    try {
                        export(directory, item.objectId)
                        exported += 1
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (error: ChurFailure) {
                        if (cancelled() || error.status == ChurStatus.CANCELLED) {
                            throw ChurFailure(ChurStatus.CANCELLED, "the album export")
                        }
                        failed += 1
                        throw error
                    } catch (_: Exception) {
                        if (cancelled()) throw ChurFailure(ChurStatus.CANCELLED, "the album export")
                        failed += 1
                        throw ChurFailure(ChurStatus.IO_FAILURE, "the album export")
                    }
                    visited += 1
                }
                if (result.nextCursor != null && result.nextCursor.contentEquals(cursor)) {
                    throw ChurFailure(ChurStatus.CONFLICT, "the album cursor")
                }
                cursor = result.nextCursor
            } while (cursor != null)
            if (visited != counts[album.id]) throw ChurFailure(ChurStatus.CONFLICT, "the album membership count")
        }
        check()
    } catch (cancel: CancellationException) { throw cancel
    } catch (error: ChurFailure) {
        failure = if (cancelled()) ChurStatus.CANCELLED else error.status
    } catch (_: Exception) {
        failure = if (cancelled()) ChurStatus.CANCELLED else ChurStatus.IO_FAILURE
    } finally {
        var cancellation: CancellationException? = null
        opened.asReversed().forEach {
            try { it.close() }
            catch (cancel: CancellationException) { cancellation = cancel }
            catch (_: Exception) { cleanupFailed = true }
        }
        cancellation?.let { throw it }
    }
    return AlbumExportOutcome(total, exported, failed, failure ?: ChurStatus.IO_FAILURE.takeIf { cleanupFailed }, cleanupFailed)
}
