package dev.po4yka.chur.app

/**
 * How a host reads the one-word security settings it keeps in files.
 *
 * A missing file is the documented default: the user never chose. A file that
 * exists but holds anything other than a known word, an empty file after a
 * power loss included, reads as the stricter choice. A torn write must never
 * turn a protection off without a word to the user.
 */
object SettingText {
    /** The stored word of the strict device-slot policy, `KEY_SLOTS.md` §1. */
    const val STRICT = "strict"

    /** The stored word of the convenient device-slot policy. */
    const val CONVENIENT = "convenient"

    /** The stored word of the whole-app lock, `DISCREET_MODE.md`. */
    const val WHOLE_APP = "whole-app"

    /** The stored word of the vault-only lock. */
    const val VAULT_ONLY = "vault-only"

    /** Whether the device slot requires biometry only. */
    fun strictPolicy(text: String?): Boolean = when (text?.trim()) {
        null, CONVENIENT -> false
        else -> true
    }

    /** Whether the whole application locks, rather than the vault only. */
    fun wholeAppLock(text: String?): Boolean = when (text?.trim()) {
        null, VAULT_ONLY -> false
        else -> true
    }

    /** The stored word of a device-slot policy. */
    fun policyWord(strict: Boolean): String = if (strict) STRICT else CONVENIENT

    /** The stored word of an app-lock choice. */
    fun appLockWord(wholeApp: Boolean): String = if (wholeApp) WHOLE_APP else VAULT_ONLY
}
