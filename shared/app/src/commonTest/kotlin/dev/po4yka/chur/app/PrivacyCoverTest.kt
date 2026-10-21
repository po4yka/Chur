package dev.po4yka.chur.app

import dev.po4yka.chur.vault.VaultState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which screens the privacy cover hides, `PLAINTEXT_LIFECYCLE.md` §1.
 *
 * This decides the iOS switcher cover and the in-app cover, which on Android
 * is `FLAG_SECURE` while the activity is resumed. For these, the public shell
 * staying uncovered is as much the contract as the session being covered.
 * Android also sets `FLAG_SECURE` on every route when the activity pauses, so
 * its recents entry is blank on every route (see [PrivacyCover.setEnabled]).
 */
class PrivacyCoverTest {
    @Test
    fun the_session_and_the_gate_are_covered_and_the_public_shell_is_not() {
        assertTrue(needsPrivacyCover(VaultState.Unlocked(generation = 1), AppRoute.Vault))
        // The restore screen takes the vault's credential and can show
        // AUTHENTICATION_FAILED, so it is a gate too.
        val gates = listOf(
            AppRoute.Unlock,
            AppRoute.Recover,
            AppRoute.AppUnlock,
            AppRoute.AppRecover,
            AppRoute.RestoreBackup,
        )
        for (gate in gates) {
            assertTrue(needsPrivacyCover(VaultState.Locked(), gate), "$gate")
        }
        assertFalse(needsPrivacyCover(VaultState.Locked(), AppRoute.PublicShell))
        assertFalse(needsPrivacyCover(VaultState.NoVault, AppRoute.PublicShell))
        // The create form takes the new vault password, which autofill must not
        // offer to save, and shows that a vault is being set up.
        assertTrue(needsPrivacyCover(VaultState.NoVault, AppRoute.CreateVault))
        assertTrue(needsPrivacyCover(VaultState.Creating, AppRoute.CreateVault))
        // A creation waiting for its recovery phrase to be confirmed shows a
        // full credential before any session opens.
        assertTrue(needsPrivacyCover(VaultState.Creating, AppRoute.CreateVault, phraseShown = true))
    }
}
