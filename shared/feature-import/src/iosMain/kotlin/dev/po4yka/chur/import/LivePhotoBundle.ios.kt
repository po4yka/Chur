@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.po4yka.chur.imports

import kotlinx.cinterop.*
import kotlin.coroutines.cancellation.CancellationException
import platform.CoreCrypto.*
import platform.CoreFoundation.*
import platform.ImageIO.*
import platform.posix.*

/** Positioned reads never change the descriptor position used by the native importer. */
internal fun readLivePhotoRange(descriptor: Int, offset: Long, length: Int): ByteArray {
    val bytes = ByteArray(length)
    var at = 0
    bytes.usePinned { pinned ->
        while (at < length) {
            val count = pread(descriptor, pinned.addressOf(at), (length - at).toULong(), offset + at)
            if (count < 0 && errno == EINTR) continue
            if (count <= 0) throw LivePhotoBundleException("Truncated Live Photo")
            at += count.toInt()
        }
    }
    return bytes
}

actual fun readLivePhotoBundle(descriptor: Int, knownLength: Long?, validatePayloads: Boolean, cancelRequested: () -> Boolean): LivePhotoBundle? {
    val first = ByteArray(8)
    val count = first.usePinned { pread(descriptor, it.addressOf(0), 8u, 0) }
    if (count < 0 && errno == ESPIPE) return null
    if (count != 8L || !LivePhotoBundle.hasMagic(first)) return null
    val size = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) != 0) throw LivePhotoBundleException("Live Photo source unavailable")
        info.st_size
    }
    val fixed = readLivePhotoRange(descriptor, 0, LivePhotoBundle.FIXED_HEADER_BYTES)
    val header = readLivePhotoRange(descriptor, 0, LivePhotoBundle.headerLength(fixed))
    val bundle = try { LivePhotoBundle.parse(header, size) } finally { fixed.fill(0); header.fill(0) }
    if (validatePayloads) {
        for (r in bundle.resources) {
            val hash = livePhotoDigest()
            var finished = false
            try {
                var at = 0L
                while (at < r.byteLength) {
                    if (cancelRequested()) throw CancellationException("Live Photo import cancelled")
                    val bytes = readLivePhotoRange(descriptor, r.offset + at, minOf(65_536L, r.byteLength - at).toInt())
                    try { hash.update(bytes); at += bytes.size } finally { bytes.fill(0) }
                }
                val digest = hash.finish(); finished = true
                try { if (!digest.contentEquals(r.sha256)) throw LivePhotoBundleException("Live Photo checksum mismatch") }
                finally { digest.fill(0) }
            } finally { if (!finished) hash.finish().fill(0) }
        }
    }
    return bundle
}

/** Map only the selected resource. ImageIO reads a bounded slice without copying the bundle. */
internal fun <T> withLivePhotoImage(media: PickedMedia, block: (CGImageSourceRef) -> T): T? {
    val resource = media.livePhoto?.displayPhoto ?: return null
    if (resource.byteLength <= 0) return null
    val page = getpagesize().toLong()
    val aligned = resource.offset / page * page
    val delta = (resource.offset - aligned).toInt()
    val length = resource.byteLength + delta
    val mapped = mmap(null, length.toULong(), PROT_READ, MAP_PRIVATE, media.descriptor, aligned)
    if (mapped == null || mapped == MAP_FAILED) return null
    try {
        val bytes = mapped.reinterpret<ByteVar>() + delta
        val data = CFDataCreateWithBytesNoCopy(null, bytes?.reinterpret(), resource.byteLength, kCFAllocatorNull) ?: return null
        try {
            val options = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr) ?: return null
            val source = try {
                CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
                CGImageSourceCreateWithData(data, options)
            } finally { CFRelease(options) } ?: return null
            try { return block(source) } finally { CFRelease(source) }
        } finally { CFRelease(data) }
    } finally { munmap(mapped, length.toULong()) }
}

/** Native SHA state has one owner and is erased when finish releases it. */
actual fun livePhotoDigest(): LivePhotoDigest = object : LivePhotoDigest {
    private var context: CC_SHA256_CTX? = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }
    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val state = checkNotNull(context)
        if (length > 0) bytes.usePinned { CC_SHA256_Update(state.ptr, it.addressOf(offset), length.toUInt()) }
    }
    override fun finish(): ByteArray {
        val state = checkNotNull(context)
        val digest = ByteArray(32)
        try { digest.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret(), state.ptr) }; return digest }
        finally { memset(state.ptr, 0, sizeOf<CC_SHA256_CTX>().toULong()); nativeHeap.free(state); context = null }
    }
}
