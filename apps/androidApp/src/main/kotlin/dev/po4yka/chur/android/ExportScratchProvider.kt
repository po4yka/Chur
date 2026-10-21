package dev.po4yka.chur.android

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves share-out scratch only while this process holds a live lease for the file.
 *
 * A recipient grant can outlive the process. The system then starts a cold process
 * for the recipient's open, and a plain FileProvider would serve plaintext that no
 * share sheet holds. The lease set lives only in memory, so a cold process serves
 * nothing, and [onCreate] deletes what the previous process left.
 */
class ExportScratchProvider : FileProvider() {
    override fun onCreate(): Boolean {
        // A provider is created before any activity, at every process start. No share
        // sheet of an earlier process can still hold one of these files.
        context?.let { ExportLeases.clearScratch(it) }
        return super.onCreate()
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        requireLive(uri)
        return super.openFile(uri, mode)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        requireLive(uri)
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    private fun requireLive(uri: Uri) {
        val context = context ?: throw FileNotFoundException("the export is not available")
        if (!ExportLeases.isLive(context, uri)) throw FileNotFoundException("the export is not available")
    }
}

/** The in-process set of share files that a recipient may open, §5 of PLAINTEXT_LIFECYCLE.md. */
internal object ExportLeases {
    /** The longest time a recipient may hold a share file, measured by the wall clock. */
    const val HOLD_MS: Long = 30 * 60 * 1000L

    private const val PATH_NAME = "exports"
    private val live = mutableSetOf<String>()

    fun scratch(context: Context): File = File(context.cacheDir, "export-scratch")

    fun clearScratch(context: Context) {
        synchronized(live) { live.clear() }
        scratch(context).deleteRecursively()
    }

    fun grant(file: File) {
        synchronized(live) { live.add(file.canonicalPath) }
    }

    fun revoke(file: File) {
        synchronized(live) { live.remove(file.canonicalPath) }
    }

    /** True when [file] was written longer ago than [HOLD_MS], or its time is in the future. */
    fun expired(file: File, now: Long = System.currentTimeMillis()): Boolean {
        val written = file.lastModified()
        return written == 0L || now - written >= HOLD_MS || written - now > HOLD_MS
    }

    /** Only `exports/<leased path>` with a fresh file is live; every other path is refused. */
    fun isLive(context: Context, uri: Uri): Boolean {
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != PATH_NAME) return false
        val root = scratch(context).canonicalFile
        val file = File(root, segments.drop(1).joinToString(File.separator)).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return false
        if (!synchronized(live) { file.path in live }) return false
        if (!file.isFile || expired(file)) {
            revoke(file)
            file.delete()
            return false
        }
        return true
    }
}
