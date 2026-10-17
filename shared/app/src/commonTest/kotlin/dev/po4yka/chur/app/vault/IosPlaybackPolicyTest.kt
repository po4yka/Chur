package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals

class IosPlaybackPolicyTest {
    @Test
    fun waiting_for_a_seek_is_buffering_even_at_the_previous_end_position() {
        assertEquals(IosPlaybackPhase.BUFFERING, iosPlaybackPhase(true, false, true, 60f, 60f))
        assertEquals(IosPlaybackPhase.PLAYING, iosPlaybackPhase(true, true, false, 10f, 60f))
    }

    @Test
    fun initial_pause_and_end_have_distinct_recovery_states() {
        assertEquals(IosPlaybackPhase.INITIAL, iosPlaybackPhase(false, false, false, 0f, 0f))
        assertEquals(IosPlaybackPhase.PAUSED, iosPlaybackPhase(true, false, false, 10f, 60f))
        assertEquals(IosPlaybackPhase.ENDED, iosPlaybackPhase(true, false, false, 60f, 60f))
    }
}
