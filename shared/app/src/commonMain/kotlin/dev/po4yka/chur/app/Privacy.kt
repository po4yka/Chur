package dev.po4yka.chur.app

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
     * It is on whenever a session is unlocked and off in the public shell,
     * because `DISCREET_MODE.md` wants the public shell to look ordinary, and
     * an ordinary application does not blank its own switcher entry.
     */
    fun setEnabled(enabled: Boolean)
}

/** A cover that does nothing, for a host with no window to cover. */
object NoPrivacyCover : PrivacyCover {
    override fun setEnabled(enabled: Boolean) {
        // Intentionally empty.
    }
}
