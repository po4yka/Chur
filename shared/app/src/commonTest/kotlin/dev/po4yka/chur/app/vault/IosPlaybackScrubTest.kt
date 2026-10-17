package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPlaybackScrubTest {
    @Test
    fun clock_samples_do_not_replace_a_drag_preview_or_an_unconfirmed_seek() {
        val drag = IosPlaybackScrub().previewAt(42f)
        assertEquals(42f, drag.displayedPosition(5f))
        assertEquals(null, drag.target, "Dragging does not request native seeks")
        val committed = drag.commit()
        assertEquals(42f, committed.target)
        assertEquals(42f, committed.displayedPosition(6f))
        assertTrue(committed.active)
        val finished = committed.complete(committed.revision)
        assertEquals(42.1f, finished.displayedPosition(42.1f))
        assertFalse(finished.active)
    }

    @Test
    fun a_superseded_seek_completion_preserves_the_latest_seek_and_new_drag() {
        val first = IosPlaybackScrub().previewAt(20f).commit()
        val second = first.previewAt(40f).commit()
        assertEquals(second, second.complete(first.revision))
        val draggingAgain = second.previewAt(55f).complete(second.revision)
        assertEquals(55f, draggingAgain.displayedPosition(40f))
        assertTrue(draggingAgain.active)
    }
}
