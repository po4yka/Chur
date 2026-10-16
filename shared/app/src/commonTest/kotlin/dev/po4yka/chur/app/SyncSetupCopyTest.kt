package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.SyncSetupCopy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The setup states the media, pairing, and independent-backup boundary. */
class SyncSetupCopyTest {

    @Test
    fun the_setup_copy_states_paired_media_and_independent_backup() {
        val text = SyncSetupCopy.INTRO.lowercase()
        assertTrue(
            "encrypted originals can sync to your paired devices" in text,
            "the copy does not identify the encrypted media and pairing boundary",
        )
        assertTrue("source device online" in text, "the copy does not explain pending object keys")
        assertTrue("sync is not a backup" in text, "the copy does not say that sync is not a backup")
        // ADR-0033: Chur operates no sync service, so the server is the user's.
        assertTrue("server you run" in text, "the copy does not say whose server it is")
    }

    @Test
    fun the_setup_copy_does_not_promise_a_current_vault_elsewhere() {
        val text = SyncSetupCopy.INTRO.lowercase()
        for (promise in listOf("keep this vault current", "backed up", "everything")) {
            assertFalse(promise in text, "the copy promises \"$promise\"")
        }
    }
}
