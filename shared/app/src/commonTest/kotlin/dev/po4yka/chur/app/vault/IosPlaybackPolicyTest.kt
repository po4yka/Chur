package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPlaybackPolicyTest {
    @Test
    fun waiting_for_a_seek_is_buffering_even_at_the_previous_end_position() {
        assertEquals(IosPlaybackPhase.BUFFERING, iosPlaybackPhase(true, false, true, 60f, 60f))
        assertEquals(IosPlaybackPhase.PLAYING, iosPlaybackPhase(true, true, false, 10f, 60f))
    }

    @Test
    fun each_accessibility_or_scrub_interaction_holds_controls_until_it_ends() {
        for (heldIndex in 0..4) {
            val guards = List(5) { it == heldIndex }
            assertTrue(iosPlaybackControlsHeld(guards[0], guards[1], guards[2], guards[3], guards[4]))
        }
        assertFalse(iosPlaybackControlsHeld(false, false, false, false, false))
    }

    @Test
    fun opening_a_paused_page_preserves_hidden_chrome_but_stopping_playback_recovers_controls() {
        assertFalse(iosPlaybackShouldReveal(false, IosPlaybackPhase.INITIAL, false))
        assertFalse(iosPlaybackShouldReveal(true, IosPlaybackPhase.PLAYING, false))
        for (phase in listOf(IosPlaybackPhase.PAUSED, IosPlaybackPhase.ENDED, IosPlaybackPhase.BUFFERING)) {
            assertTrue(iosPlaybackShouldReveal(true, phase, false))
        }
        assertTrue(iosPlaybackShouldReveal(false, IosPlaybackPhase.INITIAL, true))
    }

    @Test
    fun initial_pause_and_end_have_distinct_recovery_states() {
        assertEquals(IosPlaybackPhase.INITIAL, iosPlaybackPhase(false, false, false, 0f, 0f))
        assertEquals(IosPlaybackPhase.PAUSED, iosPlaybackPhase(true, false, false, 10f, 60f))
        assertEquals(IosPlaybackPhase.ENDED, iosPlaybackPhase(true, false, false, 60f, 60f))
    }
}
