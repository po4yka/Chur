package dev.po4yka.chur.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Untrusted external payload. It is never put in saved state or public storage. */
internal class IncomingSharePayload(
    uris: List<Uri>,
    val text: ByteArray?,
) {
    private val retainedUris = uris.toMutableList()
    val uris: List<Uri> get() = retainedUris
    val count: Int = uris.size + if (text == null) 0 else 1
    fun clear() { text?.fill(0); retainedUris.clear() }
    override fun toString(): String = "IncomingSharePayload(items=$count)"
}

/** Reject the entire batch at a limit, rather than silently lose selected items. */
internal fun incomingSharePayload(
    context: Context,
    intent: Intent,
    ownProvider: (Uri) -> Boolean = { uri ->
        context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
            ?.applicationInfo?.uid == context.applicationInfo.uid
    },
): IncomingSharePayload? {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
    return try {
        val uris = linkedSetOf<Uri>()
        fun add(uri: Uri) {
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.toString().length <= 8192)
            require(!ownProvider(uri))
            uris += uri
            require(uris.size <= MAX_INCOMING_ITEMS)
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streams = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            require(streams == null || streams.size <= MAX_INCOMING_ITEMS)
            streams?.forEach(::add)
        } else {
            val stream = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            stream?.let(::add)
        }
        intent.clipData?.let { clip ->
            require(clip.itemCount <= MAX_INCOMING_ITEMS)
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
        // A stream share often includes a caption or a URL. Keep it as a separate
        // plain text file; never dereference URLs or parse HTML from another app.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { value ->
            require(value.length <= MAX_INCOMING_TEXT_BYTES)
            value.toString().encodeToByteArray().also { bytes ->
                if (bytes.size > MAX_INCOMING_TEXT_BYTES) {
                    bytes.fill(0)
                    throw IllegalArgumentException()
                }
            }
        }?.takeIf { it.isNotEmpty() }
        if (uris.size + (if (text == null) 0 else 1) > MAX_INCOMING_ITEMS) {
            text?.fill(0)
            return null
        }
        IncomingSharePayload(uris.toList(), text).takeIf { it.count > 0 }
    } catch (_: Exception) {
        null
    }
}

internal const val MAX_INCOMING_ITEMS = 100
internal const val MAX_INCOMING_TEXT_BYTES = 1024 * 1024
