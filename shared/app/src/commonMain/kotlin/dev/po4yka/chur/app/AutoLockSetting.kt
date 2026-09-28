package dev.po4yka.chur.app

import dev.po4yka.chur.vault.LockPolicy

/**
 * The auto-lock choices of `DESIGN.md` §14.4.
 *
 * Each one counts idle time while Chur is in the foreground only. Leaving the
 * application still locks at once, `PLAINTEXT_LIFECYCLE.md` §7 and
 * `ANDROID.md` §19.3, so no choice keeps a session open in the background.
 *
 * There is no "Immediately": leaving already locks at once, and a zero limit
 * would lock the vault on the next idle tick, before the user could use it,
 * set a longer limit again, or let an import or export finish.
 */
enum class AutoLock(val idleTimeoutMs: Long, val label: String) {
    AFTER_30_SECONDS(30_000, "After 30 seconds"),
    AFTER_1_MINUTE(60_000, "After 1 minute"),
    AFTER_2_MINUTES(LockPolicy.DEFAULT_IDLE_TIMEOUT_MS, "After 2 minutes (default)"),
    AFTER_5_MINUTES(300_000, "After 5 minutes"),
    ;

    companion object {
        /** The §14.4 default. */
        val DEFAULT: AutoLock = AFTER_2_MINUTES
    }
}

/**
 * The device-wide auto-lock choice.
 *
 * It lives in the public shell beside [AppLockSetting], not in a vault
 * catalog, so it is the same in every identity, as `DECOY_VAULT.md` §10
 * requires of a setting. The stored form is the timeout in seconds; a missing
 * or unknown value reads as the default.
 */
class AutoLockSetting(
    private val reader: () -> String?,
    private val writer: (String) -> Unit,
) {
    fun read(): AutoLock {
        val stored = reader()?.trim()
        return AutoLock.entries.firstOrNull { it.stored == stored } ?: AutoLock.DEFAULT
    }

    fun write(choice: AutoLock) = writer(choice.stored)

    private val AutoLock.stored: String get() = (idleTimeoutMs / 1_000).toString()

    companion object {
        fun unset(): AutoLockSetting = AutoLockSetting({ null }, {})
    }
}
