package dev.po4yka.chur.android

import android.content.ContentResolver
import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.po4yka.chur.app.ExportSink
import dev.po4yka.chur.app.ExportDirectory
import dev.po4yka.chur.app.ExportTarget
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import java.io.File
import java.util.UUID

/** Streams verified originals to a chosen system destination. */
class ExportDestinations(
    private val context: Context,
    private val onShareLaunch: () -> Unit,
    private val onShareEnd: () -> Unit,
    private val scheduleCleanup: (Long, () -> Unit) -> Unit = { delay, cleanup ->
        Handler(Looper.getMainLooper()).postDelayed({ cleanup() }, delay)
    },
) : ExportSink {
    private val resolver: ContentResolver = context.contentResolver
    private val scratch = File(context.cacheDir, "export-scratch")
    private val pendingShares = mutableMapOf<Uri, File>()
    private val scratchReservations = mutableMapOf<File, Long>()
    private var generation = 0L
    private val directoryExports = mutableSetOf<DocumentExportDirectory>()

    init {
        scratch.deleteRecursively() // A previous process cannot have a live share sheet.
        scratch.mkdirs()
    }

    override fun cancelPending() {
        val directories = synchronized(directoryExports) {
            generation += 1
            directoryExports.toList()
        }
        // Native code may be acquiring a duplicate of an active descriptor. Invalidate
        // now; the operation's finally closes and removes its output after native work ends.
        directories.forEach { it.invalidate() }
        pendingShares.toMap().forEach { (uri, file) -> releaseShare(uri, file) }
        scratchReservations.clear()
        scratch.deleteRecursively()
        scratch.mkdirs()
        onShareEnd()
    }

    override fun openDirectory(uri: String): ExportDirectory {
        val startedAt = synchronized(directoryExports) { generation }
        val directory = DocumentExportDirectory.open(resolver, Uri.parse(uri)) { directory ->
            synchronized(directoryExports) { directoryExports.remove(directory) }
        }
        synchronized(directoryExports) {
            if (generation != startedAt) {
                directory.close()
                throw ChurFailure(ChurStatus.CANCELLED, "the folder export was cancelled")
            }
            directoryExports.add(directory)
        }
        return directory
    }

    /** Allow asynchronous recipient opens briefly after the share activity returns. */
    internal fun completePendingShares() {
        val completed = pendingShares.toMap()
        if (completed.isNotEmpty()) scheduleCleanup(60_000L) {
            completed.forEach { (uri, file) -> releaseShare(uri, file) }
        }
    }

    private fun releaseShare(uri: Uri, file: File) {
        if (pendingShares[uri] !== file) return
        pendingShares.remove(uri)
        runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        releaseScratch(file)
    }

    private fun releaseScratch(file: File) {
        scratchReservations.remove(file)
        file.delete()
    }

    private class Destination(
        private val handle: ParcelFileDescriptor,
        private val publishAction: () -> Unit,
        private val discardAction: () -> Unit,
    ) : ExportSink.Destination {
        private var closed = false
        override val descriptor: Int get() = handle.fd
        override fun publish() {
            close()
            publishAction()
        }
        override fun discard() {
            close()
            discardAction()
        }
        override fun close() {
            if (!closed) { handle.close(); closed = true }
        }
    }

    override fun create(displayName: String, contentType: String): ExportSink.Destination? =
        create(displayName, contentType, ExportTarget.DEFAULT, null)

    override fun create(
        displayName: String,
        contentType: String,
        target: ExportTarget,
        uri: String?,
    ): ExportSink.Destination? = create(displayName, contentType, target, uri, -1)

    override fun create(
        displayName: String,
        contentType: String,
        target: ExportTarget,
        uri: String?,
        plaintextSize: Long,
    ): ExportSink.Destination? = when (target) {
        ExportTarget.FILES -> {
            val document = uri?.let(Uri::parse) ?: return null
            open(document, publish = {}, discard = { resolver.delete(document, null, null) })
        }
        ExportTarget.SHARE -> {
            val singleLimit = 4L * 1024 * 1024 * 1024
            val totalLimit = 2 * singleLimit
            if (plaintextSize < 0 || plaintextSize > singleLimit || scratchReservations.size >= 4 ||
                scratchReservations.values.sum() > totalLimit - plaintextSize
            ) {
                throw ChurFailure(ChurStatus.RESOURCE_LIMIT_EXCEEDED, "the share scratch budget")
            }
            val createdGeneration = generation
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType)
                ?: displayName.substringAfterLast('.', "bin")
            val safeExtension = extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "bin"
            val file = File(scratch, "${UUID.randomUUID()}.$safeExtension")
            check(file.createNewFile()) { "the share file already exists" }
            scratchReservations[file] = plaintextSize
            val handle = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)
            } catch (failure: Exception) {
                releaseScratch(file)
                throw failure
            }
            Destination(handle, publishAction = {
                if (createdGeneration != generation) {
                    releaseScratch(file)
                    throw ChurFailure(ChurStatus.CANCELLED, "the share was cancelled")
                }
                val contentUri = FileProvider.getUriForFile(
                    context, "${context.packageName}.exports", file, displayName,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = contentType
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    clipData = ClipData.newRawUri("", contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                pendingShares[contentUri] = file
                try {
                    onShareLaunch()
                    context.startActivity(Intent.createChooser(intent, "Share original").apply {
                        clipData = ClipData.newRawUri("", contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (failure: Exception) {
                    releaseShare(contentUri, file)
                    onShareEnd()
                    throw failure
                }
                scheduleCleanup(30 * 60 * 1000L) {
                    releaseShare(contentUri, file)
                }
            }, discardAction = { releaseScratch(file) })
        }
        else -> {
            val collection = when (target) {
                ExportTarget.MEDIA_LIBRARY -> when {
                    contentType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    contentType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> return null
                }
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, contentType)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destination = resolver.insert(collection, values) ?: return null
            open(destination,
                publish = {
                    check(resolver.update(destination, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null) == 1) { "the export could not be published" }
                },
                discard = { resolver.delete(destination, null, null) },
            )
        }
    }

    private fun open(uri: Uri, publish: () -> Unit, discard: () -> Unit): ExportSink.Destination? {
        val handle = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (failure: Exception) {
            discard()
            throw failure
        }
        if (handle == null) {
            discard()
            return null
        }
        return Destination(handle, publish, discard)
    }
}
