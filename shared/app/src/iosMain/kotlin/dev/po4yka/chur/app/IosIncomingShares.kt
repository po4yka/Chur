@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app

import dev.po4yka.chur.imports.IosMediaCodec
import dev.po4yka.chur.imports.PickedMedia
import dev.po4yka.chur.imports.MediaBounds
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL

/** Host callbacks hold encrypted inbox paths; public UI receives only a count. */
public object IosIncomingShares {
    public val inbox: IncomingShareInbox = IncomingShareInbox()
    public fun hasPending(): Boolean = inbox.state.value != null
    internal var cancelOperation: (() -> Unit)? = null
    public fun cancelImport() {
        if (inbox.state.value?.busy == true) cancelOperation?.invoke()
    }

    public fun offer(
        count: Int,
        load: (index: Int, prepare: (processed: Long, total: Long) -> Boolean,
            loaded: (descriptor: Int, name: String?, contentType: String, size: Long,
                mediaPath: String?, release: () -> Unit) -> Unit) -> Unit,
        releaseSelection: () -> Unit,
    ): Boolean {
        val codec = IosMediaCodec()
        return inbox.offer(object : IncomingShareSource {
            override val count: Int = count
            override suspend fun open(index: Int, prepare: (Long, Long) -> Boolean): PickedMedia? =
                suspendCancellableCoroutine { continuation ->
                    load(index, prepare) { descriptor, name, type, size, path, release ->
                        val picked = if (path != null) {
                            val media = codec.open(NSURL.fileURLWithPath(path), name)
                            if (media == null) {
                                release()
                                null
                            } else {
                                // A malformed or mislabeled media hint must not lose
                                // an otherwise readable original.
                                val probe = try { codec.probe(media) } catch (_: Exception) { null }
                                val supported = probe != null && MediaBounds.check(probe) == null
                                media.copy(contentTypeHint = type, importAsFile = !supported,
                                    close = { media.close(); release() })
                            }
                        } else {
                            codec.openDocument(descriptor, name, type, size, release)?.copy(seekable = false)
                        }
                        continuation.resume(picked) { _, item, _ -> item?.close?.invoke() }
                    }
                }
            override fun close() { releaseSelection() }
        })
    }
}
