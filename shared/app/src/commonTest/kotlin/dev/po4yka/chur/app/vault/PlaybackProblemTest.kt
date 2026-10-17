package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackProblemTest {
    @Test
    fun integrity_is_distinct_from_missing_bytes_and_unsupported_formats() {
        listOf(ChurStatus.OBJECT_CORRUPT, ChurStatus.VAULT_CORRUPT, ChurStatus.CATALOG_CORRUPT).forEach {
            assertEquals(PlaybackProblem.INTEGRITY, playbackProblem(it))
        }
        listOf(ChurStatus.OBJECT_INCOMPLETE, ChurStatus.IO_FAILURE, ChurStatus.SESSION_EXPIRED).forEach {
            assertEquals(PlaybackProblem.UNAVAILABLE, playbackProblem(it))
        }
        assertEquals(PlaybackProblem.UNSUPPORTED, playbackProblem(ChurStatus.UNSUPPORTED_VERSION))
    }

}
