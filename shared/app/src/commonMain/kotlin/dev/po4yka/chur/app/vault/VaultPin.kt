package dev.po4yka.chur.app.vault

internal fun isValidVaultPin(pin: String): Boolean =
    pin.length in 12..20 && pin.all { it in '0'..'9' }

/** Fewest characters a new or changed password has, `PASSWORD_PROFILE.md` §3. */
internal const val NEW_PASSWORD_MIN_CHARACTERS = 12

/**
 * Whether a new or changed credential is long enough, `PASSWORD_PROFILE.md` §3.
 *
 * A PIN of 12 to 20 digits passes, and so does any other password of at least
 * 12 characters. Characters are Unicode code points, the count Rust applies to
 * the same rule, so a surrogate pair is one. An unlock never applies this: a
 * slot made before the rule still opens.
 */
internal fun isAcceptableNewPassword(password: String): Boolean =
    isValidVaultPin(password) || codePoints(password) >= NEW_PASSWORD_MIN_CHARACTERS

private fun codePoints(text: String): Int = text.length - text.count { it.isLowSurrogate() }
