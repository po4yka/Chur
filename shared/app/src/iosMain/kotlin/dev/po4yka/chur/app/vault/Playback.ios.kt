@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.resourceLoader
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

/**
 * The AVFoundation half of `MEDIA_PIPELINE.md` §9.
 *
 * `AVPlayer` owns the codec and the surface and never sees a container, a key,
 * or a path. It asks a resource loader for byte ranges, and the loader asks the
 * vault, which is §1's split applied to playback exactly as the Android side
 * applies it.
 *
 * AVFoundation consults a resource-loader delegate only for a URL whose scheme
 * it does not itself handle, which is why the asset carries the `chur` scheme.
 * The URL names no object: `DISCREET_MODE.md`'s "Deep links" section forbids a
 * private identifier in one, and the delegate already knows which object it is
 * for.
 */
@Composable
actual fun VaultPlayer(source: PlaybackSource, modifier: Modifier) {
    val loader = remember(source.objectId, source.plaintextSize) { ChurResourceLoader(source) }
    val player = remember(loader) {
        val asset = AVURLAsset(NSURL(string = "chur://vault/object"), options = null)
        // A serial queue of its own, never the main queue. The delegate answers
        // synchronously and its first answer takes the repository's mutex and
        // then a native read; on the main queue that is the user interface
        // waiting on a catalog query, which is the shape of an unresponsive
        // application rather than a slow one. Serial is what AVFoundation
        // expects and is also what the reader needs: `FFI_CONTRACT.md` §8
        // serializes calls per reader handle in v1.
        asset.resourceLoader.setDelegate(loader, loaderQueue)
        AVPlayer().apply { replaceCurrentItemWithPlayerItem(AVPlayerItem(asset as AVAsset)) }
    }
    DisposableEffect(player) {
        player.play()
        onDispose {
            // `PLAINTEXT_LIFECYCLE.md` §8 step 1: private playback stops before
            // anything else, and leaving the surface is that event here.
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            loader.release()
        }
    }
    UIKitView(
        modifier = modifier,
        factory = {
            val view = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
            val layer = AVPlayerLayer.playerLayerWithPlayer(player)
            view.layer.addSublayer(layer)
            view
        },
        update = { view ->
            // The layer does not follow its host's bounds on its own, and a
            // Compose surface resizes on rotation and on a split view.
            (view.layer.sublayers?.firstOrNull() as? AVPlayerLayer)?.setFrame(view.bounds)
        },
    )
}

/**
 * Serves one vault object's ranges to AVFoundation.
 *
 * One reader lease is held for the loader's life and every request goes through
 * it, which `FFI_CONTRACT.md` §8 permits from the loader's own queue.
 *
 * The delegate answers `shouldWaitForLoadingOfRequestedResource` synchronously
 * and returns `true`: the range is served and the request finished before the
 * method returns, which is the simplest correct shape and is what a local
 * source can do. A network source would return `true` and finish later.
 */
private class ChurResourceLoader(private val source: PlaybackSource) :
    NSObject(), AVAssetResourceLoaderDelegateProtocol {

    private val vault: VaultRepository get() = source.vault
    private var reader = 0L
    private var size = 0L
    private var complete = false

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        if (!ensureReader()) {
            request.finishLoadingWithError(null)
            return false
        }

        // §6.1: content information before the first range request. A player
        // given a length treats a later failure as transport trouble and
        // retries without end, so an incomplete object publishes none.
        request.contentInformationRequest?.let { information ->
            if (!complete) {
                request.finishLoadingWithError(null)
                return false
            }
            information.setContentLength(size)
            information.setByteRangeAccessSupported(true)
            information.setContentType(utTypeOf(source.contentType))
        }

        val data = request.dataRequest
        if (data == null) {
            request.finishLoading()
            return true
        }
        val offset = data.requestedOffset
        if (offset >= size) {
            request.finishLoading()
            return true
        }
        val wanted = minOf(data.requestedLength.toLong(), size - offset)
        val bytes = try {
            vault.readLeased(reader, offset, wanted.toInt())
        } catch (_: ChurFailure) {
            request.finishLoadingWithError(null)
            return false
        }
        if (bytes.isNotEmpty()) {
            data.respondWithData(bytes.toNSData())
        }
        request.finishLoading()
        return true
    }

    /** Releases the lease, which the composable does when it leaves. */
    fun release() {
        if (reader != 0L) {
            vault.releaseReader(reader)
            reader = 0L
        }
    }

    private fun ensureReader(): Boolean {
        if (reader != 0L) return true
        return try {
            reader = runBlocking { vault.leaseReader(source.objectId, source.kind) }
            val info = vault.readerContentInfo(reader)
            size = info.plaintextSize
            complete = info.complete && info.byteRangeSupported
            true
        } catch (_: ChurFailure) {
            false
        }
    }
}

/**
 * The uniform type identifier AVFoundation is told, from the IANA type the
 * catalog authenticated.
 *
 * `FFI_CONTRACT.md` §6.1 says iOS converts the value with `UTType(mimeType:)`.
 * That initializer is unavailable from Kotlin/Native, so the two abstract types
 * the player needs are named directly: `public.movie` and `public.audio` are
 * the identifiers every concrete video and audio type conforms to, which is
 * enough for AVFoundation to choose a track reader, and anything else is left
 * to the codec's own sniffing rather than replaced with a guess.
 */
private fun utTypeOf(contentType: String): String? = when {
    contentType.startsWith("video/") -> "public.movie"
    contentType.startsWith("audio/") -> "public.audio"
    else -> null
}

/**
 * The queue the resource loader answers on.
 *
 * One serial queue for the process. A second player replaces the first on this
 * surface, so two loaders are never live at once, and sharing the queue keeps
 * the ordering AVFoundation and `FFI_CONTRACT.md` §8 both want.
 */
private val loaderQueue = dispatch_queue_create("dev.po4yka.chur.resource-loader", null)

/** Copies a Kotlin array into an `NSData` AVFoundation can hold. */
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}
