package dev.po4yka.chur.app

import dev.po4yka.chur.app.notes.Disclosure
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The public-shell disclosure of `docs/product/DISCREET_MODE.md`.
 *
 * That section does not merely require a statement; it fixes what the statement
 * must say and what it must not, and those are the two halves checked here. A
 * screenshot test would show that a bar is on screen, which is the part that is
 * hard to get wrong; what is easy to get wrong is the copy drifting into a
 * reassurance, which is how the previous empty-state line — "Notes stay on this
 * device" — came to read as a privacy claim for content this section requires
 * be disclosed as unprotected.
 */
class DisclosureTest {

    /**
     * "states once that this content is not encrypted by Chur, and names the
     * private vault as the protected alternative".
     */
    @Test
    fun the_first_write_statement_says_unencrypted_and_names_the_vault() {
        val text = Disclosure.FIRST_WRITE.lowercase()
        assertTrue("not encrypted" in text, "the statement does not say it is unencrypted")
        assertTrue("vault" in text, "the statement does not name the vault")
    }

    /**
     * "public-shell settings carry the same statement permanently" and "the
     * settings statement carries both halves: public-shell content is backed up
     * by the platform, and vault content is not".
     */
    @Test
    fun the_settings_statement_carries_both_backup_halves() {
        val text = Disclosure.SETTINGS.lowercase()
        assertTrue("not encrypted" in text)
        assertTrue("backup" in text, "the statement does not mention the platform backup")
        assertTrue(
            "excluded from the system backup" in text,
            "the statement does not say vault content is excluded",
        )
        assertTrue("vault" in text)
    }

    /**
     * "The copy must not present the disclosure as a security feature and must
     * not imply that the public shell is private."
     *
     * The forbidden words are the ones a reassurance is built from. "Stay on
     * this device" is listed by name because it is what the shipped empty state
     * said, and it is false twice over: the content is in the platform backup,
     * and being on the device is not protection from someone holding it.
     */
    @Test
    fun no_disclosure_string_reads_as_a_reassurance() {
        val forbidden = listOf(
            "stay on this device",
            "stays on this device",
            "secure",
            "protected by chur",
            "safe",
            "only you",
            "private notes",
        )
        val strings = mapOf(
            "FIRST_WRITE" to Disclosure.FIRST_WRITE,
            "SETTINGS" to Disclosure.SETTINGS,
            "EMPTY_STATE" to Disclosure.EMPTY_STATE,
            "VAULT_ENTRY" to Disclosure.VAULT_ENTRY,
        )
        for ((name, value) in strings) {
            val text = value.lowercase()
            for (word in forbidden) {
                assertFalse(word in text, "$name claims \"$word\" for unprotected content")
            }
        }
    }

    /**
     * The two statements must agree. They are shown in different places and a
     * reader who sees both must not be told two different things, which is why
     * they are constants in one file rather than literals in two composables.
     */
    @Test
    fun the_two_statements_agree_on_what_is_unencrypted() {
        assertTrue("Notes are not encrypted by Chur" in Disclosure.FIRST_WRITE)
        assertTrue("Notes are not encrypted by Chur" in Disclosure.SETTINGS)
    }
}
