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
        assertEquals("Importing, 40 percent", running.spoken)
        assertFalse("bytes" in running.spoken)
        assertEquals(
            setOf("Importing, 40 percent"),
            (40L..49L).map { running.copy(processed = it).spoken }.toSet(),
        )
        assertEquals("Importing, 100 percent", running.copy(processed = 100).spoken)
        assertEquals("Checking items, 0 percent", running.copy(name = "verification", processed = 0).spoken)
    }

    @Test
    fun a_stage_without_a_count_is_spoken_as_it_reads() {
        val start = ActiveOperation(id = 1, name = "export")
        assertEquals("Exporting · Preparing", start.description)
        assertEquals("Exporting, Preparing", start.spoken)
        assertEquals("Exporting, Finishing", start.copy(total = 10, processed = 10, stage = 3).spoken)
        assertEquals("Exporting, Cancelling", start.copy(total = 10, stage = 2, cancelling = true).spoken)
        assertEquals("Exporting…", start.copy(stage = 2).description)
        assertEquals("Exporting", start.copy(stage = 2).spoken)
    }

    @Test
    fun other_work_reads_in_the_users_words_and_never_its_key() {
        // A reader used to hear "export, 40 percent" and "verification, 40
        // percent", and the card read "export: 1048576 of 5242880 bytes".
        val words = mapOf(
            "export" to "Exporting",
            "backup" to "Writing backup",
            "restore" to "Restoring",
            "verification" to "Checking items",
        )
        for ((name, title) in words) {
            val running = ActiveOperation(id = 1, name = name, processed = 2_100_000, total = 5_200_000, stage = 2)
            assertEquals("$title, 40 percent", running.spoken)
            assertEquals("$title · Preparing", running.copy(stage = 1).description)
            assertEquals("$title · Finishing", running.copy(stage = 3).description)
            assertEquals("$title · Cancelling", running.copy(cancelling = true).description)
            assertEquals("$title, Cancelling", running.copy(cancelling = true).spoken)
        }
        // Bytes read as a coarse size, and a check counts items.
        val export = ActiveOperation(id = 1, name = "export", processed = 1_048_576, total = 5_242_880, stage = 2)
        assertEquals("Exporting · 1.0 MB of 5.2 MB", export.description)
        val check = ActiveOperation(id = 1, name = "verification", processed = 12, total = 40, stage = 2)
        assertEquals("Checking items · 12 of 40", check.description)
    }

    @Test
    fun an_import_names_its_item_and_phase_and_no_byte_count() {
        // `DESIGN.md` §15.2 phases and the §23.2 "Importing item 3 of 12".
        val batch = ActiveOperation(id = 1, name = "import", item = 3, items = 12)
        assertEquals("Importing item 3 of 12 · Preparing", batch.description)
        val encrypting = batch.copy(stage = 2, processed = 46, total = 100)
        assertEquals("Importing item 3 of 12 · Encrypting", encrypting.description)
        assertEquals("Importing item 3 of 12, 40 percent", encrypting.spoken)
        assertEquals("Importing item 3 of 12 · Adding to library", batch.copy(stage = 4).description)
        assertEquals("Importing item 3 of 12, Adding to library", batch.copy(stage = 4).spoken)
        assertEquals("Importing · Encrypting", encrypting.copy(item = 1, items = 1).description)
        assertEquals("Importing item 3 of 12, Cancelling", encrypting.copy(cancelling = true).spoken)
    }
}
