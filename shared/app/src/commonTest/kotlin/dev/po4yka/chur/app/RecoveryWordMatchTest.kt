package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.recoveryWordMatches
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The check that the recovery phrase was written down, `RECOVERY.md` §2.3.
 *
 * A word typed back is matched by the rule §2.2 uses to tell the words apart
 * when the phrase is entered to recover: trimmed, lowercased, and compared by
 * its first four letters. A stricter rule would refuse a copy that recovers
 * the vault, and a looser one would accept a copy that does not. Only the
 * NFKD step of §2.2 is left out, as [recoveryWordMatches] explains.
 */
class RecoveryWordMatchTest {

    @Test
    fun a_word_is_matched_by_its_first_four_letters() {
        assertTrue(recoveryWordMatches("abandon", "abandon"))
        assertTrue(recoveryWordMatches("abandon", "abandonx"))
        assertTrue(recoveryWordMatches("abandon", "aban"))
    }

    @Test
    fun case_and_surrounding_spaces_are_ignored() {
        assertTrue(recoveryWordMatches("abandon", "ABAN"))
        assertTrue(recoveryWordMatches("surround", " surround "))
    }

    @Test
    fun a_short_word_is_matched_whole() {
        assertTrue(recoveryWordMatches("act", "act"))
        assertFalse(recoveryWordMatches("act", "ac"))
        assertFalse(recoveryWordMatches("act", "acts"))
    }

    @Test
    fun a_wrong_or_incomplete_word_does_not_match() {
        assertFalse(recoveryWordMatches("abandon", "ability"))
        assertFalse(recoveryWordMatches("abandon", "aba"))
        assertFalse(recoveryWordMatches("abandon", ""))
    }
}
