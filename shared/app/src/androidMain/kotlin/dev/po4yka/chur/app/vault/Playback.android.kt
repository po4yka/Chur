package dev.po4yka.chur.app.vault

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.runBlocking

/**
 * The Media3 half of `MEDIA_PIPELINE.md` §9.
 *
 * ExoPlayer owns the codec, the buffering, and the surface; it never sees a
 * container, a key, or a path. What it sees is [ChurDataSource], which asks the
 * vault for authenticated plaintext ranges and hands them over. That is §1's
 * split applied to playback, and it is why a codec bug cannot reach ciphertext:
 * the codec is downstream of every cryptographic check.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun VaultPlayer(source: PlaybackSource, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(source.objectId, source.plaintextSize) {
        val factory = ChurDataSource.Factory(source)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(factory))
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        // §6.1: the type is the authenticated one, used
                        // unchanged as the MIME type.
                        .setMimeType(mimeTypeOf(source.contentType))
                        .setUri(ChurDataSource.URI)
                        .build(),
                )
                prepare()
            }
    }
    DisposableEffect(player) {
        onDispose {
            // §8 of `PLAINTEXT_LIFECYCLE.md`: stopping private playback is the
            // first step of a lock, and leaving the surface is the same event
            // for this player's purposes.
            player.release()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { PlayerView(it).apply { this.player = player } },
        onRelease = { it.player = null },
    )
}

/**
 * The MIME type Media3 is told, from the type the catalog authenticated.
 *
 * Media3 uses the value to choose an extractor. A type it does not know makes
 * it sniff the stream instead, which still works and only costs a read, so an
 * unknown type is passed through rather than replaced with a guess.
 */
private fun mimeTypeOf(contentType: String): String = when (contentType) {
    "audio/mp4", "audio/m4a" -> MimeTypes.AUDIO_MP4
    "audio/wav", "audio/x-wav" -> MimeTypes.AUDIO_WAV
    "video/quicktime" -> MimeTypes.VIDEO_MP4
    else -> contentType
}

/**
 * A Media3 data source over one vault object, `FFI_CONTRACT.md` §6.3.
 *
 * One reader lease is held for the life of the source and every read goes
 * through it. §8's table permits that from a loader thread the reader did not
 * come from, and it is why a seek costs one chunk authentication rather than a
 * whole reopen: `PERFORMANCE_BUDGETS.md` §12 measures both, and the difference
 * is about two percent, so the lease buys correctness rather than speed — a
 * reader that stayed open across a lock would be a handle the session no longer
 * owns.
 */
@UnstableApi
private class ChurDataSource(private val source: PlaybackSource) : DataSource {

    private val vault: VaultRepository get() = source.vault
    private var reader = 0L
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        // §10 has no callbacks out of Rust and this source produces no transfer
        // events of its own; the player's own listeners cover what it needs.
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        try {
            reader = runBlocking { vault.leaseReader(source.objectId, source.kind) }
            val info = vault.readerContentInfo(reader)
            // §6.1: a reader on an incomplete object may serve ranges for
            // verification but must not be attached to a player. A player given
            // a length treats a later failure as transport trouble and retries
            // without end.
            if (!info.complete || !info.byteRangeSupported) {
                throw DataSourceException(
                    java.io.IOException("the object is not complete"),
                    DataSourceException.POSITION_OUT_OF_RANGE,
                )
            }
            position = dataSpec.position
            val available = info.plaintextSize - position
            if (available < 0) {
                throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            }
            remaining = if (dataSpec.length == androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(dataSpec.length, available)
            }
            opened = true
            return remaining
        } catch (failure: ChurFailure) {
            release()
            throw DataSourceException(java.io.IOException(failure.message), failure.status.value)
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return androidx.media3.common.C.RESULT_END_OF_INPUT
        val take = minOf(length.toLong(), remaining).toInt()
        val bytes = try {
            vault.readLeased(reader, position, take)
        } catch (failure: ChurFailure) {
            throw DataSourceException(java.io.IOException(failure.message), failure.status.value)
        }
        if (bytes.isEmpty()) return androidx.media3.common.C.RESULT_END_OF_INPUT
        bytes.copyInto(target, offset)
        position += bytes.size
        remaining -= bytes.size
        return bytes.size
    }

    override fun getUri(): Uri? = if (opened) URI else null

    override fun close() {
        if (opened || reader != 0L) release()
    }

    private fun release() {
        if (reader != 0L) {
            vault.releaseReader(reader)
            reader = 0L
        }
        opened = false
        position = 0
        remaining = 0
    }

    class Factory(private val source: PlaybackSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChurDataSource(source)
    }

    companion object {
        /**
         * The URI the media item carries.
         *
         * It names no object and no path. `DISCREET_MODE.md`'s "Deep links"
         * section forbids a private identifier in a URI, and the data source
         * already knows which object it is for, so the value is a constant.
         */
        val URI: Uri = Uri.parse("chur://vault/object")
    }
}
