package dev.po4yka.chur.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException

/** A private, seekable view of one host file. No source bytes are stored on the device. */
internal class DeviceImportProvider : ContentProvider() {
    private val worker = HandlerThread("chur-device-import")

    override fun onCreate(): Boolean {
        worker.start()
        return true
    }

    override fun getType(uri: Uri): String? = sourceFor(uri)?.contentType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val source = sourceFor(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = Array<Any?>(columns.size) { index ->
            when (columns[index]) {
                OpenableColumns.DISPLAY_NAME -> source.name
                OpenableColumns.SIZE -> source.length
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val source = sourceFor(uri) ?: throw java.io.FileNotFoundException()
        if (mode != "r") throw java.io.FileNotFoundException()
        val storage = requireNotNull(context?.getSystemService(StorageManager::class.java))
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = source.length

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset < 0 || size < 0 || offset > source.length) {
                        throw ErrnoException("read", OsConstants.EINVAL)
                    }
                    val wanted = minOf(size.toLong(), source.length - offset).toInt()
                    var copied = 0
                    try {
                        while (copied < wanted) {
                            val chunk = source.read(offset + copied, minOf(wanted - copied, 64 * 1024))
                            if (chunk.isEmpty()) throw IOException("short source read")
                            chunk.copyInto(data, copied)
                            copied += chunk.size
                        }
                    } catch (_: Exception) {
                        throw ErrnoException("read", OsConstants.EIO)
                    }
                    return copied
                }

                override fun onRelease() = Unit
            },
            Handler(worker.looper),
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun sourceFor(uri: Uri): RemoteSource? = active?.takeIf {
        uri.authority == "${context?.packageName}.device-import" && uri.lastPathSegment == it.id
    }

    companion object {
        @Volatile private var active: RemoteSource? = null

        fun mount(context: android.content.Context, source: RemoteSource): Uri {
            check(active == null)
            active = source
            return Uri.Builder().scheme("content")
                .authority("${context.packageName}.device-import")
                .appendPath(source.id).build()
        }

        fun unmount(source: RemoteSource) {
            if (active === source) active = null
        }
    }
}

internal class RemoteSource(
    val id: String,
    val name: String,
    val contentType: String,
    val length: Long,
    val read: (Long, Int) -> ByteArray,
)
