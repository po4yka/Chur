package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.StreamKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoPlaybackToolsTest {
    @Test
    fun copy_actions_require_the_whole_original_video() {
        assertTrue(videoCopyAvailable("video/mp4", StreamKind.ORIGINAL, 0, 100, 100))
        assertFalse(videoCopyAvailable("audio/mp4", StreamKind.ORIGINAL, 0, 100, 100))
        assertFalse(videoCopyAvailable("image/heic", StreamKind.ORIGINAL, 0, 100, 100))
        assertFalse(videoCopyAvailable("video/mp4", StreamKind.COMPATIBLE_VIDEO, 0, 100, 100))
        assertFalse(videoCopyAvailable("video/mp4", StreamKind.ORIGINAL, 20, 80, 100))
        assertFalse(videoCopyAvailable("video/mp4", StreamKind.ORIGINAL, 0, 80, 100))
    }

    @Test
    fun safe_failure_messages_distinguish_integrity_limits_and_unsupported_conversion() {
        assertTrue(videoCopyFailureMessage(ChurStatus.OBJECT_CORRUPT).contains("integrity check"))
        assertTrue(videoCopyFailureMessage(ChurStatus.VAULT_CORRUPT).contains("Verify the library"))
        assertTrue(videoCopyFailureMessage(ChurStatus.RESOURCE_LIMIT_EXCEEDED).contains("limits"))
        assertTrue(videoCopyFailureMessage(ChurStatus.UNSUPPORTED_VERSION).contains("cannot create"))
        assertFalse(videoCopyFailureMessage(ChurStatus.SESSION_EXPIRED).contains("integrity"))
        assertFalse(videoCopyFailureMessage(ChurStatus.UNSUPPORTED_VERSION).contains("retry", ignoreCase = true))
    }
}
