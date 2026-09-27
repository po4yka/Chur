package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.SyncSetupCopy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the sync setup form tells a user before they connect, `DESIGN.md` §27.
 *
 * The form used to promise "Keep this vault current on your other devices",
 * and the only statement that photos do not sync was on the sharing card,
 * which appears after connecting. A user could then treat the server as a
 * copy of their media and learn otherwise after losing the phone.
 */
class SyncSetupCopyTest {

    @Test
    fun the_setup_copy_states_what_sync_does_not_copy() {
        val text = SyncSetupCopy.INTRO.lowercase()
        assertTrue(
            "photos, videos, and audio are not copied to your other devices" in text,
            "the copy does not say that media stays on this device",
        )
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
