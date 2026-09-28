package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.SourceDeletionCopy
import dev.po4yka.chur.app.vault.importedOriginals
import dev.po4yka.chur.core.model.ChurStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The step after an import, `DESIGN.md` §15.3.
 *
 * An import used to end at "Imported into vault." and nothing said that the
 * original was still in the user's gallery, which is what most people move a
 * photo into a vault to change. The step offers only originals whose import
 * committed, SEC-022, and its copy never claims an erase, §15.3 and §27.
 */
class SourceDeletionTest {

    @Test
    fun only_originals_whose_import_committed_are_offered() {
        val picked = listOf("a", "b", "c", "d", "e", "f")
        val outcomes = listOf(
            MediaImporter.Outcome.Imported(byteArrayOf(1), derivatives = 1),
            MediaImporter.Outcome.Unreadable,
            MediaImporter.Outcome.Imported(byteArrayOf(2), derivatives = 0, previewsSkipped = true),
            MediaImporter.Outcome.TooLarge("too large"),
            MediaImporter.Outcome.Refused(ChurStatus.CANCELLED),
            // "f" has no outcome: the pick stopped before it opened.
        )
        assertEquals(listOf("a", "c"), importedOriginals(picked, outcomes))
        assertEquals(emptyList<String>(), importedOriginals(picked, emptyList()))
    }

    @Test
    fun the_step_uses_the_labels_of_the_design() {
        assertEquals("Keep original", SourceDeletionCopy.KEEP)
        assertEquals("Review source deletion", SourceDeletionCopy.REVIEW)
    }

    @Test
    fun the_step_says_the_system_deletes_and_copies_can_stay() {
        for (count in listOf(1, 3)) {
            val text = (SourceDeletionCopy.title(count) + " " + SourceDeletionCopy.body(count)).lowercase()
            assertTrue("still on this device" in text, "the copy does not say the original stays")
            assertTrue("your system handles deletion" in text, "the copy does not say who deletes")
            for (place in listOf("recently deleted", "backups", "other apps")) {
                assertTrue(place in text, "the copy does not name \"$place\"")
            }
        }
    }

    @Test
    fun no_copy_of_the_step_claims_an_erase() {
        val texts = listOf(1, 2).flatMap { count ->
            listOf(SourceDeletionCopy.title(count), SourceDeletionCopy.body(count))
        } + listOf(
            SourceDeletionCopy.outcome(picked = 1, found = 0, deleted = 0, place = "the Photos app"),
            SourceDeletionCopy.outcome(picked = 2, found = 2, deleted = 0, place = "the Photos app"),
            SourceDeletionCopy.outcome(picked = 2, found = 2, deleted = 2, place = "the Photos app"),
            SourceDeletionCopy.outcome(picked = 3, found = 1, deleted = 1, place = "the Photos app"),
        )
        for (text in texts) {
            for (claim in listOf("secure", "erase", "shred", "wipe", "permanent")) {
                assertFalse(claim in text.lowercase(), "\"$text\" claims \"$claim\"")
            }
        }
    }

    @Test
    fun the_outcome_says_what_the_system_did_and_where_the_rest_goes() {
        val place = "your Photos or Files app"
        assertEquals(
            "Chur could not reach the original. Delete it in your Photos or Files app.",
            SourceDeletionCopy.outcome(picked = 1, found = 0, deleted = 0, place = place),
        )
        assertEquals(
            "Chur could not reach the originals. Delete them in your Photos or Files app.",
            SourceDeletionCopy.outcome(picked = 2, found = 0, deleted = 0, place = place),
        )
        // The user declined the system's own confirmation.
        assertEquals("Kept the original.", SourceDeletionCopy.outcome(picked = 1, found = 1, deleted = 0, place = place))
        assertEquals("Deleted 1 original.", SourceDeletionCopy.outcome(picked = 1, found = 1, deleted = 1, place = place))
        assertEquals("Deleted 3 originals.", SourceDeletionCopy.outcome(picked = 3, found = 3, deleted = 3, place = place))
        assertEquals(
            "Deleted 2 originals. Delete the last one in your Photos or Files app.",
            SourceDeletionCopy.outcome(picked = 3, found = 2, deleted = 2, place = place),
        )
        assertEquals(
            "Deleted 1 original. Delete the other 2 in your Photos or Files app.",
            SourceDeletionCopy.outcome(picked = 3, found = 1, deleted = 1, place = place),
        )
    }
}
