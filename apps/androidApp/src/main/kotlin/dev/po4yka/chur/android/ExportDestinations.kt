package dev.po4yka.chur.android

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.po4yka.chur.app.ExportSink

/**
 * Where an export lands, `docs/security/PLAINTEXT_LIFECYCLE.md` §6.
 *
 * The destination is the shared Downloads collection, which is outside the
 * vault boundary by construction: §6 says the user is deliberately leaving it,
 * and a destination inside the sandbox would be a copy the user could not
 * reach, which is not an export.
 *
 * The row is created pending and published only once the whole object is
 * written. A reader that opened a half-written export would see truncated
 * plaintext and could not tell it from the whole, so the pending flag is what
 * makes an interrupted export invisible rather than wrong.
 */
class ExportDestinations(private val resolver: ContentResolver) : ExportSink {

    /** One open destination. */
    class Destination internal constructor(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val handle: ParcelFileDescriptor,
    ) : ExportSink.Destination {
        override val descriptor: Int get() = handle.fd

        /** Makes the row visible once the whole object is written. */
        override fun publish() {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }

        /** Removes a destination whose export failed. */
        override fun discard() {
            resolver.delete(uri, null, null)
        }

        /** Closes the descriptor, which the caller owns. */
        override fun close() {
            handle.close()
        }
    }

    /** Creates a pending destination for one export. */
    override fun create(displayName: String, contentType: String): ExportSink.Destination? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, contentType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val handle = resolver.openFileDescriptor(uri, "w")
        if (handle == null) {
            resolver.delete(uri, null, null)
            return null
        }
        return Destination(resolver, uri, handle)
    }
}
