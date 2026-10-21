package dev.po4yka.chur.app.vault

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultPinTest {
    @Test
    fun accepts_only_twelve_to_twenty_ascii_digits() {
        assertFalse(isValidVaultPin(""))
        assertFalse(isValidVaultPin("12345678901"))
        assertTrue(isValidVaultPin("123456789012"))
        assertTrue(isValidVaultPin("12345678901234567890"))
        assertFalse(isValidVaultPin("123456789012345678901"))
        assertFalse(isValidVaultPin("12345678901a"))
        assertFalse(isValidVaultPin(" 123456789012"))
        assertFalse(isValidVaultPin("١٢٣٤٥٦٧٨٩٠١٢"))
    }

    @Test
    fun a_new_password_needs_twelve_characters_or_a_valid_pin() {
        // `PASSWORD_PROFILE.md` section 3: the same floor Rust applies.
        assertFalse(isAcceptableNewPassword(""))
        assertFalse(isAcceptableNewPassword("1234a"))
        assertFalse(isAcceptableNewPassword("abcdefghijk"))
        assertTrue(isAcceptableNewPassword("abcdefghijkl"))
        assertTrue(isAcceptableNewPassword("123456789012"))
        assertFalse(isAcceptableNewPassword("12345678901"))
        // A surrogate pair is one character, as Rust counts scalar values.
        val emoji = "\uD83D\uDE00"
        assertFalse(isAcceptableNewPassword(emoji.repeat(11)))
        assertTrue(isAcceptableNewPassword(emoji.repeat(12)))
    }
}
