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
import dev.po4yka.chur.app.ExportTarget
import java.io.File
import java.util.UUID

/** Streams verified originals to a chosen system destination. */
class ExportDestinations(private val context: Context) : ExportSink {
    private val resolver: ContentResolver = context.contentResolver
    private val scratch = File(context.cacheDir, "export-scratch")

    init {
        scratch.deleteRecursively() // A previous process cannot have a live share sheet.
        scratch.mkdirs()
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
    ): ExportSink.Destination? = when (target) {
        ExportTarget.FILES -> {
            val document = uri?.let(Uri::parse) ?: return null
            open(document, publish = {}, discard = { resolver.delete(document, null, null) })
        }
        ExportTarget.SHARE -> {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType)
                ?: displayName.substringAfterLast('.', "bin")
            val safeExtension = extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "bin"
            val file = File(scratch, "${UUID.randomUUID()}.$safeExtension")
            check(file.createNewFile()) { "the share file already exists" }
            val handle = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)
            } catch (failure: Exception) {
                file.delete()
                throw failure
            }
            Destination(handle, publishAction = {
                val contentUri = FileProvider.getUriForFile(
                    context, "${context.packageName}.exports", file, displayName,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = contentType
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    clipData = ClipData.newRawUri("", contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Share original").apply {
                    clipData = ClipData.newRawUri("", contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { context.revokeUriPermission(contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    file.delete()
                }, 30 * 60 * 1000L)
            }, discardAction = { file.delete() })
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
