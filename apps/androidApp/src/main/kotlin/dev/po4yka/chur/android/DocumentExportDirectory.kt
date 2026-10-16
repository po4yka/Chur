package dev.po4yka.chur.android

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dev.po4yka.chur.app.ExportDirectory
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import java.util.UUID
import java.util.Locale

/** A transient SAF tree grant. Only documents created by this export can be removed. */
internal class DocumentExportDirectory private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val lease: Lease,
    private val root: Boolean,
    private val onClose: (DocumentExportDirectory) -> Unit = {},
) : ExportDirectory {
    private var closed = false

    /** Lock must not close a descriptor while Rust can still acquire its duplicate. */
    fun invalidate() { lease.invalidated = true }

    override fun createDirectory(displayName: String): ExportDirectory = synchronized(lease) {
        requireOpen()
        val created = create(displayName, DocumentsContract.Document.MIME_TYPE_DIR)
        DocumentExportDirectory(resolver, created, lease, root = false)
    }

    override fun createFile(displayName: String, contentType: String, plaintextSize: Long): ExportSink.Destination =
        synchronized(lease) {
            requireOpen()
            val created = create("chur-partial-${UUID.randomUUID()}-${uniqueDocumentName(displayName, emptySet())}", contentType)
            val handle = try {
                val flags = provider {
                    resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                        if (!it.moveToFirst()) 0 else it.getInt(0)
                    } ?: 0
                }
                val required = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                if (flags and required != required) {
                    throw ChurFailure(ChurStatus.IO_FAILURE, "the export provider must support rename and delete")
                }
                provider { resolver.openFileDescriptor(created, "w") }
                    ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the export file could not be opened")
            } catch (failure: Exception) {
                remove(created)
                throw failure
            }
            DocumentDestination(handle, created, displayName).also { lease.pending.add(it) }
        }

    /** The selected root stays intact. Successful exports remain outside the vault. */
    override fun close(): Unit = synchronized(lease) {
        if (closed) return@synchronized
        closed = true
        if (root) {
            lease.closed = true
            try {
                var failure: Exception? = null
                lease.pending.toList().forEach {
                    try { it.discard() } catch (caught: Exception) { if (failure == null) failure = caught }
                }
                failure?.let { throw it }
            } finally {
                onClose(this)
            }
        }
    }

    private fun requireOpen() {
        if (closed || lease.closed || lease.invalidated) throw ChurFailure(ChurStatus.CANCELLED, "the folder export has ended")
    }

    private fun create(displayName: String, mimeType: String): Uri {
        val children = children()
        val name = uniqueDocumentName(displayName, children.values.toSet())
        val created = provider { DocumentsContract.createDocument(resolver, uri, mimeType, name) }
            ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the export entry could not be created")
        // A provider must create a new document. Never open or delete a returned existing one.
        if (DocumentsContract.getDocumentId(created) in children || created == uri) {
            throw ChurFailure(ChurStatus.IO_FAILURE, "the provider did not create a new export entry")
        }
        return created
    }

    private fun children(): Map<String, String> = provider {
        val result = linkedMapOf<String, String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        resolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (result.size >= 100_000) {
                    throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "too many destination entries")
                }
                result[cursor.getString(0)] = cursor.getString(1).orEmpty()
            }
        } ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the export folder could not be read")
        result
    }

    private fun remove(document: Uri) {
        provider {
            if (!DocumentsContract.deleteDocument(resolver, document)) {
                throw ChurFailure(ChurStatus.IO_FAILURE, "the partial export could not be removed")
            }
        }
    }

    private inner class DocumentDestination(
        private val handle: ParcelFileDescriptor,
        private var document: Uri,
        private val finalName: String,
    ) : ExportSink.Destination {
        private var handleClosed = false
        private var published = false
        private var discarded = false
        override val descriptor: Int get() = handle.fd

        override fun publish(): Unit = synchronized(lease) {
            if (published || discarded) return@synchronized
            requireOpen()
            close()
            val name = uniqueDocumentName(finalName, children().values.toSet())
            document = provider { DocumentsContract.renameDocument(resolver, document, name) }
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the completed export could not be named")
            requireOpen()
            published = true
            lease.pending.remove(this)
        }

        override fun discard(): Unit = synchronized(lease) {
            if (discarded) return@synchronized
            try {
                close()
            } finally {
                remove(document)
                discarded = true
                lease.pending.remove(this)
            }
        }

        override fun close(): Unit = synchronized(lease) {
            if (!handleClosed) {
                handleClosed = true
                provider { handle.close() }
            }
        }
    }

    private class Lease {
        var closed = false
        @Volatile var invalidated = false
        val pending = mutableSetOf<ExportSink.Destination>()
    }

    companion object {
        fun open(
            resolver: ContentResolver,
            tree: Uri,
            onClose: (DocumentExportDirectory) -> Unit = {},
        ): DocumentExportDirectory = provider {
            if (tree.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(tree)) {
                throw ChurFailure(ChurStatus.IO_FAILURE, "choose an export folder")
            }
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
                if (!it.moveToFirst() || it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) {
                    throw ChurFailure(ChurStatus.IO_FAILURE, "the selected export folder is unavailable")
                }
            } ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the selected export folder is unavailable")
            DocumentExportDirectory(resolver, root, Lease(), root = true, onClose = onClose)
        }
    }
}

/** Keep names local to one document, bounded, and distinct from existing sibling names. */
internal fun uniqueDocumentName(requested: String, existing: Set<String>): String {
    val safe = requested.map { char ->
        if (char.isISOControl() || char in "/\\:*?\"<>|") '_' else char
    }.joinToString("").trim().trim('.')
    var end = 0
    var bytes = 0
    val bounded = StringBuilder()
    while (end < safe.length) {
        val point = safe.codePointAt(end)
        val scalar = if (point in 0xD800..0xDFFF) '_'.code else point
        val width = when { scalar < 0x80 -> 1; scalar < 0x800 -> 2; scalar < 0x10000 -> 3; else -> 4 }
        if (bytes + width > 160) break
        bounded.appendCodePoint(scalar)
        bytes += width
        end += Character.charCount(point)
    }
    val base = bounded.toString().trimEnd().trimEnd('.').ifBlank { "Untitled" }
    val names = existing.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
    if (base.lowercase(Locale.ROOT) !in names) return base
    val dot = base.lastIndexOf('.').takeIf { it > 0 && base.length - it <= 16 }
    val stem = dot?.let { base.substring(0, it) } ?: base
    val extension = dot?.let { base.substring(it) }.orEmpty()
    for (suffix in 2..100_002) {
        val candidate = "$stem ($suffix)$extension"
        if (candidate.lowercase(Locale.ROOT) !in names) return candidate
    }
    throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "too many names in the export folder")
}

private inline fun <T> provider(action: () -> T): T = try {
    action()
} catch (failure: ChurFailure) {
    throw failure
} catch (_: SecurityException) {
    throw ChurFailure(ChurStatus.PERMISSION_DENIED, "the export folder permission was refused")
} catch (_: Exception) {
    throw ChurFailure(ChurStatus.IO_FAILURE, "the export provider failed")
}
