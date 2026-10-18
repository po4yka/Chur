package dev.po4yka.chur.app.vault

import dev.po4yka.chur.imports.LivePhotoBundle
import dev.po4yka.chur.imports.LivePhotoBundleException
import dev.po4yka.chur.vault.VaultRepository

/** Reads only the bounded authenticated resource table. Native integrity still protects each range. */
suspend fun readVaultLivePhoto(vault: VaultRepository, objectId: ByteArray): LivePhotoBundle? {
    val reader = vault.leaseReader(objectId)
    try {
        val info = vault.readerContentInfo(reader)
        if (!info.complete || info.plaintextSize < LivePhotoBundle.FIXED_HEADER_BYTES) return null
        val fixed = vault.readLeased(reader, 0, LivePhotoBundle.FIXED_HEADER_BYTES)
        try {
            if (!LivePhotoBundle.hasMagic(fixed)) return null
            val length = LivePhotoBundle.headerLength(fixed)
            if (length > info.plaintextSize) throw LivePhotoBundleException("Truncated Live Photo table")
            val header = vault.readLeased(reader, 0, length)
            return try { LivePhotoBundle.parse(header, info.plaintextSize) } finally { header.fill(0) }
        } finally { fixed.fill(0) }
    } finally { vault.releaseReader(reader) }
}

/** One selected movie range. The movie retains its original audio tracks. */
suspend fun livePhotoPlaybackFor(
    vault: VaultRepository,
    objectId: ByteArray,
    onVerify: (() -> Unit)? = null,
): PlaybackSource? {
    val bundle = readVaultLivePhoto(vault, objectId) ?: return null
    val video = bundle.displayVideo
    if (!video.contentType.startsWith("video/")) return null
    return PlaybackSource(vault, objectId, video.contentType, video.byteLength,
        offset = video.offset, length = video.byteLength, onVerify = onVerify)
}
