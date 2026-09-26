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
}
