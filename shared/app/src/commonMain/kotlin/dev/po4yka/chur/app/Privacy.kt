package dev.po4yka.chur.app

import dev.po4yka.chur.vault.VaultState

/**
 * The app-switcher privacy cover of `docs/security/PLAINTEXT_LIFECYCLE.md` §1
 * and `DESIGN.md` §14.3.
 *
 * The inventory in §1 puts the app-switcher snapshot in the "No" column: the
 * platform takes a picture of the foreground when the application leaves it,
 * and that picture is written to disk outside the sandbox's protection on some
 * platforms and survives the lock. A cover is therefore not a nicety; it is the
 * only thing between a locked vault and a thumbnail of its contents.
 *
 * Each platform implements it with the platform's own mechanism, because
 * neither is expressible in the other's terms: Android sets a window flag that
 * makes the compositor refuse to capture, and iOS covers the key window before
 * the snapshot is taken.
 */
interface PrivacyCover {
    /**
     * Turns the cover on or off.
     *
     * [needsPrivacyCover] decides the iOS switcher cover and the in-app cover,
     * which on Android is `FLAG_SECURE` while the activity is resumed. For
     * these, the cover is on whenever a session is unlocked and off in the
     * public shell, because `DISCREET_MODE.md` wants the public shell to look
     * ordinary.
     *
     * Android also turns the cover on for every route when the activity
     * pauses (`MainActivity.onPause`, [ChurController.onBackground]), so
     * `FLAG_SECURE` blanks every route in recents, the public shell too.
     * `DISCREET_MODE.md` permits this: before the snapshot, it asks for "the
     * public shell or a neutral cover".
     *
     * The iOS implementation ignores the call: the scene delegate drives the
     * iOS cover from [needsPrivacyCover] (see `IosPrivacyCover`).
     */
    fun setEnabled(enabled: Boolean)
}

/**
 * Whether the switcher must not show the screen for [state] and [route],
 * `PLAINTEXT_LIFECYCLE.md` §1.
 *
 * A recovery phrase on screen is private whatever the state: it opens the
 * vault on its own, and while a creation waits for it to be confirmed the
 * state is [VaultState.Creating] rather than an open session.
 *
 * An open session is private, and so is the gate, because `IOS.md` §22.1 keeps
 * authentication errors out of the snapshot. The restore screen is private for
 * the same reason: it takes the vault's password or recovery phrase, and a
 * wrong one shows `AUTHENTICATION_FAILED`. The public shell is not private,
 * for the reason [PrivacyCover.setEnabled] gives. Android sets its flag from
 * this while the activity is resumed, and sets it on every route when the
 * activity pauses. iOS asks as the scene resigns active and again as it
 * enters the background, because its cover is a view and cannot stay up
 * while the scene is in front.
 */
fun needsPrivacyCover(state: VaultState, route: AppRoute, phraseShown: Boolean = false): Boolean =
    phraseShown || state is VaultState.Unlocked || route == AppRoute.Unlock || route == AppRoute.Recover ||
        route == AppRoute.AppUnlock || route == AppRoute.AppRecover || route == AppRoute.RestoreBackup

/** A cover that does nothing, for a host with no window to cover. */
object NoPrivacyCover : PrivacyCover {
    override fun setEnabled(enabled: Boolean) {
        // Intentionally empty.
    }
}
