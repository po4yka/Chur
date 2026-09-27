package dev.po4yka.chur.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ActiveOperationTest {
    @Test
    fun a_screen_reader_hears_tens_of_percent_and_no_byte_count() {
        // `DESIGN.md` §23.2: "Importing item 3 of 12, 46 percent". The byte
        // count changes on every poll, so it is never what is spoken.
        val running = ActiveOperation(id = 1, name = "import", processed = 41, total = 100, stage = 2)
        assertEquals("import, 40 percent", running.spoken)
        assertFalse("bytes" in running.spoken)
        assertEquals(
            setOf("import, 40 percent"),
            (40L..49L).map { running.copy(processed = it).spoken }.toSet(),
        )
        assertEquals("import, 100 percent", running.copy(processed = 100).spoken)
        assertEquals("verification, 0 percent", running.copy(name = "verification", processed = 0).spoken)
    }

    @Test
    fun a_stage_without_a_count_is_spoken_as_it_reads() {
        val start = ActiveOperation(id = 1, name = "export")
        assertEquals(start.description, start.spoken)
        assertEquals("Finishing export…", start.copy(total = 10, processed = 10, stage = 3).spoken)
        assertEquals("Cancelling export…", start.copy(total = 10, stage = 2, cancelling = true).spoken)
        assertEquals(start.copy(stage = 2).description, start.copy(stage = 2).spoken)
    }
}
