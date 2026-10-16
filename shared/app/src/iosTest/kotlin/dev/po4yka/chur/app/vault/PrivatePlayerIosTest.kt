@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.po4yka.chur.app.vault

import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.rate
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PrivatePlayerIosTest {
    @Test
    fun opening_private_media_does_not_start_or_allow_external_playback() {
        val item = AVPlayerItem(uRL = NSURL(string = "chur://vault/object"))
        val player = privatePlayer(item)
        assertEquals(0f, player.rate)
        assertFalse(player.allowsExternalPlayback)
    }
}
