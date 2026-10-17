@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.rate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PrivatePlayerIosTest {
    @Test
    fun reader_errors_keep_typed_codes_and_do_not_become_codec_failures() {
        val corrupt = iosReaderPlaybackError(ChurStatus.OBJECT_CORRUPT)
        assertEquals("dev.po4yka.chur.playback", corrupt.domain)
        assertEquals(ChurStatus.OBJECT_CORRUPT.value.toLong(), corrupt.code)
        assertEquals(PlaybackProblem.UNAVAILABLE, iosNativePlaybackProblem(corrupt))
        assertEquals(PlaybackProblem.INTEGRITY, playbackProblem(ChurStatus.OBJECT_CORRUPT))
    }

    @Test
    fun native_decoder_failure_is_unsupported_but_an_unknown_error_is_unavailable() {
        val codec = NSError(domain = AVFoundationErrorDomain, code = AVErrorDecoderNotFound, userInfo = null)
        assertEquals(PlaybackProblem.UNSUPPORTED, iosNativePlaybackProblem(codec))
        assertEquals(PlaybackProblem.UNAVAILABLE, iosNativePlaybackProblem(null))
    }

    @Test
    fun opening_private_media_does_not_start_or_allow_external_playback() {
        val item = AVPlayerItem(uRL = NSURL(string = "chur://vault/object"))
        val player = privatePlayer(item)
        assertEquals(0f, player.rate)
        assertFalse(player.allowsExternalPlayback)
    }
}
